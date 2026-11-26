#!/usr/bin/env bash
set -euo pipefail

network="${RAG_DOCKER_NETWORK:-genai-rag_default}"
rule_comment="SSH via Cloudflare Tunnel"
network_id=""

for attempt in $(seq 1 60); do
  network_id="$(docker network inspect --format '{{.Id}}' "$network" 2>/dev/null || true)"
  if [[ -n "$network_id" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$network_id" ]]; then
  echo "Docker network '$network' was not available after 120 seconds." >&2
  exit 1
fi

subnet="$(docker network inspect --format '{{range .IPAM.Config}}{{if .Subnet}}{{.Subnet}}{{end}}{{end}}' "$network")"
bridge="$(docker network inspect --format '{{index .Options "com.docker.network.bridge.name"}}' "$network")"
bridge="${bridge:-br-${network_id:0:12}}"

if [[ -z "$subnet" ]] || ! ip link show "$bridge" >/dev/null 2>&1; then
  echo "Could not determine the bridge interface and subnet for '$network'." >&2
  exit 1
fi

sed -i 's/^#\?IPV6=.*/IPV6=yes/' /etc/default/ufw
ufw default deny incoming
ufw default allow outgoing
ufw default deny routed

while rule_number="$(ufw status numbered | sed -n "/# ${rule_comment// /\\ }$/s/^[[:space:]]*\\[[[:space:]]*\\([0-9][0-9]*\\)\\].*/\\1/p" | head -n 1)" &&
      [[ -n "$rule_number" ]]; do
  ufw --force delete "$rule_number"
done

ufw allow in on "$bridge" from "$subnet" to any port 22 proto tcp comment "$rule_comment"
ufw --force enable
