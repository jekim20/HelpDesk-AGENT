# HelpDesk AI Agent — 이력서 및 면접 자료

## 이력서용 요약

**프로젝트 소개 (1문장)**

Spring AI와 문서 검색을 활용해 헬프데스크 문의에 답하고, 인증·권한 검사와 PostgreSQL 티켓 저장을 갖춘 업무형 HelpDesk Agent입니다.

**개인 역할 — 제출 전 확인 필요**

`[직접 설계·구현·검증한 범위만 기입: 기능/파일, 본인 작업, 본인이 확인한 테스트 증거]`

**검증된 프로젝트 결과 (개인 수행 주장과 분리)**

Docker Live 평가에서 15개 시나리오·17개 턴이 통과했고 Tool 선택·인자·실행은 각각 6/6이었습니다. Java는 129개 통과·3개 스킵, Python evaluator는 마지막 기록 기준 33개 통과이며, PostgreSQL 재시작과 동시 요청 멱등성 검증 결과가 있습니다.

> Git 커밋 작성자만으로 코드 작성·설계의 세부 개인 기여를 증명할 수는 없습니다. Codex도 사용되었으므로 제출 전에 본인이 직접 한 설계·구현·검증과 AI 도구가 작성·보조한 부분을 구분하세요. 아래 확인표를 먼저 작성하고 Live 15/15는 단일 고정 평가 결과로 한정해 표기하세요.

### 본인 기여 확인표

| 확인할 내용 | 제출 전 남길 증거 |
|---|---|
| 직접 내린 설계 판단 | 대안 비교, 선택 근거, 본인이 결정한 범위 |
| 직접 작성·수정한 코드 | 파일/클래스와 본인이 실제 변경한 부분. Codex 작성 코드와 구별 |
| 직접 수행한 검증 | 실행 명령, 환경, 직접 확인한 결과 파일 또는 출력 |
| Codex 사용 범위 | 코드·테스트·문서 초안에서 받은 도움과 본인이 검토·수정한 내용 |
| 설명 가능한 주장 | 각 기술 주장에 대해 코드 위치와 실패 사례를 설명할 수 있는지 |

Git 기록은 프로젝트 변경 단위를 보여주지만, 본인과 Codex 사이의 작업 분담은 확정하지 않습니다.

## 60초 아키텍처 설명

"React가 Vite proxy를 통해 Spring Boot API를 호출합니다. Spring Security가 세션과 CSRF를 검증하고, 서버는 요청 본문의 사용자 ID가 아니라 인증 principal을 사용합니다. `HelpDeskService`가 입력 안전성을 확인한 다음 세 가지 경로로 나눕니다. 권한 조회는 결정형 읽기 Tool이고, 일반 질문은 RAG와 사용자별 Chat Memory를 거치는 모델 경로입니다. 티켓 생성은 모델이 자유롭게 호출하지 못하게 일반 LLM 경로에서 쓰기 Tool을 빼고, 서버의 명시적 요청 분기만 `TicketTools`에 접근시킵니다. 티켓 저장은 Spring JDBC와 PostgreSQL을 쓰며 Flyway로 스키마를 관리합니다. 요청 키와 사용자별 DB unique 제약으로 재시도를 처리합니다. Docker Compose는 Spring Boot와 PostgreSQL을 실행하고, React/Vite와 Python 평가기는 별도 프로세스로 실행합니다. 계정, 권한 샘플, Memory, Vector Store는 데모용 인메모리라 운영 서비스라고 부르지는 않습니다."

## 주요 기술 판단

### 왜 Spring JDBC와 Flyway인가요?

티켓의 저장·조회 경로가 단순해서 ORM의 객체 매핑보다 SQL과 트랜잭션 경계를 명시하는 편이 이해하기 쉬웠습니다. JDBC로 SQL과 unique conflict 처리를 직접 확인하고, Flyway로 schema 변경을 순서가 있는 migration으로 기록했습니다. 단점은 SQL과 매핑 코드를 직접 관리해야 한다는 점입니다.

### 왜 HTTP 세션과 CSRF를 함께 썼나요?

브라우저 기반 SPA에 적합한 서버 세션을 사용했고, 쿠키가 자동 첨부되는 브라우저 요청의 CSRF 위험을 유지해서 다뤘습니다. CSRF를 비활성화하지 않고 쿠키 토큰을 변경 요청 헤더에 전달했습니다. 프런트엔드 UI에서 역할 버튼을 숨기는 것은 편의이고, 보안 결정은 API의 Spring Security에서 이뤄집니다.

### 요청 본문의 userId를 왜 무시하거나 거부하나요?

사용자가 수정할 수 있는 본문 필드는 신원을 증명하지 못합니다. 인증 principal을 대화 Memory key, 권한 조회, ToolContext, 티켓 `requestedBy`의 기준으로 사용합니다. legacy 필드가 principal과 다르면 요청을 거부해 조용한 범위 변경을 막습니다.

### 모델이 티켓을 만들지 못하게 한 방식은 무엇인가요?

모델 prompt만으로 금지하지 않았습니다. 일반 `callModel()`에는 읽기 전용 Access Tool만 넘기고, 티켓 생성은 서버의 결정형 명시 요청 경로에서만 Ticket Tool을 호출합니다. 이는 모델이 일반 질문 중 쓰기를 선택하는 경로를 제거하지만, 규칙 기반 의도 분류가 모든 자연어를 완벽히 이해한다는 뜻은 아닙니다.

### Idempotency Key가 보장하는 범위는?

클라이언트가 한 번의 논리적 제출에 UUID key를 만들고 재시도에 같은 key를 유지합니다. DB에서 사용자 ID와 key의 조합을 unique로 만들고, 요청 fingerprint가 같으면 기존 티켓을 반환합니다. 같은 key에 내용이 달라지면 409입니다. 새 key를 사용한 별도 제출까지 중복으로 판단하지 않으며, 이는 정당한 새 요청과 재시도를 구분하기 위한 선택입니다.

### 왜 fingerprint를 저장하나요?

같은 key가 다른 티켓 내용에 재사용되는 경우 기존 결과를 잘못 돌려주지 않으려는 목적입니다. fingerprint는 정규화한 요청 주요 필드의 SHA-256이며, 요청 원문을 별도 멱등성 테이블에 복제해 보관하지 않습니다. 해시는 비밀화 수단이 아니므로 입력 정책 및 개인정보 최소화는 별도로 필요합니다.

### RAG 평가 100%는 정확도 100%인가요?

아닙니다. 현재 source hit는 기대한 출처 문서가 검색 결과에 포함됐는지를 측정합니다. 답변 전체가 그 문서에 충실한지, 환각이 없는지 전부 보장하지 않습니다. AG-10의 경우 과거 적절한 답변이 좁은 문자열 판정 때문에 실패했습니다. 평가기 v2는 주제 연결, 근거 부재 설명, 근거 없는 구체 정책 단정을 함께 살피도록 개선했습니다. 따라서 15/15는 한 번의 고정 평가 결과이고, AG-10 개선은 모델 개선이 아닌 evaluator 개선입니다.

## 실패·오류 해결 사례

### 응답이 유실된 뒤 중복 티켓이 생길 수 있었음

서버 메모리 중복 체크는 재시작·동시 요청을 견디지 못합니다. 사용자별 key unique 제약과 트랜잭션으로 DB가 최종 원자성 기준이 되게 했습니다. 반복 요청과 동일 key 동시 요청을 실제 PostgreSQL에서 검증했습니다.

### 인메모리 저장소는 재시작하면 티켓을 잃음

티켓만 PostgreSQL로 옮기고 Flyway migration을 추가했습니다. DB 및 애플리케이션 재시작 뒤 데이터가 유지되는지 확인했습니다. Chat Memory, 사용자 및 Vector Store는 계속 인메모리여서 같은 보존을 주장하지 않습니다.

### AG-10 의미상 맞는 답이 실패로 판정됨

답변은 문서에 정보가 없다고 정확히 안내했지만 과거 evaluator가 미리 정한 특정 표현 일부만 찾았습니다. 의미상 정보 부재를 주제와 연결하는 규칙을 보강하고, 무근거 정책 단정과 무관한 키워드 답변을 거부하는 단위 테스트를 두었습니다. 이 변경을 모델 성능 향상이라고 부르지 않습니다.

## 예상 기술 질문과 답변

### ToolContext에 사용자 신원을 어떻게 전달했나요?

컨트롤러에서 인증 객체를 얻어 서비스에 전달하고, 서비스가 생성하는 ToolContext에 principal ID를 넣습니다. 티켓 Tool과 Access Tool은 요청 본문에서 사용자 ID를 다시 읽지 않습니다. 티켓 Repository에는 `requestedBy`로 전달되어 DB에서 확인할 수 있습니다.

### 동시 티켓 ID 발급은 어떻게 안전하게 했나요?

티켓 번호는 PostgreSQL sequence에서 발급합니다. 애플리케이션의 `count + 1` 방식은 동시 트랜잭션에서 충돌할 수 있지만 DB sequence는 동시 호출을 처리합니다. 별도의 primary key와 sequence로 충돌을 방지합니다.

### Tool Trace는 실제 실행을 어떻게 검증하나요?

호출된 Tool 이름, 정리된 인자와 결과를 요청 단위로 추적하고, 실행 성공·지연을 기록합니다. Tool trace만으로 DB commit을 증명하지 않으므로 Live 검증에서는 trace의 티켓 ID와 PostgreSQL row의 ID·requestedBy·status·key를 대조했습니다.

### 사용자 이력은 어디까지 격리되나요?

Memory conversation ID를 사용자 ID와 세션 ID의 조합으로 만듭니다. History API도 principal 기준으로 조회합니다. 이는 단일 인스턴스의 인메모리 경계이며, 분산 저장소나 수평 확장 환경에서 영속 격리를 검증한 것은 아닙니다.

### 왜 사용자 확인 상태 머신을 추가하지 않았나요?

현재 범위는 기존 명시적 신청 동작을 지키면서 일반 LLM 경로의 우발 쓰기를 막는 것이었습니다. 확인 상태를 추가하면 다중 턴 상태 저장, 만료, 재생 방지, 사용자별 세션을 정의해야 하므로 별도 설계와 테스트가 필요합니다. 현재는 문구 규칙이 확실하지 않으면 쓰기하지 않는 방향이지만, 규칙 휴리스틱의 언어 범위는 남은 한계입니다.

### 운영 배포 전에 가장 먼저 보완할 것은 무엇인가요?

실제 IdP와 사용자/권한 원장을 연결하고, 인증 계정과 Memory/Vector Store의 데이터 수명주기를 정해야 합니다. 그 다음 비밀관리, DB 백업/복구, 운영 관측성과 비용 한도, 모델 교체 호환성을 정식 환경에서 검증하겠습니다. Docker Compose 성공만으로 운영 준비가 끝났다고 보지는 않습니다.

### gpt-4o-mini 교체 시 어떤 위험이 있나요?

현재 모델명은 `application.yml`과 fallback 기본값에 있습니다. OpenAI는 GPT-4o mini snapshot의 종료일을 2027-01-14로 안내하고 `gpt-6-luna`를 권장합니다. 저장소는 snapshot ID가 아닌 `gpt-4o-mini` alias를 사용하므로 실제 종료 영향은 별도로 확인해야 합니다. Luna의 Chat Completions tool calling은 reasoning effort를 `none`으로 설정해야 한다고 모델 문서가 명시합니다. 이 프로젝트의 Spring AI 1.1.8에서 옵션 전달과 새 모델 호환성은 검증하지 않았고 모델 교체도 구현되지 않았습니다. 변경 전후 고정 평가와 Tool 호출·응답 형식·지연·토큰 비용 검증이 필요합니다.

## 결과 수치 요약

- Docker Live Agent 평가: 시나리오 15/15, 턴 17/17
- Tool 선택/인자/실행: 각 6/6
- Java JUnit: 129 pass, 3 skipped, 0 failed (132 total)
- Python evaluator: 33 pass
- 전체 요청 latency: 평균 831.69 ms, median 10.65 ms, P95 3,092.71 ms
- Live 격리 DB에서 티켓 3건 확인; user1 요청자, `PENDING`, 고유 idempotency key
- 실제 PostgreSQL 같은 key 동시 요청 12건에서 1 ticket 확인

평가 원본: [Docker 최종 실행 결과](../../evaluation/results/runs/docker-live-final-20261105-203123-602029/latest.json). 전체 latency는 모델 호출만의 시간이 아닙니다. 최신 대체 모델의 종료 정보는 [OpenAI deprecation 문서](https://developers.openai.com/api/docs/deprecations), 호환성은 [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna) 및 [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) 문서에서 확인합니다.
