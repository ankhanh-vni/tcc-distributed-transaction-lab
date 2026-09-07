"""Summarize paired same-runner measurements; correctness failures fail the run."""
import json
from pathlib import Path
from statistics import median

root = Path('stress-results')
baseline = [json.loads(p.read_text()) for p in sorted(root.glob('baseline-*/summary.json'))]
candidate = [json.loads(p.read_text()) for p in sorted(root.glob('candidate-*/summary.json'))]
assert len(baseline) == len(candidate) == 3, 'Require three runs of each revision'
assert all(s['passed'] for s in baseline + candidate), 'A stress run failed correctness checks'
rows = []
for name in [c['name'] for c in baseline[0]['cases']]:
    before = [next(c for c in s['cases'] if c['name']==name) for s in baseline]
    after = [next(c for c in s['cases'] if c['name']==name) for s in candidate]
    b = median(c['http_requests_per_second'] for c in before)
    a = median(c['http_requests_per_second'] for c in after)
    row = {'case':name, 'baseline_rps':b, 'candidate_rps':a, 'rps_change_pct':(a/b-1)*100,
           'baseline_rps_range':[min(c['http_requests_per_second'] for c in before), max(c['http_requests_per_second'] for c in before)],
           'candidate_rps_range':[min(c['http_requests_per_second'] for c in after), max(c['http_requests_per_second'] for c in after)],
           'baseline_p95_ms':median(c['p95_ms'] for c in before), 'candidate_p95_ms':median(c['p95_ms'] for c in after),
           'baseline_coordinator_sql_per_request':median(c['coordinator_sql_calls_per_http_request'] for c in before),
           'candidate_coordinator_sql_per_request':median(c['coordinator_sql_calls_per_http_request'] for c in after)}
    rows.append(row)
    print('STRESS_COMPARISON '+json.dumps(row),flush=True)
summary = {'baseline_commit':baseline[0]['commit'], 'candidate_commit':candidate[0]['commit'],
           'environment':candidate[0]['environment'], 'repetitions':3,
           'measured_http_requests':sum(s['http_requests'] for s in baseline+candidate),
           'confirmed_transactions':sum(s['confirmed_transactions'] for s in baseline+candidate),
           'comparisons':rows}
(root/'comparison.json').write_text(json.dumps(summary,indent=2))
print('STRESS_TOTAL '+json.dumps({k:v for k,v in summary.items() if k!='comparisons'}),flush=True)
