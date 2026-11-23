#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

[[ $# -eq 3 ]] || fail 'usage: record-release.sh <app@sha256:digest> <frontend@sha256:digest> <postgres@sha256:digest>'
load_config
app=$1
frontend=$2
postgres=$3
validate_image_ref HELPDESK_APP_IMAGE "$app"
validate_image_ref HELPDESK_FRONTEND_IMAGE "$frontend"
validate_image_ref HELPDESK_POSTGRES_IMAGE "$postgres"
[[ "$app" == "${ECR_APP_REPOSITORY}@sha256:"* ]] || fail "app image must come from the configured ECR repository"
[[ "$frontend" == "${ECR_FRONTEND_REPOSITORY}@sha256:"* ]] || fail "frontend image must come from the configured ECR repository"

release_dir=${HELPDESK_RELEASE_DIR:-/etc/helpdesk/releases}
install -d -m 700 "$release_dir"
release_id=$(date -u +%Y%m%dT%H%M%SZ)
output="$release_dir/${release_id}.env"
[[ ! -e "$output" ]] || fail "release file already exists; refusing to overwrite"
umask 077
tmp=$(mktemp "$release_dir/.release-XXXXXXXX")
trap 'rm -f -- "$tmp"' EXIT
{
  printf 'HELPDESK_APP_IMAGE=%s\n' "$app"
  printf 'HELPDESK_FRONTEND_IMAGE=%s\n' "$frontend"
  printf 'HELPDESK_POSTGRES_IMAGE=%s\n' "$postgres"
} >"$tmp"
install -m 600 "$tmp" "$output"
printf 'Immutable image release recorded: %s\n' "$output"
