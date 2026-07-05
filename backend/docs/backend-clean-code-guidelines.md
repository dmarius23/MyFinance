# MyFinance — Backend Clean-Code Guidelines

> Conventions every backend change (human **or** AI) must follow. Distilled from what this codebase
> already does well, plus the fixes in [`backend-improvement-plan.md`](backend-improvement-plan.md).
> When a rule here and [`CLAUDE.md`](../../CLAUDE.md) disagree, CLAUDE.md wins — this doc expands it, never
> overrides it.

These are **rules for new and changed code**, not a demand to rewrite the whole tree at once. Follow them
when you touch a file; use the improvement plan for deliberate, staged refactors. Steps referenced as
`S1`…`S19` point at that plan.

---

## 1. Golden invariants (never violate)

These are load-bearing for a multi-tenant financial system. Breaking one is a release blocker.

- **Tenant isolation is enforced at the database.** Every tenant-scoped table has `tenant_id NOT NULL`,
  RLS `ENABLE` **+ `FORCE`**, and a fail-closed policy
  (`tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`). Unset GUC → zero rows, never
  fail-open. **Add a cross-tenant isolation test for every new data-access path** (mirror
  `CrossTenantIsolationTest`).
- **Representatives are scoped to their own company, server-side.** Resolve the active company from the
  validated token / `representative_link`, never trust `X-Company-Id` alone. Re-check ownership on every
  read *and* download.
- **Extracted amounts are non-authoritative** until they pass reconciliation/confidence checks. Never let
  raw OCR/parser output drive money figures in an email or a posting without verification.
- **No iText.** PDF via PDFBox (+ tabula-java); e-Factura via JAXB/Jackson-XML. (License constraint.)
- **Secrets only via env/config**, never committed, never logged. Mask PII in logs. Payroll/PII is
  sensitive.
- **Fail-safe authorization defaults.** Deny by default at the URL layer *and* annotate the method (S1).
  A new endpoint with no role rule must not be reachable by the wrong role.

## 2. Module & layering rules

The backend is a **modular monolith**: one package per module under `ro.myfinance` (`tenant`, `access`,
`company`, `intake`, `extraction`, `reports`, `taxpayments`, `payroll`, `notifications`, `tasks`,
`dashboard`, `settings`, `ingestion`, `portal`) plus `common`. Each module is hexagonal:

```
<module>/
  domain/        entities, value objects, ports (interfaces)
  application/   services / use-cases (the only place that orchestrates)
  adapter/
    web/         controllers + request/response DTOs
    persistence/ Spring Data repositories
    external/    port implementations (OCR, email, Drive, storage, invites)
```

Rules:
- **`domain` imports nothing web-facing.** No Spring MVC, no controllers, no `HttpServletRequest`.
- **Controllers call `application` services only** — never a repository directly.
- **Cross-module access goes through the other module's `application` service or a published port —
  never its `adapter.persistence`.** (Today 11 classes import `CompanyRepository` across a boundary;
  S12 fixes this and S14 adds an ArchUnit rule to keep it fixed.)
- **External side effects sit behind a port** declared in `application` (or `common`), implemented in
  `adapter/external`. A stub and a production adapter must be swappable by config
  (`@ConditionalOnProperty`), exactly like `EmailSender`, `DocumentStorage`, `ReceiptExtractor`,
  `UserInviter`, `CloudFolderConnector`.
- **Shared ports live in `common`, not in a random module.** (S13 moves `EmailSender` out of
  `taxpayments`.)

## 3. Accepted trade-offs (documented — do **not** "fix" these)

- **JPA annotations live directly on domain entities.** This is deliberate. Introducing a parallel
  persistence-entity layer + mappers would *add* code for little benefit in a modular monolith, and it
  fights the project's "less code" goal. There is no bug here. If you need to decouple a read model,
  use a **Spring Data projection / DTO query**, not a hand-written mapping layer. Do not spend a refactor
  turning `@Entity` domain classes into POJOs + mappers.
- **No generic response envelope.** Controllers return DTOs directly; errors use RFC-7807. Keep it that
  way — don't wrap successful responses in `{data: ...}`.

## 4. DTOs & mapping

- Request/response DTOs are **records**, defined in the module's `adapter/web`.
- **Never return a JPA entity from a controller.** Map to a DTO with a static `from(entity)` factory;
  keep one canonical view per resource.
- Validate request DTOs with **Bean Validation** (`@NotNull`, `@NotBlank`, `@Positive`, …) and `@Valid`
  at the controller — don't hand-roll validation in the service.

## 5. Errors & API

- Throw the shared exceptions: `NotFoundException` (404), `ConflictException` (409),
  `IllegalArgumentException` (400). Let `common/web/ApiExceptionHandler` map them to RFC-7807
  `ProblemDetail`. Don't scatter `ResponseStatusException` or return ad-hoc error bodies.
- **No swallow-and-continue.** `catch (Exception e)` that logs and proceeds is only acceptable for
  documented best-effort work (e.g. a parser that degrades gracefully) and must log at `warn`+ with
  context. Never swallow inside a transaction that should roll back.
- URLs follow `/api/v1/*`; the rep BFF is `/api/v1/portal/*`; admin is `/api/v1/admin/*`.
- **OpenAPI (springdoc) is the frontend's contract.** Keep annotations and DTOs accurate — the FE
  generates its typed client from `/v3/api-docs`.

## 6. Persistence

- Keep `spring.jpa.open-in-view: false`. Load what you need inside the service transaction.
- **Prefer targeted/derived queries over `findAll()`-then-filter-in-Java.** Filtering a full table in
  memory is a latent scaling bug (see S16). Use `findByCompanyIdAndPeriodMonth…`, `findAllById`, or an
  explicit `@Query`.
- **Paginate list endpoints** (`Pageable`, with a max page size). Unbounded lists are acceptable only for
  provably tiny, tenant-scoped sets — and note why.
- Every hot query has a matching **composite index** (the schema convention is
  `(tenant_id, company_id, period_month)`); add one in the same migration as a new query pattern.
- `@Transactional` on **every multi-step write**; `readOnly = true` for pure reads.
- **External effects that must survive a crash go through the outbox (S4), not inline** in the request
  transaction. Don't call SES/GoTrue/Drive from a controller thread and hope.

## 7. Async & jobs

- Heavy or external work (PDF extraction, OCR, email, invites) belongs **off the request thread** — via
  the outbox relay or the job queue, consumed by the worker process.
- **Jobs are idempotent**, keyed by a natural id (document id, period, message id). Re-processing must be
  a no-op.
- Job execution has **exponential backoff + an attempts cap → DLQ**; poison messages never spin forever.
- The **outbox row is written inside the business transaction**; the worker relays it afterward under a
  dedicated **SYSTEM** DB role. In-process, best-effort notifications may use a synchronous
  `ApplicationEvent`, but anything whose loss matters must be an outbox row.
- The ingestion scheduler must run **exactly once** across instances (distributed lock or worker-only —
  S6). Never assume a single web instance.

## 8. Security defaults for new code

- New endpoint → **add a URL role rule (S1) *and* a `@PreAuthorize`**. Don't rely on either alone.
- Validate JWT **issuer + audience + clock skew**, not just the signature (S3).
- File uploads: enforce **multipart size limits + a content-type/magic-byte allowlist** (S2); **sanitize
  filenames** before building `storage_key` or a `Content-Disposition` header; keep path-traversal guards
  on storage.
- **Never log secrets or PII.** Mask emails, names, amounts, keys. The service-role key and Google
  private key are admin-grade — treat accordingly (S17).
- Prefer non-root containers, least-privilege DB roles, and restricted actuator exposure.

## 9. Reuse before you write

Before adding a helper, check `common` and existing services — the codebase already centralizes several
things and past duplication (S9/S10) is being removed, not added to:

- Email recipient/sender resolution → `EmailEnvelopeService` (in `access`).
- Email send + history → the shared `EmailDispatchService` / `common/email` (S9/S13). **Do not** create a
  fifth `*EmailService` + `*Email` entity + `*EmailRepository` triad.
- String normalization (diacritics/alnum/upper) → `common/text/StringNormalizer` (S10).
- Period parsing → the shared `PeriodMonth` resolver (S10); don't re-write `withDayOfMonth(1)` in every
  service.
- Company reads from another module → `CompanyDirectory` / `CompanyQueryService` (S12).

## 10. Testing bar (Definition of Done)

A change is done when:
- Unit tests cover the logic **and** a **Testcontainers integration test** covers the data-access path.
- A **cross-tenant isolation test** exists for every new table or query path (mandatory, non-negotiable).
- Every rep-reachable surface has a **negative authorization test** (rep cannot reach another company or a
  staff endpoint).
- Refactors are preceded by **characterization tests** that lock current behavior.
- CI is green, including `gitleaks` and the SAST/SCA gates (S15). No secrets in code; no PII in logs.
- RLS is enforced and tested; API changes are reflected in OpenAPI; acceptance criteria from the
  requirements are met.

---

*Keep this doc close to the code. When a convention changes, update this file in the same PR — and if the
change is structural, update [`backend-improvement-plan.md`](backend-improvement-plan.md) and the design
docs (S14) too.*
