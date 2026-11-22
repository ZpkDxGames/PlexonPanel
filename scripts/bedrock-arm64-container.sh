#!/usr/bin/env bash
set -euo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }

[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted &&
   ${GITHUB_REPOSITORY:-} == ZpkDxGames/PlexonPanel ]] ||
  die 'This script requires a disposable PlexonPanel GitHub-hosted runner.'
[[ $(uname -m) == aarch64 ]] || die 'The staging host must run native aarch64.'
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 ]] || die 'Ubuntu 24.04 is required.'
[[ ${GITHUB_RUN_ID:-} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:-} =~ ^[0-9]+$ ]] ||
  die 'Missing GitHub Actions run identity.'

export DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT DOCKER_TLS_VERIFY
run_root="${RUNNER_TEMP:?}/bedrock-phase2-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
container_name=tonimbedrock
label=com.plexonpanel.staging.run
version=1.26.66.1
download_url="https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-${version}.zip"

case ${1:-} in
  start)
    [[ $# == 1 && ${BEDROCK_IMAGE_DIGEST:-} =~ ^sha256:[0-9a-f]{64}$ ]] ||
      die 'Supply an immutable itzg ARM64 manifest digest.'
    [[ $(docker version --format '{{.Server.Os}}/{{.Server.Arch}}') == linux/arm64 ]] ||
      die 'The disposable Docker daemon must run native linux/arm64.'
    [[ $(docker ps --all --quiet --filter "name=^/${container_name}$") == '' ]] ||
      die 'A container already owns the staging name.'
    image="docker.io/itzg/minecraft-bedrock-server@${BEDROCK_IMAGE_DIGEST}"
    mkdir -m 0700 "$run_root"
    data="$run_root/data"
    evidence="$run_root/evidence"
    mkdir -m 0700 "$data" "$evidence"
    run_id=$(cat /proc/sys/kernel/random/uuid)
    server_name="plexonpanel-phase2-$run_id"
    level_name=PhaseTwoDisposable
    printf '%s\n' "$run_id" > "$run_root/run-id"
    printf 'BEDROCK_EVIDENCE=%s\n' "$evidence" >> "${GITHUB_ENV:?}"

    docker pull --platform linux/arm64 "$image"
    [[ $(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}') == linux/arm64 ]] ||
      die 'The pulled manifest did not resolve to linux/arm64.'
    image_id=$(docker image inspect "$image" --format '{{.Id}}')
    [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] || die 'Docker returned an invalid image ID.'
    network=$(docker network create --driver bridge --label "$label=$run_id" "plexon-phase2-$run_id")
    [[ $network =~ ^[0-9a-f]{64}$ ]] || die 'Docker returned an invalid network ID.'
    printf '%s\n' "$network" > "$run_root/network-id"

    # The owner requested an isolated run of the actual Minecraft image. All game data is created here.
    container=$(docker run --detach --interactive --tty --pull never --platform linux/arm64 \
      --name "$container_name" --label "$label=$run_id" --network "$network" \
      --restart unless-stopped --stop-signal SIGTERM --stop-timeout 60 \
      --log-driver json-file --log-opt max-size=10m --log-opt max-file=2 --memory 2g \
      --mount "type=bind,src=$data,dst=/data" \
      --env EULA=TRUE --env "VERSION=$version" --env "DIRECT_DOWNLOAD_URL=$download_url" \
      --env USE_BOX64=true --env TZ=UTC --env "UID=$(id -u)" --env "GID=$(id -g)" \
      --env "SERVER_NAME=$server_name" --env "LEVEL_NAME=$level_name" --env MAX_PLAYERS=4 \
      --env ENABLE_SSH=false --env ALLOW_LIST=true --env ENABLE_LAN_VISIBILITY=false \
      --env TRANSPORT=nethernet --env SERVER_PORT=19132 --env SERVER_UDP_PORTS=19140-19155 "$image")
    [[ $container =~ ^[0-9a-f]{64}$ ]] || die 'Docker returned an invalid container ID.'
    printf '%s\n' "$container" > "$run_root/container-id"

    docker inspect "$container" | jq -e --arg run "$run_id" --arg label "$label" \
      --arg source "$data" --arg image "$image_id" '
        .[0] | .Name == "/tonimbedrock" and .Config.Labels[$label] == $run and .Image == $image
          and .Config.Tty and .Config.OpenStdin and (.Config.StdinOnce | not)
          and .Config.StopSignal == "SIGTERM" and .HostConfig.RestartPolicy.Name == "unless-stopped"
          and .HostConfig.LogConfig.Type == "json-file" and (.HostConfig.Privileged | not)
          and ((.HostConfig.PortBindings // {}) | length == 0)
          and (.Mounts | length == 1 and all(.Type == "bind" and .RW and .Source == $source and .Destination == "/data"))
      ' > /dev/null
    probe_host=$(docker inspect "$container" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
    [[ $probe_host =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die 'Docker did not assign a private IPv4 address.'
    probe="http://${probe_host}:19132/v1/join"

    # Retain only startup fields and expected readiness evidence, never the world or raw logs.
    jq -n --arg architecture "$(uname -m)" --arg operatingSystem "$PRETTY_NAME" \
      --arg dockerVersion "$(docker version --format '{{.Server.Version}}')" \
      --arg javaVersion "$(java -version 2>&1 | head -n1)" --arg runtimeImage "$image" \
      --arg imageId "$image_id" --arg requestedVersion "$version" --arg downloadUrl "$download_url" \
      --arg runtimeRun "$run_id" --arg coreCommit "$(git rev-parse HEAD)" \
      --arg dashboardCommit "$(git -C "$BEDROCK_DASHBOARD_CHECKOUT" rev-parse HEAD)" \
      '{architecture:$architecture, operatingSystem:$operatingSystem, dockerVersion:$dockerVersion,
        javaVersion:$javaVersion, runtimeImage:$runtimeImage, imageId:$imageId, requestedVersion:$requestedVersion,
        downloadUrl:$downloadUrl, runtimeRun:$runtimeRun, coreCommit:$coreCommit, dashboardCommit:$dashboardCommit,
        logDriver:"json-file", tty:true, openStdin:true, stdinOnce:false, restartPolicy:"unless-stopped",
        stopSignal:"SIGTERM", requestedTransport:"nethernet", gamePortsPublished:false,
        imageParity:"PRODUCTION_DIGEST_UNAVAILABLE", startup:"NOT_READY"}' | tee "$evidence/runtime.json"

    ready=false
    deadline=$((SECONDS + 240))
    while (( SECONDS < deadline )); do
      if curl --fail --silent --noproxy '*' --max-time 3 --max-filesize 65536 "$probe" > "$run_root/join.json" &&
         jq -e --arg name "$server_name" --arg level "$level_name" \
           --arg version "$version" --arg displayVersion "${version%.*}" '
              .name == $name and .level == $level and (.version == $version or .version == $displayVersion)
                and .players == 0 and .maxPlayers == 4 and (.protocol | type == "number" and . > 0)
            ' "$run_root/join.json" > /dev/null 2>&1; then
        ready=true
        break
      fi
      [[ $(docker inspect "$container" --format '{{.State.Running}}') == true ]] || break
      sleep 2
    done
    if [[ $ready != true ]]; then
      docker inspect "$container" --format 'state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}'
      docker logs --tail 40 "$container" 2>&1 | \
        sed -E 's/((token|password|secret|xuid)[[:space:]]*[:=][[:space:]]*)[^[:space:]]+/\1[REDACTED]/Ig' || true
      die 'The disposable Bedrock server did not reach verified NetherNet readiness.'
    fi
    # Box64 sets its process title to the game executable on some releases. Check startup output and process args.
    docker top "$container" -eo args > "$run_root/process-titles"
    docker logs "$container" > "$run_root/startup-logs" 2>&1
    grep -Eiq '(^|[[:space:]/])box64[[:space:]].*bedrock_server' "$run_root/process-titles" ||
      grep -Eiq '(^|[^[:alnum:]])box64([^[:alnum:]]|$).*(dynarec|bits|arm64|aarch64|version)' "$run_root/startup-logs" ||
      die 'Bedrock is ready but Box64 execution was not verified.'
    grep -Fq "Version: $version" "$run_root/startup-logs" || die 'Bedrock did not log the pinned version.'
    executable="$data/bedrock_server-$version"
    LC_ALL=C readelf -h "$executable" | grep -Eqi 'Machine:.*x86-64' ||
      die 'The staged release did not contain the expected x86-64 Bedrock executable.'
    executable_sha=$(sha256sum "$executable")
    jq --arg gameExecutableSha "${executable_sha%% *}" --slurpfile response "$run_root/join.json" \
      '.startup = "PASS" | .box64 = "VERIFIED" | .gameExecutableSha256 = $gameExecutableSha
       | .netherNet = ($response[0] | {name,level,version,protocol,players,maxPlayers})' \
      "$evidence/runtime.json" > "$run_root/runtime-ready.json"
    cp "$run_root/runtime-ready.json" "$evidence/runtime.json"
    cat "$evidence/runtime.json"

    {
      printf 'BEDROCK_DATA=%s\n' "$data"
      printf 'BEDROCK_IMAGE_ID=%s\n' "$image_id"
      printf 'BEDROCK_CONTAINER_ID=%s\n' "$container"
      printf 'BEDROCK_STAGING_RUN_ID=%s\n' "$run_id"
      printf 'BEDROCK_PROBE_HOST=%s\n' "$probe_host"
      printf 'BEDROCK_PROBE_PORT=19132\n'
      printf 'BEDROCK_VERSION=%s\n' "$version"
    } >> "$GITHUB_ENV"
    ;;
  cleanup)
    [[ $# == 1 ]] || die 'Only the cleanup action is supported.'
    [[ -f $run_root/run-id ]] || exit 0
    read -r run_id < "$run_root/run-id"
    [[ $run_id =~ ^[0-9a-f]{8}-[0-9a-f-]{27}$ ]] || die 'Invalid local staging run identity.'
    if [[ -f $run_root/container-id ]]; then
      read -r container < "$run_root/container-id"
      [[ $container =~ ^[0-9a-f]{64}$ ]] || die 'Invalid recorded staging container ID.'
      if docker inspect "$container" > /dev/null 2>&1; then
        [[ $(docker inspect "$container" --format '{{index .Config.Labels "com.plexonpanel.staging.run"}}') == "$run_id" ]] ||
          die 'Refusing to remove a container outside this staging run.'
        docker stop --time 60 "$container" > /dev/null || true
        docker rm --force "$container" > /dev/null
      fi
    fi
    if [[ -f $run_root/network-id ]]; then
      read -r network < "$run_root/network-id"
      [[ $network =~ ^[0-9a-f]{64}$ ]] || die 'Invalid recorded staging network ID.'
      if docker network inspect "$network" > /dev/null 2>&1; then
        [[ $(docker network inspect "$network" --format '{{index .Labels "com.plexonpanel.staging.run"}}') == "$run_id" ]] ||
          die 'Refusing to remove a network outside this staging run.'
        docker network rm "$network" > /dev/null
      fi
    fi
    ;;
  *) die 'Choose start or cleanup.' ;;
esac
