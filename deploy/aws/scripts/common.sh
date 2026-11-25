#!/usr/bin/env bash
set -Eeuo pipefail
set +x

AWS_DEPLOY_CONFIG=${AWS_DEPLOY_CONFIG:-/etc/helpdesk/aws-deploy.env}
ACTIVE_IMAGES_FILE=${ACTIVE_IMAGES_FILE:-/var/lib/helpdesk/active-images.env}
PREVIOUS_IMAGES_FILE=${PREVIOUS_IMAGES_FILE:-/var/lib/helpdesk/previous-images.env}
COMPOSE_FILE=${COMPOSE_FILE:-}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-helpdesk-agent}

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
need_file() { [[ -f "$1" ]] || fail "required file is missing: $1"; }
need_command() { command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"; }

load_config() {
  need_file "$AWS_DEPLOY_CONFIG"
  # This file contains non-secret operator-controlled settings only.
  # shellcheck disable=SC1090
  source "$AWS_DEPLOY_CONFIG"
  : "${AWS_REGION:?AWS_REGION is required}"
  : "${COMPOSE_FILE:?COMPOSE_FILE is required}"
  : "${HELPDESK_PGDATA_PATH:?HELPDESK_PGDATA_PATH is required}"
  : "${HELPDESK_EBS_MOUNT:?HELPDESK_EBS_MOUNT is required}"
  : "${ECR_APP_REPOSITORY:?ECR_APP_REPOSITORY is required}"
  : "${ECR_FRONTEND_REPOSITORY:?ECR_FRONTEND_REPOSITORY is required}"
  : "${PARAM_DB_PASSWORD:?PARAM_DB_PASSWORD is required}"
  : "${PARAM_USER1_PASSWORD:?PARAM_USER1_PASSWORD is required}"
  : "${PARAM_USER2_PASSWORD:?PARAM_USER2_PASSWORD is required}"
  : "${PARAM_ADMIN_PASSWORD:?PARAM_ADMIN_PASSWORD is required}"
  : "${HELPDESK_DB_NAME:=helpdesk}"
  : "${HELPDESK_DB_USERNAME:=helpdesk}"
  : "${DEMO_PORT:=8080}"
  : "${HEALTH_TIMEOUT_SECONDS:=300}"
  : "${BACKUP_PREFIX:=helpdesk/postgres}"
  export AWS_REGION COMPOSE_PROJECT_NAME COMPOSE_FILE HELPDESK_PGDATA_PATH HELPDESK_EBS_MOUNT
  export HELPDESK_DB_NAME HELPDESK_DB_USERNAME DEMO_PORT HEALTH_TIMEOUT_SECONDS BACKUP_PREFIX
}

validate_image_ref() {
  local name=$1 value=$2
  [[ "$value" =~ ^[A-Za-z0-9._:/-]+@sha256:[a-f0-9]{64}$ ]] || fail "$name must be an image reference pinned by a 64-character sha256 digest"
}

load_images() {
  local file=${1:-$ACTIVE_IMAGES_FILE}
  need_file "$file"
  unset HELPDESK_APP_IMAGE HELPDESK_FRONTEND_IMAGE HELPDESK_POSTGRES_IMAGE
  # This file contains image references, not credentials.
  # shellcheck disable=SC1090
  source "$file"
  : "${HELPDESK_APP_IMAGE:?HELPDESK_APP_IMAGE is missing}"
  : "${HELPDESK_FRONTEND_IMAGE:?HELPDESK_FRONTEND_IMAGE is missing}"
  : "${HELPDESK_POSTGRES_IMAGE:?HELPDESK_POSTGRES_IMAGE is missing}"
  validate_image_ref HELPDESK_APP_IMAGE "$HELPDESK_APP_IMAGE"
  validate_image_ref HELPDESK_FRONTEND_IMAGE "$HELPDESK_FRONTEND_IMAGE"
  validate_image_ref HELPDESK_POSTGRES_IMAGE "$HELPDESK_POSTGRES_IMAGE"
  if [[ -n "${ECR_APP_REPOSITORY:-}" && "$HELPDESK_APP_IMAGE" != "$ECR_APP_REPOSITORY@sha256:"* ]]; then
    fail "application image is outside the configured ECR repository"
  fi
  if [[ -n "${ECR_FRONTEND_REPOSITORY:-}" && "$HELPDESK_FRONTEND_IMAGE" != "$ECR_FRONTEND_REPOSITORY@sha256:"* ]]; then
    fail "frontend image is outside the configured ECR repository"
  fi
  export HELPDESK_APP_IMAGE HELPDESK_FRONTEND_IMAGE HELPDESK_POSTGRES_IMAGE
}

load_secrets() {
  need_command aws
  local name value
  for name in PARAM_DB_PASSWORD PARAM_USER1_PASSWORD PARAM_USER2_PASSWORD PARAM_ADMIN_PASSWORD; do
    [[ -n "${!name:-}" ]] || fail "$name must name an SSM SecureString parameter"
    value=$(aws ssm get-parameter --region "$AWS_REGION" --name "${!name}" --with-decryption \
      --query 'Parameter.Value' --output text --no-cli-pager) || fail "unable to load a required SSM parameter"
    [[ -n "$value" && "$value" != "None" ]] || fail "a required SSM parameter is empty"
    case "$name" in
      PARAM_DB_PASSWORD) HELPDESK_DB_PASSWORD=$value ;;
      PARAM_USER1_PASSWORD) APP_USER1_PASSWORD=$value ;;
      PARAM_USER2_PASSWORD) APP_USER2_PASSWORD=$value ;;
      PARAM_ADMIN_PASSWORD) APP_ADMIN_PASSWORD=$value ;;
    esac
  done
  HELPDESK_OPENAI_API_KEY=not-set
  if [[ -n "${PARAM_OPENAI_API_KEY:-}" ]]; then
    value=$(aws ssm get-parameter --region "$AWS_REGION" --name "$PARAM_OPENAI_API_KEY" --with-decryption \
      --query 'Parameter.Value' --output text --no-cli-pager) || fail "unable to load optional AI parameter"
    [[ -n "$value" && "$value" != "None" ]] || fail "optional AI parameter is empty"
    HELPDESK_OPENAI_API_KEY=$value
  fi
  export HELPDESK_DB_PASSWORD APP_USER1_PASSWORD APP_USER2_PASSWORD APP_ADMIN_PASSWORD HELPDESK_OPENAI_API_KEY
}

compose() {
  docker compose --project-name "$COMPOSE_PROJECT_NAME" --env-file /dev/null -f "$COMPOSE_FILE" "$@"
}

verify_data_mount() {
  need_command findmnt
  local mount_target pg_target
  mount_target=$(findmnt -n -o TARGET --target "$HELPDESK_EBS_MOUNT" 2>/dev/null || true)
  pg_target=$(findmnt -n -o TARGET --target "$HELPDESK_PGDATA_PATH" 2>/dev/null || true)
  [[ "$mount_target" == "$HELPDESK_EBS_MOUNT" && "$pg_target" == "$HELPDESK_EBS_MOUNT" ]] \
    || fail "PostgreSQL data path is not backed by the dedicated EBS mount"
  [[ -d "$HELPDESK_PGDATA_PATH" ]] || fail "PostgreSQL EBS data directory does not exist"
}

wait_for_postgres() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS)) id status
  while (( SECONDS < deadline )); do
    id=$(compose ps -q postgres 2>/dev/null || true)
    if [[ -n "$id" ]]; then
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)
      if [[ "$status" == healthy ]]; then
        container_uses_image "$id" "$HELPDESK_POSTGRES_IMAGE"
        return
      fi
      [[ "$status" == exited || "$status" == dead ]] && return 1
    fi
    sleep 3
  done
  return 1
}

ecr_login() {
  need_command aws
  need_command docker
  local registry=${ECR_APP_REPOSITORY%%/*}
  [[ -n "$registry" && "$registry" != "$ECR_APP_REPOSITORY" ]] || fail "ECR_APP_REPOSITORY is invalid"
  [[ "${ECR_FRONTEND_REPOSITORY%%/*}" == "$registry" ]] || fail "app and frontend images must use the same ECR registry"
  local runtime_root=/run
  if [[ ! -d "$runtime_root" || ! -w "$runtime_root" ]]; then
    runtime_root=${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}
  fi
  ECR_DOCKER_CONFIG=$(mktemp -d "$runtime_root/helpdesk-ecr-auth.XXXXXXXX")
  chmod 700 "$ECR_DOCKER_CONFIG"
  export DOCKER_CONFIG="$ECR_DOCKER_CONFIG"
  aws ecr get-login-password --region "$AWS_REGION" --no-cli-pager \
    | docker login --username AWS --password-stdin "$registry" >/dev/null \
    || fail "ECR login failed"
}

ecr_logout() {
  [[ -n "${ECR_DOCKER_CONFIG:-}" ]] || return 0
  local registry=${ECR_APP_REPOSITORY%%/*}
  docker logout "$registry" >/dev/null 2>&1 || true
  rm -f -- "$ECR_DOCKER_CONFIG/config.json"
  rmdir -- "$ECR_DOCKER_CONFIG" 2>/dev/null || true
  unset DOCKER_CONFIG ECR_DOCKER_CONFIG
}

validate_local_images() {
  local image arch
  for image in "$HELPDESK_POSTGRES_IMAGE" "$HELPDESK_APP_IMAGE" "$HELPDESK_FRONTEND_IMAGE"; do
    arch=$(docker image inspect --format '{{.Architecture}}' "$image" 2>/dev/null) || {
      printf 'ERROR: a pinned image is missing after pull.\n' >&2
      return 1
    }
    if [[ "$arch" != arm64 ]]; then
      printf 'ERROR: a pinned image is not ARM64.\n' >&2
      return 1
    fi
  done
}

container_uses_image() {
  local container=$1 expected=$2 actual expected_id actual_id
  actual=$(docker inspect --format '{{.Config.Image}}' "$container" 2>/dev/null) || return 1
  [[ "$actual" == "$expected" ]] || return 1
  expected_id=$(docker image inspect --format '{{.Id}}' "$expected" 2>/dev/null) || return 1
  actual_id=$(docker inspect --format '{{.Image}}' "$container" 2>/dev/null) || return 1
  [[ -n "$expected_id" && "$actual_id" == "$expected_id" ]]
}

wait_for_services() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS)) service id status
  local services=(postgres app frontend)
  while (( SECONDS < deadline )); do
    local all_healthy=1
    for service in "${services[@]}"; do
      id=$(compose ps -q "$service" 2>/dev/null || true)
      if [[ -z "$id" ]]; then all_healthy=0; break; fi
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)
      if [[ "$status" != healthy ]]; then
        if [[ "$status" == exited || "$status" == dead ]]; then return 1; fi
        all_healthy=0
        break
      fi
      local expected_name
      case "$service" in
        postgres) expected_name=HELPDESK_POSTGRES_IMAGE ;;
        app) expected_name=HELPDESK_APP_IMAGE ;;
        frontend) expected_name=HELPDESK_FRONTEND_IMAGE ;;
      esac
      if ! container_uses_image "$id" "${!expected_name}"; then
        all_healthy=0
        break
      fi
    done
    if (( all_healthy )); then
      curl --fail --silent --max-time 10 "http://127.0.0.1:${DEMO_PORT}/" >/dev/null || { sleep 2; continue; }
      return 0
    fi
    sleep 5
  done
  return 1
}

ticket_fingerprint() {
  local database=${1:-$HELPDESK_DB_NAME}
  compose exec -T postgres psql -U "$HELPDESK_DB_USERNAME" -d "$database" -Atqc \
    "SELECT count(*) || ':' || count(idempotency_key) || ':' || count(*) FILTER (WHERE status='PENDING') || ':' || coalesce(md5(string_agg(id || ':' || coalesce(idempotency_key,''), ',' ORDER BY id)), md5('')) FROM tickets;"
}
