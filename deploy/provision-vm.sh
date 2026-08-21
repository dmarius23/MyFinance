#!/usr/bin/env bash
# Provision a fresh Ubuntu (24.04) Hetzner Cloud VM to host MyFinance.
#
# Installs Docker + compose, creates a non-root deploy user (for CD over SSH), sets up /opt/myfinance,
# a firewall (SSH/HTTP/HTTPS only), fail2ban, unattended security upgrades, and a small swapfile.
# Idempotent — safe to re-run.
#
# USAGE (run as root on the VM):
#   # 1) copy this file over, then:
#   DEPLOY_PUBKEY="ssh-ed25519 AAAA... cd@myfinance" bash provision-vm.sh
#   # or pass the key path:
#   DEPLOY_PUBKEY="$(cat deploy_ci.pub)" bash provision-vm.sh
#
# ENV (all optional except DEPLOY_PUBKEY, which you really want for CD):
#   DEPLOY_USER    deploy user to create           (default: deploy)
#   DEPLOY_PUBKEY  public SSH key for the CD user   (default: empty -> add it yourself later)
#   APP_DIR        app directory                    (default: /opt/myfinance)
#   SWAP_GB        swapfile size in GB, 0 disables  (default: 2)
set -euo pipefail

DEPLOY_USER="${DEPLOY_USER:-deploy}"
DEPLOY_PUBKEY="${DEPLOY_PUBKEY:-}"
APP_DIR="${APP_DIR:-/opt/myfinance}"
SWAP_GB="${SWAP_GB:-2}"

log() { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }

if [ "$(id -u)" -ne 0 ]; then echo "Run as root (sudo)."; exit 1; fi

log "Updating base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg ufw fail2ban unattended-upgrades

log "Installing Docker Engine + compose plugin"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

log "Creating deploy user '${DEPLOY_USER}' (docker group, no password login)"
if ! id -u "${DEPLOY_USER}" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" "${DEPLOY_USER}"
fi
usermod -aG docker "${DEPLOY_USER}"

if [ -n "${DEPLOY_PUBKEY}" ]; then
  log "Installing CD public key for '${DEPLOY_USER}'"
  install -d -m 700 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh"
  touch "/home/${DEPLOY_USER}/.ssh/authorized_keys"
  grep -qxF "${DEPLOY_PUBKEY}" "/home/${DEPLOY_USER}/.ssh/authorized_keys" \
    || echo "${DEPLOY_PUBKEY}" >> "/home/${DEPLOY_USER}/.ssh/authorized_keys"
  chown -R "${DEPLOY_USER}:${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh"
  chmod 600 "/home/${DEPLOY_USER}/.ssh/authorized_keys"
else
  log "No DEPLOY_PUBKEY given — add the CD public key to /home/${DEPLOY_USER}/.ssh/authorized_keys yourself"
fi

log "Preparing app directory ${APP_DIR}"
install -d -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${APP_DIR}"

log "Firewall (allow SSH/HTTP/HTTPS only)"
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

log "SSH hardening (key-only; keep root key access to avoid lockout)"
sshd_cfg=/etc/ssh/sshd_config.d/60-myfinance.conf
cat > "${sshd_cfg}" <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true

log "Enabling unattended security upgrades"
echo 'Unattended-Upgrade::Automatic-Reboot "false";' > /etc/apt/apt.conf.d/51myfinance-reboot
dpkg-reconfigure -f noninteractive unattended-upgrades || true

if [ "${SWAP_GB}" != "0" ] && ! swapon --show | grep -q '/swapfile'; then
  log "Creating ${SWAP_GB}G swapfile"
  fallocate -l "${SWAP_GB}G" /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=$((SWAP_GB*1024))
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -qxF '/swapfile none swap sw 0 0' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

log "Done. Versions:"
docker --version
docker compose version

cat <<EOF

Next:
  1) Copy the deploy files onto the VM:
       scp deploy/docker-compose.prod.yml deploy/Caddyfile ${DEPLOY_USER}@<IP>:${APP_DIR}/
       scp deploy/env.prod.example ${DEPLOY_USER}@<IP>:${APP_DIR}/.env   # then edit ${APP_DIR}/.env
  2) Set the GitHub repo secrets/variables (see deploy/README.md), then merge + approve the Deploy workflow.
  3) First manual boot (optional): cd ${APP_DIR} && docker compose -f docker-compose.prod.yml up -d
EOF
