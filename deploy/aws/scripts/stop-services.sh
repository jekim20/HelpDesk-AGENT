#!/usr/bin/env bash
set -Eeuo pipefail
set +x
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_config
load_images "$ACTIVE_IMAGES_FILE"
load_secrets
compose stop
printf 'Compose services stopped; named/bind data was preserved. EC2, EBS, S3, ECR, public IPv4, snapshots, and network resources may still incur charges.\n'
printf 'Stop the EC2 instance separately after confirming no session is active; do not delete its PostgreSQL EBS volume.\n'
