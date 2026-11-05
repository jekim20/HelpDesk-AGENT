# 로컬 Docker 실행

이 구성은 Spring Boot와 전용 PostgreSQL 컨테이너를 실행합니다. React는 기존 Vite 개발 서버를 호스트에서 실행합니다. DB 포트는 호스트에 공개하지 않고 API는 `127.0.0.1:8080`에만 공개합니다.

## 사전 준비

- Docker Desktop 또는 Docker Engine과 Docker Compose v2
- Java나 Gradle을 호스트에 설치할 필요는 없습니다. 이미지는 Java 21과 Gradle Wrapper로 빌드합니다.
- 모델 기능을 시험하지 않는 인증·결정형 Tool 확인에는 OpenAI API Key가 필요하지 않습니다.

## 환경변수

저장소 루트에서 예제 파일을 복사하고 로컬 개발용 비밀번호를 설정합니다.

```sh
cp .env.docker.example .env.docker.local
```

`.env.docker.local`에서 `HELPDESK_DB_PASSWORD`, `APP_USER1_PASSWORD`, `APP_USER2_PASSWORD`, `APP_ADMIN_PASSWORD`에 서로 구분되는 로컬 값을 지정합니다. 이 파일은 Git과 Docker 빌드 컨텍스트에서 제외됩니다. 모델 경로를 테스트할 때만 `HELPDESK_OPENAI_API_KEY`를 설정하세요. 빈 값이면 애플리케이션은 `not-set`을 사용하며 외부 모델 요청은 성공하지 않습니다. 이 접두 변수명을 사용해 저장소 루트의 기존 `.env`에 있는 키가 실수로 Compose에 주입되지 않도록 했습니다.

## 빌드 및 실행

```sh
docker compose --env-file .env.docker.local config --quiet
docker compose --env-file .env.docker.local up --build -d
docker compose --env-file .env.docker.local ps
curl --fail http://127.0.0.1:8080/actuator/health
```

Compose는 PostgreSQL health check가 성공한 뒤 Spring Boot를 시작합니다. 시작할 때 Flyway가 V1과 V2를 적용합니다. 동일 호스트 포트를 이미 사용 중이면 기존 프로세스를 종료하거나 Compose의 API 포트 매핑을 다른 로컬 포트로 바꾸고, Vite proxy 설정도 같은 포트로 맞춰야 합니다.

## React 개발 화면

기존 개발 환경을 사용합니다.

```sh
cd frontend
npm ci
npm run dev
```

브라우저에서 Vite 화면에 접속해 `user1`, `user2` 또는 `admin`으로 로그인합니다. 자격 증명은 `.env.docker.local`의 비밀번호를 사용합니다. Vite proxy는 호스트의 `localhost:8080`으로 API 요청을 전달합니다.

USER 계정에서 DEV_DB 권한 상태 확인과 명시적 티켓 신청을 확인할 수 있습니다. 관리자 화면은 티켓 목록을 보여줍니다. `Initialize policy knowledge`는 임베딩 API 요청을 발생시킬 수 있습니다. API Key와 비용 승인이 준비되지 않았다면 실행하지 마세요.

API로 확인하려면 먼저 `/api/csrf`를 조회하고 반환된 `token`과 `headerName`을 사용해 로그인 및 POST 요청을 보냅니다. 세션 쿠키와 CSRF 토큰을 브라우저 외부에 공유하지 마세요.

## 데이터 보존과 종료

PostgreSQL 데이터는 `helpdesk-agent-local` Compose 프로젝트의 `postgres_data` named volume에 저장됩니다. 애플리케이션 컨테이너를 재생성해도 티켓은 유지됩니다. `docker compose down`은 컨테이너와 네트워크만 종료하며 DB volume을 보존합니다.

```sh
docker compose --env-file .env.docker.local down
```

데이터 보존을 확인하는 동안 `docker compose down -v`나 `docker volume rm`을 실행하지 마세요. 이 개발 DB는 기존 호스트 PostgreSQL 및 `.env`의 `DATABASE_URL`과 분리되어 있습니다.

## 제한 사항

- PostgreSQL에는 티켓 데이터만 저장합니다. Chat Memory와 Vector Store는 메모리 기반이며 애플리케이션 재시작 후 유지되지 않습니다.
- 사용자 계정은 환경변수에서 생성되는 로컬 계정입니다.
- API Key가 비어 있으면 RAG 및 일반 모델 경로는 외부 모델 호출이 필요한 시점에 사용할 수 없습니다. 인증, 권한 조회, 티켓 생성은 결정형 경로로 확인할 수 있습니다.
- 시작 로그에 Jakarta Bean Validation provider 경고가 표시될 수 있습니다. 현재 Controller와 Service에서 `@Valid`/Jakarta 제약 어노테이션을 사용하지 않고 명시적인 입력 검증을 하므로 현 요청 경로에는 영향이 없습니다. 어노테이션 기반 검증을 도입할 때 `spring-boot-starter-validation` 추가를 검토하세요.
- 이미지 빌드에는 Gradle Wrapper 배포본과 Maven 의존성 다운로드를 위한 네트워크가 필요합니다.
