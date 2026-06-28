import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { portalApi, type PortalDoc } from "../api/portal";
import { getActiveCompanyId, setActiveCompanyId } from "../lib/activeCompany";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../lib/apiClient";
import { ChartCard, PlBars, Donut, Trend, Kpis } from "../components/reportCharts";
import { PortalPreviewModal } from "../components/PortalPreviewModal";

/* ---- B · Console skin palette ---------------------------------------------------------------- */
const C = {
  chrome: "#0c1413", panel: "#16211f", line: "#1c2926", line2: "#24322f",
  teal: "#14b8a6", tealLt: "#2dd4bf", tealInk: "#06201d",
  onChrome: "#f3f8f7", onChromeMut: "#8aa09c", onChromeFaint: "#5e716e",
  bg: "#fafafa", card: "#fff", border: "#e4e7e6", hair: "#f1f3f2",
  ink: "#16201f", sub: "#5e716e", mut: "#9aa6a3",
  warnBg: "#fef3c7", warnBd: "#fde68a", warnFg: "#92400e", amber: "#f59e0b",
  green: "#16a34a", greenBg: "#dcfce7", danger: "#dc2626",
};
const mono: React.CSSProperties = { fontFamily: "'IBM Plex Mono', monospace" };
const card: React.CSSProperties = { background: C.card, border: `1px solid ${C.border}`, borderRadius: 16, padding: 15, minWidth: 0 };
const cardH: React.CSSProperties = { fontSize: 15, fontWeight: 700, margin: 0, color: C.ink };

const money = (n: number) => (n ?? 0).toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const monthLabel = (period: string, lang: string) =>
  new Date(period).toLocaleDateString(lang === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });
const shiftMonth = (period: string, delta: number) => {
  const d = new Date(period); d.setMonth(d.getMonth() + delta);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`;
};
const thisMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`; };
// Reps work on closed months — the latest selectable month is the previous one (no future, no current).
const latestMonth = () => shiftMonth(thisMonth(), -1);
const initialsOf = (name?: string | null) =>
  (name ?? "").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join("").toUpperCase() || "··";

/** Representative PWA — B·Console skin: dark app chrome, light cards, teal accent, multi-company switch. */
export function RepHome() {
  const { t, i18n } = useTranslation();
  const { signOut } = useAuth();
  const qc = useQueryClient();
  const [period, setPeriod] = useState(latestMonth());
  const [preview, setPreview] = useState<{ load: () => Promise<Blob>; filename: string } | null>(null);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [notifOpen, setNotifOpen] = useState(false);
  const atLatest = period >= latestMonth(); // can't go to the current/future month
  const cameraRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const me = useQuery({ queryKey: ["portal-me"], queryFn: portalApi.me });

  // Persist the resolved active company so every subsequent request carries the X-Company-Id header.
  useEffect(() => {
    if (me.data?.companyId && !getActiveCompanyId()) setActiveCompanyId(me.data.companyId);
  }, [me.data?.companyId]);

  const switchCompany = (companyId: string) => {
    setSwitcherOpen(false);
    if (companyId === me.data?.companyId) return;
    setActiveCompanyId(companyId);
    void qc.invalidateQueries(); // refetch everything for the newly-selected company
  };
  const companyOptions = me.data?.companies ?? [];
  const multiCompany = companyOptions.length > 1;

  const notifs = useQuery({ queryKey: ["portal-notifs"], queryFn: portalApi.notifications, refetchInterval: 30000, refetchOnWindowFocus: true });
  const unread = (notifs.data ?? []).filter((n) => !n.readAt).length;
  const markRead = useMutation({
    mutationFn: (id: string) => portalApi.markNotificationRead(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["portal-notifs"] }),
  });
  const missing = useQuery({ queryKey: ["portal-missing", period], queryFn: () => portalApi.missing(period) });
  const docs = useQuery({ queryKey: ["portal-company-docs", period], queryFn: () => portalApi.companyDocuments(period) });
  const report = useQuery({ queryKey: ["portal-report", period], queryFn: () => portalApi.report(period) });
  const balanceSheet = useQuery({ queryKey: ["portal-balance", period], queryFn: () => portalApi.balanceSheet(period) });
  const payroll = useQuery({ queryKey: ["portal-payroll", period], queryFn: () => portalApi.payroll(period) });
  const payments = useQuery({ queryKey: ["portal-payments", period], queryFn: () => portalApi.payments(period) });
  const trend = useQuery({ queryKey: ["portal-trend", period], queryFn: () => portalApi.trend(period) });

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["portal-company-docs", period] });
    void qc.invalidateQueries({ queryKey: ["portal-missing", period] });
  };
  const docTypeLabel = (type: string) =>
    type === "BANK_STATEMENT" ? t("portal.docType.bank")
      : type === "INVOICE" ? t("portal.docType.invoice")
      : type === "RECEIPT" ? t("portal.docType.receipt")
      : t("portal.docType.other");
  const docBadges = (d: PortalDoc): React.ReactNode => {
    const out: React.ReactNode[] = [];
    if (d.paymentStatus === "PAID") out.push(<Badge key="p" label={t("portal.paid")} tone="green" dot />);
    else if (d.paymentStatus === "PARTIAL") out.push(<Badge key="p" label={t("portal.partial")} tone="amber" dot />);
    else if (d.paymentStatus === "UNPAID") out.push(<Badge key="p" label={t("portal.unpaid")} tone="red" dot />);
    if (d.duplicate) out.push(<Badge key="d" label={`⚠ ${t("portal.duplicate")}`} tone="red" />);
    if (d.outsidePeriod) out.push(<Badge key="o" label={`⚠ ${t("portal.outsidePeriod")}`} tone="indigo" />);
    return out.length ? out : null;
  };
  const upload = useMutation({
    mutationFn: (files: File[]) => Promise.all(files.map((f) => portalApi.uploadDocument(f, period))),
    onSuccess: refresh,
  });
  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []); e.target.value = "";
    if (files.length) upload.mutate(files);
  };
  const toggleLang = () => void i18n.changeLanguage(i18n.language === "ro" ? "en" : "ro");

  const r = report.data;
  const all = docs.data ?? [];
  const bank = all.filter((d) => d.type === "BANK_STATEMENT");
  const others = all.filter((d) => d.type !== "BANK_STATEMENT");
  const hasBank = bank.length > 0;
  const missingItems = missing.data ?? [];
  const needsDocs = !hasBank || missingItems.length > 0;

  const onView = (d: { id: string; filename: string }) =>
    () => setPreview({ load: () => portalApi.fileBlob(d.id), filename: d.filename });
  const onDownload = (d: { id: string; filename: string }) => () => portalApi.downloadFile(d.id, d.filename);

  return (
    <div style={{ minHeight: "100dvh", background: C.chrome, display: "flex", justifyContent: "center", fontFamily: "'Hanken Grotesk', sans-serif" }}>
      <div style={{ width: "100%", maxWidth: 480, minWidth: 0, minHeight: "100dvh", background: C.bg, display: "flex", flexDirection: "column", position: "relative", overflowX: "hidden" }}>

        {/* ===== dark app header ===== */}
        <div style={{ flex: "none", position: "sticky", top: 0, zIndex: 5, background: C.chrome, padding: "14px 16px 14px" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <div style={{ width: 30, height: 30, borderRadius: 9, background: C.teal, color: C.tealInk, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 800, fontSize: 16 }}>M</div>
              <div style={{ lineHeight: 1.15 }}>
                <div style={{ fontSize: 15, fontWeight: 700, color: C.onChrome }}>MyFinance</div>
                <div style={{ fontSize: 11, color: C.onChromeMut }}>{t("portal.repPortal")}</div>
              </div>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <button onClick={() => setNotifOpen(true)} title={t("notif.title")} style={{ ...chromeIcon, position: "relative" }}>
                <BellIcon />
                {unread > 0 && <span style={{ position: "absolute", top: -3, right: -3, minWidth: 16, height: 16, borderRadius: 8, background: "#f43f5e", color: "#fff", fontSize: 9.5, fontWeight: 700, display: "flex", alignItems: "center", justifyContent: "center", padding: "0 4px" }}>{unread}</span>}
              </button>
              <button onClick={toggleLang} style={{ ...chromeIcon, ...mono, fontSize: 11, fontWeight: 600 }}>{i18n.language === "ro" ? "RO" : "EN"}</button>
            </div>
          </div>

          {/* company switcher pill */}
          <button onClick={() => multiCompany && setSwitcherOpen(true)} disabled={!multiCompany}
            style={{ marginTop: 11, width: "100%", display: "flex", alignItems: "center", gap: 10, background: C.panel, border: `1px solid ${C.line}`, borderRadius: 12, padding: "9px 11px", cursor: multiCompany ? "pointer" : "default", textAlign: "left" }}>
            <div style={{ width: 30, height: 30, borderRadius: 9, background: C.chrome, border: `1px solid ${C.line2}`, color: C.tealLt, display: "flex", alignItems: "center", justifyContent: "center", flex: "none", fontSize: 11, fontWeight: 700, ...mono }}>{initialsOf(me.data?.name)}</div>
            <div style={{ flex: 1, minWidth: 0, lineHeight: 1.2 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: C.onChrome, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{me.data?.name ?? "…"}</div>
              {me.data?.cui && <div style={{ ...mono, fontSize: 10.5, color: C.onChromeFaint }}>CUI {me.data.cui}</div>}
            </div>
            {multiCompany && <>
              <span style={{ flex: "none", fontSize: 10, fontWeight: 600, color: C.tealLt, background: C.chrome, border: `1px solid ${C.line2}`, borderRadius: 999, padding: "2px 8px" }}>{companyOptions.length} {t("portal.companiesLabel")}</span>
              <ChevronIcon />
            </>}
          </button>
        </div>

        {/* ===== content ===== */}
        <div style={{ flex: 1, minWidth: 0, padding: "14px 16px 24px", display: "grid", gridTemplateColumns: "minmax(0, 1fr)", gap: 13, alignContent: "start" }}>

          {/* month stepper */}
          <div style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0, background: C.card, border: `1px solid ${C.border}`, borderRadius: 12, padding: "9px 12px" }}>
            <button onClick={() => setPeriod((p) => shiftMonth(p, -1))} aria-label="prev" style={stepBtn(false)}>‹</button>
            <span style={{ flex: 1, textAlign: "center", fontWeight: 700, fontSize: 14.5, textTransform: "capitalize", color: C.ink }}>{monthLabel(period, i18n.language)}</span>
            <button onClick={() => !atLatest && setPeriod((p) => shiftMonth(p, 1))} aria-label="next" disabled={atLatest} style={stepBtn(atLatest)}>›</button>
          </div>

          {/* documents-needed banner */}
          {needsDocs && (
            <div style={{ display: "flex", gap: 10, alignItems: "flex-start", background: C.warnBg, border: `1px solid ${C.warnBd}`, borderRadius: 12, padding: "12px 13px" }}>
              <div style={{ width: 22, height: 22, borderRadius: "50%", background: C.amber, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", flex: "none", fontSize: 13, fontWeight: 700 }}>!</div>
              <div style={{ fontSize: 13, color: C.warnFg, fontWeight: 600 }}>{t("portal.needDocsWarning")}</div>
            </div>
          )}

          {/* UPLOAD card (dark) */}
          <div style={{ background: C.chrome, borderRadius: 16, padding: 16, minWidth: 0 }}>
            <div style={{ fontSize: 15, fontWeight: 700, color: C.onChrome }}>{t("portal.uploadTitle")}</div>
            <div style={{ fontSize: 12, color: C.onChromeMut, marginTop: 1 }}>{t("portal.uploadHint")}</div>
            <input ref={cameraRef} type="file" accept="image/*" capture="environment" onChange={onPick} style={{ display: "none" }} />
            <input ref={fileRef} type="file" accept="application/pdf,image/*" multiple onChange={onPick} style={{ display: "none" }} />
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 9, marginTop: 12 }}>
              <button disabled={upload.isPending} onClick={() => cameraRef.current?.click()}
                style={{ background: C.teal, borderRadius: 12, padding: "14px 10px", display: "flex", flexDirection: "column", alignItems: "center", gap: 8, cursor: "pointer", border: "none" }}>
                <CameraIcon stroke={C.tealInk} /><span style={{ fontSize: 13, fontWeight: 700, color: C.tealInk }}>{t("portal.takePhoto")}</span>
              </button>
              <button disabled={upload.isPending} onClick={() => fileRef.current?.click()}
                style={{ background: C.panel, border: `1px solid ${C.line}`, borderRadius: 12, padding: "14px 10px", display: "flex", flexDirection: "column", alignItems: "center", gap: 8, cursor: "pointer" }}>
                <FileIcon stroke={C.tealLt} /><span style={{ fontSize: 13, fontWeight: 700, color: "#cfdbd9" }}>{t("portal.chooseFile")}</span>
              </button>
            </div>
            {upload.isPending && <div style={{ color: C.tealLt, fontSize: 12.5, marginTop: 10 }}>{t("portal.uploading")}</div>}
            {upload.isError && <div style={{ color: "#fca5a5", fontSize: 12.5, marginTop: 10 }}>{upload.error instanceof ApiError ? upload.error.message : t("portal.failed")}</div>}
          </div>

          {/* DOCUMENTS card */}
          <div style={card}>
            <h2 style={{ ...cardH, marginBottom: 10 }}>{t("portal.documents")}</h2>
            {/* bank status */}
            <div style={{ display: "flex", alignItems: "center", gap: 8, paddingBottom: 9 }}>
              <span style={{ width: 22, height: 22, borderRadius: "50%", background: hasBank ? C.greenBg : "#fee2e2", color: hasBank ? C.green : C.danger, display: "flex", alignItems: "center", justifyContent: "center", flex: "none", fontSize: 13, fontWeight: 800 }}>{hasBank ? "✓" : "!"}</span>
              <span style={{ fontSize: 13.5, fontWeight: 600, color: hasBank ? C.green : C.danger }}>{hasBank ? t("portal.bankUploaded") : t("portal.bankMissing")}</span>
            </div>
            {bank.map((d) => (
              <DocRow key={d.id} filename={d.filename} label={t("portal.docType.bank")} iconBg="#e0e7ff" iconFg="#3730a3"
                onView={onView(d)} onDownload={onDownload(d)} />
            ))}
            {others.length === 0 && !hasBank && <div style={{ color: C.mut, fontSize: 13 }}>—</div>}
            {others.map((d) => (
              <DocRow key={d.id} filename={d.filename} label={docTypeLabel(d.type)} issuer={d.issuer} badges={docBadges(d)}
                iconBg="#e6f4f2" iconFg="#0f766e" onView={onView(d)} onDownload={onDownload(d)} />
            ))}

            {/* still needed */}
            {missingItems.length > 0 && (
              <div style={{ marginTop: 11, paddingTop: 11, borderTop: `1px solid ${C.hair}` }}>
                <div style={{ fontSize: 10.5, letterSpacing: "0.06em", textTransform: "uppercase", color: "#b91c1c", fontWeight: 700, marginBottom: 7, display: "flex", alignItems: "center", gap: 5 }}>
                  <span style={{ width: 5, height: 5, borderRadius: "50%", background: C.danger }} />{t("portal.stillNeeded")} · {missingItems.length}
                </div>
                {missingItems.map((m, i) => (
                  <div key={i} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8, padding: "8px 0", borderTop: i ? `1px solid ${C.hair}` : "none" }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 12.5, fontWeight: 600, color: C.ink, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{m.partnerName ?? m.description ?? "—"}</div>
                      <div style={{ ...mono, fontSize: 11, color: C.mut }}>{m.txnDate}</div>
                    </div>
                    <span style={{ ...mono, fontSize: 12.5, fontWeight: 700, whiteSpace: "nowrap", color: C.ink }}>{money(m.amount)} RON</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* TO PAY card */}
          <div style={card}>
            <h2 style={cardH}>{t("portal.toPayTitle")}</h2>
            {payments.isLoading && <div style={{ color: C.mut, fontSize: 13, marginTop: 6 }}>…</div>}
            {payments.data && (payments.data.lines.length + payments.data.unconfigured.length) === 0 && (
              <div style={{ color: C.mut, fontSize: 13, marginTop: 6 }}>{t("portal.nothingToPay")}</div>
            )}
            {payments.data && payments.data.lines.length > 0 && (
              <>
                <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", margin: "8px 0 10px" }}>
                  <span style={{ fontSize: 12.5, color: C.sub }}>{t("portal.totalToPay")}</span>
                  <span style={{ ...mono, fontSize: 22, fontWeight: 700, color: C.ink }}>{money(payments.data.total)} <span style={{ fontSize: 13, color: C.mut }}>RON</span></span>
                </div>
                {payments.data.lines.map((l, i) => (
                  <div key={i} style={{ padding: "9px 0", borderTop: `1px solid ${C.hair}` }}>
                    <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 600, color: C.ink }}>{l.explanation ?? l.categories.join(" + ")}</span>
                      <span style={{ ...mono, fontSize: 12.5, fontWeight: 700, whiteSpace: "nowrap", color: C.ink }}>{money(l.amount)}</span>
                    </div>
                    <div style={{ ...mono, fontSize: 10.5, color: C.mut, marginTop: 2, wordBreak: "break-all" }}>{l.iban}</div>
                    {l.scadenta && <div style={{ fontSize: 10.5, color: C.mut }}>{t("portal.due")}: {l.scadenta}</div>}
                  </div>
                ))}
              </>
            )}
            {payments.data && payments.data.unconfigured.length > 0 && (
              <div style={{ marginTop: 8, fontSize: 12, color: C.warnFg }}>
                {t("portal.accountMissing")}: {payments.data.unconfigured.map((u) => `${u.category} (${money(u.amount)})`).join(", ")}
              </div>
            )}
          </div>

          {/* FINANCIALS card */}
          <div style={card}>
            <h2 style={{ ...cardH, marginBottom: 10 }}>{t("portal.financials")}</h2>
            {report.isLoading && <div style={{ color: C.mut, fontSize: 13 }}>…</div>}
            {r && (
              <div style={{ display: "flex", gap: 12, marginBottom: 12 }}>
                <div style={{ flex: 1, background: "#f7f8f8", borderRadius: 11, padding: "11px 12px" }}>
                  <div style={{ ...mono, fontSize: 18, fontWeight: 700, color: C.ink }}>{money(r.profitLoss.revenue)}</div>
                  <div style={{ fontSize: 11, color: C.mut, marginTop: 1 }}>{t("portal.revenue")}</div>
                </div>
                <div style={{ flex: 1, background: "#f7f8f8", borderRadius: 11, padding: "11px 12px" }}>
                  <div style={{ ...mono, fontSize: 18, fontWeight: 700, color: "#0f766e" }}>{money(r.profitLoss.netProfit)}</div>
                  <div style={{ fontSize: 11, color: C.mut, marginTop: 1 }}>{t("portal.netProfit")}</div>
                </div>
              </div>
            )}
            <div style={{ display: "flex", gap: 8 }}>
              <button disabled={!r} onClick={() => setPreview({ load: () => portalApi.reportBlob(period), filename: `raport-${period.slice(0, 7)}.pdf` })}
                style={{ flex: 1, border: `1px solid ${C.border}`, borderRadius: 10, padding: 10, background: C.card, fontSize: 13, fontWeight: 600, color: C.ink, cursor: "pointer", opacity: r ? 1 : 0.45, display: "flex", alignItems: "center", justifyContent: "center", gap: 6 }}>
                <EyeIcon /> {t("portal.view")}
              </button>
              <button disabled={!r} onClick={() => portalApi.downloadReport(period)}
                style={{ flex: 1, background: C.teal, border: "none", borderRadius: 10, padding: 10, fontSize: 13, fontWeight: 700, color: C.tealInk, cursor: "pointer", opacity: r ? 1 : 0.45, display: "flex", alignItems: "center", justifyContent: "center", gap: 6 }}>
                <DownloadIcon stroke={C.tealInk} /> {t("portal.downloadReport")}
              </button>
            </div>
            {(balanceSheet.data ?? []).map((d) => (
              <DocRow key={d.id} filename={d.filename} label={t("portal.docType.balance")} iconBg="#e6f4f2" iconFg="#0f766e"
                onView={onView(d)} onDownload={onDownload(d)} />
            ))}
            {!report.isLoading && !r && (balanceSheet.data ?? []).length === 0 && (
              <div style={{ color: C.mut, fontSize: 13, marginTop: 8 }}>{t("portal.reportNotReady")}</div>
            )}
          </div>

          {/* CHARTS */}
          {r && (
            <div style={card}>
              <h2 style={{ ...cardH, marginBottom: 10 }}>{t("reports.charts")}</h2>
              <div style={{ display: "grid", gap: 14 }}>
                <ChartCard title={t("reports.chart.pl")}><PlBars r={r} /></ChartCard>
                {r.profitLoss.expenseItems.length > 0 && <ChartCard title={t("reports.chart.expenses")}><Donut items={r.profitLoss.expenseItems} /></ChartCard>}
                <ChartCard title={t("reports.chart.trend")}>
                  <Trend points={trend.data ?? []} loading={trend.isLoading} emptyLabel={t("reports.trendEmpty")} />
                </ChartCard>
                <ChartCard title={t("reports.chart.kpi")}><Kpis r={r} t={t} /></ChartCard>
              </div>
            </div>
          )}

          {/* PAYROLL */}
          {(payroll.data ?? []).length > 0 && (
            <div style={card}>
              <h2 style={{ ...cardH, marginBottom: 8 }}>{t("portal.payroll")}</h2>
              {(payroll.data ?? []).map((p) => (
                <DocRow key={p.id} filename={p.filename} label={t("portal.payroll")} iconBg="#e6f4f2" iconFg="#0f766e"
                  onView={onView(p)} onDownload={onDownload(p)} />
              ))}
            </div>
          )}
        </div>

        {/* ===== bottom tab bar ===== */}
        <div style={{ flex: "none", position: "sticky", bottom: 0, zIndex: 5, background: C.chrome, borderTop: `1px solid #1a2624`, padding: "9px 0 14px", display: "flex" }}>
          <Tab active label={t("portal.navHome")} onClick={() => {}}><HomeIcon /></Tab>
          <Tab label={t("portal.navUpload")} onClick={() => fileRef.current?.click()}><CameraIcon stroke="currentColor" /></Tab>
          <Tab label={t("portal.navReports")} onClick={() => r && setPreview({ load: () => portalApi.reportBlob(period), filename: `raport-${period.slice(0, 7)}.pdf` })}><BarsIcon /></Tab>
          <Tab label={t("portal.navProfile")} onClick={() => void signOut()}><UserIcon /></Tab>
        </div>

        {/* ===== company switcher sheet ===== */}
        {switcherOpen && (
          <Sheet onClose={() => setSwitcherOpen(false)}>
            <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", marginBottom: 10 }}>
              <div style={{ fontSize: 16, fontWeight: 700, color: C.ink }}>{t("portal.switchCompany")}</div>
              <span style={{ fontSize: 12, color: C.mut }}>{companyOptions.length} {t("portal.companiesLabel")}</span>
            </div>
            <div style={{ overflowY: "auto", display: "flex", flexDirection: "column", gap: 8 }}>
              {companyOptions.map((c) => {
                const current = c.companyId === me.data?.companyId;
                return (
                  <button key={c.companyId} onClick={() => switchCompany(c.companyId)}
                    style={{ display: "flex", alignItems: "center", gap: 11, padding: 12, borderRadius: 13, cursor: "pointer", textAlign: "left", background: current ? "#ecf7f5" : C.card, border: `1px solid ${current ? "#b6e7df" : C.border}` }}>
                    <div style={{ width: 36, height: 36, borderRadius: 10, background: C.chrome, color: C.tealLt, display: "flex", alignItems: "center", justifyContent: "center", flex: "none", fontSize: 12, fontWeight: 700, ...mono }}>{initialsOf(c.name)}</div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13.5, fontWeight: 600, color: C.ink, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{c.name ?? c.companyId}</div>
                      {c.cui && <div style={{ ...mono, fontSize: 11, color: C.mut }}>CUI {c.cui}</div>}
                    </div>
                    {current && <span style={{ flex: "none", width: 22, height: 22, borderRadius: "50%", background: C.teal, color: C.tealInk, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 800 }}>✓</span>}
                  </button>
                );
              })}
            </div>
          </Sheet>
        )}

        {/* ===== notifications sheet ===== */}
        {notifOpen && (
          <Sheet onClose={() => setNotifOpen(false)}>
            <div style={{ fontSize: 16, fontWeight: 700, color: C.ink, marginBottom: 6 }}>{t("notif.title")}</div>
            <div style={{ overflowY: "auto" }}>
              {(notifs.data ?? []).length === 0 && <div style={{ color: C.mut, fontSize: 13, padding: "10px 0" }}>—</div>}
              {(notifs.data ?? []).map((n, i) => (
                <button key={n.id} onClick={() => { if (!n.readAt) markRead.mutate(n.id); }}
                  style={{ display: "flex", gap: 10, alignItems: "flex-start", width: "100%", textAlign: "left", padding: "11px 0", borderTop: i ? `1px solid ${C.hair}` : "none", background: "none", border: "none", cursor: n.readAt ? "default" : "pointer", opacity: n.readAt ? 0.6 : 1 }}>
                  <span style={{ width: 7, height: 7, borderRadius: "50%", background: n.readAt ? "#cbd5d2" : C.teal, flex: "none", marginTop: 5 }} />
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 13.5, fontWeight: 700, color: C.ink }}>{n.title}</div>
                    <div style={{ fontSize: 12.5, color: C.sub, marginTop: 1 }}>{n.body}</div>
                  </div>
                </button>
              ))}
            </div>
          </Sheet>
        )}

        {preview && <PortalPreviewModal load={preview.load} filename={preview.filename} onClose={() => setPreview(null)} />}
      </div>
    </div>
  );
}

/* ---- pieces ---------------------------------------------------------------------------------- */
const chromeIcon: React.CSSProperties = { width: 34, height: 34, padding: 0, borderRadius: 10, background: C.panel, border: "none", display: "flex", alignItems: "center", justifyContent: "center", color: C.onChromeMut, cursor: "pointer" };
const stepBtn = (disabled: boolean): React.CSSProperties => ({ width: 30, height: 30, padding: 0, borderRadius: 8, background: disabled ? "transparent" : "#f5f6f6", color: disabled ? "#cbd5d2" : "#52605d", border: "none", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 17, cursor: disabled ? "default" : "pointer" });

function DocRow({ filename, label, issuer, badges, iconBg, iconFg, onView, onDownload }:
  { filename: string; label: string; issuer?: string | null; badges?: React.ReactNode; iconBg: string; iconFg: string;
    onView: () => void; onDownload: () => void }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "9px 0", borderTop: `1px solid ${C.hair}` }}>
      <div style={{ width: 32, height: 32, borderRadius: 9, background: iconBg, color: iconFg, display: "flex", alignItems: "center", justifyContent: "center", flex: "none" }}><FileGlyph /></div>
      <button onClick={onView} style={{ flex: 1, minWidth: 0, textAlign: "left", background: "none", border: "none", cursor: "pointer", font: "inherit", padding: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: C.ink, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{filename}</div>
        <div style={{ fontSize: 11, color: C.mut, marginTop: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{label}{issuer ? ` · ${issuer}` : ""}</div>
        {badges && <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginTop: 4 }}>{badges}</div>}
      </button>
      <div style={{ display: "flex", gap: 6, flex: "none" }}>
        <button onClick={onView} title="view" style={iconBtn}><EyeIcon /></button>
        <button onClick={onDownload} title="download" style={iconBtn}><DownloadIcon stroke="#52605d" /></button>
      </div>
    </div>
  );
}
const iconBtn: React.CSSProperties = { width: 30, height: 30, padding: 0, border: `1px solid ${C.border}`, background: C.card, borderRadius: 8, display: "flex", alignItems: "center", justifyContent: "center", color: "#52605d", cursor: "pointer", flex: "none" };

function Badge({ label, tone, dot }: { label: string; tone: "green" | "amber" | "red" | "indigo"; dot?: boolean }) {
  const c = tone === "green" ? { bg: "#dcfce7", fg: "#166534", bd: "#bbf7d0" }
    : tone === "amber" ? { bg: "#fef3c7", fg: "#92400e", bd: "#fde68a" }
    : tone === "indigo" ? { bg: "#e0e7ff", fg: "#3730a3", bd: "#c7d2fe" }
    : { bg: "#fee2e2", fg: "#991b1b", bd: "#fecaca" };
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 4, background: c.bg, color: c.fg, border: `1px solid ${c.bd}`, borderRadius: 999, padding: "1px 7px", fontSize: 9.5, fontWeight: 700 }}>
      {dot && <span style={{ width: 5, height: 5, borderRadius: "50%", background: c.fg }} />}{label}
    </span>
  );
}

function Tab({ active, label, onClick, children }: { active?: boolean; label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick} style={{ flex: 1, padding: 0, display: "flex", flexDirection: "column", alignItems: "center", gap: 3, background: "none", border: "none", cursor: "pointer", color: active ? C.tealLt : C.onChromeFaint }}>
      {children}<span style={{ fontSize: 10, fontWeight: active ? 600 : 400 }}>{label}</span>
    </button>
  );
}

function Sheet({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <div onClick={onClose} style={{ position: "fixed", inset: 0, background: "rgba(5,16,14,0.55)", display: "flex", alignItems: "flex-end", justifyContent: "center", zIndex: 60 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: "100%", maxWidth: 480, background: C.card, borderRadius: "24px 24px 0 0", padding: "8px 18px 30px", maxHeight: "82dvh", display: "flex", flexDirection: "column", boxShadow: "0 -10px 40px rgba(0,0,0,0.25)" }}>
        <div style={{ width: 38, height: 4, borderRadius: 2, background: C.border, margin: "6px auto 14px" }} />
        {children}
      </div>
    </div>
  );
}

/* ---- icons (stroke = currentColor unless given) ---------------------------------------------- */
const sIcon = (size = 17) => ({ width: size, height: size, viewBox: "0 0 16 16", fill: "none", stroke: "currentColor" as const, strokeWidth: 1.5 });
const BellIcon = () => (<svg {...sIcon()}><path d="M8 2.5a3.5 3.5 0 00-3.5 3.5c0 3-1.5 4-1.5 4h10s-1.5-1-1.5-4A3.5 3.5 0 008 2.5z" /><path d="M6.5 13a1.5 1.5 0 003 0" /></svg>);
const ChevronIcon = () => (<svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke={C.onChromeMut} strokeWidth="1.6" style={{ flex: "none" }}><path d="M4 6l4 4 4-4" /></svg>);
const EyeIcon = () => (<svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4"><path d="M1.5 8S3.5 3.5 8 3.5 14.5 8 14.5 8 12.5 12.5 8 12.5 1.5 8 1.5 8z" /><circle cx="8" cy="8" r="1.8" /></svg>);
const DownloadIcon = ({ stroke = "currentColor" }: { stroke?: string }) => (<svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke={stroke} strokeWidth="1.5"><path d="M8 2.5v8M5 7.5L8 10.5l3-3" /><path d="M3 13h10" /></svg>);
const CameraIcon = ({ stroke = "currentColor" }: { stroke?: string }) => (<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.7"><rect x="2.5" y="6" width="19" height="14" rx="3" /><circle cx="12" cy="13" r="3.6" /><path d="M8.5 6l1.4-2.4h4.2L15.5 6" /></svg>);
const FileIcon = ({ stroke = "currentColor" }: { stroke?: string }) => (<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.7"><path d="M14 3.5H7a2 2 0 00-2 2v13a2 2 0 002 2h10a2 2 0 002-2V8.5z" /><path d="M14 3.5V9h5" /></svg>);
const FileGlyph = () => (<svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M4 2.5h6l4 4v9H4z" strokeLinejoin="round" /><path d="M10 2.5v4h4" /></svg>);
const HomeIcon = () => (<svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M3 9l7-5.5L17 9v7.5a1 1 0 01-1 1h-3v-5H7v5H4a1 1 0 01-1-1z" /></svg>);
const BarsIcon = () => (<svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M4 16V9M10 16V4M16 16v-5" /></svg>);
const UserIcon = () => (<svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="10" cy="7" r="3.2" /><path d="M4 17c0-3.2 2.7-5 6-5s6 1.8 6 5" /></svg>);
