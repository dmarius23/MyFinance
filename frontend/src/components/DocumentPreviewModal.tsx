import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { documentsApi } from "../api/documents";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)",
  display: "grid", placeItems: "center", zIndex: 75,
};

/** Lightweight document preview (PDF/image) opened from anywhere that has a documentId. */
export function DocumentPreviewModal({ companyId, documentId, filename, onClose }:
  { companyId: string; documentId: string; filename?: string | null; onClose: () => void }) {
  const { t } = useTranslation();
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [contentType, setContentType] = useState<string>("");
  const [error, setError] = useState(false);

  useEffect(() => {
    let revoked: string | null = null;
    setError(false);
    setBlobUrl(null);
    void documentsApi.download(companyId, documentId)
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        revoked = url;
        setContentType(blob.type);
        setBlobUrl(url);
      })
      .catch(() => setError(true));
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
  }, [companyId, documentId]);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 900, maxWidth: "94vw", height: "88vh", display: "flex", flexDirection: "column" }}
        onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <div style={{ fontWeight: 600, fontSize: 14, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {filename ?? t("files.preview")}
          </div>
          <button onClick={onClose}>✕</button>
        </div>
        <div style={{ flex: 1, border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden", background: "#525659" }}>
          {error && (
            <div style={{ display: "grid", placeItems: "center", height: "100%", color: "#fca5a5", fontSize: 13, padding: 20, textAlign: "center" }}>
              {t("files.unavailable")}
            </div>
          )}
          {!error && !blobUrl && (
            <div style={{ display: "grid", placeItems: "center", height: "100%", color: "#cbd5e1" }}>{t("common.loading")}</div>
          )}
          {blobUrl && contentType.startsWith("image/") && (
            <img src={blobUrl} alt={filename ?? ""} style={{ width: "100%", height: "100%", objectFit: "contain" }} />
          )}
          {blobUrl && contentType === "application/pdf" && (
            <iframe title="preview" src={blobUrl} style={{ width: "100%", height: "100%", border: "none" }} />
          )}
        </div>
      </div>
    </div>
  );
}
