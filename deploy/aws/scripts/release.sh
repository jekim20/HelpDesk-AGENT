#!/usr/bin/env bash
# Shared by deploy.sh and rollback.sh after sourcing common.sh.

begin_release() {
  need_command docker
  need_command curl
  need_command flock
  need_command install
  [[ "$ACTIVE_IMAGES_FILE" != "$PREVIOUS_IMAGES_FILE" ]] || fail "manifest paths must be distinct"
  umask 077
  mkdir -p -- "$(dirname -- "$ACTIVE_IMAGES_FILE")" "$(dirname -- "$PREVIOUS_IMAGES_FILE")"
  exec 9>"${ACTIVE_IMAGES_FILE}.lock"
  flock -n 9 || fail "another release operation holds the deployment lock"
  pending_dir="${ACTIVE_IMAGES_FILE}.pending"
  [[ ! -e "$pending_dir" && ! -L "$pending_dir" ]] ||
    fail "an unfinished release requires manual recovery: $pending_dir"
  stage_dir=$(mktemp -d "${ACTIVE_IMAGES_FILE}.stage.XXXXXXXX")
  trap 'if [[ -n "$stage_dir" ]]; then rm -rf -- "$stage_dir"; fi' EXIT
}

pull_candidate() (
  # Isolate ECR credentials and run cleanup even if login exits with an error.
  unset ECR_DOCKER_CONFIG
  trap ecr_logout EXIT
  ecr_login
  compose pull --quiet "$@" || fail "candidate image pull failed"
)

publish_manifest() {
  local source=$1 target=$2 tmp
  [[ ! -d "$target" ]] || return 1
  tmp=$(mktemp "${target}.tmp.XXXXXXXX") || return 1
  if install -m 600 -- "$source" "$tmp" && mv -f -- "$tmp" "$target"; then
    return 0
  fi
  rm -f -- "$tmp"
  return 1
}

up_application() {
  # Explicit services and --no-deps keep Compose from reconciling PostgreSQL.
  compose up -d --no-deps --no-build --pull never app frontend
}

stop_application() {
  local running
  compose stop frontend app || return 1
  running=$(compose ps -q app frontend) || return 1
  [[ -z "$running" ]]
}

clear_pending() {
  rm -rf -- "$pending_dir" || fail "release state could not be cleared: $pending_dir"
}

recover_release() {
  local manifests_restored=$1
  if [[ -f "$pending_dir/original.env" ]]; then
    load_images "$pending_dir/original.env"
    if up_application && wait_for_services; then
      printf 'Original app/frontend restored and verified healthy. PostgreSQL was left in place.\n' >&2
      if [[ "$manifests_restored" == yes ]]; then
        clear_pending
        return 0
      fi
      printf 'ERROR: Manual manifest reconciliation required: %s\n' "$pending_dir" >&2
      return 1
    fi
  elif stop_application; then
    clear_pending
    printf 'Initial deployment did not activate. App/frontend stopped; PostgreSQL state was preserved.\n' >&2
    return 0
  fi
  if stop_application; then
    printf 'App/frontend stopped after recovery failed.\n' >&2
  else
    printf 'ERROR: Could not confirm that app/frontend stopped. Inspect the running containers.\n' >&2
  fi
  printf 'ERROR: Release recovery failed. Active manifest, if present, records the last verified release. Manual recovery required: %s\n' "$pending_dir" >&2
  return 1
}

apply_release() {
  local initialize_postgres=$1 previous_updated=0 manifests_restored=yes
  # Save the recovery inputs before any container can be replaced. An interruption
  # leaves this directory in place so the next operator can reconcile state.
  mv -- "$stage_dir" "$pending_dir"
  stage_dir=

  if { [[ "$initialize_postgres" == no ]] ||
       compose up -d --no-deps --no-build --pull never --no-recreate postgres; } &&
     wait_for_postgres && up_application && wait_for_services; then
    if [[ -f "$pending_dir/original.env" ]]; then
      if publish_manifest "$pending_dir/original.env" "$PREVIOUS_IMAGES_FILE"; then
        previous_updated=1
      else
        printf 'ERROR: Could not publish the previous manifest.\n' >&2
      fi
    fi
    if [[ ! -f "$pending_dir/original.env" ]] || (( previous_updated )); then
      if publish_manifest "$pending_dir/candidate.env" "$ACTIVE_IMAGES_FILE"; then
        clear_pending
        printf 'Release is healthy. Active image digests recorded at %s.\n' "$ACTIVE_IMAGES_FILE"
        return 0
      fi
      printf 'ERROR: Could not publish the active manifest.\n' >&2
    fi
  else
    printf 'ERROR: Candidate did not start or pass health validation.\n' >&2
  fi

  if (( previous_updated )); then
    if [[ -f "$pending_dir/previous.env" ]]; then
      publish_manifest "$pending_dir/previous.env" "$PREVIOUS_IMAGES_FILE" || manifests_restored=no
    else
      rm -f -- "$PREVIOUS_IMAGES_FILE" || manifests_restored=no
    fi
  fi
  recover_release "$manifests_restored" || return 1
  return 1
}

snapshot_manifests() {
  local release_file=$1
  install -m 600 -- "$release_file" "$stage_dir/candidate.env"
  if [[ -f "$ACTIVE_IMAGES_FILE" ]]; then
    install -m 600 -- "$ACTIVE_IMAGES_FILE" "$stage_dir/original.env"
  fi
  if [[ -f "$PREVIOUS_IMAGES_FILE" ]]; then
    install -m 600 -- "$PREVIOUS_IMAGES_FILE" "$stage_dir/previous.env"
  fi
}

postgres_data_initialized() {
  compose exec -T postgres sh -c 'test -f "$PGDATA/PG_VERSION"' >/dev/null 2>&1
}

check_original_release() {
  local mode=$1 candidate_postgres=$HELPDESK_POSTGRES_IMAGE
  load_images "$stage_dir/original.env"
  [[ "$HELPDESK_POSTGRES_IMAGE" == "$candidate_postgres" ]] ||
    fail "PostgreSQL image changes require a separate planned database migration"
  postgres_data_initialized ||
    fail "existing release is missing its initialized PostgreSQL data directory"
  validate_local_images || fail "original images must be available locally for recovery"
  if [[ "$mode" == deploy ]]; then
    wait_for_services || fail "active manifest does not describe a healthy running release"
  else
    # An unhealthy app is a reason to roll back. Still verify the DB and
    # refuse to overwrite containers deployed outside the active manifest.
    wait_for_postgres || fail "existing PostgreSQL is not healthy at the recorded image"
    verify_original_container app "$HELPDESK_APP_IMAGE"
    verify_original_container frontend "$HELPDESK_FRONTEND_IMAGE"
  fi
  load_images "$stage_dir/candidate.env"
}

verify_original_container() {
  local service=$1 image=$2 container
  container=$(compose ps -a -q "$service") || fail "unable to inspect the existing $service container"
  if [[ -n "$container" ]]; then
    container_uses_image "$container" "$image" || fail "existing $service does not match the active manifest"
  fi
}

check_initial_postgres() {
  local existing_pg running
  running=$(compose ps -q app frontend) || fail "unable to check for existing application containers"
  [[ -z "$running" ]] || fail "application containers are running without an active manifest"
  existing_pg=$(compose ps -a -q postgres) || fail "unable to check for an existing PostgreSQL container"
  if [[ -n "$existing_pg" ]]; then
    # A failed first activation can leave an initialized, running database. Its
    # container reference is the pin until the first healthy release is recorded.
    postgres_data_initialized ||
      fail "unverified PostgreSQL data state; inspect the existing container and mount"
    wait_for_postgres || fail "existing PostgreSQL is not healthy at the requested digest"
    initialize_postgres=no
  else
    local -a data_files
    [[ -r "$HELPDESK_PGDATA_PATH" && -x "$HELPDESK_PGDATA_PATH" ]] ||
      fail "PostgreSQL data directory cannot be inspected; refusing initialization"
    shopt -s nullglob dotglob
    data_files=("$HELPDESK_PGDATA_PATH"/*)
    shopt -u nullglob dotglob
    (( ${#data_files[@]} == 0 )) ||
      fail "existing PostgreSQL data has no verified container pin; recover it manually"
    initialize_postgres=yes
  fi
}
