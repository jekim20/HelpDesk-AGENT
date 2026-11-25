# 정량 성과 실험

[2026-11-23 실측 보고서](runs/20261123-local-01/report.md)와 [검증 실행 기록](runs/20261123-local-01/validation.md).

- Tool 선택·핵심 인자·실행 결과: 각각 600/600. 6개 기존 Tool 턴의 100회 반복이며, 서버 결정형 라우팅을 평가한다.
- 멱등성: 24요청×30회, 순차/동시 각 모드의 중복 690→0. Git 75ac1dc의 이전 알고리즘을 재구성한 인메모리 비교다.
- Privacy: 수작업 검토용 정상 12개/민감정보 12개에서 탐지 10/12(83.33%), 정상 오탐 2/12(16.67%). 반복 횟수를 정확도의 표본 수로 부풀리지 않는다.
- 실제 LLM·사람 업무시간·AWS 운영 성능·일반화 성능 향상은 이번에 측정하지 않았다.

재현:

```sh
python3 scripts/quantitative/run.py
```

JDK 21과 기존 Gradle 의존성이 필요하다. 새 run 디렉터리에 JSON/CSV·JUnit·통계·보고서를 저장하며, 기존 결과가 있으면 덮어쓰지 않고 실패한다. 서버나 Docker가 없어도 실행 가능하다. 실제 API 키·DB 비밀번호가 필요 없다. 기존 Git 변경과 finetuning/phase1은 사용하지 않는다.

파일별 역할:

- `tool-routes.json/csv`: 회차·시나리오·턴·경로·ms·세 가지 정확도 원본
- `privacy.json/csv`: 입력 ID·기대/관측 차단·ms 원본 (입력은 test resource의 더미 corpus)
- `idempotency.json/csv`: 회차·순차/동시·이전/현재·저장/중복 수·batch ms
- `summary.json`: 원본으로 계산한 평균·중앙값·nearest-rank P95·오류율
- `environment.json`, `historical-repository-75ac1dc.java.txt`: 환경·소스 hash·비교 코드 출처
- `historical-inventory.json`: 기존 Live 결과의 시각/성공률/경로별 지연 재계산. 새 실행 결과가 아니다.
- `e2e-inventory.json`: 기존/이번 성공 리포트 세 파일의 일치 여부와 hash

기존 Live 기록은 별도 날짜·환경에서의 관측으로만 사용한다. 서로 다른 실행의 지연 차이를 개선율로 해석하지 않는다. 소규모 corpus의 누락과 정책 해석 차이는 보고서에 남겼다.
