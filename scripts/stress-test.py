#!/usr/bin/env python3
"""Disposable loopback-only TCC stress lab. Never accepts an external target URL."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import threading
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True, help='Checkout containing packaged service jars')
parser.add_argument('--label', required=True)
parser.add_argument('--output', type=Path, default=Path('stress-results'))
args = parser.parse_args()
source = args.source.resolve()
output = args.output.resolve() / args.label
output.mkdir(parents=True, exist_ok=True)
project = 'tccstress' + uuid.uuid4().hex[:10]
services = {'postgres': {'image': 'postgres:16-alpine', 'environment': {'POSTGRES_PASSWORD': 'postgres'},
    'command': ['postgres', '-c', 'shared_preload_libraries=pg_stat_statements', '-c', 'track_io_timing=on'],
    'volumes': [f'{source}/scripts/init-databases.sh:/docker-entrypoint-initdb.d/init-databases.sh:ro'],
    'mem_limit': '512m', 'healthcheck': {'test': ['CMD-SHELL', 'pg_isready -U postgres'], 'interval': '2s', 'retries': 30}}}
for name, port in [('inventory', 8081), ('payment', 8082), ('order', 8083), ('coordinator', 8080)]:
    upper = name.upper()
    env = {f'{upper}_DB_URL': f'jdbc:postgresql://postgres:5432/{name}_db',
           f'{upper}_DB_USER': 'orders' if name == 'order' else name,
           f'{upper}_DB_PASS': 'orders' if name == 'order' else name,
           'LOGGING_LEVEL_COM_TCC': 'WARN', 'JAVA_TOOL_OPTIONS': '-Xms128m -Xmx256m'}
    if name == 'coordinator':
        env.update({f'{n.upper()}_BASE_URL': f'http://{n}-service:{p}' for n, p in [('inventory',8081),('payment',8082),('order',8083)]})
    services[name + '-service'] = {'image': 'eclipse-temurin:21-jre-alpine', 'mem_limit': '512m',
        'depends_on': {'postgres': {'condition': 'service_healthy'}}, 'environment': env,
        'volumes': [f'{source}/{name}-service/target:/app:ro'],
        'command': ['java', '-jar', f'/app/{name}-service-0.1.0-SNAPSHOT.jar'],
        'ports': [f'127.0.0.1:{port + 10000}:{port}']}
compose_file = output / 'compose.json'
compose_file.write_text(json.dumps({'services': services, 'networks': {'default': {'driver': 'bridge'}}}, indent=2))
compose = ['docker', 'compose', '-p', project, '-f', str(compose_file)]

def command(cmd, **kwargs):
    return subprocess.check_output(cmd, text=True, timeout=kwargs.pop('timeout', 120), **kwargs)

def sql(db, query):
    return command(compose + ['exec', '-T', 'postgres', 'psql', '-X', '-U', 'postgres', '-d', db,
                              '-At', '-v', 'ON_ERROR_STOP=1', '-c', query]).strip()

def sql_json(db, query):
    return json.loads(sql(db, query))

local = threading.local()
def request(key, resource, sleep=None, qty=1):
    if not hasattr(local, 'http'):
        local.http = http.client.HTTPConnection('127.0.0.1', 18080, timeout=30)
    body = json.dumps({'customerId': f'C-{resource}', 'sku': f'S-{resource}', 'qty': qty, 'amount': 1})
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': key}
    if sleep: headers['X-Inject-Confirm-Sleep-Millis'] = str(sleep)
    start = time.perf_counter()
    try:
        local.http.request('POST', '/api/orders', body, headers)
        response = local.http.getresponse()
        raw = response.read()
        result = json.loads(raw) if raw else {}
        return {'key': key, 'code': response.status, 'ms': (time.perf_counter()-start)*1000, 'body': result}
    except Exception as exc:
        local.http.close()
        del local.http
        return {'key': key, 'code': 0, 'ms': (time.perf_counter()-start)*1000, 'error': str(exc)}

def ready():
    deadline = time.monotonic() + 90
    last_error = None
    while time.monotonic() < deadline:
        try:
            for port, path in [(18080, '/api/transactions/'), (18081, '/tcc/inventory/reservations/'),
                               (18082, '/tcc/payment/authorizations/'), (18083, '/tcc/orders/')]:
                c = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
                c.request('GET', path + str(uuid.uuid4()))
                response = c.getresponse(); response.read(); c.close()
                if response.status != 404: raise RuntimeError('Not ready')
            return
        except (OSError, http.client.HTTPException, RuntimeError) as exc:
            last_error = f'{port}: {exc}'
            time.sleep(1)
    raise RuntimeError(f'Sandbox services did not become ready: {last_error}')

def drain():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        pending = int(sql('coordinator_db', "/*stress-monitor*/ select count(*) from global_transaction where state not in ('CONFIRMED','CANCELLED','HEURISTIC')"))
        if pending == 0: return
        time.sleep(1)
    raise AssertionError(f'{pending} transactions did not converge within 90 seconds')

def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values)*p)-1)]

results = {'label': args.label, 'commit': command(['git', '-C', str(source), 'rev-parse', 'HEAD']).strip(),
           'environment': {'logical_cpus': os.cpu_count(), 'platform': platform.platform(),
                           'memory': Path('/proc/meminfo').read_text().splitlines()[0],
                           'heap_per_service': '128m initial / 256m maximum', 'container_memory_limit': '512m',
                           'logging': 'com.tcc=WARN', 'load_model': 'closed loop, fixed request count, no think time'},
           'cases': [], 'passed': False}
try:
    subprocess.run(compose + ['up', '-d'], check=True, timeout=180)
    ready()
    sql('postgres', 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements')
    sql('inventory_db', "insert into product(sku,available_qty,reserved_qty) select 'S-'||i,100000,0 from generate_series(0,2047) i")
    sql('payment_db', "insert into account(customer_id,balance,frozen_amount) select 'C-'||i,100000,0 from generate_series(0,2047) i")
    with ThreadPoolExecutor(max_workers=8) as pool:
        warmup = list(pool.map(lambda i: request('warm-'+str(i), i), range(80)))
    assert all(r['code']==200 for r in warmup), warmup[:3]
    drain()
    seen_ids = {r['key']: r['body']['txId'] for r in warmup}
    cases = [('distributed-1',1,60,'distributed'), ('distributed-8',8,160,'distributed'),
             ('distributed-32',32,320,'distributed'), ('distributed-64',64,384,'distributed'),
             ('hot-resource-32',32,320,'hot'), ('same-key-64',64,384,'replay'),
             ('conflicting-key-32',32,160,'conflict'), ('confirm-timeouts-8',8,12,'timeout')]
    for name, concurrency, count, mode in cases:
        if mode in ('replay','conflict'):
            seeded = request(name, 0)
            assert seeded['code'] == 200
            seen_ids[name] = seeded['body']['txId']
            drain()
        sql('postgres', 'select pg_stat_statements_reset()')
        start = time.perf_counter()
        def send(i):
            return request(name if mode in ('replay','conflict') else f'{name}-{i}',
                           0 if mode in ('replay','conflict','hot') else i,
                           sleep=4000 if mode=='timeout' else None, qty=2 if mode=='conflict' else 1)
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            responses = list(pool.map(send, range(count)))
        request_seconds = time.perf_counter() - start
        drain()
        total_seconds = time.perf_counter() - start
        stats = sql_json('postgres', """/*stress-monitor*/ select coalesce(json_agg(x),'[]'::json) from (
            select d.datname, s.calls, round(s.total_exec_time::numeric,2) exec_ms, s.rows, s.query
            from pg_stat_statements s join pg_database d on d.oid=s.dbid
            where d.datname in ('coordinator_db','inventory_db','payment_db','order_db')
              and s.query not like '%stress-monitor%'
            order by s.calls desc) x""")
        latency = [r['ms'] for r in responses]
        case = {'name':name, 'concurrency':concurrency, 'requests':count,
                'http_requests_per_second': count/request_seconds, 'request_seconds':request_seconds,
                'convergence_seconds':total_seconds, 'p50_ms':percentile(latency,.50),
                'p95_ms':percentile(latency,.95), 'p99_ms':percentile(latency,.99),
                'http_codes':dict(Counter(str(r['code']) for r in responses)),
                'response_states':dict(Counter(r.get('body',{}).get('state','none') for r in responses)),
                'coordinator_sql_calls_per_http_request':sum(s['calls'] for s in stats if s['datname']=='coordinator_db')/count,
                'sql':stats}
        (output / f'{name}-responses.json').write_text(json.dumps(responses,indent=2))
        results['cases'].append(case)
        print('STRESS_CASE '+json.dumps({k:v for k,v in case.items() if k!='sql'}),flush=True)
        expected = 409 if mode=='conflict' else 200
        assert all(r['code']==expected for r in responses), f'{name}: unexpected HTTP codes {case["http_codes"]}'
        for response in responses:
            if expected==409:
                assert response['body'].get('code')=='IDEMPOTENCY_KEY_REUSED'
                continue
            key, txid = response['key'], response['body']['txId']
            assert key not in seen_ids or seen_ids[key]==txid, 'one key created multiple transactions'
            seen_ids[key]=txid
        (output / 'docker-stats.jsonl').open('a').write(command(['docker','stats','--no-stream','--format','{{json .}}'] + command(compose+['ps','-q']).split())+'\n')
    drain()
    global_ids = sql_json('coordinator_db', "select coalesce(json_agg(tx_id order by tx_id),'[]'::json) from global_transaction where state='CONFIRMED'")
    for db, table, idcol in [('inventory_db','inventory_reservation','tx_id'),('payment_db','payment_authorization','tx_id'),('order_db','customer_order','id')]:
        ids = sql_json(db, f"select coalesce(json_agg({idcol} order by {idcol}),'[]'::json) from {table} where state='CONFIRMED'")
        assert ids == global_ids, f'{db}: participant/global outcomes diverged'
    assert set(global_ids)==set(seen_ids.values()), 'not all accepted keys confirmed exactly once'
    assert int(sql('coordinator_db','select count(*) from order_idempotency'))==len(seen_ids)
    assert int(sql('coordinator_db','select count(*) from global_transaction'))==len(seen_ids)
    assert int(sql('coordinator_db','select count(*) from transaction_participant'))==3*len(seen_ids)
    bad_stock = int(sql('inventory_db', """select count(*) from product p where sku like 'S-%' and
        (reserved_qty<>0 or available_qty<>100000-coalesce((select sum(qty) from inventory_reservation r where r.product_id=p.id and r.state='CONFIRMED'),0))"""))
    bad_money = int(sql('payment_db', """select count(*) from account a where customer_id like 'C-%' and
        (frozen_amount<>0 or balance<>100000-coalesce((select sum(amount) from payment_authorization r where r.account_id=a.id and r.state='CONFIRMED'),0))"""))
    assert bad_stock==0 and bad_money==0, f'accounting mismatch: stock={bad_stock}, money={bad_money}'
    results.update(passed=True, confirmed_transactions=len(global_ids), http_requests=sum(c['requests'] for c in results['cases']))
finally:
    (output/'summary.json').write_text(json.dumps(results,indent=2))
    try:
        logs = command(compose+['logs','--no-color'],timeout=30)
        (output/'containers.log').write_text(logs)
        if not results['passed']: print(logs[-24000:], flush=True)
    finally: subprocess.run(compose+['down','-v','--remove-orphans'],timeout=60,check=False)
    print('STRESS_RESULT '+json.dumps({k:v for k,v in results.items() if k!='cases'}),flush=True)
