# Meta WhatsApp Cloud API — setup guide

How to configure the Meta app, the production server, and a tenant connection so MyFinance can send
WhatsApp messages through the Meta Cloud API. Companion to
[`whatsapp-integration-implementation-plan.md`](whatsapp-integration-implementation-plan.md).

Hosts used below: app `https://app.mycompanyfinance.ro`, API `https://api.mycompanyfinance.ro`.

## 1. Business portfolio

1. Open [business.facebook.com](https://business.facebook.com) and create (or pick) the business portfolio
   that will own the Meta app — this is MyFinance's portfolio, not a tenant's.
2. Start **Business verification** (Security Center). It is required for Live mode and Tech Provider
   status and can take several days; development can continue meanwhile.

## 2. Meta app

1. [developers.facebook.com](https://developers.facebook.com) → **My Apps → Create app**.
2. Use case: **Connect with customers through WhatsApp** (or *Other → Business*). Link it to the portfolio
   from step 1.
3. **App settings → Basic**: fill App domains (`mycompanyfinance.ro`), Privacy Policy URL, Terms of
   Service URL, category, and icon. Note the **App ID** and **App secret**.

## 3. WhatsApp product

1. Add the **WhatsApp** product if the use case did not add it.
2. **WhatsApp → API Setup** gives a test WABA and test phone number, usable for development before
   verification. Add your own phone as a test recipient.

## 4. Facebook Login for Business

1. Add the **Facebook Login for Business** product.
2. **Settings**:
   - Client OAuth login: **On**
   - Web OAuth login: **On**
   - Enforce HTTPS: **On**
   - Valid OAuth Redirect URIs: `https://app.mycompanyfinance.ro/settings/messaging/whatsapp/callback`
     (the frontend host — must byte-for-byte equal `META_WHATSAPP_REDIRECT_URI`)
   - Allowed domains for the JavaScript SDK: `https://app.mycompanyfinance.ro`
3. **Configurations → Create configuration**:
   - Name: e.g. `MyFinance WhatsApp Embedded Signup`
   - Login variation: **WhatsApp Embedded Signup** (if this option is missing, the WhatsApp product is not
     added — go back to step 3)
   - Access token: **System-user access token** (cannot be changed later)
   - Token expiration: **Never** (the backend has no refresh logic)
   - Assets: **WhatsApp accounts** only
   - Permissions: `whatsapp_business_management`, `whatsapp_business_messaging`
4. Copy the **Configuration ID**.

## 5. Webhook

1. Generate a verify token: `openssl rand -hex 32`. Store it only in GitHub Secrets / a password manager.
2. **WhatsApp → Configuration → Webhook → Edit**:
   - Callback URL: `https://api.mycompanyfinance.ro/api/v1/webhooks/whatsapp/meta` (the API host)
   - Verify token: the value from step 1
3. **Webhook fields**: subscribe to **`messages`** (delivery statuses arrive on this field).

The per-WABA subscription (`/{waba-id}/subscribed_apps`) is made by the backend during onboarding; no
manual step is needed for tenant WABAs.

Verify after deploying (expect `200` with body `123456`; `403` means the server token is missing or
different):

```bash
curl -i -G 'https://api.mycompanyfinance.ro/api/v1/webhooks/whatsapp/meta' \
  --data-urlencode 'hub.mode=subscribe' \
  --data-urlencode 'hub.verify_token=<token>' \
  --data-urlencode 'hub.challenge=123456'
```

## 6. Production configuration

Add these as **GitHub Secrets** (Settings → Secrets and variables → Actions → Secrets — never Variables;
variables are not masked and this repo is public):

| Secret | Value |
|---|---|
| `META_WHATSAPP_APP_ID` | App ID (step 2) |
| `META_WHATSAPP_APP_SECRET` | App secret (step 2) — verifies webhook signatures and exchanges codes |
| `META_WHATSAPP_WEBHOOK_VERIFY_TOKEN` | Verify token (step 5) |
| `META_WHATSAPP_CONFIG_ID` | Configuration ID (step 4) |
| `META_WHATSAPP_REDIRECT_URI` | `https://app.mycompanyfinance.ro/settings/messaging/whatsapp/callback` |

Then run the **Deploy** workflow and approve it. In the log, the credentials step should print
`Credentials: changed` and the SSH step should print `synced` for each `META_WHATSAPP_*` key;
`not configured in GitHub` means the secret name is wrong. The backend and worker are recreated
automatically when `.env` changes.

`MYFINANCE_SECRET_KEY` must already be set on the server (it encrypts the stored Meta token); it is
server-owned and never synced from CI.

## 7. Message templates

1. In **WhatsApp Manager → Message templates**, create the Romanian and English templates for tax
   payments, payroll, reports, and missing-document reminders, and wait for approval.
2. Register each approved template in MyFinance: **Data sources → WhatsApp templates** (visible when the
   provider is Meta). Pick the message type and language, enter the exact Meta template name, and save
   it as Approved. Template bodies must not contain `{{1}}` variables yet.

## 8. Connect a tenant

1. Sign in as a tenant admin → **Settings → Messaging** → provider **Meta** → **Connect Meta WhatsApp**.
2. Complete Meta's dialog (pick/create the business portfolio, WABA, and phone number).
3. Meta redirects to `/settings/messaging/whatsapp/callback` with only `code` and `state`. The backend
   exchanges the code, reads the shared WABA from the token's granular scopes (`/debug_token`), lists its
   phone numbers, validates both, subscribes the app to the WABA, and stores the encrypted token.
   Share exactly one WABA with one phone number in the dialog; zero or several are rejected.
4. Send a test message and confirm the `whatsapp_message` row moves through accepted → delivered → read.

## 9. Going live

1. Business verification approved (step 1).
2. **App Review**: request Advanced Access for `whatsapp_business_management` and
   `whatsapp_business_messaging` (screencast of the Embedded Signup flow and a send).
3. Enroll as a **Tech Provider** (required to onboard other businesses' WABAs through Embedded Signup).
4. Switch the app from Development to **Live** mode.
5. Per tenant: phone-number display-name approval and template approval in their WABA.
