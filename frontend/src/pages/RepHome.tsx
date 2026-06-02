/** Representative PWA home (mobile-first). Camera capture, missing-docs checklist and latest
 * reports are implemented in the PWA build-order phase. */
export function RepHome() {
  return (
    <div style={{ maxWidth: 480, margin: "0 auto", padding: 16 }}>
      <h1 style={{ color: "var(--primary)" }}>MyFinance</h1>
      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ marginTop: 0 }}>Snap & upload a receipt</h2>
        <input type="file" accept="image/*" capture="environment" />
        <p style={{ color: "var(--text-muted)" }}>
          Camera capture → upload → extracting… (wired in the PWA phase, MOD-04/05).
        </p>
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0 }}>Missing documents</h2>
        <p style={{ color: "var(--text-muted)" }}>Checklist driven by reconciliation (MOD-04).</p>
      </div>
    </div>
  );
}
