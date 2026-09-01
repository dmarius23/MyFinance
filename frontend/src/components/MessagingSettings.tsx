import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  messagingApi,
  type EmailProvider,
  type UpdateEmailInput,
  type WhatsAppMode,
  type WhatsAppProvider,
} from "../api/messaging";
import { ApiError } from "../lib/apiClient";
import { Field } from "./Field";

const errStyle: React.CSSProperties = { color: "#dc2626", marginTop: 8 };
const okStyle: React.CSSProperties = { color: "#059669", marginTop: 8 };
const hint: React.CSSProperties = { color: "var(--text-muted)", fontSize: 13, marginTop: 0 };

/** Per-tenant email (SMTP) provider config, with a synchronous "send test" action. */
export function EmailProviderSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["messaging", "email"], queryFn: messagingApi.getEmail });

  // Local form state — seeded from server data, `undefined` means "unchanged / show server value".
  const [form, setForm] = useState<Partial<UpdateEmailInput>>({});
  const [password, setPassword] = useState<string>(""); // blank = keep stored
  const [testTo, setTestTo] = useState<string>("");
  const [msg, setMsg] = useState<{ ok?: string; err?: string }>({});

  const val = <K extends keyof EmailProvider>(k: K): EmailProvider[K] | undefined =>
    (form as Record<string, unknown>)[k] !== undefined
      ? ((form as Record<string, unknown>)[k] as EmailProvider[K])
      : data?.[k];

  const save = useMutation({
    mutationFn: () =>
      messagingApi.updateEmail({
        enabled: Boolean(val("enabled")),
        fromEmail: (val("fromEmail") as string | null) || null,
        fromName: (val("fromName") as string | null) || null,
        smtpHost: (val("smtpHost") as string | null) || null,
        smtpPort: (val("smtpPort") as number | null) ?? null,
        smtpUsername: (val("smtpUsername") as string | null) || null,
        smtpPassword: password === "" ? undefined : password, // undefined = keep stored
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["messaging", "email"] });
      setForm({});
      setPassword("");
      setMsg({ ok: t("common.save") });
    },
    onError: (e) => setMsg({ err: e instanceof ApiError ? e.message : "Failed" }),
  });

  const sendTest = useMutation({
    mutationFn: () => messagingApi.testEmail(testTo),
    onSuccess: () => setMsg({ ok: t("settings.msg.testSent") }),
    onError: (e) => setMsg({ err: e instanceof ApiError ? e.message : "Failed" }),
  });

  if (isLoading) return <div className="card"><p>{t("common.loading")}</p></div>;

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.msg.emailTitle")}</h2>
      <p style={hint}>{t("settings.msg.emailDesc")}</p>
      <form
        onSubmit={(e) => { e.preventDefault(); setMsg({}); save.mutate(); }}
        style={{ display: "grid", gap: 12, maxWidth: 520 }}
      >
        <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <input type="checkbox" checked={Boolean(val("enabled"))}
            onChange={(e) => setForm((f) => ({ ...f, enabled: e.target.checked }))} />
          {t("settings.msg.enabled")}
        </label>
        <Field label={t("settings.msg.fromEmail")}>
          <input type="email" value={(val("fromEmail") as string | null) ?? ""}
            onChange={(e) => setForm((f) => ({ ...f, fromEmail: e.target.value }))} />
        </Field>
        <Field label={t("settings.msg.fromName")}>
          <input type="text" value={(val("fromName") as string | null) ?? ""}
            onChange={(e) => setForm((f) => ({ ...f, fromName: e.target.value }))} />
        </Field>
        <div style={{ display: "flex", gap: 12 }}>
          <Field label={t("settings.msg.smtpHost")}>
            <input type="text" placeholder="smtp.resend.com" value={(val("smtpHost") as string | null) ?? ""}
              onChange={(e) => setForm((f) => ({ ...f, smtpHost: e.target.value }))} />
          </Field>
          <Field label={t("settings.msg.smtpPort")}>
            <input type="number" min={1} max={65535} style={{ width: 90 }}
              value={(val("smtpPort") as number | null) ?? ""}
              onChange={(e) => setForm((f) => ({ ...f, smtpPort: e.target.value ? Number(e.target.value) : null }))} />
          </Field>
        </div>
        <Field label={t("settings.msg.smtpUsername")}>
          <input type="text" autoComplete="off" value={(val("smtpUsername") as string | null) ?? ""}
            onChange={(e) => setForm((f) => ({ ...f, smtpUsername: e.target.value }))} />
        </Field>
        <Field label={t("settings.msg.smtpPassword")}>
          <input type="password" autoComplete="new-password"
            placeholder={data?.hasPassword ? t("settings.msg.secretStored") : ""}
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <p style={hint}>{t("settings.msg.secretKeep")}</p>
        <div>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "…" : t("common.save")}
          </button>
        </div>
      </form>

      <div style={{ display: "flex", alignItems: "flex-end", gap: 12, marginTop: 16, flexWrap: "wrap" }}>
        <Field label={t("settings.msg.testTo")}>
          <input type="email" value={testTo} onChange={(e) => setTestTo(e.target.value)} style={{ minWidth: 240 }} />
        </Field>
        <button type="button" disabled={sendTest.isPending || !testTo}
          onClick={() => { setMsg({}); sendTest.mutate(); }} style={{ marginBottom: 10 }}>
          {sendTest.isPending ? "…" : t("settings.msg.testEmail")}
        </button>
      </div>

      {msg.err && <p style={errStyle}>{msg.err}</p>}
      {msg.ok && <p style={okStyle}>{msg.ok}</p>}
    </div>
  );
}

/** Per-tenant WhatsApp provider config (Twilio credentials or manual click-to-chat). */
export function WhatsAppProviderSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["messaging", "whatsapp"], queryFn: messagingApi.getWhatsApp });

  const [form, setForm] = useState<Partial<WhatsAppProvider>>({});
  const [token, setToken] = useState<string>(""); // blank = keep stored
  const [msg, setMsg] = useState<{ ok?: string; err?: string }>({});

  const mode = (form.mode ?? data?.mode ?? "OFF") as WhatsAppMode;
  const sid = form.accountSid !== undefined ? form.accountSid : data?.accountSid ?? "";
  const fromNumber = form.fromNumber !== undefined ? form.fromNumber : data?.fromNumber ?? "";

  const save = useMutation({
    mutationFn: () =>
      messagingApi.updateWhatsApp({
        mode,
        accountSid: sid || null,
        authToken: token === "" ? undefined : token,
        fromNumber: fromNumber || null,
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["messaging", "whatsapp"] });
      setForm({});
      setToken("");
      setMsg({ ok: t("common.save") });
    },
    onError: (e) => setMsg({ err: e instanceof ApiError ? e.message : "Failed" }),
  });

  if (isLoading) return <div className="card"><p>{t("common.loading")}</p></div>;
  const twilio = mode === "TWILIO";

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.msg.whatsappTitle")}</h2>
      <p style={hint}>{t("settings.msg.whatsappDesc")}</p>
      <form
        onSubmit={(e) => { e.preventDefault(); setMsg({}); save.mutate(); }}
        style={{ display: "grid", gap: 12, maxWidth: 520 }}
      >
        <Field label={t("settings.msg.mode")}>
          <select value={mode} onChange={(e) => setForm((f) => ({ ...f, mode: e.target.value as WhatsAppMode }))}>
            <option value="OFF">{t("settings.msg.modeOff")}</option>
            <option value="TWILIO">{t("settings.msg.modeTwilio")}</option>
            <option value="CLICK_TO_CHAT">{t("settings.msg.modeClickToChat")}</option>
          </select>
        </Field>
        {twilio && (
          <>
            <Field label={t("settings.msg.accountSid")}>
              <input type="text" autoComplete="off" value={sid ?? ""}
                onChange={(e) => setForm((f) => ({ ...f, accountSid: e.target.value }))} />
            </Field>
            <Field label={t("settings.msg.authToken")}>
              <input type="password" autoComplete="new-password"
                placeholder={data?.hasToken ? t("settings.msg.secretStored") : ""}
                value={token} onChange={(e) => setToken(e.target.value)} />
            </Field>
            <p style={hint}>{t("settings.msg.secretKeep")}</p>
            <Field label={t("settings.msg.fromNumber")}>
              <input type="text" placeholder="+14155238886" value={fromNumber ?? ""}
                onChange={(e) => setForm((f) => ({ ...f, fromNumber: e.target.value }))} />
            </Field>
          </>
        )}
        <div>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "…" : t("common.save")}
          </button>
        </div>
      </form>
      {msg.err && <p style={errStyle}>{msg.err}</p>}
      {msg.ok && <p style={okStyle}>{msg.ok}</p>}
    </div>
  );
}
