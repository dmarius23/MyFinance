/**
 * A single column-aligned "missing / to-do" count chip for the summary strip that sits on top of a
 * monthly-hub table. Renders nothing when the count is zero, so it can be dropped into any column cell
 * unconditionally. The descriptive text lives in the tooltip; meaning also comes from the column it sits
 * above.
 */
export function MissingCount({ n, label }: { n: number; label: string }) {
  if (n <= 0) return null;
  return (
    <span className="pill round danger" title={`${n} ${label}`}
      style={{ fontSize: 10.5, fontWeight: 700, whiteSpace: "nowrap" }}>
      {n}
    </span>
  );
}

/** Shared styling for the on-top-of-table summary strip (same grid template as the table rows). */
export const summaryStrip: React.CSSProperties = {
  background: "var(--row-active)", borderBottom: "1px solid var(--hair)", padding: "7px 16px",
};
export const summaryLabel: React.CSSProperties = {
  fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)",
};
