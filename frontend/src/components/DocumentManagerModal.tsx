import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { documentsApi, type Document as Doc } from "../api/documents";
import { ingestionApi, type SyncResult } from "../api/ingestion";
import { ApiError } from "../lib/apiClient";
import { Icon } from "./Icon";

/**
 * Upload-manager modal (master-detail) used by Payroll and Reports — mirrors the declarations workspace:
 * list the uploaded files of one type, upload more (filename-duplicate guard + server checks), delete,
 * and preview the selected file inline. Surfaces advisory flags (wrong company / outside period).
 */
export function DocumentManagerModal({ companyId, companyName, period, type, title, subtitle, accept, multiple = true, onClose, onChanged }:
  { companyId: string; companyName: string; period: string; type: string; title: string; subtitle: string;
    accept?: string; multiple?: boolean; onClose: () => void; onChanged?: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [previewType, setPreviewType] = useState("");

  const docsQ = useQuery({
    queryKey: ["docs-of-type", companyId, period, type],
    queryFn: async () => (await documentsApi.list(companyId, period)).filter((d) => d.type === type),
  });
  const flagsQ = useQuery({
    queryKey: ["doc-flags", companyId, period, type],
    queryFn: () => documentsApi.flags(companyId, period, type),
  });
  const docs = docsQ.data ?? [];
  const flagBy = new Map((flagsQ.data ?? []).map((f) => [f.documentId, f]));

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["docs-of-type", companyId, period, type] });
    void qc.invalidateQueries({ queryKey: ["doc-flags", companyId, period, type] });
    onChanged?.();
  };

  // Auto-select the first file; load its preview.
  useEffect(() => {
    if (!selectedId && docs.length) setSelectedId(docs[0].id);
    if (selectedId && !docs.some((d) => d.id === selectedId)) setSelectedId(docs[0]?.id ?? null);
  }, [docs, selectedId]);

  useEffect(() => {
    if (!selectedId) { setBlobUrl(null); return; }
    let revoked: string | null = null;
    setBlobUrl(null);
    void documentsApi.download(companyId, selectedId).then((blob) => {
      const url = URL.createObjectURL(blob); revoked = url; setPreviewType(blob.type); setBlobUrl(url);
    }).catch(() => setBlobUrl(null));
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
  }, [companyId, selectedId]);

  const upload = useMutation({
    mutationFn: async (files: File[]) => {
      const existing = new Set(docs.map((d) => d.originalFilename.trim().toLowerCase()));
      const seen = new Set<string>();
      const skipped: string[] = [];
      const rejected: string[] = [];
      for (const f of files) {
        const key = f.name.trim().toLowerCase();
        if (existing.has(key) || seen.has(key)) { skipped.push(f.name); continue; }
        seen.add(key);
        try { await documentsApi.upload(companyId, period, f, type); }
        catch (e) { rejected.push(`${f.name} — ${e instanceof ApiError ? e.message : t("payroll.uploadError")}`); }
      }
      return { skipped, rejected };
    },
    onSuccess: ({ skipped, rejected }) => {
      refresh();
      const parts: string[] = [];
      if (skipped.length) parts.push(t("payroll.duplicateSkipped", { names: skipped.join(", ") }));
      if (rejected.length) parts.push(t("payroll.uploadRejected", { items: rejected.join(" · ") }));
      setNote(parts.length ? parts.join(" · ") : null);
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => documentsApi.remove(companyId, id),
    onSuccess: () => { setSelectedId(null); refresh(); },
  });

  // When this document type is sourced from a cloud folder, manual upload/delete is replaced by a
  // per-company, per-month "Sync" button.
  const driveQ = useQuery({ queryKey: ["ingestion-source", type], queryFn: () => ingestionApi.source(type) });
  const driveMode = driveQ.data?.driveEnabled === true;
  const sync = useMutation({
    mutationFn: () => ingestionApi.syncCompany({ companyId, period, type }),
    onSuccess: (r: SyncResult) => { refresh(); setNote(t("payroll.syncDone", r as unknown as Record<string, number>)); },
    onError: (e) => setNote(e instanceof ApiError ? e.message : t("payroll.uploadError")),
  });

  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []); e.target.value = "";
    if (files.length) upload.mutate(files);
  };
  const del = (d: Doc) => { if (window.confirm(t("payroll.confirmDelete", { name: d.originalFilename }))) remove.mutate(d.id); };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{subtitle}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
            <div style={{ color: "var(--chrome-text)", fontSize: 12 }}>{title}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        {note && (
          <div style={{ display: "flex", justifyContent: "space-between", gap: 8, background: "var(--warn-bg, #fef3c7)", borderBottom: "1px solid var(--warn-bd, #fcd34d)", color: "var(--warn-fg, #92400e)", padding: "8px 14px", fontSize: 12.5 }}>
            <span>{note}</span><button onClick={() => setNote(null)} style={{ background: "none", border: "none", cursor: "pointer", color: "inherit" }}><Icon name="x" size={13} /></button>
          </div>
        )}

        <div style={{ display: "grid", gridTemplateColumns: "300px 1fr", flex: 1, minHeight: 0 }}>
          {/* Left: file list + upload */}
          <div style={{ borderRight: "1px solid var(--border)", display: "flex", flexDirection: "column", minHeight: 0 }}>
            <div style={{ padding: 12 }}>
              {driveMode ? (
                <>
                  <button className="primary" style={{ width: "100%" }} disabled={sync.isPending} onClick={() => sync.mutate()}>
                    <Icon name="reconcile" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                    {sync.isPending ? t("payroll.syncing") : t("payroll.syncFromDrive")}
                  </button>
                  <div style={{ fontSize: 11, color: "var(--text-muted)", marginTop: 6, textAlign: "center" }}>{t("payroll.driveSourced")}</div>
                </>
              ) : (
                <>
                  <input ref={fileRef} type="file" accept={accept ?? "application/pdf"} multiple={multiple} onChange={onFile} style={{ display: "none" }} />
                  <button className="primary" style={{ width: "100%" }} disabled={upload.isPending} onClick={() => fileRef.current?.click()}>
                    <Icon name="upload" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                    {upload.isPending ? t("taxes.sending") : t("files.addFiles")}
                  </button>
                </>
              )}
            </div>
            <div style={{ overflowY: "auto", flex: 1, padding: "0 8px 12px" }}>
              {docsQ.isLoading && <div style={{ color: "var(--text-muted)", fontSize: 12.5, padding: 8 }}>{t("common.loading")}</div>}
              {!docsQ.isLoading && docs.length === 0 && <div style={{ color: "var(--text-faint)", fontSize: 12.5, padding: 8 }}>{t("files.none")}</div>}
              {docs.map((d) => {
                const f = flagBy.get(d.id);
                const active = d.id === selectedId;
                return (
                  <div key={d.id} onClick={() => setSelectedId(d.id)}
                    style={{ display: "flex", alignItems: "center", gap: 6, padding: "8px 8px", borderRadius: 8, cursor: "pointer", marginBottom: 2, background: active ? "var(--row-active)" : undefined }}>
                    <Icon name="doc" size={14} style={{ color: "var(--text-muted)", flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 12.5, fontWeight: active ? 600 : 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{d.originalFilename}</div>
                      <div style={{ display: "flex", gap: 4, marginTop: 2, flexWrap: "wrap" }}>
                        {f?.wrongParty === true && <span className="pill round danger" title={t("doc.warn.wrongParty")}>{t("doc.wrongPartyChip")}</span>}
                        {f?.outsidePeriod === true && <span className="pill round warn" title={t("docs.outsidePeriodTip")}>{t("docs.outsidePeriod")}</span>}
                      </div>
                    </div>
                    {!driveMode && (
                      <button onClick={(e) => { e.stopPropagation(); del(d); }} title={t("payroll.deleteDoc")} disabled={remove.isPending}
                        style={{ border: "none", background: "none", color: "#dc2626", cursor: "pointer", padding: "0 2px", fontSize: 13, flexShrink: 0 }}>✕</button>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          {/* Right: preview */}
          <div style={{ display: "flex", flexDirection: "column", minHeight: 0, background: "var(--bg)" }}>
            <div style={{ flex: 1, margin: 12, borderRadius: 10, overflow: "hidden", background: "#525659", minHeight: 0 }}>
              {!selectedId && <div style={center}>{t("files.selectToPreview")}</div>}
              {selectedId && !blobUrl && <div style={center}>{t("common.loading")}</div>}
              {blobUrl && previewType.startsWith("image/") && <img src={blobUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "contain" }} />}
              {blobUrl && previewType === "application/pdf" && <iframe title="preview" src={blobUrl} style={{ width: "100%", height: "100%", border: "none" }} />}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "center", justifyContent: "center", padding: "4vh 16px", zIndex: 60 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(960px, 96vw)", height: "86vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "flex-start", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const center: React.CSSProperties = { display: "grid", placeItems: "center", height: "100%", color: "#cbd5e1", fontSize: 13, textAlign: "center", padding: 20 };
