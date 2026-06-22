import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation } from "@tanstack/react-query";
import { portalApi } from "../api/notifications";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../lib/apiClient";

/**
 * Representative PWA home (mobile-first). Upload a document/receipt for the rep's own company — each
 * upload notifies the firm in-app and emails the responsible accountant.
 */
export function RepHome() {
  const { t } = useTranslation();
  const { signOut } = useAuth();
  const cameraRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [done, setDone] = useState<string[]>([]);

  const upload = useMutation({
    mutationFn: (files: File[]) => Promise.all(files.map((f) => portalApi.uploadDocument(f))),
    onSuccess: (_res, files) => setDone((d) => [...files.map((f) => f.name), ...d]),
  });
  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []); e.target.value = "";
    if (files.length) upload.mutate(files);
  };

  return (
    <div style={{ maxWidth: 480, margin: "0 auto", padding: 16, display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ color: "var(--primary)", margin: 0, fontSize: 24 }}>MyFinance</h1>
        <button onClick={() => void signOut()}>{t("auth.logout")}</button>
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 17 }}>{t("portal.uploadTitle")}</h2>
        <p style={{ color: "var(--text-muted)", fontSize: 13.5, marginTop: 4 }}>{t("portal.uploadHint")}</p>

        <input ref={cameraRef} type="file" accept="image/*" capture="environment" onChange={onPick} style={{ display: "none" }} />
        <input ref={fileRef} type="file" accept="application/pdf,image/*" multiple onChange={onPick} style={{ display: "none" }} />

        <div style={{ display: "grid", gap: 8, marginTop: 8 }}>
          <button className="primary" style={{ padding: "12px", fontSize: 15 }} disabled={upload.isPending} onClick={() => cameraRef.current?.click()}>
            📷 {t("portal.takePhoto")}
          </button>
          <button style={{ padding: "12px", fontSize: 15 }} disabled={upload.isPending} onClick={() => fileRef.current?.click()}>
            📎 {t("portal.chooseFile")}
          </button>
        </div>

        {upload.isPending && <p style={{ color: "var(--text-secondary)", fontSize: 13, marginTop: 10 }}>{t("portal.uploading")}</p>}
        {upload.isError && <p style={{ color: "var(--danger-fg, #b91c1c)", fontSize: 13, marginTop: 10 }}>{upload.error instanceof ApiError ? upload.error.message : t("portal.failed")}</p>}
        {done.length > 0 && (
          <div style={{ marginTop: 12 }}>
            <div style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase", color: "var(--text-muted)", marginBottom: 6 }}>{t("portal.sent")}</div>
            {done.map((name, i) => (
              <div key={i} style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13, padding: "4px 0" }}>
                <span style={{ color: "#16a34a" }}>✓</span><span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{name}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 17 }}>{t("portal.missingTitle")}</h2>
        <p style={{ color: "var(--text-muted)", fontSize: 13.5 }}>{t("portal.missingHint")}</p>
      </div>
    </div>
  );
}
