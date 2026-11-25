#!/usr/bin/env python3
"""Run opt-in component measurements without external API/DB credentials."""
import datetime
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
os.chdir(root)
stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime('%Y%m%dT%H%M%S-%f%z')
out = root / 'evaluation/quantitative/runs' / stamp
out.mkdir(parents=True, exist_ok=False)
env = os.environ.copy()
for key in ('OPENAI_API_KEY', 'LIVE_RAG_UNSEEN', 'LIVE_RAG_DIAGNOSTIC', 'LIVE_RAG_SCORE_DIAGNOSTIC', 'TICKET_IT_DB_URL'):
    env.pop(key, None)
env['HELPDESK_QUANT_OUTPUT'] = str(out)
cmd = ['./gradlew', 'test', '--tests', 'com.devdesk.agent.QuantitativeEvidenceTest', '--rerun-tasks', '--console=plain']
result = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(out / 'command.txt').write_text('HELPDESK_QUANT_OUTPUT=<new-run-directory> ' + ' '.join(cmd) + '\n')
(out / 'gradle.log').write_text(result.stdout)
src = root / 'build/test-results/test/TEST-com.devdesk.agent.QuantitativeEvidenceTest.xml'
if src.exists():
    shutil.copy2(src, out / src.name)
if result.returncode:
    print(f'Experiment failed ({result.returncode}); evidence: {out}')
    sys.exit(result.returncode)
subprocess.run([sys.executable, 'scripts/quantitative/report.py', str(out)], check=True)
print(out)
