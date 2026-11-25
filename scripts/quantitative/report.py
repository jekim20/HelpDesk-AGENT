#!/usr/bin/env python3
"""Analyze raw measurements; no network calls, no fabricated historical baseline."""
import csv
import datetime
import hashlib
import json
import math
import platform
from pathlib import Path
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]).resolve()
def read(p): return json.loads(p.read_text())
def write(name, data):
    with (OUT / name).open('x') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write('\n')
def stats(values):
    values = sorted(values)
    return dict(n=len(values), mean=statistics.mean(values), median=statistics.median(values),
                p95=values[math.ceil(.95*len(values))-1])
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

tools, privacy, idem = [read(OUT / p) for p in ('tool-routes.json','privacy.json','idempotency.json')]
for name, rows in [('tool-routes',tools),('privacy',privacy),('idempotency',idem)]:
    with (OUT / (name+'.csv')).open('x', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
unique = {p['id']:p for p in privacy}
tp = sum(p['blocked'] and p['expectedBlocked'] for p in unique.values())
fn = sum(not p['blocked'] and p['expectedBlocked'] for p in unique.values())
fp = sum(p['blocked'] and not p['expectedBlocked'] for p in unique.values())
tn = sum(not p['blocked'] and not p['expectedBlocked'] for p in unique.values())
summary = dict(tool={k:sum(bool(r[k]) for r in tools) for k in ['selectionCorrect','argumentsCorrect','executionCorrect']},
               routes={p:stats([r['latencyMs'] for r in tools if r['path']==p]) for p in sorted({r['path'] for r in tools})},
               privacy=dict(tp=tp,fn=fn,fp=fp,tn=tn,recall=tp/(tp+fn),falsePositiveRate=fp/(fp+tn),
                            mismatches=[p['id'] for p in unique.values() if p['blocked']!=p['expectedBlocked']],
                            latencyMs=stats([p['latencyMs'] for p in privacy])),
               idempotency={mode:{v:dict(trials=len(rows := [r for r in idem if r['mode']==mode and r['variant']==v]),
                                    storedTickets=sum(r['storedTickets'] for r in rows),
                                    duplicates=sum(r['duplicates'] for r in rows),
                                    batchLatencyMs=stats([r['batchLatencyMs'] for r in rows]))
                                  for v in ['historical','current']} for mode in ['sequential','concurrent-24']})
write('summary.json', summary)

# Preserve provenance only for explicitly selected source/result files; never inspect env files.
paths = ['src/main/java/com/devdesk/agent/HelpDeskService.java',
         'src/main/java/com/devdesk/agent/UserInputSafetyAdvisor.java',
         'src/main/java/com/devdesk/agent/InMemoryTicketRepository.java',
         'src/test/java/com/devdesk/agent/QuantitativeEvidenceTest.java',
         'src/test/resources/quantitative/privacy-cases.json','evaluation/agent_scenarios.json']
old_source = subprocess.check_output(['git','show','75ac1dc:src/main/java/com/devdesk/agent/InMemoryTicketRepository.java'],cwd=ROOT)
(OUT/'historical-repository-75ac1dc.java.txt').write_bytes(old_source)
env = dict(recordedAt=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).isoformat(),
           os=platform.system(),release=platform.release(),architecture=platform.machine(),
           java=subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),
           commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
           sourceHashes={p:sha(ROOT/p) for p in paths}, baselineCommit='75ac1dc',
           baselineSourceHash=hashlib.sha256(old_source).hexdigest(), liveApiCalls=0,
           measuredScope='JVM component, in-memory repository, fixed model; not HTTP or DB latency')
write('environment.json',env)

historical=[]
for rel in ['evaluation/results/latest.json','evaluation/results/runs/2026-11-05-live-ag10-v2/latest.json',
            'evaluation/results/runs/docker-live-final-20261105-203123-602029/latest.json']:
    p=ROOT/rel
    if not p.exists(): continue
    d=read(p)
    groups={}
    for s in d['scenarios']:
        for turn in s['turns']:
            groups.setdefault(s['category'],[]).append(turn['latencyMs'])
    historical.append(dict(path=rel,sha256=sha(p),generatedAt=d['generatedAt'],summary=d['summary'],
                           turnLatenciesByCategory={k:stats(v) for k,v in groups.items()},rerun=False))
write('historical-inventory.json',historical)

# Independent reconciliation of old AND latest completed browser runs.
e2e=[]
for folder in sorted((ROOT/'artifacts/e2e/runs').glob('*')):
    if not all((folder/n).exists() for n in ['run-summary.json','playwright-results.json','database-verification.txt']): continue
    s=read(folder/'run-summary.json')
    if s.get('result')!='passed': continue
    p=read(folder/'playwright-results.json')
    counts={}
    def walk(suites):
        for suite in suites:
            for spec in suite.get('specs',[]):
                for test in spec.get('tests',[]):
                    status=test.get('results',[{}])[-1].get('status','unknown')
                    counts[status]=counts.get(status,0)+1
            walk(suite.get('suites',[]))
    walk(p.get('suites',[]))
    db=(folder/'database-verification.txt').read_text().strip()
    e2e.append(dict(path=str(folder.relative_to(ROOT)),counts=counts,
                    countsMatch=counts.get('passed',0)==s['tests']['passed']==s['tests']['total'],
                    databaseMatches=db==s['databaseVerification'].strip(), databaseVerification=db,
                    liveLlmCalls=s['liveLlmCalls'],startedAt=s['startedAt'],
                    files={n:sha(folder/n) for n in ['run-summary.json','playwright-results.json','database-verification.txt']}))
write('e2e-inventory.json',e2e)

lines=['# 정량 성과 실측 보고서 — 2026-11-23 KST','',
       '애플리케이션 기능은 수정하지 않았다. 실행 환경·소스 SHA-256은 `environment.json`, 원본은 JSON/CSV에 저장했다.',
       '', '## 이번 실험과 과거 증거의 구분','',
       '- 현재 구성: macOS ARM64/JDK 21, 직접 서비스 호출, 인메모리 Repository와 실제 Tool/AOP. HTTP·PostgreSQL·LLM 응답 속도가 아니다.',
       '- 이번 측정: Tool 6개 턴 × warm-up 10회 후 100회 = 600회. 기존 agent_scenarios.json의 Tool 턴만 순서대로 재사용했다.',
       '- Privacy: 정상 12개·민감정보 12개의 고정 더미 입력, warm-up 100회 후 100회 반복. 정확도 분모는 중복 제거한 24개다.',
       '- 멱등성: 순차 및 24 worker 동시 모드, 각 30회. 회차마다 새 저장소/동일 UUID/동일 본문. 양 구현의 실행 순서를 교대했다.',
       '- 비교 Baseline: Git 75ac1dc 저장소 create 알고리즘을 현재 타입에 맞춰 옮긴 **재구성 실험**. 과거 전체 서버를 실행한 결과가 아니다.',
       '', '## 측정 결과','', '| Tool 경로 | n | 평균 ms | 중앙값 ms | P95 ms |', '|---|---:|---:|---:|---:|']
for p,s in summary['routes'].items(): lines.append(f"| {p} | {s['n']} | {s['mean']:.6f} | {s['median']:.6f} | {s['p95']:.6f} |")
lines += ['',f"Tool 선택·핵심 인자·실행 결과 각각 {summary['tool']['selectionCorrect']}/600, {summary['tool']['argumentsCorrect']}/600, {summary['tool']['executionCorrect']}/600. 선택은 Trace 이름·호출 수, 인자는 resource/type, 실행은 success·답변·티켓 ID/PENDING/요청자 및 조회 상태를 확인했다.",
          '', '| 멱등성 모드 | 이전 재구성 저장/중복 | 현재 저장/중복 | 중복 감소율 |','|---|---:|---:|---:|']
for m,d in summary['idempotency'].items():
    a,b=d['historical'],d['current']; pct=(a['duplicates']-b['duplicates'])/a['duplicates']*100
    lines.append(f"| {m} (30회×24요청) | {a['storedTickets']}/{a['duplicates']} | {b['storedTickets']}/{b['duplicates']} | {pct:.1f}% |")
lines += ['', '| 멱등성 batch (24요청) | 평균 ms | 중앙값 ms | P95 ms |', '|---|---:|---:|---:|']
for mode, variants in summary['idempotency'].items():
    for variant, data in variants.items():
        timing = data['batchLatencyMs']
        lines.append(f"| {mode}/{variant} | {timing['mean']:.6f} | {timing['median']:.6f} | {timing['p95']:.6f} |")
p = summary['privacy']['latencyMs']
lines += ['', f"Privacy 입력 검사 시간(n={p['n']}): 평균 {p['mean']:.6f} ms, 중앙값 {p['median']:.6f} ms, P95 {p['p95']:.6f} ms.",
          '', '멱등성 비교는 중복 방지가 목적이며 이번 측정에서 시간 단축을 주장하지 않는다. 멱등성 batch에는 별도 warm-up이 없고 초기 호출 비용도 포함한다.']
lines += ['', '중복=저장 건수−논리 요청 1건. 감소율=(이전 중복−현재 중복)/이전 중복×100. 각 모드에서 현재 30/30회 티켓 한 건·반환 ID 한 개로 수렴했다. DB 동시성·재시작 보장은 이 실험으로 주장하지 않는다.',
          '', f'Privacy TP={tp}, FN={fn}, FP={fp}, TN={tn}. 탐지율=TP/(TP+FN)={tp/(tp+fn)*100:.2f}%, 정상 오탐률=FP/(FP+TN)={fp/(fp+tn)*100:.2f}%.',
          '', f"기대 판정과 다른 입력 ID는 {summary['privacy']['mismatches']}이다.",
          '', 'Privacy 입력은 기존 테스트의 정상·공격 문장을 더미 값으로 재사용·확장한 소규모 수작업 세트다. 정상 N11/N12에는 보안 설명도 허용해야 한다는 평가 기준을 사용했다. 현재 제품 정책이 이 주제 자체를 금지한다면 정책 차이를 별도로 논의해야 한다.',
          '', '## 과거 Live 및 브라우저 증거','']
for h in historical:
    lines.append(f"- `{h['path']}`: {h['generatedAt']}, {h['summary']['passed']}/{h['summary']['scenarioCount']} 성공. 이번 Live 재실행 없음. 경로별 턴 지연 재계산은 historical-inventory.json.")
lines += ['', '서로 다른 날짜·환경·모델 상태의 과거 성공률/지연을 전후 개선율로 계산하지 않았다. 특히 과거 summary 지연은 턴 집계인지 시나리오 집계인지 평가기 정의를 따라 해석해야 하며, 재계산은 개별 turn.latencyMs를 사용했다.',
          '', 'E2E 증거 세 파일을 재귀적으로 대조한 목록은 e2e-inventory.json. Live LLM 대신 로컬 AI 대역을 사용한 React→Spring Boot→Tool→PostgreSQL 검증이다. 과거 15/15 Live와 합산하지 않는다.',
          '', '## 자소서·면접에 쓸 수 있는 성과','',
          '- 멱등성: “Git의 도입 전 로직을 재구성해 동일 요청 24회 재시도를 순차·동시 각 30회 비교했고, 불필요한 중복 690건을 각 모드에서 0건으로 줄임을 인메모리 실험으로 확인했습니다.”',
          '- Tool: “실제 서비스의 6개 업무 턴을 100회 반복해 Tool 선택·핵심 인자·실행 결과를 각각 600/600 확인했습니다.” LLM이 Tool을 선택한 정확도로 표현하면 안 된다. 서버 결정형 라우팅 결과다.',
          '- Privacy: “정상 문의까지 포함한 더미 입력으로 탐지율과 오탐률을 함께 측정해 민감정보 차단과 정상 입력 허용 여부를 검증했습니다.” 보안 공격 전체 차단 또는 성능 개선율을 주장하면 안 된다.',
          '- 지연: “경로별 평균·중앙값·P95를 분리해 수집했습니다.” 실제 사용자 업무시간 단축률·LLM 비용 절감·부하 한계는 측정하지 않았다.',
          '', '면접 설명은 문제(재전송 중복) → 원인(요청 식별/원자적 보호 부재) → 행동(사용자별 키·fingerprint·원자적 저장) → 증거(같은 입력/반복/실제 저장 수) → 한계(인메모리 비교, PostgreSQL은 별도 테스트) 순서로 한다.',
          '', '## 통계와 재현 방법','',
          'P95는 오름차순 ceil(0.95×N)번째 값(nearest rank), 평균은 합/N, 중앙값은 가운데 값이다. ms는 System.nanoTime 차이/1e6. 멱등성 원본의 시간은 24개 요청 전체 batch 시간이며 단일 요청 시간으로 나누어 표현하지 않는다.',
          '', '워밍업 후 같은 JVM에서 측정한 마이크로 실험으로 JIT·GC·AOP 로깅·OS 스케줄링 영향을 받는다. 엄밀한 JMH/부하 테스트 또는 운영 SLO가 아니다. 600회 반복은 6개 입력의 반복이며 600개 독립 시나리오가 아니다.',
          '', '```sh','python3 scripts/quantitative/run.py', 'env -u OPENAI_API_KEY -u LIVE_RAG_UNSEEN -u LIVE_RAG_DIAGNOSTIC -u LIVE_RAG_SCORE_DIAGNOSTIC -u TICKET_IT_DB_URL ./gradlew test --console=plain', 'python3 -m unittest discover -s scripts -p "test_*.py"', 'npm test --prefix e2e', '```',
          '', '첫 명령은 새 run 디렉터리를 만들고 원본·JUnit·로그·통계 보고서를 보존한다. HELPDESK_QUANT_OUTPUT이 없으면 새 측정 테스트는 스킵된다. Docker E2E는 고유 프로젝트/새 볼륨과 더미 계정만 사용하며 그 리소스만 정리한다. 실제 .env 값을 열람할 필요가 없다.',
          '', '현재 서버 전체의 이전/이후 15개 시나리오 성공률, 실 AI/RAG 품질·비용, 사람의 수작업 업무 시간은 미측정이다. 외부 Live 재검증은 호출 범위·예산 사전 승인이 필요하다.']
(OUT/'report.md').write_text('\n'.join(lines)+'\n')
print(json.dumps(summary,ensure_ascii=False,indent=2))
