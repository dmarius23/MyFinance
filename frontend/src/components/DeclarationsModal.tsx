import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { declarationsApi, type DeclarationFile } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";

const money = (n: number) => n.toLocaleString("ro-RO");

/** Manage the ANAF declarations uploaded for one company + month: list, preview, delete, upload. */
export function DeclarationsModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [selId, setSelId] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data = [], isLoading } = useQuery({
    queryKey: ["declarations", companyId, period],
    queryFn: () => declarationsApi.list(companyId, period),
  });

  const selected: DeclarationFile | undefined = data.find((d) => d.id === selId) ?? data[0];

  useEffect(() => {
    let revoked: string | null = null;
    if (selected) {
      void documentsApi.download(companyId, selected.documentId).then((blob) => {
        const url = URL.createObjectURL(blob);
        revoked = url;
        setBlobUrl(url);
      });
    } else {
      setBlobUrl(null);
    }
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
  }, [companyId, selected?.documentId]);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["declarations", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["tax-list", period] });
    void qc.invalidateQueries({ queryKey: ["tax-payments", companyId, period] });
  };

  const upload = useMutation({
    mutationFn: (file: File) => documentsApi.upload(companyId, period, file),
    onSuccess: () => { setError(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Upload failed"),
  });
  const remove = useMutation({
    mutationFn: (declarationId: string) => declarationsApi.remove(companyId, declarationId),
    onSuccess: () => { setSelId(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Delete failed"),
  });

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = "";
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("taxes.declarations")} — {companyName}</h2>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
            <button className="primary" onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? t("taxes.uploading") : "+ " + t("taxes.uploadDeclaration")}
            </button>
            <button onClick={onClose} style={{ border: "none", background: "none", fontSize: 18, cursor: "pointer" }}>✕</button>
          </div>
        </div>

        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        {isLoading && <p>{t("common.loading")}</p>}

        <div style={{ display: "grid", gridTemplateColumns: "360px 1fr", gap: 14, marginTop: 12, alignItems: "start" }}>
          <div style={{ maxHeight: 560, overflow: "auto" }}>
            {!isLoading && data.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("taxes.noDeclarations")}</div>}
            {data.map((d) => (
              <div key={d.id} onClick={() => setSelId(d.id)}
                style={{
                  display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", borderRadius: 8,
                  cursor: "pointer", marginBottom: 6,
                  background: selected?.id === d.id ? "var(--primary-light, #eef2ff)" : "var(--card-bg,#fff)",
                  border: "1px solid var(--border)",
                }}>
                {d.outsidePeriod && (
                  <span title={t("taxes.outsidePeriod")} style={{ fontSize: 14 }}>📅</span>
                )}
                <div style={{ flex: 1 }}>
                  <div><b>{d.type}</b> · {money(d.computedTotal)}
                    {d.mismatch && <span title={t("taxes.mismatch")} style={{ color: "#b45309", marginLeft: 4 }}>⚠</span>}
                  </div>
                  <div style={{ fontSize: 11, color: "var(--text-muted)" }}>CUI: {d.cui ?? "—"}</div>
                  {d.wrongParty && (
                    <span title={t("taxes.wrongPartyTip")} style={chip("#fee2e2", "#991b1b", "#fecaca")}>
                      {t("taxes.wrongParty")}
                    </span>
                  )}
                </div>
                <button title={t("common.delete")}
                  onClick={(e) => { e.stopPropagation(); remove.mutate(d.id); }}
                  disabled={remove.isPending}
                  style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", fontSize: 14 }}>✕</button>
              </div>
            ))}
          </div>

          <div style={{ minHeight: 480, border: "1px solid var(--border)", borderRadius: 8, overflow: "hidden" }}>
            {blobUrl
              ? <iframe title="declaration" src={blobUrl} style={{ width: "100%", height: 560, border: "none" }} />
              : <div style={{ padding: 16, color: "var(--text-muted)" }}>{t("files.preview")}</div>}
          </div>
        </div>
      </div>
    </div>
  );
}

function chip(bg: string, fg: string, bd: string): React.CSSProperties {
  return { background: bg, color: fg, border: `1px solid ${bd}`, borderRadius: 999, padding: "0 8px",
    fontSize: 11, display: "inline-block", marginTop: 3 };
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "5vh 16px", zIndex: 50, overflowY: "auto",
};
const modal: React.CSSProperties = { background: "var(--card-bg, #fff)", borderRadius: 12, padding: 20, width: "min(980px, 100%)" };
