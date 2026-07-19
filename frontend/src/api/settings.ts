import { api } from "../lib/apiClient";

/**
 * Tenant settings. Tax rates + treasury accounts are GLOBAL, SUPER_ADMIN-managed reference data,
 * surfaced here read-only (resolved for today); only the sender email is editable per-tenant.
 */
export interface GeneralSettings {
  vatRate: number | null;
  microRate: number | null;
  profitRate: number | null;
  senderEmail: string | null;
}

/** One treasury entry per fiscal residence, with a dedicated IBAN per tax category. Read-only. */
export interface TreasuryAccount {
  residence: string;
  validFrom: string; // ISO date
  ibanCam: string | null;
  ibanImpozite: string | null;
  ibanCass: string | null;
  ibanCas: string | null;
  ibanTva: string | null;
}

/** The five IBAN fields, keyed by category — column order: CAM, impozite, CASS, CAS, TVA. */
export type TreasuryIbans = Pick<
  TreasuryAccount,
  "ibanCam" | "ibanImpozite" | "ibanCass" | "ibanCas" | "ibanTva"
>;

export const settingsApi = {
  get: () => api<GeneralSettings>("/api/v1/settings"),
  updateSenderEmail: (senderEmail: string | null) =>
    api<GeneralSettings>("/api/v1/settings", {
      method: "PUT",
      body: JSON.stringify({ senderEmail }),
    }),
  listTreasury: () => api<TreasuryAccount[]>("/api/v1/settings/treasury-accounts"),
};
