# 배포용 Compose 로컬 검증

`compose.yaml`은 기존 로컬 개발 구성이며 그대로 유지한다. AWS Linux 데모와 동일한 단일 호스트 구조는 `compose.deploy.yaml`로 분리했다. 이 구성은 Spring Boot, PostgreSQL, Nginx 정적 프론트엔드를 ARM64에서 실행하고, 호스트에는 프론트엔드만 `127.0.0.1`로 공개한다. DB와 Spring Boot 포트는 공개하지 않는다.

## 로컬 실행

1. Docker Engine을 실행하고 비밀값을 포함하지 않는 예제 파일을 기반으로 로컬 전용 env 파일을 준비한다. 아래 실제 배포 예에서는 테스트용 임의 비밀번호만 사용한다. 파일을 저장소에 넣지 않는다.

   ```sh
   cp .env.deploy.example /tmp/helpdesk-deploy.env
   # /tmp/helpdesk-deploy.env 안의 네 개 테스트 비밀번호를 로컬에서 교체한다.
   docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml up --build -d
   docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml ps
   curl --fail http://127.0.0.1:8080/
   curl --fail http://127.0.0.1:8080/api/csrf
   ```

2. 브라우저에서 `http://127.0.0.1:8080`을 연다. API, 로그인, 로그아웃은 같은 origin의 Nginx 경유로 Spring Boot에 전달된다. 이 포트는 AWS에 배포한 뒤 SSM 포트 포워딩의 원격 대상이 될 localhost 포트다. 외부 인터넷 공개 또는 HTTPS 종료 용도로 사용하지 않는다.

3. 개발 앱/DB를 대신하는 볼륨을 쓰지 않도록 이 Compose 파일 전용 `deploy_postgres_data` named volume을 사용한다. 중지 후 재기동은 데이터를 유지한다. 테스트가 끝난 뒤에도 볼륨은 보존하며 `down -v`는 실행하지 않는다.

앱 메모리 상한 1400 MiB, JVM 최대 heap 비율 60% (약 840 MiB), PostgreSQL 768 MiB, Nginx 128 MiB다. 서비스 상한 합계는 2296 MiB여서 4 GiB 인스턴스의 커널·Docker·페이지 캐시와 일시적인 여유 약 1.7 GiB를 남긴다. 이는 초기 안전 설정이지 실측 결과를 대체하지 않는다. 배포 전/후 `docker stats --no-stream`과 `free -h`로 다시 측정한다.

### 2026-11-11 Apple Silicon ARM64 기준선

`docker stats --no-stream` 단일 샘플이다. 시작 직후 샘플은 컨테이너 시작 약 2분 후, smoke 샘플은 인증·티켓 중복 재전송 후 수집했다. 유휴 후 샘플은 시작 후 최소 5분 경과 시 별도로 기록한다. 값은 앱/JVM 전체 메모리가 아니라 Docker가 보고한 컨테이너 사용량이다.

| 시점 | Spring Boot CPU / 메모리 | PostgreSQL CPU / 메모리 | Nginx CPU / 메모리 |
| --- | --- | --- | --- |
| 기동 후 약 2분 | 0.34% / 240.5 MiB | 0.00% / 74.51 MiB | 0.00% / 6.25 MiB |
| idle 5분 이상 | 0.52% / 252.5 MiB | 0.25% / 55.64 MiB | 0.00% / 6.78 MiB |
| 인증·티켓 smoke 직후 | 0.39% / 251.3 MiB | 0.75% / 55.59 MiB | 0.00% / 7.60 MiB |

컨테이너 상한 합계는 2.25 GiB 정도이며, 시작·smoke 시점 관측 합계는 약 0.30 GiB였다. 단일 사용자 데모에는 4 GiB가 실행 가능한 시작 사양으로 보인다. 이 관측은 작은 smoke 부하만 나타내므로 동시 사용자, RAG 적재, 외부 모델 요청의 최대 메모리를 보증하지 않는다. 초기 운영에서는 동시 요청 수와 실제 LLM/RAG 부하를 제한하고 OOM 및 메모리 추이를 확인한다. Docker Desktop의 Linux VM과 AWS Linux의 커널·디스크 성능은 같지 않으므로 EC2에서 재측정한다.

PostgreSQL의 `POSTGRES_PASSWORD`는 빈 데이터 디렉터리를 최초 초기화할 때만 적용된다. 이미 만들어진 named volume에서 env 값만 변경하면 DB role 비밀번호는 바뀌지 않는다. 테스트 볼륨도 보존하고, 비밀번호 변경 시 PostgreSQL role과 앱 설정을 조정된 절차로 함께 회전한다. 테스트 중 DB 연결 인증이 실패했을 때는 테스트 볼륨을 삭제하거나 재초기화하지 않고, 두 쪽의 설정 일치부터 확인한다.

## 테스트 DB 백업 및 복원

아래 명령은 이 전용 테스트 DB의 티켓만 다룬다. `DUMP`는 매번 새 경로로 지정하고 기존 파일을 덮어쓰지 않는다. 패스워드나 데이터가 들어간 덤프 파일은 저장소에 커밋하지 않는다.

```sh
set -eu
DUMP="/tmp/helpdesk-test-$(date +%Y%m%d-%H%M%S).dump"
docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml exec -T postgres \
  pg_dump -U helpdesk -d helpdesk -Fc > "$DUMP"

docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml exec -T postgres \
  createdb -U helpdesk helpdesk_restore_check
docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml exec -T postgres \
  pg_restore -U helpdesk -d helpdesk_restore_check --no-owner --no-privileges < "$DUMP"

docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml exec -T postgres \
  psql -U helpdesk -d helpdesk_restore_check -c \
  "select count(*) as tickets, count(idempotency_key) as idempotent_tickets from tickets;"
docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml exec -T postgres \
  psql -U helpdesk -d helpdesk_restore_check -c \
  "select conname from pg_constraint where conrelid = 'tickets'::regclass and conname = 'uq_tickets_requester_idempotency';"
```

`helpdesk_restore_check`는 검증 전용 DB다. 검증이 끝나도 원본 `helpdesk`나 볼륨은 건드리지 않는다. 복원 티켓 수, 멱등성 키 수, unique 제약 이름이 원본과 일치해야 한다.

## 중지와 이미지 롤백

```sh
docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml stop
docker compose --env-file /tmp/helpdesk-deploy.env -f compose.deploy.yaml start
```

AWS에서는 빌드 이미지에 Git commit SHA tag를 부여하고, 직전 승인 버전 SHA를 보관해 해당 태그로 되돌린다. `latest` 단독 사용은 피한다. 배포 자동화와 AWS 리소스 작업은 이 단계에 포함하지 않는다.

## 이 메모리 상한의 조정 기준

컨테이너 전체 메모리가 1400 MiB에 반복해서 접근하거나 JVM OOM이 발생하면 heap/비heap 분포와 idle·smoke 부하를 조사한 뒤 조정한다. PostgreSQL은 단일 사용자 데모의 초기 768 MiB 상한에서 시작한다. 앱 시작 직후, idle 5분, 인증·티켓 smoke 직후 각각 `docker stats --no-stream` 결과를 기록한다. Apple Silicon 로컬에서 ARM64 결과는 네이티브이며, CI는 `linux/arm64` 이미지 빌드를 에뮬레이션해 검증한다.
