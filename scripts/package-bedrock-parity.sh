#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
umask 022

[[ $# == 4 ]] || {
  echo 'Usage: package-bedrock-parity.sh <host.jar> <dashboard checkout> <relay package> <new output directory>' >&2
  exit 2
}
jar=$(realpath -e -- "$1")
dashboard=$(realpath -e -- "$2")
relay=$(realpath -e -- "$3")
core=$(cd -- "$(dirname -- "$0")/.." && pwd)
[[ -f $jar && -f $relay/relay/dist/standalone/server.js && -f $relay/relay/scripts/bedrock-enrollment.mjs && -f $relay/package.json ]]
core_sha=$(git -C "$core" rev-parse HEAD)
dashboard_sha=$(git -C "$dashboard" rev-parse HEAD)
[[ $core_sha =~ ^[a-f0-9]{40}$ && $dashboard_sha =~ ^[a-f0-9]{40}$ ]]
mkdir -- "$4"
output=$(realpath -e -- "$4")

archive() {
  tar --format=ustar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
    --mode='u+rwX,go+rX,go-w' -C "$1" -cf - -- "${@:3}" | gzip -n > "$2"
}

install -m 0644 -- "$jar" "$output/bedrock-host.jar"
archive "$core/bedrock-host/deploy" "$output/bedrock-units.tar.gz" \
  broker.properties.example host.properties.example plexonpanel-bedrock-broker@.service plexonpanel-bedrock-host@.service
archive "$relay" "$output/standalone-relay.tar.gz" package.json plexonpanel-relay.service relay.env.example relay
# The hosted dashboard has its own approved build configuration. Preserve the exact source input here.
git -C "$dashboard" archive --format=tar --prefix=dashboard/ "$dashboard_sha" | gzip -n > "$output/dashboard-source.tar.gz"
jq -n --arg core "$core_sha" --arg dashboard "$dashboard_sha" --arg node "$(node --version)" \
  --arg java "$(java -version 2>&1 | sed -n '1p')" \
  '{coreCommit:$core,dashboardCommit:$dashboard,nodeVersion:$node,javaVersion:$java,dashboardArtifact:"source"}' \
  > "$output/SOURCES.json"
(
  cd "$output"
  sha256sum SOURCES.json bedrock-host.jar bedrock-units.tar.gz standalone-relay.tar.gz dashboard-source.tar.gz > SHA256SUMS
  sha256sum --check --strict SHA256SUMS
)
