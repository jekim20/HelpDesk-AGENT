# DevDesk AI Architecture

## 요청 흐름

```text
User
  ↓
POST /api/chat (userId·sessionId는 클라이언트가 지정)
  ↓
ChatController ── HTTP validation
  ↓
HelpDeskService
  ├─ Prompt Injection·입력 안전성 검사 (Memory·RAG·Tool보다 먼저)
  ├─ deterministic access route ── AccessTools ── AccessRepository(in-memory)
  ├─ explicit task route ───────── TicketTools ── TicketRepository(PostgreSQL, PENDING)
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

리소스가 명확한 권한 조회를 생성 모델 판단에 전부 맡기지 않는다. 규칙으로 intent와 지원 리소스를 확인한 뒤 `AccessTools`를 직접 호출한다. Tool 내부의 사용자 식별은 질문 문장 속 ID가 아닌 `ToolContext.userId`를 사용한다. 현재 이 값은 인증 시스템이 아니라 HTTP 요청 본문의 `userId`에서 온다.

### 정보 질문과 작업 요청 분리

정책과 절차는 RAG 문서를 근거로 답하도록 구성했다. 권한 조회나 티켓 접수는 Repository를 외부 시스템 경계로 둔 Tool에서 수행한다. LLM 경로에도 두 Tool을 등록해 맥락이 필요한 표현을 처리할 수 있게 했다.

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
이는 저장 키의 분리다. 현재 호출자는 `userId`를 임의로 지정할 수 있고 `/api/history`와 `/api/admin/tickets`에도 인증·권한 검사가 없다. 이 구조는 로컬 데모용이며 실제 사용자 데이터가 있는 공개 서비스의 접근 통제가 아니다.

### 입력 Guard의 범위

`HelpDeskService.validate()`는 모델·RAG·Tool·Memory보다 먼저 Prompt Injection 규칙과 `UserInputSafetyAdvisor`의 비밀번호·OTP·API 키 형태 및 타 사용자 요청 표현을 검사한다. 모델 경로에서는 같은 Safety Advisor가 Memory Advisor보다 앞에 있다. 현재 `main`에는 별도 `PrivacyGuard`가 없으며 이메일·전화번호·주민번호의 포괄적 선차단을 주장하지 않는다.

### RAG source의 의미

`QuestionAnswerAdvisor`가 모델 요청의 검색을 처리한다. 응답의 `sources`는 `HelpDeskService.findSources()`가 **모델 답변 뒤 별도로 검색**한 파일명이다. 따라서 화면의 source 목록이나 source hit 지표가 답변의 문장별 근거·충실도를 증명하지는 않는다. 인메모리 VectorStore는 서버 재시작 후 다시 인제스트해야 한다.

### 실행 범위

`TicketRepository`는 PostgreSQL에 저장하며 Flyway migration을 사용한다. `AccessRepository`, Chat Memory와 VectorStore는 여전히 인메모리다. 실제 IAM·ITSM 연동은 범위 밖이다. 기본 fallback 모델명은 primary와 동일하며 `FALLBACK_MODEL`로 별도 지정할 수 있다.
