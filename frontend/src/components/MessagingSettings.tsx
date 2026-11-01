import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "react-router-dom";
import {
  messagingApi,
  type EmailProvider,
  type UpdateEmailInput,
  type WhatsAppMode,
  type WhatsAppProvider,
  type WhatsAppTemplate,
  type WhatsAppTemplateKind,
  type WhatsAppTemplateLanguage,
  type WhatsAppTemplateStatus,
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
  const location = useLocation();
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

  const startMeta = useMutation({
    mutationFn: messagingApi.startMetaOnboarding,
    onSuccess: ({ authorizationUrl }) => {
      window.location.assign(authorizationUrl);
    },
    onError: (e) => setMsg({ err: e instanceof ApiError ? e.message : t("common.error") }),
  });

  const testMeta = useMutation({
    mutationFn: messagingApi.testWhatsApp,
    onSuccess: () => setMsg({ ok: t("settings.msg.whatsappConfigValid") }),
    onError: (e) => setMsg({ err: e instanceof ApiError ? e.message : t("common.error") }),
  });

  if (isLoading) return <div className="card"><p>{t("common.loading")}</p></div>;
  const twilio = mode === "TWILIO";
  const meta = mode === "META_CLOUD_API";
  const connected = data?.mode === "META_CLOUD_API" && data.hasMetaToken;
  const connectedFromCallback = new URLSearchParams(location.search).get("whatsapp") === "connected";

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
            <option value="META_CLOUD_API">{t("settings.msg.modeMeta")}</option>
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
        {meta && (
          <div style={{ display: "grid", gap: 8, padding: 12, border: "1px solid var(--border)", borderRadius: 8 }}>
            <strong>{connected ? t("settings.msg.metaConnected") : t("settings.msg.metaNotConnected")}</strong>
            <p style={hint}>{t("settings.msg.metaHelp")}</p>
            {data?.metaWabaId && <span className="mono">WABA: {data.metaWabaId}</span>}
            {data?.metaPhoneNumberId && <span className="mono">Phone ID: {data.metaPhoneNumberId}</span>}
            <button type="button" className="primary" disabled={startMeta.isPending}
              onClick={() => { setMsg({}); startMeta.mutate(); }}>
              {startMeta.isPending ? "…" : connected ? t("settings.msg.metaReconnect") : t("settings.msg.metaConnect")}
            </button>
            {connected && (
              <button type="button" disabled={testMeta.isPending}
                onClick={() => { setMsg({}); testMeta.mutate(); }}>
                {testMeta.isPending ? "…" : t("settings.msg.testWhatsapp")}
              </button>
            )}
          </div>
        )}
        {!meta && <div>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "…" : t("common.save")}
          </button>
        </div>}
      </form>
      {connectedFromCallback && <p style={okStyle}>{t("settings.msg.metaConnected")}</p>}
      {msg.err && <p style={errStyle}>{msg.err}</p>}
      {msg.ok && <p style={okStyle}>{msg.ok}</p>}
    </div>
  );
}

const TEMPLATE_KINDS: WhatsAppTemplateKind[] = ["TAX", "PAYROLL", "REPORT", "DOCUMENT_REMINDER"];
const TEMPLATE_LANGUAGES: WhatsAppTemplateLanguage[] = ["ro_RO", "en_US"];
const TEMPLATE_STATUSES: WhatsAppTemplateStatus[] = ["APPROVED", "PENDING", "DISABLED"];
const emptyTemplate = { kind: "TAX" as WhatsAppTemplateKind, language: "ro_RO" as WhatsAppTemplateLanguage,
  metaTemplateName: "", status: "APPROVED" as WhatsAppTemplateStatus };

/**
 * Registers which Meta-approved template each module sends (one per kind + language). Only APPROVED rows
 * are used; the name must match WhatsApp Manager exactly. Shown only for the Meta Cloud API provider.
 */
export function WhatsAppTemplatesSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const provider = useQuery({ queryKey: ["messaging", "whatsapp"], queryFn: messagingApi.getWhatsApp });
  const isMeta = provider.data?.mode === "META_CLOUD_API";
  const list = useQuery({
    queryKey: ["messaging", "whatsapp-templates"],
    queryFn: messagingApi.listWhatsAppTemplates,
    enabled: isMeta,
  });
  const [form, setForm] = useState(emptyTemplate);
  const [msg, setMsg] = useState<{ ok?: string; err?: string }>({});
  const refresh = () => qc.invalidateQueries({ queryKey: ["messaging", "whatsapp-templates"] });
  const fail = (e: unknown) => setMsg({ err: e instanceof ApiError ? e.message : t("common.error") });

  const save = useMutation({
    mutationFn: () => messagingApi.saveWhatsAppTemplate({ ...form, metaTemplateName: form.metaTemplateName.trim(),
      category: "UTILITY" }),
    onSuccess: () => { void refresh(); setForm(emptyTemplate); setMsg({ ok: t("common.save") }); },
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: messagingApi.deleteWhatsAppTemplate,
    onSuccess: () => { void refresh(); setMsg({}); },
    onError: fail,
  });

  if (!isMeta) return null;
  const edit = (x: WhatsAppTemplate) => {
    setMsg({});
    setForm({ kind: x.kind, language: x.language, metaTemplateName: x.metaTemplateName, status: x.status });
  };
  const templates = list.data ?? [];

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.tpl.title")}</h2>
      <p style={hint}>{t("settings.tpl.desc")}</p>

      {list.isLoading && <p>{t("common.loading")}</p>}
      {!list.isLoading && templates.length === 0 && <p style={hint}>{t("settings.tpl.none")}</p>}
      {templates.length > 0 && (
        <table style={{ width: "100%", maxWidth: 720, borderCollapse: "collapse", fontSize: 13, marginBottom: 16 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)", fontSize: 11, textTransform: "uppercase" }}>
              <th style={cell}>{t("settings.tpl.colKind")}</th>
              <th style={cell}>{t("settings.tpl.language")}</th>
              <th style={cell}>{t("settings.tpl.name")}</th>
              <th style={cell}>{t("settings.tpl.colStatus")}</th>
              <th style={cell} />
            </tr>
          </thead>
          <tbody>
            {templates.map((x) => (
              <tr key={x.id} style={{ borderTop: "1px solid var(--hair)" }}>
                <td style={cell}>{t(`settings.tpl.kind.${x.kind}`)}</td>
                <td style={cell} className="mono">{x.language}</td>
                <td style={cell} className="mono">{x.metaTemplateName}</td>
                <td style={cell}>
                  <span className={`pill round ${x.status === "APPROVED" ? "ok" : x.status === "PENDING" ? "info" : "danger"}`}>
                    {t(`settings.tpl.status.${x.status}`)}
                  </span>
                </td>
                <td style={{ ...cell, textAlign: "right", whiteSpace: "nowrap" }}>
                  <button type="button" onClick={() => edit(x)}>{t("common.edit")}</button>{" "}
                  <button type="button" disabled={remove.isPending}
                    onClick={() => { if (window.confirm(t("settings.tpl.confirmDelete"))) remove.mutate(x.id); }}>
                    {t("settings.tpl.delete")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <form onSubmit={(e) => { e.preventDefault(); setMsg({}); save.mutate(); }}
        style={{ display: "grid", gap: 12, maxWidth: 520 }}>
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <Field label={t("settings.tpl.colKind")}>
            <select value={form.kind} onChange={(e) => setForm((f) => ({ ...f, kind: e.target.value as WhatsAppTemplateKind }))}>
              {TEMPLATE_KINDS.map((k) => <option key={k} value={k}>{t(`settings.tpl.kind.${k}`)}</option>)}
            </select>
          </Field>
          <Field label={t("settings.tpl.language")}>
            <select value={form.language}
              onChange={(e) => setForm((f) => ({ ...f, language: e.target.value as WhatsAppTemplateLanguage }))}>
              {TEMPLATE_LANGUAGES.map((l) => <option key={l} value={l}>{t(`settings.tpl.lang.${l}`)}</option>)}
            </select>
          </Field>
          <Field label={t("settings.tpl.colStatus")}>
            <select value={form.status}
              onChange={(e) => setForm((f) => ({ ...f, status: e.target.value as WhatsAppTemplateStatus }))}>
              {TEMPLATE_STATUSES.map((s) => <option key={s} value={s}>{t(`settings.tpl.status.${s}`)}</option>)}
            </select>
          </Field>
        </div>
        <Field label={t("settings.tpl.name")}>
          <input type="text" required pattern="[a-z][a-z0-9_]{1,511}" placeholder="document_reminder"
            value={form.metaTemplateName} onChange={(e) => setForm((f) => ({ ...f, metaTemplateName: e.target.value }))} />
        </Field>
        <p style={hint}>{t("settings.tpl.nameHint")}</p>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="primary" type="submit" disabled={save.isPending || !form.metaTemplateName.trim()}>
            {save.isPending ? "…" : t("common.save")}
          </button>
          {form !== emptyTemplate && (
            <button type="button" onClick={() => { setForm(emptyTemplate); setMsg({}); }}>{t("common.cancel")}</button>
          )}
        </div>
      </form>
      {msg.err && <p style={errStyle}>{msg.err}</p>}
      {msg.ok && <p style={okStyle}>{msg.ok}</p>}
    </div>
  );
}

const cell: React.CSSProperties = { padding: "7px 8px 7px 0" };
