# DevDesk AI Architecture

## 요청 흐름

```text
User
  ↓
POST /api/chat
  ↓
ChatController ── HTTP validation
  ↓
HelpDeskService
  ├─ Safety validation (Memory보다 먼저)
  ├─ deterministic access route ── AccessTools ── AccessRepository(in-memory)
  ├─ explicit task route ───────── TicketTools ── TicketRepository(in-memory, PENDING)
  └─ conversational route
       ↓
     TokenMeter → Safety Advisor → Chat Memory → RAG → LLM + registered Tools
       ├─ primary client
       └─ fallback client (primary failure 시 1회)
  ↓
answer + sources + toolUsed + toolCalls[] + fallbackUsed

Agent Scenario Evaluation
  └─ 실제 /api/chat 응답으로 task/tool/source/safety/memory/latency 검증
```

## 주요 결정

### 확정 가능한 상태 조회는 deterministic하게 처리

리소스가 명확한 본인 권한 조회를 생성 모델 판단에 전부 맡기지 않는다. 규칙으로 intent와 지원 리소스를 확인한 뒤 `AccessTools`를 직접 호출한다. Tool 내부의 사용자 식별은 발화가 아닌 `ToolContext.userId`를 사용한다.

### 정보 질문과 작업 요청 분리

정책과 절차는 RAG 문서를 근거로 답한다. 권한 조회나 티켓 접수는 Repository를 외부 시스템 경계로 둔 Tool에서 수행한다. LLM 경로에도 두 Tool을 등록해 맥락이 필요한 표현을 처리할 수 있게 했다.

### write operation에 승인 gate 유지

`TicketTools`는 `ACCESS_REQUEST`, `INCIDENT`, `ACCOUNT_SUPPORT`를 만들 수 있지만 결과는 항상 `PENDING`이다. Agent는 권한 부여나 장애 해결을 직접 수행하지 않는다.

### Tool 실행 결과 추적

`ToolAuditAspect`가 Tool 이름, allowlist argument, 마스킹된 결과, 업무 성공 여부와 Tool 내부 실행 시간을 수집한다.
`ToolInvocationTracker`는 한 요청의 호출을 순서대로 보관하고 응답 생성 후 반드시 비운다. 기존 `toolUsed`는 하위 호환을 위해 유지한다.
Access Tool은 `resource`, Ticket Tool은 `type`과 `resource`만 공개하며 `ToolContext`, `userId`, 티켓 `reason`은 제외한다.

### 실패를 성공으로 바꾸지 않음

Access 조회 실패는 상태를 추측하지 않고 “조회할 수 없음”을 반환한다. Ticket 저장 실패는 “접수하지 못함”을 반환한다. write Tool은 중복 생성 위험 때문에 자동 retry하지 않는다.

### 대화 경계

Memory key는 `userId:sessionId`다. 동일 sessionId라도 userId가 다르면 이력을 공유하지 않는다. 20-message window로 대화 길이와 토큰을 제한한다.
