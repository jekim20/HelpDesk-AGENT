#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

[[ $# -eq 1 ]] || fail 'usage: restore-test-postgres.sh <local.dump|s3://bucket/key>'
source_backup=$1
load_config
verify_data_mount
load_images "$ACTIVE_IMAGES_FILE"
load_secrets
need_command aws
umask 077

if [[ "$source_backup" == s3://* ]]; then
  dump_file=$(mktemp "${TMPDIR:-/var/tmp}/helpdesk-restore-XXXXXXXX.dump")
  trap 'rm -f -- "$dump_file"' EXIT
  aws s3 cp "$source_backup" "$dump_file" --region "$AWS_REGION" --only-show-errors --no-cli-pager \
    || fail "unable to download requested backup"
else
  [[ -f "$source_backup" ]] || fail "backup file does not exist"
  dump_file=$source_backup
fi
[[ -s "$dump_file" ]] || fail "backup file is empty"

restore_db="restore_check_$(date -u +%Y%m%d%H%M%S)_$$"
compose exec -T postgres createdb -U "$HELPDESK_DB_USERNAME" -T template0 "$restore_db"
# A failed restore is intentionally left in place for diagnosis. No existing database is dropped.
docker compose --project-name "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" exec -T postgres \
  pg_restore -U "$HELPDESK_DB_USERNAME" -d "$restore_db" --no-owner --no-privileges <"$dump_file"

result=$(compose exec -T postgres psql -U "$HELPDESK_DB_USERNAME" -d "$restore_db" -Atqc \
  "SELECT count(*) || ':' || count(idempotency_key) || ':' || count(*) FILTER (WHERE status='PENDING') FROM tickets;")
constraint=$(compose exec -T postgres psql -U "$HELPDESK_DB_USERNAME" -d "$restore_db" -Atqc \
  "SELECT count(*) FROM pg_constraint WHERE conrelid='tickets'::regclass AND conname='uq_tickets_requester_idempotency';")
[[ "$constraint" == 1 ]] || fail "restored database is missing the ticket idempotency constraint"
printf 'Restore verified in isolated database %s: tickets/idempotent/pending=%s; unique constraint=present.\n' \
  "$restore_db" "$result"
printf 'The restore database is preserved for inspection; it was not dropped.\n'
