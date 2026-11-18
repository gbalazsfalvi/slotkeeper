#!/usr/bin/env bash
# Local owned-resource launcher. The Windows branch shares the PowerShell lock and behavior.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ACTION="${1:-up}"
case "$ACTION" in up|verify|rehearse|down|status) ;; *) echo 'Use up, verify, rehearse, down or status.' >&2; exit 2 ;; esac
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) exec pwsh -NoProfile -File "$ROOT/slotkeeper.ps1" "$ACTION" ;;
esac
cd "$ROOT"
RUNTIME="$ROOT/.local/runtime"
ENVIRONMENT="$RUNTIME/compose.env"
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"
command -v flock >/dev/null || { echo 'The local Linux launcher requires flock.' >&2; exit 2; }
exec 9>"$RUNTIME/launcher.lock"
flock --nonblock 9 || { echo 'Another launcher is active for this checkout.' >&2; exit 2; }

assert_local_docker() {
  docker info --format '{{.ServerVersion}}'
  local context endpoint
  context="$(docker context inspect --format '{{json .Endpoints.docker.Host}}')"
  endpoint="$(python3 -c 'import json,sys; print(json.load(sys.stdin))' <<<"$context")"
  for endpoint in "$endpoint" "${DOCKER_HOST:-$endpoint}"; do
    if [[ ! "$endpoint" =~ ^unix:/// && ! "$endpoint" =~ ^npipe:/{2,4}\./pipe/[a-zA-Z0-9._-]+$ && ! "$endpoint" =~ ^tcp://(localhost|127\.0\.0\.1|\[::1\]):[0-9]+$ ]]; then
      echo 'Only a local Docker endpoint is accepted.' >&2; exit 2
    fi
  done
}

toolchains() {
  java -version 2>&1 | grep -Eq 'version "21[."]' || { echo 'Select a Java 21 JDK in JAVA_HOME/PATH.' >&2; exit 2; }
  node --version | grep -Eq '^v24\.' || { echo 'Node 24 is required.' >&2; exit 2; }
  printf '%s  %s\n' '423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9' 'gradle/wrapper/gradle-wrapper.jar' | sha256sum --check --status
}

configuration() {
  if [[ ! -f "$ENVIRONMENT" ]]; then
    python3 - "$ROOT" "$ENVIRONMENT" <<'PY'
import hashlib,os,secrets,sys,uuid
root,output=sys.argv[1:]
values={"SLOTKEEPER_PROJECT_NAME":"slotkeeper-"+hashlib.sha256(root.lower().encode()).hexdigest()[:8],"SLOTKEEPER_OWNER_ID":str(uuid.uuid4())}
for name in ("OWNER","MIGRATION","APP"): values[f"SLOTKEEPER_{name}_PASSWORD"]=secrets.token_hex(32)
for name in ("APP","APP2","TRAINING"): values[f"SLOTKEEPER_{name}_INSTANCE_ID"]=str(uuid.uuid4())
with open(output,"x",encoding="utf-8") as stream:
 for key,value in values.items():stream.write(f"{key}={value}\n")
os.chmod(output,0o600)
PY
  fi
  local key value
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^SLOTKEEPER_[A-Z0-9_]+$ && "$value" =~ ^[a-zA-Z0-9-]+$ ]] || { echo 'Invalid private local configuration.' >&2; exit 2; }
    case "$key" in SLOTKEEPER_PROJECT_NAME|SLOTKEEPER_OWNER_ID|SLOTKEEPER_OWNER_PASSWORD|SLOTKEEPER_MIGRATION_PASSWORD|SLOTKEEPER_APP_PASSWORD|SLOTKEEPER_APP_INSTANCE_ID|SLOTKEEPER_APP2_INSTANCE_ID|SLOTKEEPER_TRAINING_INSTANCE_ID) declare -g "$key=$value"; export -n "$key" ;; *) echo 'Unknown private configuration key.' >&2; exit 2 ;; esac
  done <"$ENVIRONMENT"
  : "${SLOTKEEPER_PROJECT_NAME:?}" "${SLOTKEEPER_OWNER_ID:?}" "${SLOTKEEPER_APP_INSTANCE_ID:?}" "${SLOTKEEPER_APP2_INSTANCE_ID:?}" "${SLOTKEEPER_TRAINING_INSTANCE_ID:?}"
}

compose() (
  # Only this CLI child receives interpolation secrets; builds and tests do not inherit them.
  export SLOTKEEPER_PROJECT_NAME SLOTKEEPER_OWNER_ID SLOTKEEPER_OWNER_PASSWORD SLOTKEEPER_MIGRATION_PASSWORD SLOTKEEPER_APP_PASSWORD SLOTKEEPER_APP_INSTANCE_ID SLOTKEEPER_APP2_INSTANCE_ID SLOTKEEPER_TRAINING_INSTANCE_ID
  docker compose --project-name "$SLOTKEEPER_PROJECT_NAME" --env-file "$ENVIRONMENT" --project-directory "$ROOT" --file "$ROOT/compose.yaml" "$@"
)

assert_owned() {
  local identity owner identities
  identities="$(docker ps --all --quiet --filter "label=com.docker.compose.project=$SLOTKEEPER_PROJECT_NAME")" || return 2
  while IFS= read -r identity; do
    [[ -n "$identity" ]] || continue
    owner="$(docker inspect --format '{{index .Config.Labels "io.slotkeeper.owner"}}' "$identity")"
    [[ "$owner" == "$SLOTKEEPER_OWNER_ID" ]] || { echo 'Foreign container ownership; refusing mutation.' >&2; return 2; }
  done <<<"$identities"
  identities="$(docker volume ls --quiet --filter "label=com.docker.compose.project=$SLOTKEEPER_PROJECT_NAME")" || return 2
  while IFS= read -r identity; do
    [[ -n "$identity" ]] || continue
    owner="$(docker volume inspect --format '{{index .Labels "io.slotkeeper.owner"}}' "$identity")"
    [[ "$owner" == "$SLOTKEEPER_OWNER_ID" ]] || { echo 'Foreign volume ownership; refusing reuse.' >&2; return 2; }
  done <<<"$identities"
  identities="$(docker network ls --quiet --filter "label=com.docker.compose.project=$SLOTKEEPER_PROJECT_NAME")" || return 2
  while IFS= read -r identity; do
    [[ -n "$identity" ]] || continue
    owner="$(docker network inspect --format '{{index .Labels "io.slotkeeper.owner"}}' "$identity")"
    [[ "$owner" == "$SLOTKEEPER_OWNER_ID" ]] || { echo 'Foreign network ownership; refusing reuse.' >&2; return 2; }
  done <<<"$identities"
  local names expected
  names="$(docker volume ls --format '{{.Name}}')" || return 2
  expected="${SLOTKEEPER_PROJECT_NAME}_database"
  if grep -Fxq -- "$expected" <<<"$names"; then
    owner="$(docker volume inspect --format '{{index .Labels "io.slotkeeper.owner"}}' "$expected")" || return 2
    [[ "$owner" == "$SLOTKEEPER_OWNER_ID" ]] || { echo 'Expected volume ownership mismatch.' >&2; return 2; }
  fi
  names="$(docker network ls --format '{{.Name}}')" || return 2
  for expected in "${SLOTKEEPER_PROJECT_NAME}_private" "${SLOTKEEPER_PROJECT_NAME}_teaching"; do
    if grep -Fxq -- "$expected" <<<"$names"; then
      owner="$(docker network inspect --format '{{index .Labels "io.slotkeeper.owner"}}' "$expected")" || return 2
      [[ "$owner" == "$SLOTKEEPER_OWNER_ID" ]] || { echo 'Expected network ownership mismatch.' >&2; return 2; }
    fi
  done
}

wait_instance() {
  python3 - "$1" "$2" "${3:-Slotkeeper}" <<'PY'
import json,sys,time,urllib.request
port,expected,project=sys.argv[1:];deadline=time.monotonic()+120
while time.monotonic()<deadline:
 try:
  with urllib.request.urlopen(f"http://127.0.0.1:{int(port)}/api/instance",timeout=3) as response:marker=json.load(response)
  if marker.get("instanceId")!=expected or marker.get("project")!=project:raise SystemExit("Foreign local instance marker.")
  break
 except (OSError,ValueError):time.sleep(.5)
else:raise SystemExit("Owned local instance did not become ready.")
PY
}

build_product() { ./gradlew --console=plain --no-daemon --dependency-verification=strict :application:bootJar :verification:installDist :training-target:installDist; }

verify_product() {
  local changes revision
  changes="$(git status --porcelain --untracked-files=normal)" || { echo 'Cannot inspect the source tree.' >&2; exit 2; }
  if [[ "$ACTION" == rehearse && -n "$changes" ]]; then
    echo 'Commit the intended source changes before a revision-matched rehearsal.' >&2; exit 2
  fi
  revision="$(git rev-parse HEAD)" || { echo 'A local Git revision is required.' >&2; exit 2; }
  export SLOTKEEPER_REVISION="$revision"
  export SLOTKEEPER_CONTENTION_OUTPUT="$RUNTIME/database-contention.json"
  ./gradlew --console=plain --no-daemon --dependency-verification=strict clean verify :application:bootJar :verification:installDist :training-target:installDist
  [[ -f "$SLOTKEEPER_CONTENTION_OUTPUT" ]] || { echo 'No independent-process contention evidence was produced.' >&2; exit 1; }
  (cd web && npm run format:check)
  changes="$(git status --porcelain --untracked-files=normal)" || { echo 'Cannot inspect the verified source tree.' >&2; exit 2; }
  if [[ "$ACTION" == rehearse && -n "$changes" ]]; then
    echo 'Verification changed the source tree; commit the reviewed changes and rerun.' >&2; exit 2
  fi
}

assert_local_docker
if [[ ( "$ACTION" == status || "$ACTION" == down ) && ! -f "$ENVIRONMENT" ]]; then
  echo 'No private runtime configuration exists; existing resources are preserved.'; exit 0
fi
if [[ "$ACTION" == verify ]]; then toolchains; verify_product; echo 'Locked local verification passed.'; exit 0; fi
configuration
assert_owned
compose config --quiet
case "$ACTION" in
  up)
    toolchains; build_product
    compose up --detach --build --wait --wait-timeout 150 app
    wait_instance 4190 "$SLOTKEEPER_APP_INSTANCE_ID"
    echo 'Slotkeeper is ready: http://127.0.0.1:4190'
    ;;
  rehearse)
    toolchains; verify_product
    cleanup_secondary() {
      local primary_status=$? cleanup_status=0
      trap - EXIT
      if assert_owned; then compose --profile rehearsal stop app2 training || cleanup_status=$?; else cleanup_status=$?; fi
      if ((primary_status!=0)); then exit "$primary_status"; fi
      exit "$cleanup_status"
    }
    trap cleanup_secondary EXIT
    compose --profile rehearsal up --detach --build --wait --wait-timeout 150 app app2 training
    wait_instance 4190 "$SLOTKEEPER_APP_INSTANCE_ID"
    wait_instance 4192 "$SLOTKEEPER_APP2_INSTANCE_ID"
    wait_instance 4193 "$SLOTKEEPER_TRAINING_INSTANCE_ID" 'Slotkeeper Teaching Target'
    output="$ROOT/artifacts/rehearsal/$(date -u +%Y%m%dT%H%M%SZ)"
    SLOTKEEPER_PROBE_DB_PASSWORD="$SLOTKEEPER_APP_PASSWORD" \
      verification/build/install/verification/bin/verification \
      --app http://127.0.0.1:4190 --app-instance "$SLOTKEEPER_APP_INSTANCE_ID" \
      --app2 http://127.0.0.1:4192 --app2-instance "$SLOTKEEPER_APP2_INSTANCE_ID" \
      --training http://127.0.0.1:4193 --training-instance "$SLOTKEEPER_TRAINING_INSTANCE_ID" \
      --output "$output" --revision "$SLOTKEEPER_REVISION" \
      --contention-evidence "$SLOTKEEPER_CONTENTION_OUTPUT" \
      --database-url 'jdbc:postgresql://127.0.0.1:55419/slotkeeper?currentSchema=slotkeeper'
    echo "Observed rehearsal evidence: $output"
    ;;
  down) compose --profile rehearsal down; echo 'Owned containers stopped; the local database volume is retained.' ;;
  status) compose --profile rehearsal ps --all ;;
esac
