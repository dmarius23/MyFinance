# Gmail production readiness

This guide covers the two Google-specific release gates for the Gmail email agent:

1. enforcing the Pub/Sub push service-account identity;
2. completing restricted-scope OAuth verification and, where required, the Google security assessment.

## `GMAIL_PUBSUB_SERVICE_ACCOUNT`

This setting protects the webhook from accepting forged or misconfigured Pub/Sub requests.

There are two different Google service accounts involved:

- Gmail's Google-managed service agent publishes notifications to the Pub/Sub topic.
- The Pub/Sub push subscription uses a configured service account to sign the OIDC bearer token sent to MyFinance.

`GMAIL_PUBSUB_SERVICE_ACCOUNT` must contain the email of the second account: the account configured on
the push subscription.

This is not a permission that each client requests. It is an IAM/service-account configuration owned by
the MyFinance production Google Cloud project. Client users only authorize Gmail mailbox access through the
application's OAuth flow.

Production behavior must be:

```text
Missing configuration → application refuses production startup
JWT issuer ≠ accounts.google.com → reject
JWT audience ≠ GMAIL_PUBSUB_AUDIENCE → reject
JWT email ≠ GMAIL_PUBSUB_SERVICE_ACCOUNT → reject
Invalid signature or expiry → reject
```

### Deployment checklist

1. Select the Google Cloud project that owns the production Gmail integration.
   Set its actual **Project ID** once in your shell. Do not leave the literal placeholder `PROJECT_ID`.

   ```bash
   export PROJECT_ID="project-b84a3ce8-a9db-4b9b-984"
   gcloud config set project "$PROJECT_ID"
   ```

2. Enable the required APIs:

   ```bash
   gcloud services enable gmail.googleapis.com pubsub.googleapis.com iamcredentials.googleapis.com \
     --project="$PROJECT_ID"
   ```

3. Record the project number:

   ```bash
   PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" \
     --format='value(projectNumber)')"
   ```

4. Create a dedicated service account for Pub/Sub push authentication:

   ```bash
   gcloud iam service-accounts create myfinance-pubsub-push \
     --display-name="MyFinance Gmail Pub/Sub push authentication" \
     --project="$PROJECT_ID"
   ```

   Its email is:

   ```text
   myfinance-pubsub-push@PROJECT_ID.iam.gserviceaccount.com
   ```

5. Create the Pub/Sub topic:

   ```bash
   gcloud pubsub topics create gmail-notifications \
     --project="$PROJECT_ID"
   ```
6. Grant Gmail's Google-managed service agent permission to publish to that topic. Gmail uses this
   identity when `users.watch` sends mailbox notifications:

   ```bash
   GMAIL_SERVICE_AGENT="gmail-api-push@system.gserviceaccount.com"

   gcloud pubsub topics add-iam-policy-binding \
     "projects/${PROJECT_ID}/topics/gmail-notifications" \
     --member="serviceAccount:${GMAIL_SERVICE_AGENT}" \
     --role="roles/pubsub.publisher"
   ```

   This is a Google-managed global Gmail publisher identity. Do not replace it with a
   project-number-based service account and do not use the push-authentication service account for this
   grant.

7. Allow the Pub/Sub service agent to mint OIDC tokens using the push-authentication service account:

   ```bash
   PUBSUB_SERVICE_AGENT="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"
   PUSH_SERVICE_ACCOUNT="myfinance-pubsub-push@${PROJECT_ID}.iam.gserviceaccount.com"

   gcloud iam service-accounts add-iam-policy-binding "${PUSH_SERVICE_ACCOUNT}" \
     --member="serviceAccount:${PUBSUB_SERVICE_AGENT}" \
     --role="roles/iam.serviceAccountTokenCreator" \
     --project="$PROJECT_ID"
   ```

8. Create the HTTPS Pub/Sub push subscription. The OIDC service account in this command is the identity
   that must be configured as `GMAIL_PUBSUB_SERVICE_ACCOUNT`.

   > ⚠️ **The push endpoint is on the API host, not the app host.** Caddy routes `APP_HOST` to the
   > frontend container and only `API_HOST` to the backend, and the frontend's nginx has no `/api`
   > proxy — just `try_files $uri /index.html`. A push sent to `app.…` therefore matches the SPA
   > fallback and returns **HTTP 200 with index.html**; Pub/Sub treats that as successful delivery
   > and discards the message. Every Gmail notification would vanish with no error, no retry and
   > no dead-letter. This document specified `app.…` until 2026-10-28.
   >
   > Note `GMAIL_REDIRECT_URI` *is* on `APP_HOST` — that callback is a frontend route. The two
   > intentionally live on different hosts; do not "correct" one to match the other.

   ```bash
   gcloud pubsub subscriptions create gmail-webhook-production \
     --topic="projects/${PROJECT_ID}/topics/gmail-notifications" \
     --push-endpoint=https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub \
     --push-auth-service-account="${PUSH_SERVICE_ACCOUNT}" \
     --push-auth-token-audience=https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub \
     --ack-deadline=30 \
     --project="$PROJECT_ID"
   ```

   The identity creating or updating this subscription also needs permission to act as the push service
   account, typically:

   ```bash
   gcloud iam service-accounts add-iam-policy-binding "${PUSH_SERVICE_ACCOUNT}" \
     --member="user:YOUR_OPERATOR_EMAIL" \
     --role="roles/iam.serviceAccountUser" \
     --project="$PROJECT_ID"
   ```

   Replace `YOUR_OPERATOR_EMAIL` with the actual Google identity that creates or updates the subscription.
   Do not pass the placeholder literally. To see the currently authenticated human identity:

   ```bash
   gcloud auth list --filter=status:ACTIVE --format='value(account)'
   ```

   For CI/CD, use the deployment service account instead:

   ```bash
   --member="serviceAccount:YOUR_DEPLOYMENT_ACCOUNT@${PROJECT_ID}.iam.gserviceaccount.com"
   ```

   If the project IAM policy contains conditional bindings, add `--condition=None` for this unconditional
   grant, subject to your organization's IAM policy:

   ```bash
   gcloud iam service-accounts add-iam-policy-binding "${PUSH_SERVICE_ACCOUNT}" \
     --member="user:actual.user@example.com" \
     --role="roles/iam.serviceAccountUser" \
     --condition=None \
     --project="$PROJECT_ID"
   ```

9. Set the backend configuration:

   ```text
   GMAIL_PUBSUB_TOPIC=projects/PROJECT_ID/topics/gmail-notifications
   GMAIL_PUBSUB_AUDIENCE=https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub
   GMAIL_PUBSUB_SERVICE_ACCOUNT=myfinance-pubsub-push@PROJECT_ID.iam.gserviceaccount.com
   ```

10. Set the variables in the production secret/configuration store and restart the backend. The
    application should refuse production startup when `GMAIL_PUBSUB_SERVICE_ACCOUNT` is missing. Until
    startup validation is added, verify this as a deployment gate.
11. Authorize a test Gmail mailbox through MyFinance, then confirm that `users.watch` succeeds and stores
    a watch expiration and history cursor.
12. Send a test message with an attachment to the authorized mailbox.
13. Confirm the Pub/Sub push request reaches the webhook and that the JWT contains:
    - issuer: `https://accounts.google.com`;
    - audience: the configured `GMAIL_PUBSUB_AUDIENCE`;
    - email: the configured `GMAIL_PUBSUB_SERVICE_ACCOUNT`.
14. Confirm the webhook creates one tenant-bound `SYNC_GMAIL` outbox message and that duplicate Pub/Sub
    deliveries do not create concurrent sync claims.
15. Test rejection with:
    - another valid Google service account;
    - the wrong audience;
    - an expired token;
    - an invalid signature;
    - a missing token.

Keep `GMAIL_PUBSUB_AUDIENCE` stable and environment-specific. Do not share a broad audience across
   development, staging, and production.

### Console equivalent

The complete setup can be performed in Google Cloud Console without installing `gcloud`.

#### A. Create or select the production project

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Use the project selector at the top of the page.
3. Click **New project**, or select the existing production project used by MyFinance.
4. If creating a project:
   - set a recognizable name, such as `MyFinance Production`;
   - record the generated **Project ID**;
   - associate the project with the company organization, if applicable.
5. Open **Billing** and link a billing account. Pub/Sub and quota management may require billing even
   when normal usage remains within free or included limits.

#### B. Enable APIs

1. Open **APIs & Services → Library**.
2. Search for and enable:
   - **Gmail API**;
   - **Pub/Sub API**;
   - **IAM Service Account Credentials API**.
3. Wait until each API shows **API enabled**.

#### C. Configure the OAuth application

1. Open **Google Auth Platform → Branding**.
2. Configure:
   - application name;
   - support email;
   - developer contact email;
   - authorized application domain.
3. Open **Google Auth Platform → Audience**.
4. Select **External** unless every user belongs to the same Google Workspace organization.
5. Open **Google Auth Platform → Data access**.
6. Add:

   ```text
   https://www.googleapis.com/auth/gmail.readonly
   ```

7. Open **Google Auth Platform → Clients**.
8. Click **Create client**.
9. Select **Web application**.
10. Add this production redirect URI:

    ```text
    https://app.mycompanyfinance.ro/settings/email-connections/gmail/callback
    ```

    Use the public frontend URL if the OAuth callback is handled by the frontend, as in the current
    MyFinance configuration.
11. Create the client.
12. Copy the client ID into the production secret store as `GMAIL_CLIENT_ID`.
13. Copy the client secret into the production secret store as `GMAIL_CLIENT_SECRET`.
14. Do not commit either value or place the client secret in frontend code.

#### D. Create the Pub/Sub push service account

1. Open **IAM & Admin → Service Accounts**.
2. Click **Create service account**.
3. Use:

   ```text
   Name:        myfinance-gmail-pubsub-push
   Description: Authenticated push identity for Gmail Pub/Sub notifications
   ```

4. Click **Create and continue**.
5. Do not grant broad project roles to this account.
6. Finish creating the account.
7. Copy its email. It will look like:

   ```text
   myfinance-pubsub-push@PROJECT_ID.iam.gserviceaccount.com
   ```

8. Store this value as:

   ```text
   GMAIL_PUBSUB_SERVICE_ACCOUNT
   ```

Do not create or download a JSON key for this account. Pub/Sub uses the account to sign OIDC tokens;
MyFinance does not need its private key.

#### E. Create the Pub/Sub topic

1. Open **Pub/Sub → Topics**.
2. Click **Create topic**.
3. Enter:

   ```text
   Topic ID: gmail-notifications
   ```

4. Leave encryption at the organization-approved default unless a customer-managed key is required.
5. Create the topic.
6. Open the topic's **Permissions** or **IAM** panel.
7. Grant **Pub/Sub Publisher** to Google's Gmail publisher identity:

   ```text
   gmail-api-push@system.gserviceaccount.com
   ```

8. Save the policy.

Do not grant this role to the MyFinance push service account. Google uses the global
`gmail-api-push@system.gserviceaccount.com` identity when Gmail publishes notifications.

Set the topic value in the backend secret store:

```text
GMAIL_PUBSUB_TOPIC=projects/PROJECT_ID/topics/gmail-notifications
```

#### F. Grant Pub/Sub permission to sign push tokens

1. Return to **IAM & Admin → Service Accounts**.
2. Open `myfinance-pubsub-push`.
3. Open the **Permissions** or **Grant access** panel.
4. Add the Pub/Sub service agent as a principal:

   ```text
   service-PROJECT_NUMBER@gcp-sa-pubsub.iam.gserviceaccount.com
   ```

5. Grant:

   ```text
   Service Account Token Creator
   ```

6. Save.

This allows Pub/Sub to create the OIDC token used to authenticate each webhook request.

#### G. Allow the operator or CI/CD identity to attach the service account

The identity creating or updating the subscription must be allowed to act as the push service account.

1. On the `myfinance-pubsub-push` service account, click **Permissions → Grant access**.
2. Add the human operator or deployment service account.
3. Grant:

   ```text
   Service Account User
   ```

4. Save.

The operator also needs permission to create subscriptions, normally through **Pub/Sub Editor** on the
project or an equivalent least-privilege custom role.

#### H. Create the authenticated push subscription

1. Open **Pub/Sub → Subscriptions**.
2. Click **Create subscription**.
3. Set:

   ```text
   Subscription ID: gmail-webhook-production
   Topic: projects/PROJECT_ID/topics/gmail-notifications
   ```

4. Under **Delivery type**, choose **Push**.
5. Set the endpoint to:

   ```text
   https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub
   ```

6. Enable **Authentication** or **Authenticated push**.
7. Select:

   ```text
   myfinance-pubsub-push@PROJECT_ID.iam.gserviceaccount.com
   ```

8. Set the audience to the exact same webhook URL:

   ```text
   https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub
   ```

9. Set the acknowledgement deadline to 30 seconds.
10. Configure retry behavior using the platform default initially. Add a dead-letter topic before
    production if the operational runbook includes DLQ monitoring.
11. Create the subscription.

Set the matching backend value:

```text
GMAIL_PUBSUB_AUDIENCE=https://api.mycompanyfinance.ro/api/v1/ingestion/gmail/pubsub
```

The audience is case-sensitive and must not differ by a trailing slash.

#### I. Validate the configuration

1. Open the subscription details page.
2. Confirm:
   - the correct topic;
   - push delivery;
   - the HTTPS endpoint;
   - authenticated push enabled;
   - the correct push service account;
   - the exact audience.
3. Restart the backend with the production secrets.
4. Connect a test Gmail mailbox through MyFinance.
5. Send a message with an attachment.
6. Confirm the webhook receives the request and accepts the Google OIDC token.
7. Confirm a tenant-bound `SYNC_GMAIL` outbox message is created.
8. Confirm the worker imports the attachment.
9. Test rejection using an incorrect service account, incorrect audience, expired token, and missing token.

The current verifier supports an optional service-account value for development. Production deployment must
make this setting mandatory and fail closed; this should be enforced both by application startup validation
and by the deployment secret checklist.

## Restricted-scope OAuth verification

The application requests:

```text
https://www.googleapis.com/auth/gmail.readonly
```

This is a restricted Gmail scope. Verification is required before external production users can use the
OAuth client without warning or quota restrictions.

### Application materials

Prepare the following for Google's verification process:

- a verified application domain;
- a public privacy policy describing Gmail access, retention, deletion, and tenant isolation;
- public terms of service, where applicable;
- accurate OAuth consent-screen branding and support contact;
- a production HTTPS redirect URI;
- a data-flow diagram:

  ```text
  Gmail → Google OAuth → MyFinance backend → encrypted refresh token
       → Gmail API reads → tenant-scoped document storage
  ```

- an explanation that only messages and attachments needed for document ingestion are read;
- proof that refresh tokens are encrypted and never exposed to the frontend;
- evidence that PostgreSQL RLS prevents cross-tenant access;
- a reviewer test account and reproducible verification steps;
- a video or walkthrough of the OAuth flow and Gmail data usage, if requested.

Because the application stores and processes Gmail-restricted data on its backend, Google may require an
independent security assessment in addition to OAuth verification.

## Security assessment preparation

The assessment should cover:

- authentication and authorization;
- secret and OAuth-token handling;
- encryption in transit and at rest;
- tenant isolation and RLS;
- vulnerability and dependency management;
- logging and incident response;
- infrastructure security;
- data deletion and retention;
- secure development practices;
- penetration-testing evidence.

The implementation should demonstrate:

- server-side OAuth code exchange;
- encrypted refresh-token storage;
- disconnect/revoke support;
- no OAuth credentials in the browser;
- Pub/Sub JWT issuer, audience, signature, expiry, and service-account validation;
- idempotent Gmail message and attachment processing;
- durable sync claims and cursor safety;
- cross-tenant isolation tests;
- review handling for unknown or ambiguous senders;
- malware scanning before document persistence.

## Release process

1. Create a separate production OAuth client.
2. Remove localhost and development redirect URIs from the production client.
3. Submit the OAuth consent screen for verification.
4. Submit the restricted-scope justification.
5. Start the security-assessment process if Google requires it.
6. Fix assessment findings and retain the final report.
7. Re-submit after material architecture or data-flow changes.
8. Document token revocation, tenant disconnect, retention, and deletion procedures.

These are external approval processes and should be treated as release gates, not merely configuration
tasks.
