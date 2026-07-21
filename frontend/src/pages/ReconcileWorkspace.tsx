import { useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bankApi, invoicesApi, reconciliationApi, type BankTransaction, type OpenInvoice, type MatchSuggestion } from "../api/bank";
import { companiesApi } from "../api/companies";
import { documentsApi } from "../api/documents";
import { ingestionApi } from "../api/ingestion";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";
import { DocumentPreviewModal } from "../components/DocumentPreviewModal";
import { SendReminderModal } from "../components/SendReminderModal";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const maskIban = (iban: string | null) => (!iban || iban.length <= 4 ? (iban ?? "") : `…${iban.slice(-4)}`);
const TOL = 0.01;

/** Distinctive lowercase tokens of a name (≥4 letters, diacritics/punct stripped) for supplier matching. */
function tokens(s: string | null): string[] {
  if (!s) return [];
  return s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ").split(/\s+/).filter((w) => w.length >= 4);
}
function nameMatch(supplier: string | null, txnText: string): boolean {
  const hay = txnText.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
  return tokens(supplier).some((tk) => tk !== "romania" && hay.includes(tk));
}
/** True when the transaction plausibly names the invoice's supplier — by name token, fiscal code, or IBAN. */
function supplierMatch(inv: OpenInvoice, txnText: string, partnerIban: string | null): boolean {
  if (nameMatch(inv.supplierName, txnText)) return true;
  const cui = (inv.issuerCif ?? "").replace(/\D/g, "");
  if (cui.length >= 5 && txnText.replace(/\D/g, "").includes(cui)) return true;
  const iban = (inv.supplierIban ?? "").replace(/\s/g, "").toUpperCase();
  const pib = (partnerIban ?? "").replace(/\s/g, "").toUpperCase();
  return !!iban && iban === pib;
}

type WSuggestion = {
  key: string;
  kind: "EXACT" | "AMOUNT" | "SUPPLIER" | "SPLIT" | "INSTALLMENT";
  invoices: { invoiceId: string; documentId: string | null; filename: string | null; supplierName: string | null; amount: number }[];
  total: number;
  apply: () => void;
};

export function ReconcileWorkspace() {
  const { companyId = "" } = useParams();
  const { period } = usePeriod();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const qc = useQueryClient();

  const company = useQuery({ queryKey: ["company", companyId], queryFn: () => companiesApi.get(companyId) });
  const statements = useQuery({ queryKey: ["bank-stmts", companyId, period], queryFn: () => bankApi.statements(companyId, period) });
  const txns = useQuery({ queryKey: ["bank-txns", companyId, period], queryFn: () => bankApi.transactions(companyId, period) });
  const openQ = useQuery({ queryKey: ["open-invoices", companyId, period], queryFn: () => invoicesApi.open(companyId, period, 18, true) });
  const suggestionsQ = useQuery({ queryKey: ["match-suggestions", companyId, period], queryFn: () => reconciliationApi.suggestions(companyId, period) });

  const [selectedTxnId, setSelectedTxnId] = useState<string | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [txnFilter, setTxnFilter] = useState<"all" | "unmapped">("all");
  const [invFilter, setInvFilter] = useState<"all" | "unmapped">("all");
  const [search, setSearch] = useState("");
  const [preview, setPreview] = useState<{ documentId: string; filename: string | null; invoiceId?: string } | null>(null);
  const [requesting, setRequesting] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const stmtFileRef = useRef<HTMLInputElement>(null);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["open-invoices", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["match-suggestions", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["recon-summary", period] });
    void qc.invalidateQueries({ queryKey: ["doc-status", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["doc-summary", period] });
  };
  const onErr = (e: unknown) => window.alert(`${t("recon.linkFailed")}: ${e instanceof Error ? e.message : String(e)}`);

  const match = useMutation({ mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.match(companyId, txnId, invoiceId), onSuccess: invalidate, onError: onErr });
  const unmatch = useMutation({ mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.unmatch(companyId, txnId, invoiceId), onSuccess: invalidate, onError: onErr });
  const setReq = useMutation({ mutationFn: ({ id, requiresDocument }: { id: string; requiresDocument: boolean }) => bankApi.setRequirement(companyId, id, requiresDocument), onSuccess: invalidate });
  const applySuggestion = useMutation({
    mutationFn: async (s: MatchSuggestion) => { for (const l of s.links) await bankApi.match(companyId, l.transactionId, l.invoiceId, l.amount); },
    onSuccess: invalidate, onError: onErr,
  });
  const upload = useMutation({
    mutationFn: async (files: File[]) => { for (const f of files) await documentsApi.upload(companyId, period, f); },
    onSuccess: invalidate, onError: onErr,
  });
  // Drive sync (bank statements + invoices together): a general/mixed connection routes each file
  // through the classifier, exactly like the documents modal.
  const driveQ = useQuery({ queryKey: ["ingestion-source", "MIXED"], queryFn: () => ingestionApi.source("MIXED") });
  const driveEnabled = driveQ.data?.driveEnabled === true;
  const sync = useMutation({
    mutationFn: () => ingestionApi.syncCompany({ companyId, period, type: "MIXED" }),
    onSuccess: invalidate, onError: onErr,
  });
  const mapChecked = useMutation({
    mutationFn: async ({ txnId, invoiceIds }: { txnId: string; invoiceIds: string[] }) => { for (const id of invoiceIds) await bankApi.match(companyId, txnId, id); },
    onSuccess: () => { setChecked(new Set()); invalidate(); }, onError: onErr,
  });

  const list = txns.data ?? [];
  const openInvoices = openQ.data ?? [];
  const invById = useMemo(() => new Map(openInvoices.map((i) => [i.id, i])), [openInvoices]);
  const selectedTxn = selectedTxnId ? list.find((x) => x.id === selectedTxnId) ?? null : null;

  const needsDoc = (tx: BankTransaction) => tx.requiresDocument && !tx.fullyAllocated;
  const counts = useMemo(() => {
    let matched = 0, partial = 0, need = 0;
    for (const tx of list) {
      if (!tx.requiresDocument) continue;
      if (tx.fullyAllocated) matched++;
      else if (tx.matched) partial++;
      else need++;
    }
    return { matched, partial, need };
  }, [list]);

  const shownTxns = list.filter((tx) => (txnFilter === "all" ? true : needsDoc(tx)));

  // Per-transaction suggestions (client-side): EXACT (remaining ≈ txn remaining) + SUPPLIER (name match),
  // plus any backend SPLIT/INSTALLMENT/cross-period suggestion that involves the selected transaction.
  const txnSuggestions: WSuggestion[] = useMemo(() => {
    if (!selectedTxn) return [];
    const R = selectedTxn.remainingAmount;
    if (R <= TOL) return [];
    const already = new Set(selectedTxn.matchedInvoices.map((m) => m.invoiceId));
    const pool = openInvoices.filter((inv) => (inv.remaining ?? 0) > TOL && !already.has(inv.id) && !inv.wrongParty);
    const txnText = [selectedTxn.partnerName, selectedTxn.description].filter(Boolean).join(" ");
    const out: WSuggestion[] = [];
    const seen = new Set<string>();
    const add = (inv: OpenInvoice, kind: WSuggestion["kind"]) => {
      if (seen.has(inv.id)) return;
      seen.add(inv.id);
      const amount = Math.min(R, inv.remaining ?? 0);
      out.push({
        key: `${kind}-${inv.id}`, kind, total: amount,
        invoices: [{ invoiceId: inv.id, documentId: inv.documentId, filename: inv.filename, supplierName: inv.supplierName, amount }],
        apply: () => match.mutate({ txnId: selectedTxn.id, invoiceId: inv.id }),
      });
    };
    // EXACT = amount *and* supplier match; AMOUNT = amount only; SUPPLIER = supplier only (weaker).
    const amountOk = (inv: OpenInvoice) => Math.abs((inv.remaining ?? 0) - R) < TOL;
    const supplierOk = (inv: OpenInvoice) => supplierMatch(inv, txnText, selectedTxn.partnerIban);
    const byDate = (a: OpenInvoice, b: OpenInvoice) =>
      Math.abs((a.invoiceDate ?? "").localeCompare(selectedTxn.txnDate)) - Math.abs((b.invoiceDate ?? "").localeCompare(selectedTxn.txnDate));
    pool.filter((inv) => amountOk(inv) && supplierOk(inv)).sort(byDate).forEach((inv) => add(inv, "EXACT"));
    pool.filter((inv) => amountOk(inv) && !supplierOk(inv)).forEach((inv) => add(inv, "AMOUNT"));
    pool.filter((inv) => !amountOk(inv) && supplierOk(inv)).sort(byDate).forEach((inv) => add(inv, "SUPPLIER"));
    // Backend combos (split/installment/cross-period exact) touching this transaction.
    for (const s of suggestionsQ.data ?? []) {
      const relevant = s.links.filter((l) => l.transactionId === selectedTxn.id && !already.has(l.invoiceId));
      if (relevant.length === 0) continue;
      const invs = relevant.map((l) => ({ invoiceId: l.invoiceId, documentId: null, filename: l.invoiceFilename, supplierName: l.supplierName, amount: l.amount }));
      if (invs.length === 1 && seen.has(invs[0].invoiceId)) continue;
      out.push({ key: `bk-${s.kind}-${relevant[0].invoiceId}`, kind: s.kind, invoices: invs, total: invs.reduce((n, i) => n + i.amount, 0), apply: () => applySuggestion.mutate(s) });
    }
    return out.slice(0, 6);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedTxn, openInvoices, suggestionsQ.data]);

  // Every invoice referenced by a suggestion (amount, supplier, split, …) — all get highlighted in the pool.
  const suggestedIds = useMemo(
    () => new Set(txnSuggestions.flatMap((s) => s.invoices.map((i) => i.invoiceId))),
    [txnSuggestions],
  );

  // Invoice pool grouped by month (this month, then older), filtered by search + the Unmapped toggle.
  const groups = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const monthIdx = (ym: string) => { const [y, m] = ym.split("-").map(Number); return y * 12 + (m - 1); };
    const curIdx = monthIdx(period.slice(0, 7));
    const filtered = openInvoices.filter((inv) => {
      const fullyPaid = !((inv.remaining ?? 0) > TOL);
      // Fully-paid documents from an earlier month are resolved history — never surface them here.
      if (fullyPaid && monthIdx(inv.periodMonth.slice(0, 7)) < curIdx) return false;
      if (invFilter === "unmapped" && fullyPaid) return false;
      if (!needle) return true;
      return [inv.filename, inv.supplierName, inv.totalAmount?.toString(), inv.remaining?.toString(), inv.invoiceDate]
        .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
    });
    const cur: OpenInvoice[] = [], other: OpenInvoice[] = [];
    for (const inv of filtered) (monthIdx(inv.periodMonth.slice(0, 7)) >= curIdx ? cur : other).push(inv);
    const rank = (inv: OpenInvoice) => (suggestedIds.has(inv.id) ? 0 : 1);
    const sortFn = (a: OpenInvoice, b: OpenInvoice) => rank(a) - rank(b) || (b.invoiceDate ?? "").localeCompare(a.invoiceDate ?? "");
    cur.sort(sortFn); other.sort(sortFn);
    return [
      { key: "cur", label: t("recon.thisMonth"), items: cur },
      { key: "other", label: t("recon.otherMonths"), items: other },
    ].filter((g) => g.items.length > 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openInvoices, search, invFilter, period, suggestedIds]);
  const poolTotal = groups.reduce((n, g) => n + g.items.length, 0);

  const selectTxn = (id: string) => { setSelectedTxnId((cur) => (cur === id ? null : id)); setChecked(new Set()); };
  const toggleCheck = (id: string) => setChecked((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  const hasStatement = (statements.data?.length ?? 0) > 0;
  const c = company.data;
  const submeta = c ? [`CIF ${c.cui}`, c.locality, monthLabel(period)].filter(Boolean).join(" · ") : "";

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "calc(100vh - 46px)", minHeight: 0 }}>
      {/* ===== header ===== */}
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "12px 18px 10px" }}>
        <button onClick={() => navigate("/statements")} title={t("recon.back")}
          style={{ ...iconBtn, width: 34, height: 34 }}><Icon name="chevronLeft" size={18} /></button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 21, fontWeight: 700, color: "var(--text)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{c?.legalName ?? "…"}</div>
          <div className="mono" style={{ fontSize: 12, color: "var(--text-muted)" }}>{submeta}</div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 14, fontSize: 12.5, fontWeight: 600 }}>
          <span style={{ color: "var(--ok-fg, #166534)" }}>{counts.matched} {t("recon.matched").toLowerCase()}</span>
          <span style={{ color: "var(--warn-fg, #92400e)" }}>{counts.partial} {t("recon.partialShort")}</span>
          <span style={{ color: "var(--danger-fg, #991b1b)" }}>{counts.need} {t("recon.needDoc")}</span>
        </div>
        <button className="primary" disabled={counts.need + counts.partial === 0} onClick={() => setRequesting(true)}>
          <Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
          {t("recon.requestClient")} · {counts.need + counts.partial}
        </button>
      </div>

      {/* ===== statement strip / empty upload ===== */}
      {hasStatement ? (
        <div style={{ margin: "0 18px 10px", ...card, padding: "4px 12px" }}>
          {(statements.data ?? []).map((s) => (
            <div key={s.id} style={{ display: "flex", alignItems: "center", gap: 12, fontSize: 12.5, padding: "7px 0", borderBottom: "1px solid var(--hair)" }}>
              <span style={{ width: 30, height: 30, borderRadius: 8, background: "var(--th-bg)", display: "flex", alignItems: "center", justifyContent: "center", flex: "none" }}><Icon name="statements" size={15} style={{ color: "var(--text-muted)" }} /></span>
              <b>{s.bankCode ?? "—"}</b>
              <span className="mono" style={{ color: "var(--text-muted)" }}>{maskIban(s.accountIban)}</span>
              <span className="mono" style={{ color: "var(--text-muted)" }}>{s.openingBalance != null ? money(s.openingBalance) : "—"} → {s.closingBalance != null ? money(s.closingBalance) : "—"}</span>
              <span className={`pill round ${s.crossCheckOk ? "ok" : "danger"}`}>✓ {t("recon.txnsParsed", { n: s.txnCount })}</span>
              <span style={{ flex: 1 }} />
              <button onClick={() => setPreview({ documentId: s.documentId, filename: [s.bankCode, maskIban(s.accountIban)].filter(Boolean).join(" ") || null })}
                style={eyeBtn} title={t("recon.viewStatement")} aria-label={t("recon.viewStatement")}><Icon name="eye" size={15} /></button>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ margin: "0 18px 10px", border: "1px dashed var(--danger-bd, #fecaca)", background: "var(--danger-bg, #fee2e2)", borderRadius: 12, padding: 14, display: "flex", alignItems: "center", gap: 14 }}>
          <span style={{ width: 40, height: 40, borderRadius: 10, background: "#fff", display: "flex", alignItems: "center", justifyContent: "center", flex: "none" }}><Icon name="statements" size={18} style={{ color: "var(--danger-fg, #991b1b)" }} /></span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 700, color: "var(--danger-fg, #991b1b)", fontSize: 14 }}>{t("recon.noStatementTitle")}</div>
            <div style={{ fontSize: 12.5, color: "var(--text-secondary, #55605d)" }}>{t("recon.noStatementSub")}</div>
          </div>
          {driveEnabled && (
            <button disabled={sync.isPending} onClick={() => sync.mutate()}>
              <Icon name="reconcile" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />{sync.isPending ? t("files.syncing") : t("files.syncFromDrive")}
            </button>
          )}
          <button className="primary" disabled={upload.isPending} onClick={() => stmtFileRef.current?.click()}>
            <Icon name="upload" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />{t("recon.uploadStatement")}
          </button>
          <input ref={stmtFileRef} type="file" accept="application/pdf,image/*,text/xml,application/xml,text/plain" style={{ display: "none" }}
            onChange={(e) => { const f = Array.from(e.target.files ?? []); e.target.value = ""; if (f.length) upload.mutate(f); }} />
        </div>
      )}

      {/* ===== two columns ===== */}
      <div style={{ flex: 1, minHeight: 0, display: "flex", gap: 14, padding: "0 18px 16px" }}>
        {/* ----- LEFT: transactions ----- */}
        <div style={{ flex: 1.12, minWidth: 0, display: "flex", flexDirection: "column", ...card }}>
          <ColHeader title={t("recon.bankTransactions")} filter={txnFilter} setFilter={setTxnFilter} t={t} />
          <div style={{ flex: 1, overflow: "auto", minHeight: 0 }}>
            {shownTxns.length === 0 && <Empty text={txnFilter === "unmapped" ? t("recon.allReconciled") : t("recon.noTxns")} />}
            {shownTxns.map((tx) => {
              const sel = tx.id === selectedTxnId;
              const accent = sel ? "var(--primary)" : tx.fullyAllocated ? "var(--dot-green, #16a34a)" : needsDoc(tx) ? "var(--dot-red, #dc2626)" : "transparent";
              return (
                <div key={tx.id} onClick={() => selectTxn(tx.id)}
                  style={{ borderLeft: `3px solid ${accent}`, borderBottom: "1px solid var(--hair)", padding: "9px 12px", cursor: "pointer", background: sel ? "var(--row-active, #ecf7f5)" : undefined }}>
                  <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                    <span style={{ fontWeight: 600, flex: 1, minWidth: 0, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{tx.partnerName ?? "—"}</span>
                    {tx.category && <span className="pill round muted" style={{ flex: "none" }}>{tx.category}</span>}
                    <span className="mono" style={{ flex: "none", fontWeight: 700, fontVariantNumeric: "tabular-nums", color: tx.amount < 0 ? "var(--text)" : "#15803d" }}>{tx.amount < 0 ? "−" : "+"}{money(Math.abs(tx.amount))}</span>
                  </div>
                  <div className="mono" style={{ fontSize: 11.5, color: "var(--text-muted)", marginTop: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{tx.txnDate} · {tx.description ?? maskIban(tx.partnerIban)}</div>
                  {/* status */}
                  {tx.matched ? (
                    <div style={{ marginTop: 5, display: "grid", gap: 3 }}>
                      {tx.matchedInvoices.map((mi) => (
                        <div key={mi.invoiceId} style={{ display: "flex", alignItems: "center", gap: 6, background: "var(--ok-bg, #dcfce7)", border: "1px solid var(--ok-bd, #bbf7d0)", borderRadius: 7, padding: "3px 8px", fontSize: 11.5 }}>
                          <span style={{ color: "var(--ok-fg, #166534)" }}>✓</span>
                          <span style={{ flex: 1, minWidth: 0, color: "var(--ok-fg, #166534)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{mi.filename ?? "factura"}</span>
                          <span className="mono" style={{ color: "var(--text-muted)" }}>{mi.allocatedAmount != null ? money(mi.allocatedAmount) : ""}</span>
                          <button onClick={(e) => { e.stopPropagation(); unmatch.mutate({ txnId: tx.id, invoiceId: mi.invoiceId }); }} style={linkBtn}>{t("recon.unmap")}</button>
                        </div>
                      ))}
                      {!tx.fullyAllocated && (
                        <div style={{ fontSize: 11.5, color: "var(--warn-fg, #92400e)" }}>⚠ {money(tx.remainingAmount)} {t("recon.stillUnallocated")} →</div>
                      )}
                    </div>
                  ) : tx.requiresDocument ? (
                    <div style={{ marginTop: 4, fontSize: 11.5, color: sel ? "var(--warn-fg, #92400e)" : "var(--danger-fg, #991b1b)" }}>
                      ● {sel ? t("recon.matchingNow") : t("recon.needsDoc")}
                    </div>
                  ) : (
                    <div style={{ marginTop: 4, fontSize: 11.5, color: "var(--text-muted)", display: "flex", alignItems: "center", gap: 6 }}>
                      <span>{t("recon.notNeeded")} · {tx.reason}</span>
                      <button onClick={(e) => { e.stopPropagation(); setReq.mutate({ id: tx.id, requiresDocument: true }); }} style={linkBtn}>{t("recon.markNeedsDoc")}</button>
                    </div>
                  )}
                  {tx.requiresDocument && !tx.matched && (
                    <button onClick={(e) => { e.stopPropagation(); setReq.mutate({ id: tx.id, requiresDocument: false }); }} style={{ ...linkBtn, marginTop: 2 }}>{t("recon.markNoDoc")}</button>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* ----- RIGHT: invoices & receipts ----- */}
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", ...card }}>
          <ColHeader title={`${t("recon.invoicesReceipts")} · ${poolTotal}`} filter={invFilter} setFilter={setInvFilter} t={t} />
          <div style={{ padding: "8px 12px", borderBottom: "1px solid var(--hair)" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 6, border: "1px solid var(--border)", borderRadius: 8, padding: "3px 8px" }}>
              <Icon name="search" size={13} style={{ color: "var(--text-muted)" }} />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("recon.search")}
                style={{ border: "none", outline: "none", width: "100%", fontSize: 12.5, background: "transparent" }} />
            </div>
          </div>

          <div style={{ flex: 1, overflow: "auto", minHeight: 0, padding: "0 0 8px" }}>
            {/* context card */}
            {!selectedTxn ? (
              <div style={{ margin: 12, border: "1px dashed var(--border)", borderRadius: 10, padding: "22px 14px", textAlign: "center", color: "var(--text-muted)", fontSize: 12.5 }}>{t("recon.selectTxnHint")}</div>
            ) : selectedTxn.remainingAmount <= TOL && selectedTxn.matched ? (
              <ContextBox tone="ok">{t("recon.fullyReconciled")}</ContextBox>
            ) : !selectedTxn.requiresDocument ? (
              <ContextBox tone="muted">{t("recon.noDocNeeded")}</ContextBox>
            ) : txnSuggestions.length > 0 ? (
              <div style={{ margin: 12, border: "1px solid var(--info-bd, #c7d2fe)", background: "var(--info-bg, #e0e7ff)", borderRadius: 10, padding: 10 }}>
                <div style={{ fontSize: 11.5, fontWeight: 700, color: "var(--info-fg, #3730a3)", marginBottom: 7 }}>
                  {t("recon.suggestions")}{selectedTxn.matched ? ` · ${t("recon.remaining").toLowerCase()} ${money(selectedTxn.remainingAmount)}` : ""}
                </div>
                {txnSuggestions.map((sg) => (
                  <div key={sg.key} style={{ display: "flex", alignItems: "flex-start", gap: 8, padding: "8px 0", borderTop: "1px solid var(--info-bd, #c7d2fe)" }}>
                    <span style={{ flex: "none", marginTop: 2, fontSize: 9.5, fontWeight: 700, borderRadius: 999, padding: "2px 7px", color: "#fff", background: sg.kind === "EXACT" ? "#16a34a" : "#2563eb" }}>{t(`recon.kind.${sg.kind}`)}</span>
                    <div style={{ flex: 1, minWidth: 0, display: "grid", gap: 6 }}>
                      {sg.invoices.map((si) => {
                        const full = invById.get(si.invoiceId);
                        const docId = full?.documentId ?? si.documentId;
                        // Show the invoice's own remaining as the amount; for a single-invoice suggestion
                        // whose amount differs from the transaction, the transaction remaining shows in grey.
                        const single = sg.invoices.length === 1;
                        const invAmount = full ? (full.remaining ?? full.totalAmount ?? si.amount) : si.amount;
                        return full ? (
                          <div key={si.invoiceId} style={{ display: "flex", alignItems: "flex-start", gap: 8 }}>
                            <InvoiceInfo inv={full} amount={invAmount}
                              secondaryAmount={single ? (selectedTxn?.remainingAmount ?? null) : null}
                              amountTone="info" companyName={c?.legalName ?? null} companyCui={c?.cui ?? null} t={t} />
                            {docId && <button onClick={() => setPreview({ documentId: docId, filename: si.filename })} style={eyeBtn} title={t("recon.viewDoc")}><Icon name="eye" size={14} /></button>}
                          </div>
                        ) : (
                          <div key={si.invoiceId} style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                            <span style={{ flex: 1, minWidth: 0, fontSize: 12, fontWeight: 600, ...clip }}>{si.filename ?? si.supplierName ?? "factura"}</span>
                            <span className="mono" style={{ flex: "none", fontSize: 12, fontWeight: 700 }}>{money(si.amount)}</span>
                          </div>
                        );
                      })}
                      {sg.invoices.length > 1 && <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)", textAlign: "right" }}>= {money(sg.total)}</div>}
                    </div>
                    <button className="primary" disabled={match.isPending || applySuggestion.isPending} onClick={sg.apply} style={{ flex: "none", marginTop: 2 }}>{t("recon.accept")}</button>
                  </div>
                ))}
              </div>
            ) : (
              <ContextBox tone="muted">{t("recon.noConfident")}</ContextBox>
            )}

            {/* pool */}
            {groups.map((g) => (
              <div key={g.key}>
                <div style={{ display: "flex", justifyContent: "space-between", padding: "6px 12px", fontSize: 10.5, fontWeight: 700, textTransform: "uppercase", color: "var(--text-muted)", background: "var(--hair)" }}>
                  <span>{g.label}</span><span>{g.items.length}</span>
                </div>
                {g.items.map((inv) => {
                  const mapped = !((inv.remaining ?? 0) > TOL);
                  const isChecked = checked.has(inv.id);
                  const suggested = suggestedIds.has(inv.id);
                  return (
                    <div key={inv.id} onClick={() => { if (!mapped && selectedTxn) toggleCheck(inv.id); }}
                      style={{ display: "flex", alignItems: "flex-start", gap: 8, padding: "8px 12px", borderBottom: "1px solid var(--hair)", cursor: mapped || !selectedTxn ? "default" : "pointer", opacity: mapped ? 0.6 : 1,
                        border: isChecked ? "1px solid var(--primary)" : suggested ? "1px solid var(--info-bd, #c7d2fe)" : undefined,
                        background: isChecked ? "var(--row-active, #ecf7f5)" : suggested ? "var(--info-bg, #e0e7ff)" : undefined }}>
                      {mapped ? <span className="pill round ok" style={{ flex: "none", marginTop: 2 }}>{t("recon.mapped")}</span>
                        : <input type="checkbox" checked={isChecked} disabled={!selectedTxn} onChange={() => toggleCheck(inv.id)} onClick={(e) => e.stopPropagation()} style={{ flex: "none", marginTop: 3 }} />}
                      <InvoiceInfo inv={inv} amount={(mapped ? inv.totalAmount : inv.remaining) ?? inv.totalAmount ?? 0}
                        amountTone={mapped ? "muted" : suggested ? "info" : "default"} companyName={c?.legalName ?? null} companyCui={c?.cui ?? null} t={t} />
                      <button onClick={(e) => { e.stopPropagation(); setPreview({ documentId: inv.documentId, filename: inv.filename }); }}
                        style={{ ...eyeBtn, marginTop: 1 }} title={t("recon.viewDoc")} aria-label={t("recon.viewDoc")}><Icon name="eye" size={15} /></button>
                    </div>
                  );
                })}
              </div>
            ))}
            {poolTotal === 0 && <Empty text={t("recon.noInvoicesYet")} />}
          </div>

          {/* map bar */}
          {checked.size > 0 && selectedTxn && (
            <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 12px", borderTop: "1px solid var(--border)", background: "var(--row-active, #ecf7f5)" }}>
              <span style={{ flex: 1, fontSize: 12.5, minWidth: 0, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                {t("recon.mapSelected", { n: checked.size, target: `${selectedTxn.txnDate} · ${selectedTxn.partnerName ?? "—"}` })}
              </span>
              <button onClick={() => setChecked(new Set())}>{t("common.cancel")}</button>
              <button className="primary" disabled={mapChecked.isPending} onClick={() => mapChecked.mutate({ txnId: selectedTxn.id, invoiceIds: [...checked] })}>{t("recon.mapN", { n: checked.size })}</button>
            </div>
          )}

          {/* dropzone */}
          <div onClick={() => fileRef.current?.click()} style={{ margin: 12, border: "1px dashed var(--primary, #14b8a6)", borderRadius: 10, padding: "12px", textAlign: "center", cursor: "pointer", background: "var(--primary-light, #ecf7f5)" }}>
            <Icon name="upload" size={16} style={{ color: "var(--primary-dark, #0f766e)" }} />
            <div style={{ fontSize: 12.5, fontWeight: 600, color: "var(--primary-dark, #0f766e)", marginTop: 3 }}>{upload.isPending ? "…" : t("recon.dropUpload")}</div>
            <div style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("recon.uploadHint")}</div>
            <input ref={fileRef} type="file" multiple accept="application/pdf,image/png,image/jpeg,image/webp" style={{ display: "none" }}
              onChange={(e) => { const f = Array.from(e.target.files ?? []); e.target.value = ""; if (f.length) upload.mutate(f); }} />
          </div>
          {driveEnabled && (
            <button disabled={sync.isPending} onClick={() => sync.mutate()} title={t("files.syncHint")}
              style={{ margin: "0 12px 12px", width: "calc(100% - 24px)", justifyContent: "center" }}>
              <Icon name="reconcile" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />{sync.isPending ? t("files.syncing") : t("files.syncFromDrive")}
            </button>
          )}
        </div>
      </div>

      {preview && <DocumentPreviewModal companyId={companyId} documentId={preview.documentId} filename={preview.filename} onClose={() => setPreview(null)} />}
      {requesting && c && (
        <SendReminderModal companies={[{ id: companyId, name: c.legalName, hasBankStatement: hasStatement, hasInvoiceOrReceipt: true }]} period={period} onClose={() => setRequesting(false)} />
      )}
    </div>
  );
}

const card: React.CSSProperties = { background: "var(--surface)", border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden" };
const iconBtn: React.CSSProperties = { padding: 0, border: "1px solid var(--border)", background: "var(--surface)", borderRadius: 9, display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-secondary, #55605d)", cursor: "pointer" };
const eyeBtn: React.CSSProperties = { ...iconBtn, width: 30, height: 30, flex: "none", color: "var(--primary-dark, #0f766e)", background: "var(--primary-light, #ecf7f5)" };
const linkBtn: React.CSSProperties = { border: "none", background: "none", color: "var(--primary-dark, #0f766e)", cursor: "pointer", font: "inherit", fontSize: 11.5, padding: 0, textDecoration: "underline" };

const clip: React.CSSProperties = { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" };

/**
 * The 3-line invoice descriptor shared by the invoice pool and the suggestion cards, so a suggested
 * invoice reads identically to the same row in the list:
 *   1) supplier · CUI · issue date        · amount (right)
 *   2) buyer (current company) · buyer CUI
 *   3) file name · labels (DUP / wrong party)
 */
function InvoiceInfo({ inv, amount, amountTone = "default", secondaryAmount = null, companyName, companyCui, t }: {
  inv: OpenInvoice; amount: number; amountTone?: "default" | "info" | "muted";
  secondaryAmount?: number | null; companyName: string | null; companyCui: string | null; t: (k: string) => string;
}) {
  const amtColor = amountTone === "muted" ? "var(--text-muted)" : amountTone === "info" ? "var(--info-fg, #3730a3)" : "var(--text)";
  // On a suggestion where the invoice amount differs from the transaction's (a supplier-name match), show
  // the invoice amount in red with the transaction amount in grey underneath, so the gap is obvious.
  const mismatch = secondaryAmount != null && Math.abs(amount - secondaryAmount) > TOL;
  // Line 2 is the *buyer* as stated on the document. When it isn't the current company the backend
  // flags wrongParty — then show only that foreign CUI (no company name) so the mismatch is visible.
  const isCurrent = !inv.wrongParty;
  const buyerCif = inv.clientCif ?? (isCurrent ? companyCui : null);
  const buyerLine = [isCurrent ? companyName : null, buyerCif ? `CUI ${buyerCif}` : null].filter(Boolean).join(" · ") || "—";
  return (
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
        <div style={{ flex: 1, minWidth: 0, fontSize: 12.5, ...clip }}>
          <span style={{ fontWeight: 600 }}>{inv.supplierName ?? "—"}</span>
          {inv.issuerCif && <span style={{ color: "var(--text-muted)" }}> · CUI {inv.issuerCif}</span>}
          {inv.invoiceDate && <span className="mono" style={{ color: "var(--text-muted)" }}> · {inv.invoiceDate}</span>}
        </div>
        <div style={{ flex: "none", textAlign: "right" }}>
          <div className="mono" style={{ fontSize: 12.5, fontWeight: 700, color: mismatch ? "var(--danger-fg, #dc2626)" : amtColor }}>{money(amount)}</div>
          {mismatch && <div className="mono" style={{ fontSize: 10.5, fontWeight: 600, color: "var(--text-muted)" }}>{money(secondaryAmount)}</div>}
        </div>
      </div>
      <div style={{ fontSize: 11, color: inv.wrongParty ? "var(--warn-fg, #b45309)" : "var(--text-muted)", ...clip }}>{buyerLine}</div>
      <div style={{ fontSize: 11, color: "var(--text-muted)", ...clip }}>
        {inv.filename ?? "—"}
        {inv.duplicate && <span className="pill round danger" style={{ marginLeft: 6 }}>DUP</span>}
        {inv.wrongParty && <span className="pill round warn" style={{ marginLeft: 6 }}>{t("doc.wrongPartyChip")}</span>}
      </div>
    </div>
  );
}

function ColHeader({ title, filter, setFilter, t }: { title: string; filter: "all" | "unmapped"; setFilter: (f: "all" | "unmapped") => void; t: (k: string) => string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "9px 12px", background: "var(--th-bg)", borderBottom: "1px solid var(--border)" }}>
      <span style={{ fontSize: 12.5, fontWeight: 700 }}>{title}</span>
      <div style={{ display: "flex", border: "1px solid var(--border)", borderRadius: 999, overflow: "hidden" }}>
        {(["all", "unmapped"] as const).map((f) => (
          <button key={f} onClick={() => setFilter(f)}
            style={{ border: "none", padding: "2px 10px", fontSize: 11, cursor: "pointer", background: filter === f ? "var(--primary, #14b8a6)" : "transparent", color: filter === f ? "#fff" : "var(--text-muted)" }}>
            {t(`recon.filter.${f}`)}
          </button>
        ))}
      </div>
    </div>
  );
}

function ContextBox({ tone, children }: { tone: "ok" | "muted"; children: React.ReactNode }) {
  const s = tone === "ok"
    ? { border: "1px solid var(--ok-bd, #bbf7d0)", background: "var(--ok-bg, #dcfce7)", color: "var(--ok-fg, #166534)" }
    : { border: "1px dashed var(--border)", background: "var(--surface)", color: "var(--text-muted)" };
  return <div style={{ margin: 12, borderRadius: 10, padding: "14px", textAlign: "center", fontSize: 12.5, ...s }}>{children}</div>;
}

function Empty({ text }: { text: string }) {
  return <div style={{ padding: "26px 14px", textAlign: "center", color: "var(--text-muted)", fontSize: 12.5 }}>{text}</div>;
}

function monthLabel(period: string): string {
  const [y, m] = period.slice(0, 7).split("-");
  return `${m}.${y}`;
}
