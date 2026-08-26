import { useTranslation } from "react-i18next";
import type { CompletenessFilter as FilterValue } from "../api/payroll";

/**
 * Shared segmented toggle for the module lists: [ All | Needs attention ]. "Needs attention" narrows the
 * list to companies that still owe a document/declaration for the selected month (server-side filter).
 * Reused across Payroll, Reports, Bank statements and Taxe so the control is identical everywhere.
 */
export function CompletenessFilter({ value, onChange }: { value: FilterValue; onChange: (v: FilterValue) => void }) {
  const { t } = useTranslation();
  const opts: { key: FilterValue; label: string }[] = [
    { key: "all", label: t("filter.all") },
    { key: "missing", label: t("filter.needsAttention") },
  ];
  return (
    <div role="group" aria-label={t("filter.needsAttention")}
      style={{ display: "inline-flex", border: "1px solid var(--border)", borderRadius: 8, overflow: "hidden" }}>
      {opts.map((o) => {
        const active = value === o.key;
        return (
          <button key={o.key} type="button" aria-pressed={active} onClick={() => onChange(o.key)}
            style={{
              fontSize: 12, padding: "5px 12px", border: "none", cursor: "pointer", whiteSpace: "nowrap",
              background: active ? "var(--chrome-active)" : "transparent",
              color: active ? "var(--chrome-text)" : "var(--text-secondary)",
              fontWeight: active ? 600 : 400,
            }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
