import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { templatesApi, type MessageTemplate, type TemplateKind } from "../api/templates";
import { ApiError } from "../lib/apiClient";
import { Field } from "./Field";

const errStyle: React.CSSProperties = { color: "#dc2626", marginTop: 8 };
const okStyle: React.CSSProperties = { color: "#059669", marginTop: 8 };
const hint: React.CSSProperties = { color: "var(--text-muted)", fontSize: 13, marginTop: 0 };
const chip: React.CSSProperties = {
  display: "inline-block", padding: "1px 6px", borderRadius: 4, fontSize: 12,
  background: "var(--th-bg)", marginRight: 6, marginBottom: 4, fontFamily: "monospace",
};
const pre: React.CSSProperties = {
  whiteSpace: "pre-wrap", background: "var(--th-bg)", padding: 10, borderRadius: 6,
  fontSize: 13, margin: 0,
};

/**
 * Per-tenant message wording. The firm admin edits each message once here; every send then pre-fills
 * from this text and stays editable in its own modal. Placeholders like {{month}} are substituted by the
 * server — an unknown one is rejected on save rather than mailed to a client as literal text.
 *
 * Draft state uses `undefined` to mean "unedited, show the server's value". Switching kinds discards
 * any unsaved draft (via `pick()`). This is intentional: the editor is a one-at-a-time form, and
 * silently keeping a draft for a hidden kind would be more surprising than discarding it. Admins
 * wanting to preserve a draft should save before switching.
 */
export function MessageTemplatesSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["templates"], queryFn: templatesApi.list });

  const [kind, setKind] = useState<TemplateKind>("TAX");
  // undefined = not edited yet, so the server's text shows through.
  const [draft, setDraft] = useState<{ subject?: string; body?: string }>({});
  const [msg, setMsg] = useState<{ ok?: string; err?: string }>({});
  const [preview, setPreview] = useState<string | null>(null);

  const current: MessageTemplate | undefined = data?.find((x) => x.kind === kind);
  const subject = draft.subject ?? current?.subject ?? "";
  const body = draft.body ?? current?.body ?? "";

  const hasUnsaved = draft.subject !== undefined || draft.body !== undefined;

  const pick = (next: TemplateKind) => {
    if (next === kind) return;
    if (hasUnsaved && !window.confirm(t("templates.unsavedWarning"))) return;
    setKind(next);
    setDraft({});
    setMsg({});
    setPreview(null);
  };

  const invalidate = () => qc.invalidateQueries({ queryKey: ["templates"] });
  const fail = (e: unknown) => setMsg({ err: e instanceof ApiError ? e.message : "Failed" });

  const save = useMutation({
    mutationFn: () => templatesApi.update(kind, { subject, body }),
    onSuccess: () => { void invalidate(); setDraft({}); setMsg({ ok: t("templates.saved") }); },
    onError: fail,
  });

  const reset = useMutation({
    mutationFn: () => templatesApi.reset(kind),
    onSuccess: () => { void invalidate(); setDraft({}); setPreview(null); setMsg({ ok: t("templates.saved") }); },
    onError: fail,
  });

  const runPreview = useMutation({
    mutationFn: () => templatesApi.preview(kind, { subject, body }),
    onSuccess: (p) => { setPreview(`${p.subject}\n\n${p.body}`); setMsg({}); },
    onError: fail,
  });

  if (isLoading) return <div className="card">…</div>;

  return (
    <div className="card" style={{ display: "grid", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0 }}>{t("templates.title")}</h3>
        <p style={hint}>{t("templates.intro")}</p>
      </div>

      <Field label={t("templates.title")}>
        <select value={kind} onChange={(e) => pick(e.target.value as TemplateKind)}>
          {(data ?? []).map((x) => (
            <option key={x.kind} value={x.kind}>
              {t(`templates.kind.${x.kind}`)} — {x.customized ? t("templates.customized") : t("templates.default")}
            </option>
          ))}
        </select>
      </Field>

      <Field label={t("templates.subject")}>
        <input value={subject} onChange={(e) => setDraft((d) => ({ ...d, subject: e.target.value }))} />
        <p style={hint}>{t("templates.subjectHint")}</p>
      </Field>

      <Field label={t("templates.body")}>
        <textarea rows={16} value={body} style={{ fontFamily: "monospace", fontSize: 13 }}
          onChange={(e) => setDraft((d) => ({ ...d, body: e.target.value }))} />
      </Field>

      <div>
        <div style={{ fontSize: 13, marginBottom: 4 }}>{t("templates.placeholders")}</div>
        <div>
          {[...(current?.placeholders ?? []), "signOff"].map((p) => (
            <code key={p} style={chip}>{`{{${p}}}`}</code>
          ))}
        </div>
        {(current?.blocks?.length ?? 0) > 0 && (
          <>
            <div style={{ fontSize: 13, margin: "8px 0 4px" }}>{t("templates.blocks")}</div>
            <div>
              {current?.blocks.map((b) => <code key={b} style={chip}>{`{{${b}}}`}</code>)}
            </div>
            <p style={hint}>{t("templates.blocksHint")}</p>
          </>
        )}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
        <button onClick={() => save.mutate()} disabled={save.isPending || !body.trim()}>
          {t("common.save")}
        </button>
        {hasUnsaved && (
          <span style={{ fontSize: 12, color: "var(--text-muted)", fontStyle: "italic" }}>
            {t("templates.unsaved")}
          </span>
        )}
        <button onClick={() => runPreview.mutate()} disabled={runPreview.isPending || !body.trim()}>
          {t("templates.preview")}
        </button>
        {current?.customized && (
          <button onClick={() => reset.mutate()} disabled={reset.isPending}>
            {t("templates.reset")}
          </button>
        )}
      </div>

      {preview !== null && (
        <div>
          <p style={hint}>{t("templates.previewNote")}</p>
          <pre style={pre}>{preview}</pre>
        </div>
      )}

      {msg.err && <div style={errStyle}>{msg.err}</div>}
      {msg.ok && <div style={okStyle}>{msg.ok}</div>}
    </div>
  );
}
