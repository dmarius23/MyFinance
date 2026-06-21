import { api } from "../lib/apiClient";

export interface GeneralSettings {
  vatRate: number;
  microRate: number;
  profitRate: number;
  senderEmail: string | null;
}

/** One treasury entry per fiscal residence, with a dedicated IBAN per tax category. */
export interface TreasuryAccount {
  id: string;
  residence: string;
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
  updateRates: (rates: { vatRate: number; microRate: number; profitRate: number; senderEmail?: string | null }) =>
    api<GeneralSettings>("/api/v1/settings", {
      method: "PUT",
      body: JSON.stringify(rates),
    }),
  listTreasury: () => api<TreasuryAccount[]>("/api/v1/settings/treasury-accounts"),
  addTreasury: (input: { residence: string } & TreasuryIbans) =>
    api<TreasuryAccount>("/api/v1/settings/treasury-accounts", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  updateTreasury: (id: string, ibans: TreasuryIbans) =>
    api<TreasuryAccount>(`/api/v1/settings/treasury-accounts/${id}`, {
      method: "PUT",
      body: JSON.stringify(ibans),
    }),
  deleteTreasury: (id: string) =>
    api<void>(`/api/v1/settings/treasury-accounts/${id}`, { method: "DELETE" }),
};
