import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type CreateCompanyInput } from "../api/companies";
import { ApiError } from "../lib/apiClient";

export function AddCompanyModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<CreateCompanyInput>({ legalName: "", cui: "" });
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => companiesApi.create(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["companies"] });
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to create company"),
  });

  const set = (k: keyof CreateCompanyInput) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div style={overlay} onClick={onClose}>
      <form
        className="card"
        style={{ width: 460 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          mutation.mutate();
        }}
      >
        <h2 style={{ marginTop: 0 }}>Add company</h2>
        <Field label="Legal name *"><input required value={form.legalName} onChange={set("legalName")} /></Field>
        <Field label="CUI *"><input required value={form.cui} onChange={set("cui")} /></Field>
        <Field label="Entity type"><input value={form.entityType ?? ""} onChange={set("entityType")} placeholder="SRL" /></Field>
        <Field label="Locality"><input value={form.locality ?? ""} onChange={set("locality")} /></Field>
        <Field label="VAT status"><input value={form.vatStatus ?? ""} onChange={set("vatStatus")} placeholder="VAT_PAYER" /></Field>
        <Field label="VAT period"><input value={form.vatPeriod ?? ""} onChange={set("vatPeriod")} placeholder="MONTHLY" /></Field>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 12 }}>
          <button type="button" onClick={onClose}>Cancel</button>
          <button className="primary" type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Create"}
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "block", marginBottom: 10 }}>
      <span style={{ display: "block", color: "var(--text-muted)", fontSize: 13 }}>{label}</span>
      {children}
    </label>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(15,23,42,0.4)",
  display: "grid",
  placeItems: "center",
  zIndex: 50,
};
