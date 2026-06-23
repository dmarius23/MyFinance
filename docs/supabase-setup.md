# Supabase setup (auth, JWT claims, first admin)

MyFinance uses Supabase Auth as the identity provider. Supabase issues the JWT; the SPA sends it to
the Spring API as a Bearer token. The backend reads three custom claims — `tenant_id`, `role`,
`company_id` — and uses them to bind the request identity and drive PostgreSQL row-level security.

Those claims are injected by a **custom access-token hook** that copies a user's `app_metadata` into the
JWT. Without the hook, tokens validate but carry no tenant/role → every secured request fails closed.

## 1. Create the project & collect values

From the Supabase dashboard (Project Settings → API):

| Value | Used by |
|---|---|
| Project URL (`https://<ref>.supabase.co`) | backend `SUPABASE_URL`, frontend `VITE_SUPABASE_URL`, `SUPABASE_JWKS_URI` |
| `anon` public key | frontend `VITE_SUPABASE_ANON_KEY` |
| `service_role` key (**secret**) | backend `SUPABASE_SERVICE_ROLE_KEY` (invites) |

## 2. Backend env

Copy `backend/.env.example` and fill it in (or export the vars in `backend/run-local.sh`, which is
gitignored). Required:

```
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service_role secret>
SUPABASE_JWKS_URI=https://<ref>.supabase.co/auth/v1/.well-known/jwks.json
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

- `SUPABASE_SERVICE_ROLE_KEY` blank → the **logging invite fallback** is used (flow works, no real email).
- Tokens are ES256 (already configured in `application.yml`).

## 3. Frontend env

Copy `frontend/.env.example` → `frontend/.env.local`:

```
VITE_SUPABASE_URL=https://<ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<anon public key>
VITE_API_BASE_URL=http://localhost:8080
```

## 4. Install & enable the access-token hook

1. Open the SQL editor (runs as `postgres`) and run [`supabase/access_token_hook.sql`](../supabase/access_token_hook.sql).
2. Enable it: **Dashboard → Authentication → Hooks → "Customize Access Token (JWT) Claims"** →
   select `public.custom_access_token_hook` → Save.

For the **local Supabase CLI**, add to `supabase/config.toml` instead of the dashboard:

```toml
[auth.hook.custom_access_token]
enabled = true
uri = "pg-functions://postgres/public/custom_access_token_hook"
```

Verify: after a fresh login, decode the access token (jwt.io) and confirm `tenant_id` and `role`
appear at the top level.

## 5. Bootstrap the first admin

Invites need an existing admin, so seed the first one by hand — see
[`supabase/bootstrap_first_admin.sql`](../supabase/bootstrap_first_admin.sql):

1. Create the auth user (Dashboard → Authentication → Users → Add user).
2. Set their `app_metadata` (`tenant_id`, `role = TENANT_ADMIN`).
3. Insert the matching `tenant` + `app_user` rows — **`app_user.id` must equal the auth user id** (the
   JWT subject), which is exactly how the invite flow links new users too.

That admin can then invite the rest of the team from the in-app **Team** page; those invites create the
Supabase auth user (with claims) and send the invite email automatically.

## How it fits together

```
Supabase Auth  --login-->  JWT (sub = auth user id; claims tenant_id/role/company_id from the hook)
      |                                   |
      v                                   v
  invite email                  Spring resource server validates via JWKS (ES256)
  (UserInviter →                          |
   /auth/v1/invite +                      v
   app_metadata)                TenantContextFilter binds {tenant, user, role, company}
                                          |
                                          v
                                PostgreSQL RLS (app.tenant_id) enforces isolation
```
