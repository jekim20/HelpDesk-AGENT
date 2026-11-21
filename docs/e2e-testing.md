# Browser E2E 테스트

## 실행

최초 한 번 Playwright와 Chromium을 준비하고, 이후에는 한 명령으로 격리된 시스템을 세우고 검증한다.

```sh
npm ci --prefix e2e
npm exec --prefix e2e -- playwright install chromium
npm test --prefix e2e
```

`npm test --prefix e2e`는 매 실행마다 고유 Compose project와 새 PostgreSQL volume을 사용한다. 임의의 DB·USER·ADMIN 비밀번호는 권한 `0600`인 임시 env 파일로만 주입하며 프로세스 종료 시 삭제한다. 테스트 모델 대역은 E2E overlay에만 있고, OpenAI API key 대신 무효한 고정 테스트 문자열을 사용한다. `compose.deploy.yaml`과 개발 `compose.yaml`의 AI endpoint는 바뀌지 않는다.

테스트 종료 시 runner는 생성한 고유 project에 한해 `docker compose down --volumes --rmi local`로 컨테이너·테스트 DB·전용 volume·로컬 테스트 이미지를 정리한다. 실행마다 무작위 project name을 사용하므로 기존 개발 컨테이너/볼륨을 정리 대상으로 사용하지 않는다. 실패하면 자격증명·세션 식별자를 가린 Compose 로그를 보존한다.

## 검증 구분

이 테스트는 **결정론적 브라우저 통합 E2E**다. Chromium에서 production React 파일을 열고 실제 Spring Security 세션·CSRF, Spring Boot API, Spring AI의 OpenAI 호환 HTTP client, `QuestionAnswerAdvisor`/`SimpleVectorStore`, JDBC repository, PostgreSQL까지 연결한다. 별도 Python 서버가 임베딩과 채팅 응답을 고정 반환한다. 그러므로 RAG retrieval·서비스 라우팅·DB 결과를 테스트하지만 실제 OpenAI 모델의 정책 이해력이나 실제 임베딩 품질을 측정하지 않는다. Mock을 Live LLM E2E라고 부르지 않는다.

이 suite는 다음을 확인한다.

- 잘못된 로그인은 401이며 브라우저에 안전한 안내를 표시한다.
- 정책 문서 인제스트 후 VPN 질문의 검색 출처와 결정론 모델 답변을 표시하고, 읽기 Tool Trace는 발생하지 않는다.
- USER 권한 조회 및 티켓 접수는 동작하고, 관리자 API 접근은 403이다. 같은 `sessionId`에서도 서로 다른 사용자의 대화 기록은 분리된다.
- 티켓 화면·API·PostgreSQL의 요청자, 상태, 티켓 ID가 일치한다. 동일 idempotency key의 재전송은 한 행만 만든다.
- 잘못된 리소스, 입력 길이 경계(UI 빈 질문), Prompt Injection, 개인정보 입력이 모델/Tool 경로를 호출하지 않는다.
- AI endpoint의 결정론 실패는 UI에 표시되고 티켓 행을 만들지 않는다. primary 실패 후 성공한 fallback은 화면에 표시된다.

실제 Live 모델/RAG 평가는 이 suite에서 실행하지 않는다. 기존 Python Agent 평가와 `LiveRag*` Java 테스트는 opt-in 별도 경로이며, 유효한 API key와 명시적인 실행 승인이 있어야 한다. 본 suite는 외부 호출 없이 실행한다.

## 결과와 증빙

각 실행은 `artifacts/e2e/runs/<UTC timestamp>-<id>/`에 고유 디렉터리로 저장한다.

- `run-summary.json`: 실행 시각, 결과 수, LLM/AWS 비호출 확인
- `playwright-results.json`, `html-report/`: 테스트 결과
- 실제 Chromium 화면 캡처: 정책 RAG, 로그인 실패, 사용자 분리, 티켓/멱등성, fallback 복구
- `database-verification.txt`: 티켓·멱등성 행 수, 상태, DB constraint, Flyway 버전만 기록
- 실패 시 `compose-failure.log`: 테스트 fixture 비밀값·쿠키·Authorization/API key 패턴을 가린 로그

화면 캡처는 브라우저에서 테스트를 실제 실행하며 생성한다. 테스트 계정과 문장은 더미 데이터다. 결과를 저장소에 공유할 때는 run directory 전체 대신 선택한 스크린샷과 요약 JSON만 추가한다. HTML report는 테스트 코드와 화면 내용을 포함할 수 있으므로 비공개 artifact로 다룬다.

## 최신 로컬 검증

2026-11-19 UTC 실행 `artifacts/e2e/runs/2026-11-19T043740-488Z-dc4fa2/`에서 Chromium 시나리오 6개가 모두 통과했다. 같은 Docker Compose 실행에서 이미지 빌드, PostgreSQL/Flyway 초기화, 정적 파일과 API 연결, health check, 로그인·CSRF 흐름을 확인했다. 사후 DB 확인값은 티켓 3건, 멱등성 키 3건, 모두 `PENDING`, 요청자 `admin,user1`, unique 제약 `true`, Flyway 버전 `1,2`였다. 실제 OpenAI와 AWS 호출은 없었다.

같은 날 전체 Java 테스트는 133개 중 130 통과·3 스킵·0 실패였다. 전용 PostgreSQL 통합 테스트 9개와 Spring Security 통합 테스트 16개가 통과했고, Python evaluator 테스트 33개 및 frontend production build도 통과했다. Java 스킵 3개는 외부 API를 쓰는 Live RAG 테스트이며 실행하지 않았다. E2E 실행의 JSON/HTML 리포트와 캡처는 run directory에 있다. `artifacts/e2e/runs/`는 raw report의 실수 커밋을 막도록 Git ignore 대상이다. 선별한 실제 캡처와 JUnit 증빙은 [포트폴리오 검증 기록](portfolio/e2e-validation-2026-11-19.md)에 연결했다.

E2E 과정에서 예외가 `/error`로 전달될 때 deny-all 보안 규칙이 ERROR dispatcher까지 막아 AI 의존성 오류가 403으로 보이는 결함을 발견했다. `DispatcherType.ERROR`만 허용하고 일반 API 접근 제어는 유지했다. MockMvc 회귀 테스트와 브라우저에서 HTTP 500, 티켓 데이터 무변경, fallback 복구를 확인했다. 결정론 mock은 HTTP/1.1 framing을 명시해 embedding 요청 연결을 안정적으로 유지한다.
