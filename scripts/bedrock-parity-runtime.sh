#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted &&
   ${GITHUB_REPOSITORY:-} == ZpkDxGames/PlexonPanel && $(uname -m) == aarch64 ]] || {
  echo 'The runtime installer requires the disposable PlexonPanel hosted ARM64 runner.' >&2
  exit 1
}

docker_home=/opt/plexon-phase25-docker
java_home=/opt/plexon-phase25-java
unit=plexon-phase25-docker.service
docker_url=https://download.docker.com/linux/static/stable/aarch64/docker-29.10.0.tgz
jdk_url=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.4.1_1.tar.gz

case ${1:-} in
  install)
    [[ ! -e $docker_home && ! -e $java_home ]]
    scratch=$(mktemp -d "${RUNNER_TEMP:?}/plexon-parity.XXXXXXXX")
    trap 'rm -rf -- "$scratch"' EXIT
    curl --proto '=https' --fail --silent --show-error --location --retry 2 --max-time 180 -o "$scratch/docker.tgz" "$docker_url"
    curl --proto '=https' --fail --silent --show-error --location --retry 2 --max-time 180 -o "$scratch/java.tgz" "$jdk_url"
    (
      cd "$scratch"
      printf '%s  %s\n' \
        6e5159e8b5615e0c0c2954a2a1a2a12bc65f5d9b603595dba24a10cb367408cd docker.tgz \
        69df11a02cfa3ef7d7ca645e03edce6778ec090e100f6ae2b42097865730ac52 java.tgz | sha256sum --check --strict
    )
    sudo install -d -m 0755 "$docker_home/bin" "$java_home" /run/plexon-phase25
    sudo tar --no-same-owner --strip-components=1 -xzf "$scratch/docker.tgz" -C "$docker_home/bin"
    sudo tar --no-same-owner --strip-components=1 -xzf "$scratch/java.tgz" -C "$java_home"
    sudo install -o root -g root -m 0644 /dev/null /run/plexon-phase25/daemon.json
    echo '{}' | sudo tee /run/plexon-phase25/daemon.json > /dev/null
    "$java_home/bin/java" -XshowSettings:properties -version 2>&1 | grep -E 'os\.arch = aarch64|java\.version = 25\.0\.4\.1'
    "$java_home/bin/java" -version 2>&1 | grep -F 'version "25.0.4.1"'

    # Run a fresh daemon on the broker's fixed socket inside this throwaway VM.
    sudo systemctl stop docker.service docker.socket
    sudo systemd-run --unit="$unit" --collect --property=Delegate=yes --property=Type=notify \
      --setenv="PATH=$docker_home/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      "$docker_home/bin/dockerd" --config-file=/run/plexon-phase25/daemon.json \
      --host=unix:///run/docker.sock --group=docker --pidfile=/run/plexon-phase25/docker.pid \
      --exec-root=/run/plexon-phase25/engine --data-root=/var/lib/plexon-phase25-docker
    export DOCKER_HOST=unix:///run/docker.sock
    for (( attempt=0; attempt<30; attempt++ )); do
      if versions=$("$docker_home/bin/docker" version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}} {{.Server.APIVersion}}' 2>/dev/null); then
        break
      fi
      sleep 1
    done
    if [[ ${versions:-} != '29.10.0 linux/arm64 '* ]]; then
      sudo journalctl --unit="$unit" --lines=25 --no-pager
      echo 'Expected native Docker Engine 29.10.0.' >&2
      exit 1
    fi
    printf 'Native Docker server: %s\n' "$versions"
    printf 'JAVA_HOME=%s\nDOCKER_HOST=%s\n' "$java_home" "$DOCKER_HOST" >> "${GITHUB_ENV:?}"
    printf '%s\n' "$docker_home/bin" "$java_home/bin" >> "${GITHUB_PATH:?}"
    ;;
  stop)
    if sudo systemctl is-active --quiet "$unit"; then
      sudo systemctl stop "$unit"
    fi
    ;;
  *) echo 'Expected install or stop.' >&2; exit 2 ;;
esac
