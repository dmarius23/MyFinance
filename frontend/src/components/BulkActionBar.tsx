import { useTranslation } from "react-i18next";
import { Icon } from "./Icon";

/**
 * Floating selection toolbar shown while ≥1 company is selected in a monthly-hub list. Rendered as a
 * fixed, centered pill that floats OVER the page header (within the content column, clear of the sidebar)
 * instead of pushing the table down. Offers the bulk Email + bulk WhatsApp actions and Clear.
 *
 * The outer wrapper is click-through (pointer-events: none) so only the pill itself is interactive — the
 * header/table underneath stay clickable in the flanks.
 */
export function BulkActionBar({ count, label, onClear, onEmail, onWhatsapp }: {
  count: number;
  /** Already-pluralized noun after the count, e.g. "companies selected". */
  label: string;
  onClear: () => void;
  onEmail: () => void;
  onWhatsapp: () => void;
}) {
  const { t } = useTranslation();
  if (count <= 0) return null;
  return (
    <div style={wrap} role="region" aria-label={label}>
      <div style={bar}>
        <span style={{ fontSize: 13.5, color: "var(--chrome-text)", whiteSpace: "nowrap" }}>
          <b style={{ color: "var(--primary)" }}>{count}</b> {label}
        </span>
        <div style={{ display: "flex", gap: 8 }}>
          <button onClick={onClear} style={ghost}>{t("email.clear")}</button>
          <button className="primary" onClick={onEmail}>
            <Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("email.sendN", { n: count })}
          </button>
          <button onClick={onWhatsapp} style={waBtn}>
            <Icon name="whatsapp" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("channel.sendWhatsappN", { n: count })}
          </button>
        </div>
      </div>
    </div>
  );
}

const wrap: React.CSSProperties = {
  position: "fixed",
  top: "calc(var(--topbar-height) + 10px)",
  left: "var(--sidebar-width)",
  right: 0,
  display: "flex",
  justifyContent: "center",
  pointerEvents: "none",
  zIndex: 45,
};
const bar: React.CSSProperties = {
  pointerEvents: "auto",
  display: "flex",
  alignItems: "center",
  gap: 16,
  background: "var(--chrome-bg)",
  borderRadius: 999,
  padding: "8px 10px 8px 18px",
  boxShadow: "var(--shadow-modal)",
  border: "1px solid #2a3a37",
};
const ghost: React.CSSProperties = { background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" };
const waBtn: React.CSSProperties = { background: "#128C7E", color: "#fff", border: "1px solid #0e6f64" };
