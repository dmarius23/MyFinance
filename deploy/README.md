# MyFinance — production deployment (Hetzner + Supabase) & CI/CD

One Hetzner VM runs the whole app behind **Caddy** (auto-HTTPS): the **frontend** (static SPA), the
**backend web** API, the **worker**, and **Redis**. **Supabase** (EU) provides Postgres, Storage, and
auth. GitHub Actions builds images to **GHCR** and, after a one-click approval, deploys over SSH.

```
Internet ─▶ Caddy :443 ─┬─ APP_HOST  ─▶ frontend (nginx)
                        └─ API_HOST  ─▶ backend :8080
backend + worker ─▶ Supabase Postgres (session mode) + Supabase Storage
                 └▶ Redis (local container)
```

Files in this folder: `docker-compose.prod.yml`, `Caddyfile`, `env.prod.example` (template for the
gitignored `.env` you create on the VM). CI/CD lives in `.github/workflows/deploy.yml`.

---

## One-time setup

### 1. Supabase (EU project)
Use the existing project (or create one in an EU region), then in the **SQL editor**:
1. Run `supabase/create_app_role.sql` — creates the non-BYPASSRLS `myfinance_app` login role. Set a
   strong password; reuse it as `DB_APP_PASSWORD`.
2. Run `supabase/access_token_hook.sql`, then **Dashboard → Authentication → Hooks → Customize Access
   Token (JWT)** → select `public.custom_access_token_hook` and enable it.
3. **Storage → New bucket** → `documents` (private).
4. **Dashboard → Authentication → URL Configuration** → add the frontend origin (`https://APP_HOST`) to
   Site URL / redirect allow-list.
5. Collect: DB **session-mode** connection host (Settings → Database → *Connection string*, use the
   **direct / session** one on **port 5432**, NOT the transaction pooler on 6543), the DB password
   (`DB_ADMIN_PASSWORD`), the **service role key**, the project URL, JWKS URL, and the **anon** key.

### 2. Email (Brevo or Mailjet, EU)
Create an account, verify a sender address/domain, and grab the SMTP host/port/user/pass for the
`SMTP_*` vars.

### 3. Hetzner VM
- Create a small EU VM (e.g. **CX22**, Ubuntu 24.04). Add your SSH key. Note its public IP.
- Generate a dedicated **CD SSH keypair** (its private key becomes the `DEPLOY_SSH_KEY` GitHub secret):
  ```bash
  ssh-keygen -t ed25519 -f deploy_ci -C "cd@myfinance" -N ""
  ```
- Copy `deploy/provision-vm.sh` to the VM and run it as root — it installs Docker + compose, creates the
  `deploy` user (with the CD public key), sets up `/opt/myfinance`, a firewall (SSH/HTTP/HTTPS only),
  fail2ban, unattended security upgrades, and a swapfile:
  ```bash
  scp deploy/provision-vm.sh deploy_ci.pub root@<IP>:/root/
  ssh root@<IP> 'DEPLOY_PUBKEY="$(cat /root/deploy_ci.pub)" bash /root/provision-vm.sh'
  ```
  (Re-runnable/idempotent. Override `DEPLOY_USER`, `APP_DIR`, `SWAP_GB` via env if needed.)

### 4. DNS (a real domain — **not** sslip.io)
Create two A-records at the registrar, both pointing at the VM's public IP:

| Record | Type | Value |
| --- | --- | --- |
| `app` | A | `<VM IP>` |
| `api` | A | `<VM IP>` |

- `APP_HOST = app.<domain>`
- `API_HOST = api.<domain>`

> **Do not use `sslip.io` (or `nip.io`, or any wildcard-DNS service) for a host real users visit.**
> Security vendors classify those zones as dynamic-DNS/anonymiser and block them wholesale — corporate
> firewalls, mobile-carrier filters and DNS filters (Umbrella, Fortinet, NextDNS, Quad9…). Users get an
> "access not authorized" block page *before the app loads*, on every device and network that filters,
> while the server itself is perfectly healthy. It is fine for a private smoke test, never for a pilot.

If DNS is delegated to Cloudflare, keep both records **DNS-only (grey cloud)**, not proxied — Caddy needs
a direct inbound path for the Let's Encrypt HTTP-01 challenge.

#### Changing domain later
Cutover is config-only (the Caddyfile and CD workflow are already env-driven — no code change):
1. Add the new A-records; wait for them to resolve.
2. On the VM, in `/opt/myfinance/.env`: `APP_HOST`, `API_HOST`, `CORS_ALLOWED_ORIGINS`, `MYFINANCE_APP_URL`.
3. Set the `VITE_API_BASE_URL` GitHub **variable** to `https://<new API_HOST>` and **rebuild the frontend
   image** — this value is baked in at build time, so a restart alone will not pick it up.
4. Supabase → Authentication → URL Configuration: set Site URL and add the new origin to Redirect URLs.
5. Redeploy. Caddy issues fresh certs automatically for the new hostnames.
6. **Re-send any outstanding invitations** — links already in inboxes carry the old host.

### 5. Put config on the VM
Copy this folder's `docker-compose.prod.yml` + `Caddyfile` to `/opt/myfinance/`, then create
`/opt/myfinance/.env` from `env.prod.example` and fill in every value. **Never commit this `.env`.**
```bash
scp deploy/docker-compose.prod.yml deploy/Caddyfile deploy-user@<IP>:/opt/myfinance/
scp deploy/env.prod.example deploy-user@<IP>:/opt/myfinance/.env   # then edit on the server
```

### 6. GitHub CI/CD config (repo → Settings → Secrets and variables → Actions)
- **Secrets:** `DEPLOY_HOST` (VM IP), `DEPLOY_USER` (deploy user), `DEPLOY_SSH_KEY` (private key).
- **Variables** (public, baked into the frontend build):
  `VITE_API_BASE_URL=https://<API_HOST>`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`.
- **Environments → New environment → `production`** → add yourself as a **required reviewer** (this is the
  one-click approval gate before any deploy). Remove the reviewer later for fully-automatic deploys.

---

## First deploy
1. Push to `master` (or run the **Deploy** workflow manually). The `build` job pushes images to GHCR.
2. Approve the `production` gate. The `deploy` job SSHes in, `docker compose pull && up -d`, and health-checks.
3. On first boot the backend runs **Flyway** (V1…V52) as the admin role against Supabase.
4. Bootstrap users in the Supabase SQL editor: run `supabase/bootstrap_super_admin.sql`, then
   `supabase/bootstrap_first_admin.sql` for the pilot firm (create the auth users in the dashboard first,
   then re-run so their JWT carries tenant/role). The firm admin invites their own staff/reps in-app.

Manual first run (before CI is wired) — from `/opt/myfinance`:
```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f backend
```

---

## Verify
- `curl https://<API_HOST>/actuator/health` → `{"status":"UP"}`; backend logs show Flyway applied V1…V52.
- Open `https://<APP_HOST>` → SPA loads over HTTPS (valid cert); service worker registers.
- Log in as the firm admin → you see only that tenant's data (RLS).
- Trigger a Google Drive month sync → docs land in Supabase Storage; a report generates.
- Send a test report email to yourself → confirm delivery in the SMTP provider dashboard; check the
  `worker` container drained the outbox.

## Operate
- **Logs:** `docker compose -f docker-compose.prod.yml logs -f backend worker`
- **Roll back:** set `BACKEND_IMAGE`/`FRONTEND_IMAGE` in `.env` to a specific `:<git-sha>` tag, then
  `docker compose -f docker-compose.prod.yml up -d` (or re-run Deploy from an older commit).
- **Restart:** `docker compose -f docker-compose.prod.yml restart backend worker`
- **Secrets rotation:** edit `/opt/myfinance/.env`, then `up -d`.

## Not in the pilot (later hardening)
Bedrock EU for receipt OCR (keep Anthropic-direct or disabled now); WhatsApp stays `logging`/sandbox
(prod sender is Meta-locked); Prometheus/metrics; extra DB backups beyond Supabase's; zero-downtime rollouts.
