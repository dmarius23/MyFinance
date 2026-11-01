# WhatsApp API v1 contract

This is the application-facing contract. Provider-specific Meta and Twilio
payloads are not exposed to frontend clients.

The existing endpoint is being migrated in phases. Phase 1 implements the
authorization, recipient, validation, and idempotency rules below; the current
body-based request remains a temporary compatibility shape until the
tenant-template and attachment adapter work is implemented.

## Authorization

All endpoints require a valid tenant JWT. Only `TENANT_ADMIN` and `EMPLOYEE`
may send or read firm WhatsApp history. The server verifies that the company
belongs to the current tenant and that the resolved contact is assigned to
that company. The browser is not a trust boundary.

## Resolve recipient

`GET /api/v1/companies/{companyId}/whatsapp/recipient`

Response `200`:

```json
{
  "phone": "+40711223344"
}
```

`phone` is the normalized E.164 phone of the designated primary company
contact, or `null` when no eligible contact exists.

## Send a templated message

`POST /api/v1/companies/{companyId}/whatsapp`

Required header:

`Idempotency-Key: <opaque request key>`

Request:

```json
{
  "kind": "TAX",
  "period": "2026-10-01",
  "language": "ro_RO",
  "templateVariables": {
    "companyName": "Acme SRL",
    "amount": "1000.00"
  },
  "attachmentDocumentIds": [
    "00000000-0000-0000-0000-000000000000"
  ]
}
```

The server resolves the recipient and validates template ownership, approval,
financial-value verification, consent, attachment ownership, provider
configuration, quota, and company authorization. There is no recipient field
and no arbitrary free-form body field.

Response `202`:

```json
{
  "id": "00000000-0000-0000-0000-000000000000",
  "recipient": "+40711223344",
  "status": "QUEUED",
  "sentAt": "2026-10-31T13:00:00Z"
}
```

Repeating the same `Idempotency-Key` for the same tenant returns the original
message result and does not enqueue a second provider delivery. Reusing a key
with a different request returns `409`.

Possible errors:

- `400` invalid kind, period, template, variable, attachment, or phone;
- `403` caller cannot act for the company;
- `404` company/contact/template/document not found;
- `409` duplicate idempotency key, missing consent, disabled provider, or quota;
- `413` attachment too large;
- `429` per-user burst limit;
- `503` provider configuration unavailable.

## Message history

`GET /api/v1/companies/{companyId}/whatsapp?kind={kind}&period={date}`

Response `200` is an array of message views ordered newest first. Each view
may include the provider message lifecycle (`accepted`, `sent`, `delivered`,
`read`, or `failed`) and its timestamp. Provider message IDs are persisted
server-side and are not exposed to clients.

Message bodies and provider payloads are never returned from provider adapters.
History responses contain only the fields needed by the tenant UI.

## Provider onboarding

`POST /api/v1/settings/messaging/whatsapp/meta/onboarding`

Starts tenant-admin-only Embedded Signup and returns:

```json
{
  "authorizationUrl": "https://www.facebook.com/...",
  "expiresAt": "2026-10-31T13:10:00Z"
}
```

`POST /api/v1/settings/messaging/whatsapp/meta/onboarding/complete`

Accepts the authorization code and, optionally, asset identifiers. Meta's redirect flow returns only
`code` and `state`; when `wabaId` or `phoneNumberId` is omitted the server resolves it from the token
(`/debug_token` granular scopes, then `/{waba-id}/phone_numbers`) and rejects the request with `409` if
Meta granted none or more than one:

```json
{
  "state": "opaque-state",
  "authorizationCode": "meta-code",
  "wabaId": "123",
  "phoneNumberId": "456",
  "businessPortfolioId": "789"
}
```

The Embedded Signup/OAuth flow is tenant-admin-only. OAuth state is bound to
the authenticated tenant and initiating user. The callback exchanges the code
server-side, validates the returned tenant-owned Business Portfolio/WABA/phone
number, encrypts token material, and marks the provider sendable only after
Meta verification succeeds.

## Meta delivery webhook

`GET /api/v1/webhooks/whatsapp/meta` performs Meta subscription verification
using the server-side webhook verify token.

`POST /api/v1/webhooks/whatsapp/meta` accepts Meta delivery-status events.
The server verifies `X-Hub-Signature-256`, resolves the tenant from the
validated Meta phone-number ID, and applies status updates idempotently.
Unknown messages are ignored; late events cannot move a message backwards
from delivered/read.

`POST /api/v1/settings/messaging/whatsapp/test`

Tenant-admin-only configuration validation. This does not send a client
message; it verifies that an API provider is selected and that its required
credentials/identifiers are present.

Response `200`:

```json
{
  "status": "VALID"
}
```

Production sends are rejected when the tenant has no provider, has selected
`OFF` or `CLICK_TO_CHAT`, or has incomplete provider configuration. The
logging transport is enabled only explicitly in local/test configuration.
