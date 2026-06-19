import { api } from "../lib/apiClient";

export interface GeneralSettings {
  vatRate: number;
  microRate: number;
  profitRate: number;
}

export interface CountyTreasuryAccount {
  id: string;
  county: string;
  taxType: string;
  iban: string;
  label: string | null;
}

export const settingsApi = {
  get: () => api<GeneralSettings>("/api/v1/settings"),
  updateRates: (rates: { vatRate: number; microRate: number; profitRate: number }) =>
    api<GeneralSettings>("/api/v1/settings", {
      method: "PUT",
      body: JSON.stringify(rates),
    }),
  listTreasury: () => api<CountyTreasuryAccount[]>("/api/v1/settings/treasury-accounts"),
  addTreasury: (input: { county: string; taxType: string; iban: string; label?: string }) =>
    api<CountyTreasuryAccount>("/api/v1/settings/treasury-accounts", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  deleteTreasury: (id: string) =>
    api<void>(`/api/v1/settings/treasury-accounts/${id}`, { method: "DELETE" }),
};
