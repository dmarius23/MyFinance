/**
 * Amber inline banner that names why a company can't be fully contacted before sending — a missing
 * recipient email, phone number, or (for tax emails) an unresolved treasury IBAN. Renders nothing when
 * there are no problems, so callers can drop it in unconditionally.
 */
export function MissingInfoWarning({ problems }: { problems: string[] }) {
  if (problems.length === 0) return null;
  return (
    <div style={box} role="alert">
      <span aria-hidden style={{ fontSize: 14, lineHeight: 1 }}>⚠</span>
      <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {problems.map((p, i) => <span key={i}>{p}</span>)}
      </div>
    </div>
  );
}

const box: React.CSSProperties = {
  display: "flex", gap: 8, alignItems: "flex-start",
  background: "#fef6e7", border: "1px solid #f3cd86", color: "#8a4d0a",
  borderRadius: 8, padding: "7px 10px", fontSize: 12, marginBottom: 8, lineHeight: 1.35,
};
