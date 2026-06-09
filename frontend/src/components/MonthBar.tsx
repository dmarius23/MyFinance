/** Prev/next month navigator. `value` is yyyy-MM-01; calls onChange with the new yyyy-MM-01. */
export function MonthBar({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const d = new Date(value);
  const label = d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const shift = (delta: number) => {
    const nd = new Date(d.getFullYear(), d.getMonth() + delta, 1);
    onChange(`${nd.getFullYear()}-${String(nd.getMonth() + 1).padStart(2, "0")}-01`);
  };
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      <button onClick={() => shift(-1)} aria-label="Previous month">◀</button>
      <span style={{ minWidth: 140, textAlign: "center", fontWeight: 600 }}>{label}</span>
      <button onClick={() => shift(1)} aria-label="Next month">▶</button>
    </div>
  );
}
