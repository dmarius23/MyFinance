# ADR: Tenant WhatsApp delivery

- Status: Accepted for implementation
- Date: 2026-10-31
- Scope: outbound WhatsApp messages for accounting-firm tenants

## Decision

MyFinance will support tenant-owned WhatsApp Business accounts through a shared
`WhatsAppSender` port with separate provider adapters:

- the existing Twilio implementation remains supported unchanged;
- Meta WhatsApp Business Cloud API is added as a separate provider;
- Meta onboarding uses Embedded Signup/OAuth;
- tenants own their Meta Business Portfolio, WABA, sender, phone-number ID,
  templates, and System User access token;
- a normal personal WhatsApp account is not supported.

The first release supports only these categories:

- tax-payment notices;
- missing-document reminders;
- payroll;
- reports.

All sends use tenant-approved Meta templates. Arbitrary free-form messages are
out of scope. Attachments are uploaded as WhatsApp media. Inbound messages are
out of scope; outbound delivery-status webhooks are in scope.

The recipient is resolved server-side to the company's designated primary
WhatsApp/company contact. Clients cannot override the recipient phone in the
API or UI. Unverified extracted financial values cannot be inserted into
automatic messages.

Both manual and scheduled sends use the same authorization, consent,
verification, idempotency, quota, and transactional-outbox rules.

WhatsApp message history will be stored in a dedicated tenant-scoped
`whatsapp_message` table. It will not remain a long-term use of the shared
`email_history` table. The new model will own WhatsApp provider identifiers,
delivery lifecycle, template metadata, attachments, and retention independently
from email history.

## Data and security

- Provider credentials are encrypted with `SecretCipher`.
- Tokens and provider secrets are write-only through the API and never logged.
- RLS and application authorization both enforce tenant/company boundaries.
- Consent is recorded per company/phone before sending.
- Retention: raw webhooks 90 days, provider IDs 12 months, message history
  24 months.
- Per-user burst limits and per-tenant daily/monthly quotas apply to all sends,
  including scheduled compliance messages.
- Quota warnings occur at 70–80%; reaching the limit blocks new sends.

## Consequences

The provider-neutral port keeps dispatch, authorization, outbox, and quota
logic independent of Meta or Twilio. The dedicated message model keeps
WhatsApp-specific lifecycle changes from destabilizing email history. Provider adapters remain responsible
for credentials, API payloads, templates, media upload, status mapping, and
provider-specific errors.

Meta app review, Embedded Signup permissions, tenant onboarding, sender
verification, template approval, consent UX, exact quota numbers, attachment
limits, and the final delivery-status display remain implementation
prerequisites.
