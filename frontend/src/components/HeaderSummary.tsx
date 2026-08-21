/**
 * Compact "to resolve this month" summary shown inline in a module header, next to the Sync button. Each
 * item renders as "<n> <label>" (e.g. "4 D100 lipsă"); zero-count items are dropped and the remaining
 * ones are separated by a colored dot. Renders nothing when there is nothing outstanding.
 */
export function HeaderSummary({ items }: { items: { n: number; label: string }[] }) {
  const shown = items.filter((i) => i.n > 0);
  if (shown.length === 0) return null;
  return (
    <div style={wrap}>
      {shown.map((it, i) => (
        <span key={i} style={{ display: "inline-flex", alignItems: "center", gap: 8, whiteSpace: "nowrap" }}>
          {i > 0 && <span aria-hidden style={sep}>•</span>}
          <span><b style={{ color: "var(--text)" }}>{it.n}</b> {it.label}</span>
        </span>
      ))}
    </div>
  );
}

const wrap: React.CSSProperties = {
  display: "flex", alignItems: "center", flexWrap: "wrap", gap: 8,
  fontSize: 12, color: "var(--text-secondary)", justifyContent: "flex-end",
};
const sep: React.CSSProperties = { color: "var(--dot-red)", fontWeight: 900, fontSize: 14, lineHeight: 1 };
