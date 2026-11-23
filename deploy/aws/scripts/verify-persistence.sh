#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_config
verify_data_mount
load_images "$ACTIVE_IMAGES_FILE"
load_secrets
wait_for_services || fail "services are not healthy before restart check"
before=$(ticket_fingerprint)
compose restart postgres

deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  pg_id=$(compose ps -q postgres)
  pg_status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$pg_id" 2>/dev/null || true)
  [[ "$pg_status" == healthy ]] && break
  sleep 3
done
[[ "${pg_status:-}" == healthy ]] || fail "PostgreSQL did not become healthy after restart"

compose restart app frontend
wait_for_services || fail "services did not recover after restart"
after=$(ticket_fingerprint)
[[ "$before" == "$after" ]] || fail "ticket/idempotency data changed across restart"
printf 'Restart persistence verified: ticket/idempotency/pending counts and ticket-key fingerprint are unchanged.\n'
