#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

[[ $# -eq 1 ]] || fail 'usage: deploy.sh <release-images.env>'
release_file=$1
load_config
verify_data_mount
need_file "$release_file"
if [[ ! -f "$ACTIVE_IMAGES_FILE" && -f "$HELPDESK_PGDATA_PATH/PG_VERSION" ]]; then
  fail "existing PostgreSQL data has no active image manifest; restore its known version before deploying"
fi
load_images "$release_file"
candidate_postgres_image=$HELPDESK_POSTGRES_IMAGE
load_secrets
need_command docker
need_command curl
need_command install
mkdir -p "$(dirname -- "$ACTIVE_IMAGES_FILE")"
chmod 700 "$(dirname -- "$ACTIVE_IMAGES_FILE")"

if [[ -f "$ACTIVE_IMAGES_FILE" ]]; then
  load_images "$ACTIVE_IMAGES_FILE"
  if [[ "$HELPDESK_POSTGRES_IMAGE" != "$candidate_postgres_image" ]]; then
    fail "PostgreSQL image changes require a separate planned database migration"
  fi
  existing_pg=$(compose ps -a -q postgres 2>/dev/null || true)
  if [[ -n "$existing_pg" || -f "$HELPDESK_PGDATA_PATH/PG_VERSION" ]]; then
    # Start only the existing database to make a consistent pre-deployment backup.
    compose up -d postgres
    wait_for_postgres || fail "existing PostgreSQL data did not become healthy; refusing deployment"
    "$SCRIPT_DIR/backup-postgres.sh"
    install -m 600 "$ACTIVE_IMAGES_FILE" "$PREVIOUS_IMAGES_FILE"
  fi
fi

candidate=$(mktemp "${TMPDIR:-/var/tmp}/helpdesk-images-XXXXXXXX.env")
install -m 600 "$release_file" "$candidate"
trap 'rm -f -- "$candidate"' EXIT
install -m 600 "$candidate" "$ACTIVE_IMAGES_FILE"
load_images "$ACTIVE_IMAGES_FILE"

ecr_login
if ! compose pull --quiet; then
  ecr_logout
  printf 'ERROR: candidate image pull failed. Previous active release was not changed to running state.\n' >&2
  if [[ -f "$PREVIOUS_IMAGES_FILE" ]]; then install -m 600 "$PREVIOUS_IMAGES_FILE" "$ACTIVE_IMAGES_FILE"; fi
  exit 1
fi
ecr_logout
if ! validate_local_images; then
  printf 'ERROR: candidate image platform validation failed.\n' >&2
  if [[ -f "$PREVIOUS_IMAGES_FILE" ]]; then install -m 600 "$PREVIOUS_IMAGES_FILE" "$ACTIVE_IMAGES_FILE"; fi
  exit 1
fi
if compose up -d --no-build --remove-orphans && wait_for_services; then
  printf 'Deployment is healthy. Active image digests are recorded at %s.\n' "$ACTIVE_IMAGES_FILE"
  exit 0
fi

printf 'ERROR: candidate failed health validation. Attempting image rollback.\n' >&2
if [[ -f "$PREVIOUS_IMAGES_FILE" ]]; then
  install -m 600 "$PREVIOUS_IMAGES_FILE" "$ACTIVE_IMAGES_FILE"
  load_images "$ACTIVE_IMAGES_FILE"
  if compose pull --quiet && compose up -d --no-build --remove-orphans && wait_for_services; then
    printf 'Previous application/frontend image digests restored and healthy. Database was not rolled back.\n' >&2
    exit 1
  fi
  printf 'ERROR: image rollback did not become healthy; use the documented manual recovery procedure.\n' >&2
else
  compose stop app frontend || true
  printf 'No previous image manifest exists. Initial deployment services were stopped; PostgreSQL data was preserved.\n' >&2
fi
exit 1
