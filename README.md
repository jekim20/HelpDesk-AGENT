# DevDesk AI

DevDesk AI는 사내 IT 정책 안내, 접근 권한 조회, 지원 티켓 접수를 하나의 대화 흐름으로 연결한 Spring AI 기반 HelpDesk Agent다.

정책성 질문은 RAG로 근거 문서를 조회하고, 현재 사용자 상태 확인과 작업 요청은 Tool로 외부 기능에 연결한다. 사용자·세션별 Chat Memory와 서버가 전달하는 ToolContext로 사용자 경계를 유지하며, 시나리오 평가로 Tool 판단, RAG source, 안전성, 멀티턴 맥락과 응답 지연을 검증할 수 있다.

## 핵심 기능

- RAG: `QuestionAnswerAdvisor`, 인메모리 `VectorStore`, 정책 Markdown, 응답 `sources`
- Context: `conversationId = userId + ":" + sessionId`, 최대 20-message window
- Tool Calling: `AccessTools`, `TicketTools`와 서버 측 `ToolContext.userId`
- Safety: 민감정보·Prompt Injection·타 사용자 정보 요청을 Memory 저장 전에 차단
- Write guard: 권한/장애 요청은 실제 작업 완료가 아닌 `PENDING` 티켓만 생성
- Failure handling: 저장소 장애 시 성공 상태를 추측하거나 접수 완료라고 응답하지 않음
- Observability: token, latency, Tool call metric과 마스킹된 audit log
- Model fallback: primary 실패 시 한 번만 별도 fallback client로 전환
- Agent evaluation: 15개 시나리오, 17개 API turn의 deterministic 판정

## Agent 설계 원칙

1. 정보 질문과 기능 수행을 분리한다. 정책·절차 질문은 RAG, 현재 상태 조회와 작업 요청은 Tool로 처리한다.
2. 모든 결정을 LLM에 맡기지 않는다. 리소스가 명확한 권한 조회는 deterministic route로 Tool을 실행해 결과를 일관되게 만든다.
3. 사용자 발화의 ID를 신뢰하지 않는다. 실행 사용자는 서버의 `ToolContext`로만 Tool에 전달한다.
4. 쓰기 요청에는 승인 경계를 둔다. Agent는 실제 권한을 부여하거나 장애를 해결하지 않고 `PENDING` 티켓만 만든다.
5. 실제 실행 결과를 확인한 뒤 말한다. Repository 실패 시 안전한 실패 메시지를 반환하며 성공으로 표현하지 않는다.
6. Safety를 Memory보다 앞에 둔다. 차단 대상 입력이 대화 이력에 남지 않게 한다.

구조와 의사결정은 [아키텍처 문서](docs/architecture.md), 평가 상세는 [Agent 평가 문서](docs/evaluation.md)에 정리했다. 개별 API를 직접 확인하려면 [수동 검증 가이드](docs/manual-testing.md)를 참고한다.

## 실행

요구 사항은 Java 21과 OpenAI API key다. 키는 소스나 Git에 저장하지 않는다.

```bash
export OPENAI_API_KEY="sk-..."
bash gradlew bootRun
```

기본 주소는 `http://localhost:8080`, Swagger UI는 `http://localhost:8080/swagger-ui.html`이다. 키가 없어도 애플리케이션 구성은 시작할 수 있지만 embedding/chat을 실제 호출하면 인증에 실패한다.

fallback model은 primary와 독립적으로 설정할 수 있다. 지정하지 않으면 호환성을 위해 primary 기본 모델과 같은 `gpt-4o-mini`를 사용한다.

```bash
export FALLBACK_MODEL="gpt-4o-mini"
```

## API

정책 문서를 먼저 인제스트한다.

```bash
curl -X POST http://localhost:8080/api/ingest
```

대화 요청은 JSON으로 보낸다.

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "question": "내 DEV_DB 권한 상태 알려줘.",
    "sessionId": "demo",
    "userId": "user1"
  }'
```

응답 예시는 다음 구조다.

```json
{
  "answer": "DEV_DB 권한 상태는 APPROVED입니다.",
  "sessionId": "demo",
  "sources": [],
  "toolUsed": true,
  "toolCalls": [
    {
      "toolName": "getAccessStatus",
      "toolArguments": {
        "resource": "DEV_DB"
      },
      "toolResult": "DEV_DB 권한 상태는 APPROVED입니다.",
      "success": true,
      "latencyMs": 1
    }
  ],
  "fallbackUsed": false
}
```

`toolCalls`는 한 요청에서 실행된 Tool을 순서대로 담는다. 기존 `toolUsed`는 하위 호환을 위해 유지한다.
Tool argument는 allowlist로 제한하며 `ToolContext`, `userId`, 티켓의 자유 텍스트 `reason`은 포함하지 않는다.

주요 endpoint:

| Method | Path                                    | 용도                       |
| ------ | --------------------------------------- | -------------------------- |
| `POST` | `/api/chat`                             | Agent 대화                 |
| `POST` | `/api/ingest`                           | 정책 문서 RAG 인제스트     |
| `GET`  | `/api/history?userId=...&sessionId=...` | 격리된 대화 이력 확인      |
| `GET`  | `/api/admin/tickets`                    | 인메모리 PENDING 티켓 확인 |
| `GET`  | `/api/metrics`                          | token 및 모델 호출 누적값  |
| `GET`  | `/actuator/metrics/ai.latency`          | 모델 호출 지연 시간 지표   |
| `GET`  | `/actuator/metrics/ai.tool.latency`     | Tool 호출 지연 시간 지표   |
| `GET`  | `/actuator/prometheus`                  | Prometheus 형식 지표       |

## 테스트

```bash
bash gradlew clean test
```

테스트는 Access 사용자 격리, 지원 리소스 검증, PENDING gate, Repository 장애 안전 응답, `userId:sessionId` Memory 격리, Safety 선차단, HTTP 400과 fallback 전환을 검증한다.

## CI

GitHub Actions 기반 CI는 `main` 브랜치 push와 `main` 대상 pull request에서 Java 21 Gradle regression/unit test와 application build를 자동 검증한다.
외부 LLM API나 secret이 필요 없는 Python evaluator unit test도 함께 실행한다.

## Agent Evaluation

“Tool이 존재한다”와 “상황에 맞게 Tool을 사용한다”를 구분하기 위해 실제 `/api/chat`을 호출하는 시나리오 평가를 제공한다.

```bash
python3 scripts/evaluate-agent.py
```

스크립트는 Python 표준 라이브러리만 사용하고 기본적으로 `/api/ingest`를 먼저 호출한다. 이미 인제스트를 마쳤다면 `--skip-ingest`를 사용할 수 있다.

```bash
python3 scripts/evaluate-agent.py \
  --base-url http://localhost:8080 \
  --scenarios evaluation/agent_scenarios.json \
  --skip-ingest
```

평가 항목:

- Task Success Rate
- Tool Decision Accuracy
- Tool Selection Accuracy
- Tool Argument Accuracy
- Tool Execution Success Rate
- RAG Source Hit Rate
- Safety Pass Rate
- Multi-turn Pass Rate
- 요청별 latency와 nearest-rank P95

`Tool Decision Accuracy`는 Tool 사용 여부를, `Tool Selection Accuracy`는 기대 Tool의 개수·순서·이름을 평가한다.
`Tool Argument Accuracy`는 scenario에 정의한 핵심 argument만 비교하고, `Tool Execution Success Rate`는 정상 Tool scenario의 업무 성공 여부를 평가한다.
이 지표들은 현재 정의된 Agent evaluation set에 대한 결과이며 모든 요청에 대한 일반 정확도를 의미하지 않는다.

Evaluator 비교 로직은 외부 API 없이 실행할 수 있다.

```bash
python3 -m unittest scripts/test_evaluate_agent.py
```

서버 연결과 문서 인제스트를 정상적으로 마친 실행은 시나리오 성공·실패 결과를 `evaluation/results/latest.json`과 `latest.md`에 기록한다. 서버 연결 실패나 인증 실패 같은 환경 오류가 발생하면 기존 결과를 덮어쓰지 않는다.

최근 실행의 요청별 판정과 지연 시간은 [evaluation/results/latest.md](evaluation/results/latest.md)에 보관한다. 모델 및 네트워크 환경에 따라 달라지는 값이므로 실행 시점과 base URL을 결과에 함께 기록한다.

## 기술 스택

- Java 21, Spring Boot 3.5.16
- Spring AI 1.1.8 (`ChatClient`, Advisors, Tool Calling, Chat Memory, VectorStore)
- OpenAI chat/embedding model
- Micrometer, Actuator, Prometheus registry
- JUnit 5, AssertJ, Mockito
