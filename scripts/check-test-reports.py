"""Fail CI when Docker-gated suites silently skipped or reports are missing."""
from pathlib import Path
import xml.etree.ElementTree as ET

required = {
    'InventoryTccServiceTest', 'PaymentTccServiceTest', 'OrderTccServiceTest',
    'CoordinatorServiceTest', 'RecoveryServiceTest', 'HappyPathTest',
    'IdempotencyTest', 'PaymentTryFailsTest', 'TerminalStateGuardTest',
    'ConfirmTimeoutRecoveryTest', 'CoordinatorCrashRecoveryTest',
}
seen = set()
for path in Path('.').glob('*/target/surefire-reports/TEST-*.xml'):
    suite = ET.parse(path).getroot()
    name = suite.attrib['name'].rsplit('.', 1)[-1]
    if name in required:
        counts = {key: int(suite.get(key, '0')) for key in ('tests', 'skipped', 'errors', 'failures')}
        if counts['tests'] == 0 or counts['skipped'] or counts['errors'] or counts['failures']:
            raise SystemExit(f'{name} did not fully pass: {counts}')
        seen.add(name)
missing = required - seen
if missing:
    raise SystemExit(f'Missing integration reports: {sorted(missing)}')
print(f'All {len(required)} required integration suites executed without skips or failures.')
