# WhatsApp integration implementation plan

Status: implementation in progress  
Date: 2026-10-31  
Branch: `feat/whatsapp-integration`

## Confirmed decisions

- Meta WhatsApp Business Cloud API is the production provider.
- Each tenant owns its Meta Business Portfolio, WABA, sender, templates, and token.
- Tenant admins connect through Meta Embedded Signup/OAuth; tokens are never pasted or exposed.
- Recipients are resolved server-side to the authorized company contact.
- Approved Romanian/English templates are required; arbitrary free-form messages are out of scope.
- Tax, payroll, reports, and missing-document reminders are in scope.
- Attachments are required and uploaded as WhatsApp media.
- Unverified extracted financial values must not be sent.
- Inbound messages are out of scope; delivery-status webhooks are in scope.
- Consent is required before sending.
- WhatsApp uses a dedicated `whatsapp_message` table, not `email_history`.

## Implemented

### Backend

- Tenant-scoped provider configuration with encrypted credentials and RLS.
- Twilio adapter retained; Meta Cloud API adapter added behind `WhatsAppSender`.
- Meta OAuth state, authorization-code exchange, WABA/phone validation, and token persistence.
- Tenant template registry with category, locale, version, schema, and approval status.
- Company ownership, server-side recipient resolution, phone normalization, idempotency, fingerprints,
  monthly quota, and provider configuration validation.
- Durable outbox delivery with provider message IDs.
- Dedicated `whatsapp_message` table and migration from existing WhatsApp history rows.
- Dispatch, outbox, history, quota, monthly summaries, and webhook lookup use the new message repository.
- Meta webhook verification, HMAC validation, tenant binding, and monotonic delivery statuses.
- Retry classification: most 4xx errors are permanent; timeouts, 408, 425, 429, and 5xx are retryable.
- Local Meta Graph API contract tests for successful, 4xx, and 5xx responses.

### Frontend

- Meta provider selection in messaging settings.
- Connect/Reconnect Meta WhatsApp action and configuration validation.
- Callback route: `/settings/messaging/whatsapp/callback`.
- Callback submits authorization code, state, WABA ID, phone-number ID, and business ID.
- Connection status, identifiers, errors, and translated Romanian/English UI.

## Remaining product decisions

- Daily quota, per-user burst limit, and priority behavior at quota exhaustion.
- Final attachment types, size limits, and media-header versus separate-message behavior.
- Visible delivery statuses: accepted, delivered, read, and failed.
- Meta data-processing/EU residency approval.
- Whether click-to-chat remains after API rollout.
- STOP handling or a tenant-managed suppression list.
- Production observability: metrics, alerts, correlation IDs, and delivery dashboard.

## Remaining implementation

- Meta app review, permissions, business verification, sender verification, display-name approval, and
  template approval.
- Reconnect, revoke, and incomplete-onboarding recovery states.
- Handle Meta SDK/postMessage callback variants if asset IDs are not returned in URL parameters.
- Enforce template variable schemas and provider-defined positional ordering.
- Complete safe error mapping, provider request IDs, and rate-limit behavior.
- Add consent records, opt-out enforcement, retention cleanup/anonymization, and scheduled job producers.
- Represent the designated primary WhatsApp contact explicitly in the company model.
- Remove the temporary public `body` compatibility field and require validated server-generated variables.
- Add template management UI and accepted/delivered/read/retryable/permanent status UI.
- Add usage dashboards, quota warnings, cost estimates, frontend tests, API contract tests, and full
  Testcontainers/RLS coverage.

## Verification status

- Backend compilation and test compilation pass.
- WhatsApp lifecycle tests pass.
- Frontend ESLint and TypeScript compilation pass.
- Full frontend build remains blocked by the existing PWA service-worker/Terser failure.
- Full Testcontainers verification requires Docker.

## Next action

Reload this file in the editor, configure a Meta development app/test WABA, and verify the callback
format against Meta. Then finalize consent/contact/quota policies and complete RLS/webhook/frontend tests.
