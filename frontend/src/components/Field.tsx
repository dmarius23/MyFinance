import type { ReactNode } from "react";

/** Labeled form field wrapper shared by the company add/edit forms. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label style={{ display: "block", marginBottom: 10 }}>
      <span style={{ display: "block", color: "var(--text-muted)", fontSize: 13 }}>{label}</span>
      {children}
    </label>
  );
}
