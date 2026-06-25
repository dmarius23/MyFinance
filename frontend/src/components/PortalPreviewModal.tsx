import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

/**
 * Representative-side document preview. Takes a loader that returns the file Blob (via a portal
 * endpoint) and shows it inline (PDF in an iframe, images directly). Mobile-friendly full-screen.
 */
export function PortalPreviewModal({ load, filename, onClose }:
  { load: () => Promise<Blob>; filename: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [contentType, setContentType] = useState("");
  const [error, setError] = useState(false);

  useEffect(() => {
    let revoked: string | null = null;
    setError(false);
    setBlobUrl(null);
    void load()
      .then((blob) => { const url = URL.createObjectURL(blob); revoked = url; setContentType(blob.type); setBlobUrl(url); })
      .catch(() => setError(true));
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <span style={{ fontSize: 13.5, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{filename}</span>
          <button onClick={onClose} style={{ background: "none", border: "none", fontSize: 20, cursor: "pointer", lineHeight: 1 }}>✕</button>
        </div>
        <div style={{ flex: 1, background: "#525659", minHeight: 0 }}>
          {error && <div style={center}>{t("files.unavailable")}</div>}
          {!error && !blobUrl && <div style={center}>{t("common.loading")}</div>}
          {blobUrl && contentType.startsWith("image/") && <img src={blobUrl} alt={filename} style={{ width: "100%", height: "100%", objectFit: "contain" }} />}
          {blobUrl && contentType === "application/pdf" && <iframe title="preview" src={blobUrl} style={{ width: "100%", height: "100%", border: "none" }} />}
          {blobUrl && !contentType.startsWith("image/") && contentType !== "application/pdf" && (
            <div style={center}>{t("files.preview")}: {filename}</div>
          )}
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.6)", display: "flex", alignItems: "center", justifyContent: "center", padding: "3vh 10px", zIndex: 80 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 12, width: "min(900px, 100%)", height: "92vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8, padding: "10px 14px", borderBottom: "1px solid var(--border)" };
const center: React.CSSProperties = { display: "grid", placeItems: "center", height: "100%", color: "#e5e7eb", fontSize: 13, textAlign: "center", padding: 20 };
