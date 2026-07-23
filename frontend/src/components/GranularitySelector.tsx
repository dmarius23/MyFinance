import { useTranslation } from "react-i18next";
import type { Granularity } from "../api/portal";

const OPTIONS: Granularity[] = ["MONTH", "QUARTER", "HALF", "YEAR"];

/** Segmented control to pick the report period grain: Month / Quarter / Half-year / Year. */
export function GranularitySelector({
  value,
  onChange,
}: {
  value: Granularity;
  onChange: (g: Granularity) => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      role="tablist"
      aria-label={t("granularity.label")}
      style={{ display: "flex", gap: 3, background: "var(--th-bg)", borderRadius: 10, padding: 3 }}
    >
      {OPTIONS.map((g) => {
        const active = g === value;
        return (
          <button
            key={g}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(g)}
            style={{
              flex: 1,
              border: "none",
              borderRadius: 8,
              padding: "6px 4px",
              fontSize: 12,
              fontWeight: 600,
              cursor: "pointer",
              background: active ? "var(--surface)" : "transparent",
              color: active ? "var(--primary-dark)" : "var(--text-muted)",
              boxShadow: active ? "0 1px 2px rgba(0,0,0,0.08)" : "none",
            }}
          >
            {t(`granularity.${g.toLowerCase()}`)}
          </button>
        );
      })}
    </div>
  );
}
