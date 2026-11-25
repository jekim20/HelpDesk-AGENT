#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=release.sh
source "$SCRIPT_DIR/release.sh"

[[ $# -eq 1 ]] || fail 'usage: deploy.sh <release-images.env>'
load_config
verify_data_mount
need_file "$1"
begin_release
snapshot_manifests "$1"
load_images "$stage_dir/candidate.env"
load_secrets

initialize_postgres=no
if [[ -f "$stage_dir/original.env" ]]; then
  check_original_release deploy
else
  check_initial_postgres
fi

pull_services=(app frontend)
if [[ "$initialize_postgres" == yes ]]; then pull_services+=(postgres); fi
pull_candidate "${pull_services[@]}"
validate_local_images || fail "candidate images did not pass platform validation"
if [[ -f "$stage_dir/original.env" ]]; then
  "$SCRIPT_DIR/backup-postgres.sh"
fi
apply_release "$initialize_postgres"
