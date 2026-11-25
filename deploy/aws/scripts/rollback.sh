#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
# shellcheck source=release.sh
source "$SCRIPT_DIR/release.sh"

[[ $# -eq 0 ]] || fail 'usage: rollback.sh'
load_config
verify_data_mount
begin_release
need_file "$ACTIVE_IMAGES_FILE"
need_file "$PREVIOUS_IMAGES_FILE"
snapshot_manifests "$PREVIOUS_IMAGES_FILE"
load_images "$stage_dir/candidate.env"
load_secrets
check_original_release rollback
# A locally cached rollback target can be activated during an ECR outage.
if ! validate_local_images; then
  pull_candidate app frontend
  validate_local_images || fail "rollback images did not pass platform validation"
fi
apply_release no
