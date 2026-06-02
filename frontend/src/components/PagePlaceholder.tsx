/** Stub page used by routes whose UI lands in later build-order phases. */
export function PagePlaceholder({ title, module }: { title: string; module: string }) {
  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>{title}</h1>
      <p style={{ color: "var(--text-muted)" }}>
        Scaffolded route. The interactive UI for this screen is implemented in {module} — see the
        frontend build order and the clickable prototype.
      </p>
    </div>
  );
}
