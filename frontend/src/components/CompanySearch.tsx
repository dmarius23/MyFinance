import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

/**
 * Debounced company search box (by name or CUI) shared across the module lists. Calls {@code onSearch}
 * with the trimmed query after the user stops typing. {@code onSearch} should be stable (e.g. a
 * useState setter) so the debounce isn't reset on every parent render.
 */
export function CompanySearch({ onSearch, delay = 300 }: { onSearch: (q: string) => void; delay?: number }) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => onSearch(text.trim()), delay);
    return () => window.clearTimeout(timer.current);
  }, [text, delay, onSearch]);

  return (
    <div style={{ position: "relative" }}>
      <input
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={t("search.companyPlaceholder")}
        aria-label={t("search.companyPlaceholder")}
        style={{
          width: 220, padding: "6px 26px 6px 10px", fontSize: 13, borderRadius: 8,
          border: "1px solid var(--border)", background: "var(--input-bg, var(--bg))", color: "var(--text)",
        }}
      />
      {text && (
        <button onClick={() => setText("")} aria-label="Clear" style={{
          position: "absolute", right: 6, top: "50%", transform: "translateY(-50%)", background: "none",
          border: "none", cursor: "pointer", color: "var(--text-muted)", fontSize: 15, lineHeight: 1, padding: 2,
        }}>×</button>
      )}
    </div>
  );
}
