# MyFinance — data residency & sub-processors (v1)

**Status:** dated snapshot, 2026-11-06. Verified against the code and configuration in this repo at
that date. Referenced from `backend/src/main/resources/application.yml` (receipt OCR, storage, email).

**Purpose.** Establish where personal data and client accounting data are processed, so that:

- §10 of the Privacy Policy (*Transferuri în afara Spațiului Economic European*) can be completed
  with facts rather than boilerplate;
- the sub-processor annex the Privacy Policy and the DPA both point at has a source;
- a production deployment can be checked against a concrete list.

This document records **what the system does**, not what is legally sufficient. The adequacy of any
transfer, and the SCCs/DPAs required for it, is a legal assessment and is out of scope here.

---

## 1. Summary

Under the shipped production configuration (`deploy/env.prod.example`), **all personal data and all
client accounting data stay in the EU.** Every non-EU path is either off by default or must be
switched on deliberately.

The exception that was live until 2026-11-06 — Google Fonts, which disclosed every visitor's IP
address to Google on each page load — has been removed; fonts are now self-hosted.

| Component | Data | Location | Default |
|---|---|---|---|
| Application backend, worker, Redis | All app data in transit/processing | 🇪🇺 Hetzner VM | active |
| Supabase: Postgres, Auth, Storage | Identities, accounting data, documents | 🇪🇺 AWS `eu-central-1` (Frankfurt) | active |
| Email — SMTP (Brevo) | Message bodies, attachments, recipients | 🇪🇺 France | active |
| Fonts | Visitor IP (none now — self-hosted) | 🇪🇺 own origin | active |
| ANAF treasury sync | Public treasury IBANs, no personal data | 🇪🇺 Romania | optional |
| Email — AWS SES | Message bodies, attachments, recipients | 🇪🇺 if `EMAIL_REGION=eu-*` | alternative |
| Receipt OCR — AWS Bedrock | Receipt/invoice images | 🇪🇺 if `RECEIPT_REGION=eu-*` | alternative |
| Receipt OCR — Anthropic API | Receipt/invoice images | 🇺🇸 `api.anthropic.com` | **off; see §3** |
| WhatsApp — Meta Cloud API | Phone numbers, message content, media | 🇺🇸 Meta | off (`logging`) |
| WhatsApp — Twilio | Phone numbers, message content | 🇺🇸 Twilio | off |
| Gmail / Drive ingestion | Mail, attachments, documents | ⚠️ customer's Google Workspace region | off per company |
| Web push | Notification payload, endpoint | ⚠️ Google / Mozilla / Apple | off (no VAPID keys) |

---

## 2. Verified: Supabase is in `eu-central-1`

Supabase holds the database, the auth identities and every uploaded document, so its region
determines most of the answer.

Verified on 2026-11-06 by resolving the project's database host and matching the address against
Amazon's published ranges (`https://ip-ranges.amazonaws.com/ip-ranges.json`):

```
db.<project-ref>.supabase.co  →  2a05:d014:700:dc02:…
2a05:d014::/35                →  eu-central-1 (AMAZON, EC2)
```

This is strong evidence but is inference from network addressing. **The authoritative source is the
Supabase dashboard** — see §6. Re-confirm it there and record the result here.

---

## 3. Receipt OCR: off by decision, and the default is unsafe

**Decision, 2026-11-06: vision-LLM receipt OCR stays off in production.** Nothing to configure — it
is already disabled, and no document images leave the VM.

This costs little. The vision LLM is only the third tier of extraction: a deterministic PDF text
parse runs first, then Tesseract (bundled in the backend image, `tesseract-ocr-ron tesseract-ocr-eng`),
both entirely local. The LLM is reached only for a photographed receipt that Tesseract cannot read,
or an invoice whose supplier appears solely in an image letterhead. Those documents are marked
`NEEDS_REVIEW` and keyed in by the accountant instead.

Revisit when the volume of unreadable receipts justifies the work in §3.2.

### 3.1 The default provider is the US one

`ReceiptProperties` defaults `provider` to `anthropic`, and the US path activates as soon as an API
key is present:

```java
provider = (provider == null || provider.isBlank()) ? "anthropic" : provider;
public boolean isAnthropic() { return "anthropic".equalsIgnoreCase(provider) && !apiKey.isBlank(); }
```

`deploy/env.prod.example` lists `# ANTHROPIC_API_KEY=` immediately above `# RECEIPT_PROVIDER=anthropic`.
**Uncommenting only the key sends customer receipt images — real accounting documents, potentially
containing personal data — to `api.anthropic.com` in the United States,** with no prompt and no
guard. The only signal is a log line at startup:

```
Receipt OCR: Anthropic vision API (model=…)
```

With no key configured the no-op extractor is used and image receipts are simply marked
`NEEDS_REVIEW`; nothing leaves the EU.

### 3.2 What enabling OCR with EU residency would take

Not a single flag. `RECEIPT_PROVIDER=bedrock` on its own makes OCR fail silently (see below). The
full list:

- an **AWS account** — the production stack is Hetzner + Supabase + Brevo today, with no AWS
  variables anywhere in `deploy/`. AWS becomes a new sub-processor, needing a DPA and an annex entry;
- **credentials that work on Hetzner.** `BedrockReceiptExtractor` uses the AWS default provider
  chain, whose production case is an instance role — an EC2/ECS concept that does not exist on a
  Hetzner VM. In practice that means static `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in the
  container, with rotation;
- **Bedrock model access requested** in the console, per region and per model;
- the **EU inference profile** id (below). Note this implies cross-region inference *within* the EU,
  so the policy should say "within the EU" rather than naming one city.

Configuration once those exist:

```
RECEIPT_PROVIDER=bedrock
RECEIPT_REGION=eu-central-1
RECEIPT_MODEL=eu.anthropic.claude-sonnet-4-5-20250929-v1:0   # EU inference profile
```

The `eu.` model prefix matters: it selects the EU inference profile, which keeps inference inside EU
regions. A bare model id may route elsewhere.

**Misconfiguration is silent.** `ClaudeReceiptExtractor.extract` catches `RuntimeException`, logs a
warning and returns `ParsedReceipt.empty()`. Wrong credentials or missing model access therefore do
not fail loudly — receipts simply stop extracting and accumulate in `NEEDS_REVIEW`, with nothing in
the UI indicating the integration is broken. Verify a Bedrock rollout by extracting a known receipt,
not by observing the absence of errors.

### 3.3 Open hardening item

The safe configuration is a convention, not an invariant: the unsafe path needs one uncommented
line, the safe one needs five settings plus credentials that do not yet exist. Making the
application refuse to start when the US provider is active under a production profile — overridable
only by an explicit `RECEIPT_ALLOW_NON_EU=true` — would turn this into an enforced guarantee.
`deploy/env.prod.example` should also stop listing the key and the provider as independently
uncommentable lines.

---

## 4. Optional integrations that leave the EU when enabled

These are per-tenant features, off unless a tenant turns them on. Each needs its own transfer basis.

**Meta WhatsApp Cloud API** (`graph.facebook.com/v24.0`) — recipient phone numbers, message bodies
and any media are processed by Meta (US). **Twilio** (`api.twilio.com`) is the alternative provider,
also US. Default is `logging` (nothing sent).

**Gmail ingestion** (`gmail.googleapis.com`) and **Google Drive ingestion**
(`www.googleapis.com/drive/v3`) — the documents and mail involved are the customer's own, held in
the customer's Google Workspace. Where Google stores them depends on that Workspace's data-region
settings, which MyFinance neither controls nor can detect. If a client requires EU-only processing,
either the customer's Workspace must have EU data regions configured, or these connectors must stay
off and documents be uploaded directly.

**Web push** — a push subscription endpoint belongs to the browser vendor's push service (Google
FCM for Chrome, Mozilla for Firefox, Apple for Safari). Payloads are encrypted end-to-end by the
VAPID/Web Push scheme, but endpoint metadata and delivery transit those services. Off while the
VAPID keys are blank.

---

## 5. Fonts — resolved 2026-11-06

`frontend/index.html` previously loaded two families from `fonts.googleapis.com` and
`fonts.gstatic.com`. Because this happened on every page load, including the login screen, it
disclosed the IP address of every visitor — representatives and firm staff alike — to Google before
authentication. It applied to all users unconditionally and could not be switched off.

The fonts are now self-hosted: `frontend/public/fonts/*.woff2`, declared in `frontend/src/fonts.css`,
both generated by `frontend/scripts/fetch_fonts.py`. Re-run that script to change the font set; it
keeps only the `latin` and `latin-ext` subsets (Romanian ă/ș/ț live in `latin-ext`) and collapses
variable fonts to a single file per subset.

Verified after the change: no request to `googleapis.com`/`gstatic.com` from a loaded page, and no
such URL anywhere in `dist/`.

---

## 6. How to confirm the Supabase region authoritatively

Any one of:

1. **Dashboard** — open the project → *Project Settings* → *General*. The region is shown next to
   the project name (e.g. *Central EU (Frankfurt)*).
2. **Management API** — with a personal access token:
   `curl -H "Authorization: Bearer $SUPABASE_PAT" https://api.supabase.com/v1/projects`
   Each project object carries a `region` field.
3. **CLI** — `supabase projects list` prints a region column.

Note that a project's region is **fixed at creation**. Moving an existing project to another region
means creating a new project and migrating, not flipping a setting.

---

## 7. Production checklist

- [ ] Supabase project region confirmed as EU via the dashboard (§6), and recorded here.
- [ ] `EMAIL_PROVIDER=smtp` with an EU provider, **or** `EMAIL_PROVIDER=ses` with `EMAIL_REGION=eu-*`.
- [x] Receipt OCR disabled — no `ANTHROPIC_API_KEY`, no `RECEIPT_PROVIDER` (decision, §3). If it is
      ever enabled: `RECEIPT_PROVIDER=bedrock` with an EU region **and** an `eu.`-prefixed model id.
      Never a bare `ANTHROPIC_API_KEY` — that silently selects the US provider.
- [ ] WhatsApp left at `logging` unless a transfer basis with Meta or Twilio is in place.
- [ ] Gmail/Drive connectors enabled only for customers whose Workspace region is acceptable to them.
- [ ] Web push enabled only once the browser-vendor routing is reflected in the privacy policy.
- [ ] Hetzner VM located in an EU datacentre (Germany or Finland).
- [ ] No new third-party script, font, CDN or analytics added to the frontend without revisiting §5.

---

## 8. Feeding this into the legal documents

**Privacy Policy §10** can state that, in the default production configuration, personal data is
processed within the EU — application and database in Germany, email delivery in France — and that
the optional integrations in §4 above involve transfers to the United States or to a location
determined by the customer's own Google Workspace, each enabled only on the client's instruction and
covered by the relevant transfer mechanism.

**The sub-processor annex** referenced by the Privacy Policy (`[LINK CĂTRE LISTA SUBÎMPUTERNICIȚILOR]`)
and by the DPA should list, at minimum: Hetzner (hosting, DE), Supabase (database/auth/storage,
AWS eu-central-1), Brevo or the chosen SMTP provider (email, FR), plus — only where actually enabled
— AWS (Bedrock OCR / SES), Meta or Twilio (WhatsApp) and Google (Gmail/Drive ingestion).

**Privacy Policy §12** should not claim encryption at rest or any other measure that has not been
confirmed against the actual deployment; the policy's own text already warns against this.
