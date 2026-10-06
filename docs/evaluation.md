# Agent Evaluation

## 1. 목적

기능 클래스의 존재만 확인하지 않고 Agent가 상황에 맞는 행동을 수행하는지 검증한다. 자연어 표현은 변동될 수 있으므로 LLM-as-a-Judge나 exact match 대신 HTTP 상태, Tool 사용 여부, source 파일명, 필수·금지 문자열을 조합한 deterministic 판정을 사용한다.

## 2. 시나리오 범주

`evaluation/agent_scenarios.json`에는 15개 scenario와 17개 turn이 있다.

| 범주 | 검증 내용 |
|---|---|
| RAG | VPN/DB 정책 답변과 기대 source |
| ACCESS | 본인 권한 조회, ToolContext 사용자 경계, 지원하지 않는 리소스 |
| TICKET | 접근 요청과 장애 요청의 PENDING 접수 |
| SAFETY | 민감정보, Prompt Injection, 타 사용자 정보, 빈 입력 |
| MEMORY | 정책 후속 질문과 권한 조회 후 후속 신청 |
| UNKNOWN | 문서에 없는 정책의 추측 방지, primary failure fallback |

## 3. 실행 방법

Java 21, 실행 가능한 OpenAI API key와 모델 접근 권한이 필요하다.

```bash
export OPENAI_API_KEY="sk-..."
bash gradlew bootRun
```

다른 터미널에서 실행한다.

```bash
python3 scripts/evaluate-agent.py
```

옵션 예시:

```bash
python3 scripts/evaluate-agent.py \
  --base-url http://localhost:8080 \
  --scenarios evaluation/agent_scenarios.json \
  --output-dir evaluation/results \
  --timeout 60
```

기본 실행은 먼저 `POST /api/ingest`로 정책 문서를 인제스트한다. 데이터가 이미 준비됐다면 `--skip-ingest`를 지정한다. 이어서 scenario 안의 turn 순서를 유지하면서 `POST /api/chat`을 호출한다.

서버 연결 실패, timeout, HTTP 401, 문서 인제스트 실패는 환경 오류로 명확히 출력하고 `latest` 결과를 갱신하지 않는다.

## 4. Metric 정의

- `Task Success Rate`: 모든 필수 turn을 통과한 scenario / 전체 scenario
- `Tool Decision Accuracy`: Tool 사용 여부가 정의된 turn에서 `toolUsed` actual과 expected가 같은 비율
- `Tool Selection Accuracy`: `expectedToolCalls`가 정의된 turn 중 실제 호출 개수, 순서와 Tool 이름이 모두 일치한 turn의 비율
- `Tool Argument Accuracy`: 기대 Tool call 중 같은 위치의 같은 Tool에 평가 대상으로 정의한 핵심 argument가 모두 일치한 call의 비율
- `Tool Execution Success Rate`: 기대 Tool call 중 같은 위치의 같은 Tool이 기대한 업무 `success` 상태로 완료된 call의 비율
- `RAG Source Hit Rate`: `sourceAny`가 비어 있지 않은 turn에서 기대 파일 중 하나가 actual sources에 포함된 비율
- `Safety Pass Rate`: SAFETY scenario 중 모든 조건을 통과한 비율
- `Multi-turn Pass Rate`: 2개 이상 turn을 가진 scenario 중 모든 turn을 통과한 비율
- `Average/Median Latency`: client-side `/api/chat` elapsed time
- `P95 Latency`: 정렬된 요청 latency의 nearest-rank 95 percentile

Multi-turn scenario는 같은 `userId + sessionId`로 순서대로 실행한다. 한 turn이라도 실패하면 scenario는 실패다.
Tool 세부 지표는 현재 정의한 Agent evaluation set만 대상으로 하며 일반적인 모든 요청에 대한 Agent 정확도를 의미하지 않는다.

## 5. 판정 규칙

- `httpStatus`: 기본 200, 빈 질문은 400
- `toolUsed`, `fallbackUsed`: boolean 일치
- `expectedToolCalls`: 실제 `toolCalls`의 개수, 순서와 `toolName` 일치
- `arguments`: actual `toolArguments` 전체와 exact match하지 않고 expected에 정의한 핵심 key/value만 일치
- `success`: 정상 Tool scenario에서 기대한 업무 성공 상태와 일치
- `latencyMs`: 숫자이며 0 이상. sub-millisecond 실행의 `0`도 허용
- `toolUsed=false`: 기존 boolean 판정과 함께 actual `toolCalls`가 빈 배열인지 확인
- `sourceAny`: 하나 이상의 기대 source hit. 빈 배열이면 actual sources도 비어야 함
- `answerContainsAny`: 후보 중 하나 이상 포함
- `answerContainsAll`: 모든 문자열 포함
- `answerNotContains`: 금지 문자열이 하나도 없어야 함

비교는 대소문자를 구분하지 않으며 자연어 전체 문장을 exact match하지 않는다.
Ticket의 동적 ID와 `toolResult` 전체 문자열은 exact match하지 않는다. `reason`, `userId`, `ToolContext`도 argument 평가에서 제외한다.

Tool 세부 조건의 실패는 turn의 `failures`에 추가되므로 해당 turn과 scenario도 실패한다. 세부 metric만 실패하고 scenario는 통과하는 방식으로 분리하지 않는다.

Evaluator 비교 로직은 Python 표준 라이브러리만으로 검증한다.

```bash
python3 -m unittest scripts/test_evaluate_agent.py
```

## 6. 결과 파일

정상 완료 시 다음 파일을 생성한다.

- `evaluation/results/latest.json`: 요청별 actual, latency, 실패 이유와 집계
- `evaluation/results/latest.md`: 사람이 읽는 요약과 실패 table

최근 완료된 실행 결과는 [`evaluation/results/latest.md`](../evaluation/results/latest.md)와 JSON 원본에 보관한다. 실제 실행 없이 임의의 성공률이나 latency를 결과로 사용하지 않는다.

## 7. 실패 사례 분석 방법

`latest.md`의 실패 행과 `latest.json`의 `failures`를 함께 본다.

- Tool decision 실패: Tool 사용 여부 판단 확인
- Tool selection 실패: 호출 개수, 순서와 Tool 이름 확인
- Tool argument 실패: scenario의 핵심 expected argument와 actual `toolArguments` 확인
- Tool execution 실패: actual `success`와 Repository 실행 결과 확인
- source 실패: 인제스트 여부, threshold, 문서 metadata 확인
- Safety 실패: 차단 순서와 민감 문자열 재노출 확인
- Multi-turn 실패: `userId:sessionId`와 지칭 리소스 유지 확인
- 답변 실패: 일반 system rule로 수정 가능한지 확인

특정 scenario 문장을 system prompt에 하드코딩하지 않는다. 작은 일반 규칙으로 해결할 수 없으면 한계로 남긴다.

## 8. 한계

- 문자열 기반 평가는 의미상 같은 모든 자연어 표현을 포착하지 못한다.
- client-side latency에는 네트워크와 모델 제공자의 변동이 포함된다.
- 시나리오 평가는 인메모리 Repository를 사용하며 실제 IAM/ITSM 통합 테스트가 아니다.
- 쓰기 Tool 자동 retry와 idempotency는 평가 범위 밖이다.
