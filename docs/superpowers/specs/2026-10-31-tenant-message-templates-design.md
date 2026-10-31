# Tenant-configurable message templates — design

**Date:** 2026-10-31
**Status:** approved design, ready for implementation planning
**Modules touched:** `settings` (MOD-03), `taxpayments` (MOD-07), `reports` (MOD-06), `payroll` (MOD-08),
`extraction` (MOD-04 reminders), `common`, frontend settings + three reminder screens.

## 1. Purpose

Each accounting firm owns the wording of the messages it sends its clients. Today every body and subject
is hardcoded — Romanian string literals in Java builders (`PaymentEmailBuilder`, `ReportEmailBuilder`,
`PayrollEmailBuilder`) and in the frontend `reminderBody.ts`. A firm can only tweak text per send, in the
modal; the *default* wording requires a code change and a deploy.

After this change a TENANT_ADMIN edits each message once in Settings, and every subsequent send for that
tenant pre-fills from their own text.

**Success criteria.** A firm admin rewrites all six messages in Settings, sends one of each, and the
client receives the firm's wording with correct figures. A firm that changes nothing sees no difference.
No tenant can read or write another tenant's templates.

## 2. Decisions

| Decision | Rationale |
|---|---|
| Templates are **per tenant**, one per message kind | Matches how the firm, not the individual accountant, owns client correspondence. |
| **Prose + block placeholders**; the system renders computed lists | A firm controls all prose and ordering; it cannot corrupt IBANs, money figures, or row structure. |
| **One template per kind, any language** | The firm writes in the language it uses with its clients. Removes today's oddity where the reminder's language follows the *accountant's UI toggle* rather than the recipient. |
| **Defaults stay in Java**; a row exists only when customized | Reset-to-default comes free, no backfill migration, and default text can still be improved centrally for every firm that hasn't customized. |
| **Rendering is server-side only** | One engine, not one in Java and one in TypeScript. Kills the existing drift between `reminderBody.ts` and `DocumentReminderService`. |
| **Invite email stays hardcoded** | It carries the set-password link; it is auth plumbing, not client correspondence. A firm that deleted the link would silently break onboarding. Revisit only with a required-placeholder rule. |
| **Placeholders validated on save** | A typo like `{{compnayName}}` must fail in Settings, not arrive at a client as literal text. |

Rejected alternatives: seeding every tenant's defaults as rows (defaults freeze per tenant and can never
be centrally improved); keeping reminder rendering in the browser (duplicates the engine and preserves
the drift bug).

## 3. Template kinds and placeholder contract

Six kinds. Each declares its own allowed names; saves are validated against that set.

| Kind | Simple placeholders | Blocks |
|---|---|---|
| `TAX` | `companyName`, `cui`, `month`, `treasury`, `totalToPay` | `paymentSummary`, `paymentAccounts` |
| `REPORT` | `companyName`, `cui`, `month`, `revenue`, `expenses`, `grossProfit`, `incomeTax`, `netProfit` | — |
| `PAYROLL` | `companyName`, `month`, `taxDeadline` | — |
| `REMINDER_NOTHING_RECEIVED` | `companyName`, `month` | — |
| `REMINDER_DOCS_MISSING` | `companyName`, `month`, `missingCount` | `missingTransactions` |
| `REMINDER_DOCS_ONLY` | `companyName`, `month` | — |

`{{signOff}}` is available to every kind. Subjects accept the simple placeholders only — no blocks.

**`{{signOff}}` renders the sender and firm name lines only, not the closing line.** The closing
("O zi bună,", "Mulțumim,") is plain prose in the template so the firm owns it. Blank parts are dropped,
so a message never goes out with an unfilled placeholder. This narrows today's
`SignOff.block(closing, sender, firm)` to a new `SignOff.names(sender, firm)`; `block` stays for the
invite email.

**The tax email needs two blocks**, not one: its current body iterates the payment lines twice — first as
a summary (`TVA: 1250`), then under `Conturi:` with IBAN, beneficiary, beneficiary CUI, explanation and
due date per line. Two blocks let a firm drop the summary or move it below the accounts.

**`{{month}}` is `RomanianMonths.monthYear`** — `"Aprilie 2026"` — for every kind. See §11 for the one
user-visible change this causes.

### Block row formats (system-owned)

- `paymentSummary` — one line per payment line: `Impozite: 1250`
- `paymentAccounts` — per line, a blank line then `- {amount} lei in contul {iban}`,
  `- Beneficiar: {treasury}`, `- CUI Beneficiar: {cui}`, `- Explicație: {explanation}`, and
  `- Scadență: {d MMMM yyyy}` when a due date exists.
- `missingTransactions` — one line per transaction: `• 15.04.2026 — Partner SRL — 1.234,56 RON`
  (`ro-RO` grouping, 2 decimals, absolute value; `—` when the partner is unknown).

## 4. Data model

`V62__tenant_message_template.sql`, following the `V57__tenant_messaging_providers.sql` pattern:

```sql
CREATE TABLE tenant_message_template (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    kind       text NOT NULL,          -- MessageTemplateKind
    subject    text,                   -- null = built-in default subject
    body       text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    PRIMARY KEY (tenant_id, kind)
);
ALTER TABLE tenant_message_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_message_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_message_template
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- GRANT SELECT, INSERT, UPDATE, DELETE TO myfinance_app (guarded by the pg_roles check)
```

`kind` is `text` with the enum enforced in Java, matching how `tenant_whatsapp_provider.mode` is handled.
`updated_by` comes from `TenantContext.current().map(Identity::userId)`.

## 5. Rendering

New package `ro.myfinance.common.messaging`:

- **`MessageTemplateKind`** — the six kinds, each exposing its allowed placeholder and block names.
- **`TemplateRenderer`** — `{{key}}` substitution over a `Map<String,String>`. No conditionals, no loops,
  no new dependency: blocks arrive already rendered as strings.
- **`DefaultMessageTemplates`** — today's exact text, per kind (§6).
- **`MessageTemplateService`** — `effective(kind)` returns the tenant's row if present, else the default.
- **`MessageTemplate`** entity + repository under `settings/adapter/persistence`.

### Renderer rules

1. A known placeholder is replaced by its value; a blank value yields the empty string.
2. **Empty-line elision:** a line whose content is only placeholders that all render empty is removed
   entirely. This keeps `O zi bună,\n{{signOff}}` correct when both sender and firm are blank, and keeps
   an empty block from leaving a stray blank line.
3. An unrecognized placeholder is stripped. This cannot normally occur — saves are validated — so it is
   defence in depth against pre-existing rows after a kind's contract changes.
4. The renderer never throws on content. Malformed delimiters are rejected at save time (§8).

## 6. Default templates

The implementation must reproduce today's output. A golden test per kind asserts this (§10).

The blank lines below are **indicative, not authoritative**: blocks carry their own internal line breaks,
so the exact whitespace of each default is whatever makes the golden test pass. Treat the test as the
source of truth and adjust these defaults to it.

**`TAX`** — subject `Sume de plată — {{month}}`

```
Bună ziua,

Sumele de plată pentru luna {{month}} aferente firmei {{companyName}} sunt următoarele:

{{paymentSummary}}
Conturi:
{{paymentAccounts}}
O zi plăcută,
{{signOff}}
```

**`REPORT`** — subject `Raport financiar — {{month}}`

```
Bună ziua,

Vă transmitem atașat raportul financiar pentru luna {{month}}.

Pe scurt:
• Venituri: {{revenue}} RON
• Cheltuieli: {{expenses}} RON
• Profit net: {{netProfit}} RON

Detaliile complete și graficele se găsesc în documentele atașate.

O zi bună,
{{signOff}}
```

**`PAYROLL`** — subject `State de plată — {{month}}`

```
Bună ziua,

Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna {{month}}.

Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de {{taxDeadline}}.

În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

O zi bună,
{{signOff}}
```

**`REMINDER_NOTHING_RECEIVED`** — subject `Documente lipsă — {{month}}`

```
Bună ziua,

Pentru luna {{month}} mai avem nevoie de extrasul de cont și de facturile / chitanțele aferente.

Vă rugăm să le încărcați în portal.

Mulțumim,
{{signOff}}
```

**`REMINDER_DOCS_MISSING`** — subject `Documente lipsă — {{month}}`

```
Bună ziua,

Pentru luna {{month}} mai avem nevoie de documentele justificative (factură/chitanță) pentru următoarele tranzacții:
{{missingTransactions}}

Vă rugăm să le încărcați în portal.

Mulțumim,
{{signOff}}
```

**`REMINDER_DOCS_ONLY`** — subject `Documente lipsă — {{month}}`

```
Bună ziua,

Pentru luna {{month}} mai avem nevoie de facturile / chitanțele aferente.

Vă rugăm să le încărcați în portal.

Mulțumim,
{{signOff}}
```

The three reminder defaults are today's `email.needStatementAndDocs`, `email.needDocsForTxns` and the
currently-orphaned `email.needDocs` i18n strings.

## 7. Builder refactor

Each builder inverts: it stops owning prose and starts assembling variables.

- **`PaymentEmailBuilder`** keeps its per-line rendering, which becomes the `paymentSummary` /
  `paymentAccounts` block renderer, and loses the surrounding prose.
- **`ReportEmailBuilder`**, **`PayrollEmailBuilder`** become variable-map assemblers.
- Money and date formatting stay in the builders, so a firm cannot break number formats.
- Subjects move from `TaxEmailService.subject`, `ReportEmailBuilder.subject`,
  `PayrollEmailBuilder.subject` and `DocumentReminderService.send` into the templates.

`DocumentReminderService.send` currently recomputes the subject itself. After this change the subject
arrives from the rendered template alongside the body.

## 8. API

New `TemplateSettingsController` under `/api/v1/settings/messaging/templates`, with
`@PreAuthorize("hasRole('TENANT_ADMIN')")` to match `MessagingSettingsController`.

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/templates` | All six kinds: `{kind, subject, body, customized, placeholders[], blocks[]}`. Returns the default text when not customized, so the editor always has a starting point. |
| `PUT` | `/templates/{kind}` | Upsert the tenant's row. |
| `DELETE` | `/templates/{kind}` | Delete the row — reset to default. |
| `POST` | `/templates/{kind}/preview` | Render the submitted (unsaved) text against **sample** data, so preview needs no real company. |

Plus `GET /api/v1/companies/{companyId}/document-reminders/body?period=YYYY-MM-DD` →
`{state, subject, body}` (§9), alongside the existing `report/email-body` and `payroll/email-body`.

### Validation (on `PUT`, rejected as a field error)

- unknown placeholder name for that kind
- malformed delimiters (unbalanced `{{` / `}}`)
- blank body
- body over 10 000 characters; subject over 300

## 9. Reminder consolidation

State selection moves to the server, which already has the data — `docRequestBody` calls
`reconciliation.transactionsWithMatches` for the in-app notification:

| Condition | Kind |
|---|---|
| no bank statement for the period | `REMINDER_NOTHING_RECEIVED` |
| statement present, ≥1 transaction requires a document and has none matched | `REMINDER_DOCS_MISSING` |
| statement present, nothing missing | `REMINDER_DOCS_ONLY` |

`frontend/src/lib/reminderBody.ts` is **deleted**. Its three callers —
`SendReminderModal`, `ReconcileWorkspace`, `Statements` — fetch the body instead.

This removes work from the client. `SendReminderModal` today fires N `bankApi.transactions` queries purely
to compute the missing list itself; those go away, replaced by one body call per company (it already makes
N `emailApi.envelope` calls, so the request shape is unchanged in character).

The `email.greeting`, `email.needStatementAndDocs`, `email.needDocsForTxns`, `email.uploadPortal` and
`email.signoff` i18n keys are used **only** by `reminderBody.ts` (verified), so they are removed with it
in both RO and EN. `email.needDocs` and `email.subject` are already unused — dead-code cleanup, not a
behaviour change. `email.subjectLabel` and the other `email.*` UI labels stay: they label controls, not
message content.

## 10. Testing

Per `backend/CLAUDE.md`, a cross-tenant isolation test is mandatory for every new data-access path.

- **Renderer unit tests** — substitution, blank values, empty-line elision, unknown-placeholder stripping,
  malformed delimiters.
- **Golden default tests** — for `TAX`, `REPORT`, `PAYROLL`, the rendered default is byte-identical to the
  current builder output. These are the regression net proving no firm sees a change.
- **Reminder default tests** — expected output per state, asserted explicitly (not byte-identical to
  today; see §11).
- **Validation tests** — each rejection rule.
- **`MessageTemplateIsolationIT`** — tenant A cannot read, update or delete tenant B's templates, and
  tenant A's send renders A's text while B's renders B's.
- **`TemplateSettingsAuthzIT`** — STAFF and REPRESENTATIVE get 403 on all four endpoints.
- **State-selection test** — all three reminder states from realistic fixtures.

## 11. Deliberate behaviour changes

1. **Reminder month format.** The reminder subject renders `04.2026` today (`MM.yyyy`) and the body gets
   `2026-04` from the frontend's `period.slice(0, 7)`. Both become `Aprilie 2026`, consistent with the tax,
   report and payroll messages. This is why the reminder defaults are tested against explicit expected
   output rather than byte-identity.
2. **Missing-transaction row format.** Server-side rendering settles the current divergence between the
   frontend row (`• 2026-04-15 — Partner — 1.234,56 RON`) and the backend notification row
   (`• 15.04 · Partner · 1234 lei (furnizor)`) in favour of `• 15.04.2026 — Partner SRL — 1.234,56 RON`.
3. **Reminder language no longer follows the accountant's UI toggle.** It follows the tenant's template.

## 12. Out of scope

- **Invite email** (`InviteMessageFactory`) — stays hardcoded; see §2.
- **In-app notification bodies** (`DocumentReminderService.docRequestBody`, `TaxEmailService.notifyTaxDue`)
  — a different channel and a shorter message; still hardcoded Romanian.
- **Per-company template overrides** — tenant-level only, as requested.
- **Per-language template variants** — one template per kind.
- **Audit-log entries for template edits** — `updated_by` / `updated_at` only for now.
