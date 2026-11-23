#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_config
need_command docker
need_command findmnt
need_command aws
need_command stat
need_file "$COMPOSE_FILE"
need_file "$ACTIVE_IMAGES_FILE"
load_images "$ACTIVE_IMAGES_FILE"

[[ "$(uname -m)" == aarch64 ]] || fail "host architecture is not aarch64"
docker_arch=$(docker info --format '{{.Architecture}}' 2>/dev/null) || fail "Docker daemon is unavailable"
[[ "$docker_arch" == aarch64 || "$docker_arch" == arm64 ]] || fail "Docker daemon is not ARM64"
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is unavailable"

[[ -d "$HELPDESK_EBS_MOUNT" ]] || fail "configured EBS mount directory does not exist"
verify_data_mount

[[ -n "${EBS_VOLUME_ID:-}" && "$EBS_VOLUME_ID" == vol-* ]] || fail "EBS_VOLUME_ID is not configured"
encrypted=$(aws ec2 describe-volumes --region "$AWS_REGION" --volume-ids "$EBS_VOLUME_ID" \
  --query 'Volumes[0].Encrypted' --output text --no-cli-pager) || fail "unable to verify EBS encryption"
[[ "$encrypted" == True ]] || fail "dedicated PostgreSQL EBS volume is not encrypted"

# Ensure the bind directory is writable by the UID in the pinned PostgreSQL image.
docker pull --quiet "$HELPDESK_POSTGRES_IMAGE" >/dev/null
pg_uid=$(docker run --rm --entrypoint sh "$HELPDESK_POSTGRES_IMAGE" -c 'id -u postgres' 2>/dev/null) || fail "cannot determine PostgreSQL container UID"
pg_gid=$(docker run --rm --entrypoint sh "$HELPDESK_POSTGRES_IMAGE" -c 'id -g postgres' 2>/dev/null) || fail "cannot determine PostgreSQL container GID"
if [[ "$EUID" -eq 0 ]]; then
  chown "$pg_uid:$pg_gid" "$HELPDESK_PGDATA_PATH"
  chmod 700 "$HELPDESK_PGDATA_PATH"
elif [[ "$(stat -c '%u:%g' "$HELPDESK_PGDATA_PATH")" != "$pg_uid:$pg_gid" ]]; then
  fail "run as root once to set PostgreSQL EBS directory ownership to the pinned image UID"
fi

load_secrets
compose config --quiet
printf 'Host checks passed: ARM64, Compose, encrypted EBS mount, and deployment configuration.\n'
