import { useTranslation } from "react-i18next";
import { Icon } from "./Icon";

/**
 * Shared building blocks for the uniform module tables (Statements / Payroll / Reports).
 * Status columns are display-only; every actionable control lives in the Actions column as one of
 * these icon buttons, so the three screens read the same way. WhatsApp is scaffolded (disabled) until
 * the channel backend exists.
 */

const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

const actionBtn: React.CSSProperties = {
  width: 30, height: 30, display: "grid", placeItems: "center", padding: 0,
  border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)",
  color: "#52605d", fontSize: 13,
};
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)" };

/** One uniform icon action button. */
export function ActionBtn({ icon, title, onClick, disabled, emphasis }:
  { icon: string; title: string; onClick?: () => void; disabled?: boolean; emphasis?: boolean }) {
  return (
    <button type="button" title={title} onClick={onClick} disabled={disabled}
      style={{ ...actionBtn, opacity: disabled ? 0.4 : 1, cursor: disabled ? "default" : "pointer",
        ...(emphasis && !disabled ? { borderColor: "var(--primary)", color: "var(--primary)" } : {}) }}>
      <Icon name={icon} size={15} />
    </button>
  );
}

/** WhatsApp send action — scaffolded, not yet wired to a backend channel. */
/** WhatsApp send action. When {@code onClick} is given it's enabled; otherwise it renders as "soon". */
export function WhatsAppAction({ onClick }: { onClick?: () => void }) {
  const { t } = useTranslation();
  if (!onClick) {
    return (
      <button type="button" title={t("channel.whatsappSoon")} disabled
        style={{ ...actionBtn, opacity: 0.45, cursor: "default" }}>
        <Icon name="whatsapp" size={15} />
      </button>
    );
  }
  return (
    <button type="button" title={t("channel.whatsapp")} onClick={onClick}
      style={{ ...actionBtn, borderColor: "#25D366", color: "#128C7E" }}>
      <Icon name="whatsapp" size={15} />
    </button>
  );
}

/** Wraps a row's action buttons in a consistent, right-aligned cluster. */
export function RowActions({ children }: { children: React.ReactNode }) {
  return <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>{children}</div>;
}

/** Last-email status cell (display only) → opens the read-only history log when a mail exists. */
export function LastEmailCell({ lastSentAt, count, onOpen }:
  { lastSentAt?: string | null; count?: number; onOpen: () => void }) {
  const { t } = useTranslation();
  if (!lastSentAt) return <span style={{ color: "var(--text-faint)", fontSize: 12 }}>—</span>;
  return (
    <button className="pill teal round" style={pillBtn} title={t("channel.viewHistory")} onClick={onOpen}>
      <Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />
      {dmy(lastSentAt)}{(count ?? 0) > 1 ? ` · ${count}` : ""}
    </button>
  );
}

/** Last-WhatsApp status cell → opens the WhatsApp history when a message has been sent, else a dash. */
export function LastWhatsAppCell({ lastSentAt, count, onOpen }:
  { lastSentAt?: string | null; count?: number; onOpen?: () => void } = {}) {
  if (!lastSentAt) return <span style={{ color: "var(--text-faint)", fontSize: 12 }}>—</span>;
  return (
    <button className="pill round" style={{ ...pillBtn, borderColor: "#25D366", color: "#128C7E" }} onClick={onOpen}>
      <Icon name="whatsapp" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />
      {dmy(lastSentAt)}{(count ?? 0) > 1 ? ` · ${count}` : ""}
    </button>
  );
}
