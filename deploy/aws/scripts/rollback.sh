#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_config
verify_data_mount
need_file "$ACTIVE_IMAGES_FILE"
need_file "$PREVIOUS_IMAGES_FILE"
load_images "$PREVIOUS_IMAGES_FILE"
load_secrets
need_command install

rollback_candidate=$(mktemp "${TMPDIR:-/var/tmp}/helpdesk-rollback-XXXXXXXX.env")
trap 'rm -f -- "$rollback_candidate"' EXIT
install -m 600 "$ACTIVE_IMAGES_FILE" "$rollback_candidate" 2>/dev/null || true
install -m 600 "$PREVIOUS_IMAGES_FILE" "$ACTIVE_IMAGES_FILE"
ecr_login
if compose pull --quiet; then pull_ok=1; else pull_ok=0; fi
ecr_logout
if (( pull_ok )) && validate_local_images && compose up -d --no-build --remove-orphans && wait_for_services; then
  install -m 600 "$rollback_candidate" "$PREVIOUS_IMAGES_FILE"
  printf 'Previous app/frontend image digests are healthy. PostgreSQL and its data were not rolled back.\n'
else
  install -m 600 "$rollback_candidate" "$ACTIVE_IMAGES_FILE"
  fail "rollback did not become healthy; active manifest restored, inspect logs before retrying"
fi
