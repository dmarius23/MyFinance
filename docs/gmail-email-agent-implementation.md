# Gmail email agent — implementation

## Scope

The Gmail agent monitors a tenant-owned mailbox and imports email attachments into the existing MyFinance document intake pipeline.

Supported document flow:

1. A representative sends an email to the configured Gmail mailbox.
2. Gmail publishes a mailbox-change notification through Google Cloud Pub/Sub.
3. MyFinance validates the Google OIDC push token.
4. The webhook creates a tenant-scoped `SYNC_GMAIL` outbox message.
5. The worker reads Gmail history and downloads new messages and attachments.
6. The sender is resolved to a representative and company inside the current tenant.
7. Attachments are imported through `DocumentService` with `DocumentSource.EMAIL_AGENT`.
8. Existing classification, extraction and reconciliation listeners process the document.
9. The responsible accountant receives an in-app notification and, for successful imports, an email notification.

Unknown or ambiguous senders are never imported into a company. Their message and attachments are recorded as `NEEDS_REVIEW`.

## Backend implementation

### Domain and persistence

Migration `V58__gmail_email_ingestion.sql` adds:

- `email_connection`
  - tenant-owned mailbox configuration;
  - encrypted Gmail refresh token;
  - Gmail history cursor;
  - watch expiration and sync status.
- `email_message`
  - Gmail message id and thread id;
  - sender, subject and received timestamp;
  - idempotent message processing status.
- `email_attachment`
  - Gmail attachment id;
  - filename, MIME type and SHA-256;
  - resulting MyFinance document id;
  - import/review status.

All three tables have tenant ID, `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, tenant isolation policies and unique provider references.

Migration `V59__gmail_sync_claim.sql` adds a tenant-scoped, durable lease per connection. Sync and
backfill acquire this lease before reading Gmail and release it in `finally`, so duplicate Pub/Sub
deliveries cannot concurrently advance one cursor.

### Gmail OAuth

OAuth uses the server-side authorization-code flow:

- frontend starts authorization through the backend;
- backend generates a random state;
- only a SHA-256 hash of the state is stored;
- state is bound to the tenant connection and expires after 10 minutes;
- authorization code exchange happens server-side;
- refresh tokens are encrypted with the existing AES-256-GCM `SecretCipher`;
- client ID and client secret are environment configuration, never tenant data or frontend data.

Endpoints:

```text
POST /api/v1/ingestion/email-connections
GET  /api/v1/ingestion/email-connections
POST /api/v1/ingestion/email-connections/{id}/oauth/start
POST /api/v1/ingestion/email-connections/{id}/oauth/complete
POST /api/v1/ingestion/email-connections/{id}/sync
POST /api/v1/ingestion/email-connections/{id}/backfill
```

The backfill endpoint accepts optional `fromInclusive` and `toExclusive` ISO dates. When omitted, it
imports the last 30 days. Gmail history expiration is reported as `NEEDS_REVIEW` without advancing
the stored cursor; the explicit backfill establishes a new watch baseline, imports the bounded interval,
drains changes received during the import, and only then advances the cursor.

### Gmail API adapter

`GmailApiMailboxClient` uses Gmail REST APIs through Spring `RestClient`; it does not expose Google SDK types to the application layer.

Implemented operations:

- refresh access token from the encrypted refresh token;
- unfiltered `users.watch` (mailbox rules may archive or relabel messages immediately);
- incremental `users.history.list`;
- full message retrieval;
- sender, subject and received-date extraction;
- recursive MIME attachment discovery;
- inline attachment decoding;
- Gmail attachment download.

The adapter uses the least-privilege Gmail scope:

```text
https://www.googleapis.com/auth/gmail.readonly
```

### Sender and company resolution

The sender is resolved through a tenant-local representative link:

```text
sender email → app_user → representative_link → company
```

The import is accepted only when exactly one company is resolved. The following cases go to review:

- sender is not linked to a representative;
- sender is linked to multiple companies;
- document import or validation fails.

The connection and company are checked server-side. The caller cannot choose an arbitrary tenant or company for the import.

### Pub/Sub and worker processing

The push endpoint is:

```text
POST /api/v1/ingestion/gmail/pubsub
```

The endpoint:

- is excluded from Supabase bearer-token processing;
- validates the Google Pub/Sub OIDC JWT;
- validates issuer, audience and optionally the Pub/Sub service account email;
- extracts `emailAddress` and `historyId`;
- resolves matching active connections using the admin database connection;
- creates a tenant-bound `SYNC_GMAIL` outbox message.

Actual Gmail processing runs through the existing worker outbox:

```text
Outbox type: SYNC_GMAIL
Aggregate:   email_connection
```

Existing outbox behavior provides:

- transactional persistence;
- single-flight claiming;
- retries with exponential backoff;
- visibility-timeout recovery;
- DLQ after exhausted attempts.

### Watch renewal

`GmailWatchRenewalScheduler` renews watches before expiration and is protected by ShedLock.

Important behavior: renewing a Gmail watch updates only `watch_expires_at`. It does not replace the stored `gmail_history_id`, because the history ID returned by a renewal is not a processed cursor and replacing it could skip messages.

### Notifications

Email-agent imports use the notification service:

- successful import: `EMAIL_DOCUMENT_IMPORTED`;
- failed or uncertain import: `EMAIL_DOCUMENT_REVIEW`.

Notifications target the responsible accountant, falling back to tenant administrators. Successful imports also queue an email through the existing transactional email outbox.

## Frontend implementation

The Data Sources screen includes a Gmail mailbox section with:

- mailbox creation;
- OAuth authorization start;
- OAuth callback handling;
- Gmail connection status;
- last sync result;
- manual synchronization.
- a 30-day recovery backfill action when Gmail history requires review.

The frontend stores only the connection ID temporarily in `localStorage` during the OAuth round trip. OAuth credentials and authorization codes are handled by the backend.

The callback route is:

```text
/settings/email-connections/gmail/callback
```

## Configuration

Required backend configuration:

```text
MYFINANCE_SECRET_KEY
GMAIL_CLIENT_ID
GMAIL_CLIENT_SECRET
GMAIL_REDIRECT_URI
GMAIL_PUBSUB_TOPIC
GMAIL_PUBSUB_AUDIENCE
GMAIL_PUBSUB_SERVICE_ACCOUNT       # optional but recommended
GMAIL_WATCH_RENEWAL_ENABLED
GMAIL_WATCH_RENEWAL_CRON
```

`MYFINANCE_SECRET_KEY` must be a base64-encoded 32-byte AES key.

Google Cloud setup still requires:

1. Gmail API enabled.
2. Pub/Sub API enabled.
3. Pub/Sub topic created.
4. Gmail push service account granted permission to publish to the topic.
5. Pub/Sub push subscription configured with OIDC authentication.
6. OAuth consent screen and Gmail restricted-scope verification completed for production.

## Tests and verification

Implemented tests cover:

- email attachment import through the normal document pipeline;
- sender/company resolution;
- unknown/ambiguous tenant-local sender handling;
- cross-tenant connection rejection;
- failed attachment import becoming `NEEDS_REVIEW`;
- watch renewal preserving the unprocessed history cursor;
- expired-history recovery without cursor advancement;
- bounded date backfill and per-connection claim behavior;
- `SYNC_GMAIL` outbox serialization and error propagation;
- signed Pub/Sub verifier rejection paths;
- fake-Gmail ingestion success/failure and cursor safety;
- Gmail connection, message and attachment RLS isolation with PostgreSQL/Testcontainers.

Verification performed:

- backend compilation passed;
- Gmail unit tests passed;
- frontend TypeScript compilation passed;
- frontend ESLint passed;
- frontend production build passed with Node 22 LTS.

Node 24.3.0 currently triggers an upstream Workbox/Terser service-worker generation failure. Use Node 20/22 LTS for frontend builds until the dependency chain is updated.

## Remaining production hardening

### P0 — completed in this implementation

- Recover expired Gmail cursors through a bounded, explicit date backfill.
- Protect each connection with a durable sync lease and preserve cursor safety.
- Cover message/attachment RLS, claim single-flight behavior, outbox failure propagation and Pub/Sub
  authentication rejection paths.

The remaining production hardening below is outside this P0 slice. A full fake-Gmail/Testcontainers
worker execution test remains useful as an additional integration test before production rollout.

### P0 — remaining before production

- Add global document-hash deduplication across manual, Drive and email sources.

### P1 — implementation status

Implemented:

- Notify tenant administrators when the sender is unknown or mapped to multiple companies.
- Add Gmail disconnect/revoke support and wipe the encrypted refresh token.
- Add explicit UI states for unauthorized, syncing, expired, failed and review-required connections.
- Add frontend controls for mailbox backfill and connection removal.
- Add frontend translations for the Gmail connection UI.
- Add deterministic classification rules using email subject, filename and sender metadata; conflicting
  hints remain in review.
- Handle revoked refresh tokens, Gmail rate limits and transient HTTP errors.
- Publish Gmail sync outcome metrics through the Actuator metrics endpoints.
- Add a conservative pre-storage malware filter for EICAR and executable attachment types. The scanner is
  behind `AttachmentMalwareScanner` and should be replaced by ClamAV or a managed scanner in production.

Remaining production rollout tasks:

- Add alert rules for failed sync metrics, DLQ messages and expired watches.
- Make `GMAIL_PUBSUB_SERVICE_ACCOUNT` mandatory in production and enforce sender trust with SPF/DKIM/DMARC
  where available.
- Complete Gmail restricted-scope OAuth verification and security assessment.
- Standardize frontend builds on Node 20/22 LTS. Node 24.3.0 currently triggers an upstream Workbox/Terser
  failure.

## Google authentication library migration

The Google service-account and Gmail refresh-token flows now use
`com.google.auth:google-auth-library-oauth2-http`:

- `GoogleDriveTokens` uses `GoogleCredentials` for service-account scopes, JWT construction, signing,
  token exchange, caching and expiry handling.
- `GoogleDriveFolderConnector` delegates token acquisition to `GoogleDriveTokens`; it no longer signs
  service-account JWTs or calls the OAuth token endpoint directly.
- `GmailApiMailboxClient` uses `UserCredentials` for encrypted refresh-token exchange and continues to
  expose only the application-level `GmailMailboxClient` port.
- Gmail and Drive REST resource calls remain behind their existing adapters and ports; the migration does
  not expose Google SDK types to the application layer.

Verification: backend compilation passed after the migration. The production configuration still requires
the existing service-account email/private-key secrets and encrypted Gmail refresh-token settings.
