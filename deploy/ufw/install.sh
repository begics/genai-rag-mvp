#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y ufw
install -o root -g root -m 0750 "$script_dir/configure-rag-ufw.sh" /usr/local/sbin/configure-rag-ufw
install -o root -g root -m 0644 "$script_dir/rag-ufw.service" /etc/systemd/system/rag-ufw.service
systemctl daemon-reload
systemctl enable --now rag-ufw.service
