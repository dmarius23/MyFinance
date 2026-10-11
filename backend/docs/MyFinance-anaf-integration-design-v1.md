# MyFinance — ANAF integration: company lookup + e-Factura (design note v1)

**Status:** Draft for review · **Scope:** (A) company data by CUI, (B) e-Factura invoice retrieval · **Owner:** TBD

## 1. Goal

Two independent ANAF integrations, deliberately split because they differ by an order of magnitude in effort:

- **A — Company lookup by CUI.** When an accountant adds a client company, fetch its official data from
  ANAF instead of typing it: legal name, **fiscal domicile**, trade-register number, CAEN, VAT status,
  inactive/struck-off status, and whether it is enrolled in the RO e-Factura register. Public, no
  credentials. **Days of work.**
- **B — e-Factura retrieval.** Pull each client's invoices straight from SPV as structured
  **UBL / RO_CIUS XML**, so purchase invoices stop depending on PDF heuristics and OCR. Requires the
  firm's qualified certificate + OAuth. **Weeks of work.**

Non-goals (v1): e-Factura **upload** of issued invoices (later), e-Transport, SAF-T/D406, ANAF balance
sheets (`bilant`), VIES cross-checks.

## 2. Key decisions

| Decision | Choice | Rationale |
|---|---|---|
| Split A from B | Ship **A first**, independently | A has no credential dependency and pays off immediately |
| A: auth | **None** — public service | ANAF `PlatitorTvaRest` is unauthenticated |
| B: auth | **OAuth 2.0 Bearer**, per tenant | Firm's qualified cert is already registered in SPV with împuterniciri |
| B: credential storage | `SecretCipher` (AES-256-GCM), per tenant | Same pattern already proven for SMTP/WhatsApp secrets (V57) |
| B: trigger | **Scheduled poll** in the worker | Mirrors `IngestionScheduler`; no public webhook surface |
| B: source of truth | **XML**, not the PDF visualisation | Structured fields beat the existing PDF heuristics outright |
| B: idempotency | ANAF **message id** | Exact; better than the content hash used for Drive |
| Where it fits | A third **ingestion source** beside Drive and the planned email agent | Reuses classifier → validator → reconcile unchanged |
| Residency | ANAF is a Romanian state service | No EU-egress concern (unlike the US vision OCR) |

---

# Part A — Company lookup by CUI

## A.1 The service (verified Oct 2026)

`POST https://webservicesp.anaf.ro/api/PlatitorTvaRest/v9/tva`
Body: `[{"cui": 48556230, "data": "2026-10-11"}]` — **max 100 CUIs per request, max 1 request/second**,
no auth, no registration.

Response groups and the fields we care about:

| Group | Fields we use | Feeds |
|---|---|---|
| `date_generale` | `denumire`, `adresa`, `nrRegCom`, `cod_CAEN`, `iban`, `stare_inregistrare`, **`statusRO_e_Factura`** | company record; e-Factura eligibility (Part B) |
| `adresa_domiciliu_fiscal` | `dlocalitate`, `djudet`, `dstrada`, `dcod_postal` | **fiscal residence** (see A.3) |
| `adresa_sediu_social` | `slocalitate`, `sjudet`, … | registered office, when it differs |
| `inregistrare_scop_Tva` | `scpTVA`, `data_inceput_ScpTVA`, `data_sfarsit_ScpTVA` | VAT-payer status |
| `stare_inactiv` | `statusInactivi`, `dataRadiere` | inactive / struck-off guard |
| `inregistrare_SplitTVA` | `statusSplitTVA` | informational |

## A.2 Shape

Port `CompanyRegistryLookup` in `company/application`, adapter `AnafCompanyLookup` in
`company/adapter/external` (RestClient, like the existing ANAF IBAN scraper). A thin web endpoint
`GET /api/v1/companies/lookup?cui=…` lets the "add company" form autofill on CUI blur; the accountant
still confirms before saving — ANAF data is a **suggestion, not an override**.

Fail open: ANAF down ⇒ the form stays manually editable. Never block company creation on it.

Cache per `(cui, date)` for the day — the data changes at most daily and the rate limit is 1 req/s.

## A.3 Three existing problems this quietly fixes

1. **Fiscal residence.** Treasury-IBAN matching needs the *fiscal* residence; today we only hold a
   free-text `locality` typed by hand, which is why tax emails showed "accounts missing".
   `adresa_domiciliu_fiscal.dlocalitate/djudet` is exactly that value, authoritative. This supersedes the
   parked "dedicated `fiscal_residence` field" TODO — the field should be **populated from ANAF**.
2. **VAT status.** `scpTVA` feeds the existing "D300 N/A for non-VAT companies" rule instead of relying on
   a manually ticked box.
3. **Inactive companies.** `statusInactivi` / `dataRadiere` feeds the existing "hide inactive companies"
   filter, and should warn when adding a struck-off company.

## A.4 Data model

Add to `company`: `reg_com`, `caen`, `vat_payer` (+ `vat_from`/`vat_to`), `fiscal_locality`,
`fiscal_county`, `anaf_status` (`ACTIVE|INACTIVE|RADIATED`), `efactura_registered`,
`anaf_synced_at`. Migration **V58** (next free). No new table ⇒ inherits `company`'s RLS.

A nightly refresh for existing companies is a cheap follow-up (100 CUIs per call covers the whole firm in
a couple of requests) — worth it to catch a client going inactive or losing VAT registration.

---

# Part B — e-Factura retrieval

## B.1 The service (verified Oct 2026)

OAuth-protected REST under `https://api.anaf.ro/prod/FCTEL/rest/` (ANAF also documents
`webserviceapl.anaf.ro/prod/FCTEL/rest/`; test realm is `/test/`). Bearer token in `Authorization`.

| Endpoint | Use |
|---|---|
| `listaMesajeFactura?zile=N&cif=X&filtru=P` | recent messages for one CIF (`P` = *primite*, purchases) |
| `listaMesajePaginatieFactura?startTime&endTime&cif&pagina` | date-range paged listing, for backfill |
| `descarcare?id=<messageId>` | ZIP containing the invoice **XML** + ANAF signature |
| `stareMesaj?id_incarcare=` | status of an upload (Part C, later) |
| `transformare/FACT1` | XML → PDF, if we ever want ANAF's own visualisation |

⚠️ **Verify before coding:** the `zile` window, pagination limits and token lifetimes have changed
repeatedly across ANAF releases. Treat the numbers above as "confirm against current docs", not settled.

## B.2 Authentication — the one genuinely awkward part

The firm holds a qualified certificate registered in SPV, with împuterniciri for its clients (confirmed).
Token acquisition is **interactive**: a browser + the certificate on a USB token/smartcard. It cannot be
fully headless the first time.

Therefore: a **one-time, operator-driven authorisation** in Setări — the admin completes ANAF's OAuth
flow, MyFinance stores the `access_token` + `refresh_token` encrypted per tenant, and the worker refreshes
on schedule. Surface token expiry prominently; a silently dead token means invoices stop arriving, which
is exactly the failure mode this feature exists to prevent. Treat "token expires in < 14 days" as a
visible warning, not a log line.

## B.3 Pipeline

```
poll per tenant (worker, scheduled)
  └─ for each company with efactura_registered  (Part A tells us which)
       └─ listaMesajeFactura(cif, zile)          → message ids
            └─ skip ids already in the ledger    ← idempotency = ANAF message id
                 └─ descarcare(id)               → ZIP
                      └─ unzip → UBL/CII XML
                           └─ UblInvoiceParser   → ParsedInvoice (behind the existing InvoiceExtractor port)
                                └─ DocumentService.upload(type=INVOICE, source=ANAF)
                                     └─ existing validator → reconcile pipeline (unchanged)
```

- New `DocumentSource.ANAF` (the enum already carries `REP|EMPLOYEE|EMAIL_AGENT|DRIVE`).
- Store **both** the XML (source of truth) and, optionally, a rendered PDF for human viewing.
- The XML gives supplier name + CIF, invoice number, issue/due dates, net/VAT/total, currency and line
  items **exactly** — no OCR, no confidence threshold, no "wrong party" heuristics.
- Keep `DocumentValidator` in the loop anyway for the duplicate and wrong-company checks: an împuternicire
  mistake could surface another company's invoice, and that must be flagged, not imported.

## B.4 Data model

- `anaf_credential` (tenant_id, access_token_enc, refresh_token_enc, expires_at, scope, created_by) — RLS,
  secrets via `SecretCipher`.
- Reuse `import_file` as the ledger (`source_ref` = ANAF message id) so the existing
  `/ingestion/unresolved` review surface works unchanged.
- Migration **V59**.

## B.5 Risks

| Risk | Mitigation |
|---|---|
| Token expiry stops ingestion silently | Expiry surfaced in Setări + warning well before; alert on refresh failure |
| Împuternicire missing for a client | ANAF returns an error per CIF → mark that company, don't fail the whole run |
| Listing window too short to backfill | Use the paginated date-range endpoint for history, `zile` for the daily poll |
| Duplicate against a Drive/email copy of the same invoice | Content hash still applies across sources; prefer the ANAF XML as authoritative |
| ANAF outage / rate limiting | Retry with backoff; it's a scheduled job, lateness is acceptable |

---

## 3. Suggested sequence

1. **A** — lookup + autofill on add-company (+ V58 fields). Immediate value, no credentials.
2. **A′** — nightly refresh; wire `scpTVA` / `statusInactivi` into the existing rules; populate fiscal
   residence and re-check the treasury-IBAN matching that prompted this.
3. **B1** — OAuth setup screen + encrypted token storage + refresh job.
4. **B2** — poll → download → `UblInvoiceParser` → INVOICE documents, for one pilot company.
5. **B3** — roll out to all `efactura_registered` companies; backfill via the paginated endpoint.
6. **C (later)** — e-Factura *upload* for issued invoices.

## 4. Open questions

- Should ANAF data **overwrite** a manually edited company field on refresh, or only fill blanks and flag
  drift? (Proposal: never overwrite silently; show a "differs from ANAF" badge.)
- Do we need **sales** invoices (`filtru=E`) too, or purchases only in v1?
- Retention: keep the ANAF ZIP/signature for audit, or just the XML + parsed row?

## Sources

- [ANAF — e-Factura web services (endpoint list)](https://static.anaf.ro/static/10/Anaf/Informatii_R/Servicii_web/url_eFactura.html)
- [ANAF — PlatitorTvaRest v9 specification](https://static.anaf.ro/static/10/Anaf/Informatii_R/Servicii_web/doc_WS_V9.txt)
- [MF — Prezentare servicii web e-Factura (PDF)](https://mfinante.gov.ro/static/10/eFactura/prezentare%20api%20efactura.pdf)
