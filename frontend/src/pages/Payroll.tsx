import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsApi } from "../api/documents";
import { payrollApi, type PayrollRow } from "../api/payroll";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";
import { PayrollEmailModal, type PayrollTarget } from "../components/PayrollEmailModal";
import { PayrollLogModal } from "../components/PayrollLogModal";

const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

/** MOD-08 Payroll — monthly hub list (Console B skin): upload payroll docs per company, send the
 *  standard email with attachments, track email status. Salary data is firm-staff only. */
export function Payroll() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadFor, setUploadFor] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<PayrollTarget[] | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [uploadNote, setUploadNote] = useState<string | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const payroll = useQuery({ queryKey: ["payroll", period], queryFn: () => payrollApi.list(period) });
  const rowBy = new Map<string, PayrollRow>((payroll.data ?? []).map((r) => [r.companyId, r]));

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => (rowBy.get(c.id)?.documents.length ?? 0) > 0).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  useEffect(() => { setSelected(new Set()); }, [period]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): PayrollTarget =>
    ({ companyId: id, companyName: nameOf(id), docCount: rowBy.get(id)?.documents.length ?? 0 });

  const upload = useMutation({
    mutationFn: async ({ companyId, files }: { companyId: string; files: File[] }) => {
      for (const file of files) await documentsApi.upload(companyId, period, file, "PAYROLL");
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["payroll", period] }); },
  });
  const removeDoc = useMutation({
    mutationFn: ({ companyId, id }: { companyId: string; id: string }) => documentsApi.remove(companyId, id),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["payroll", period] }); },
  });
  const pickUpload = (id: string) => { setUploadFor(id); fileRef.current?.click(); };
  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    const cid = uploadFor;
    e.target.value = "";
    if (!files.length || !cid) return;
    // Duplicate guard — filename only (case-insensitive), against what's already uploaded for the
    // company/period and within the current selection. Duplicates are skipped, not uploaded twice.
    const existing = new Set((rowBy.get(cid)?.documents ?? []).map((d) => d.filename.trim().toLowerCase()));
    const seen = new Set<string>();
    const toUpload: File[] = [];
    const skipped: string[] = [];
    for (const f of files) {
      const key = f.name.trim().toLowerCase();
      if (existing.has(key) || seen.has(key)) { skipped.push(f.name); continue; }
      seen.add(key);
      toUpload.push(f);
    }
    setUploadNote(skipped.length ? t("payroll.duplicateSkipped", { names: skipped.join(", ") }) : null);
    if (toUpload.length) upload.mutate({ companyId: cid, files: toUpload });
  };
  const deleteDoc = (companyId: string, id: string, filename: string) => {
    if (window.confirm(t("payroll.confirmDelete", { name: filename }))) {
      removeDoc.mutate({ companyId, id });
    }
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("payroll.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("payroll.title")}</h2>
        </div>
      </div>

      {selected.size > 0 && (
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", borderRadius: 10, padding: "9px 14px" }}>
          <span style={{ fontSize: 13.5, color: "var(--chrome-text)" }}><b style={{ color: "var(--primary)" }}>{selected.size}</b> {t("email.selected", { n: selected.size })}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())} style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}>{t("email.clear")}</button>
            <button className="primary" onClick={() => setSendList([...selected].map(target))}><Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

      <input ref={fileRef} type="file" multiple accept="application/pdf,image/png,image/jpeg,image/webp" onChange={onFile} style={{ display: "none" }} />

      {uploadNote && (
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8, background: "var(--warn-bg, #fef3c7)", border: "1px solid var(--warn-bd, #fcd34d)", color: "var(--warn-fg, #92400e)", borderRadius: 10, padding: "8px 12px", fontSize: 12.5 }}>
          <span>{uploadNote}</span>
          <button onClick={() => setUploadNote(null)} style={{ background: "none", border: "none", cursor: "pointer", color: "inherit" }}><Icon name="x" size={14} /></button>
        </div>
      )}

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 920 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div>{t("documents.company")}</div>
            <div>{t("payroll.documents")}</div>
            <div>{t("statements.lastSent")}</div>
            <div style={{ textAlign: "right" }}>{t("statements.actions")}</div>
          </div>

          {rows.map((c) => {
            const r = rowBy.get(c.id);
            const docs = r?.documents ?? [];
            const selectable = docs.length > 0;
            return (
              <div key={c.id} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: selected.has(c.id) ? "var(--row-active)" : undefined }}>
                <div>{selectable ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div>
                  <div style={{ fontWeight: 600 }}>{c.legalName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                </div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                  {docs.length === 0
                    ? <span className="pill round danger">{t("payroll.missing")}</span>
                    : docs.map((d) => (
                        <span key={d.id} style={docChip}>
                          <button className="pill round muted" title={t("payroll.download")}
                            onClick={() => documentsApi.download(c.id, d.id)}
                            style={{ cursor: "pointer", font: "inherit", border: "none", background: "none", padding: 0, maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                            <Icon name="doc" size={10} style={{ verticalAlign: "-1px", marginRight: 3 }} />{d.filename}
                          </button>
                          <button title={t("payroll.deleteDoc")} disabled={removeDoc.isPending}
                            onClick={() => deleteDoc(c.id, d.id, d.filename)}
                            style={{ border: "none", background: "none", color: "#dc2626", cursor: "pointer", padding: "0 2px", lineHeight: 1, fontSize: 12 }}>✕</button>
                        </span>
                      ))}
                </div>
                <div>
                  {r?.lastSentAt
                    ? <button className="pill teal round" style={pillBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>
                        <Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />{dmy(r.lastSentAt)}{r.sentCount > 1 ? ` · ${r.sentCount}` : ""}
                      </button>
                    : <button style={neverBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>{t("taxes.neverSent")} · <u>{t("taxes.sendShort")}</u></button>}
                </div>
                <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <button style={iconBtn} title={t("payroll.upload")} disabled={upload.isPending} onClick={() => pickUpload(c.id)}><Icon name="upload" size={14} /></button>
                  <button style={{ ...iconBtn, opacity: selectable ? 1 : 0.4 }} title={t("payroll.sendEmail")} disabled={!selectable} onClick={() => setSendList([target(c.id)])}><Icon name="mail" size={14} /></button>
                </div>
              </div>
            );
          })}
          {payroll.data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {sendList && <PayrollEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
      {logFor && <PayrollLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => setLogFor(null)}
        onCompose={() => setSendList([target(logFor.id)])} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px minmax(200px,1.4fr) minmax(220px,2fr) 116px 88px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const iconBtn: React.CSSProperties = { width: 28, height: 28, display: "grid", placeItems: "center", padding: 0, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)", color: "#52605d", cursor: "pointer" };
const docChip: React.CSSProperties = { display: "inline-flex", alignItems: "center", gap: 2, background: "var(--th-bg)", border: "1px solid var(--border)", borderRadius: 999, padding: "1px 5px 1px 8px", fontSize: 11, color: "var(--text-muted)" };
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)", font: "inherit" };
const neverBtn: React.CSSProperties = { background: "none", border: "1px dashed var(--border)", borderRadius: 999, padding: "1px 8px", fontSize: 11, color: "var(--primary-dark)", cursor: "pointer" };
