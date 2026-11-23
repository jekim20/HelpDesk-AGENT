#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

LOCAL_ONLY_FILE=
if [[ "${1:-}" == --local-only ]]; then
  [[ $# -eq 2 ]] || fail 'usage: backup-postgres.sh [--local-only OUTPUT.dump]'
  LOCAL_ONLY_FILE=$2
  [[ ! -e "$LOCAL_ONLY_FILE" && ! -L "$LOCAL_ONLY_FILE" ]] || fail "refusing to overwrite existing backup file"
fi

load_config
verify_data_mount
load_images "$ACTIVE_IMAGES_FILE"
load_secrets
need_command aws
umask 077

if [[ -n "$LOCAL_ONLY_FILE" ]]; then
  mkdir -p "$(dirname -- "$LOCAL_ONLY_FILE")"
  dump_file=$LOCAL_ONLY_FILE
  remove_dump=0
else
  : "${BACKUP_BUCKET:?BACKUP_BUCKET is required for S3 backup}"
  dump_file=$(mktemp "${TMPDIR:-/var/tmp}/helpdesk-pg-XXXXXXXX.dump")
  remove_dump=1
fi
cleanup() { (( remove_dump == 0 )) || rm -f -- "$dump_file"; }
trap cleanup EXIT

compose exec -T postgres pg_dump -U "$HELPDESK_DB_USERNAME" -d "$HELPDESK_DB_NAME" -Fc >"$dump_file"
[[ -s "$dump_file" ]] || fail "pg_dump produced an empty file"
chmod 600 "$dump_file"

if [[ -n "$LOCAL_ONLY_FILE" ]]; then
  printf 'Local backup created; it contains database data and must be protected: %s\n' "$dump_file"
else
  key="${BACKUP_PREFIX%/}/$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
  aws s3 cp "$dump_file" "s3://${BACKUP_BUCKET}/${key}" --region "$AWS_REGION" --sse AES256 \
    --only-show-errors --no-cli-pager || fail "S3 upload failed; deployment must not continue"
  printf 'Encrypted S3 backup uploaded: s3://%s/%s\n' "$BACKUP_BUCKET" "$key"
fi
