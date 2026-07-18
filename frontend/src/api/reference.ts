import { api } from "../lib/apiClient";
import type { TreasuryIbans } from "./settings";

/** SUPER_ADMIN-managed GLOBAL reference data — national tax rates + treasury accounts. Effective-dated. */

export type TaxRateCategory = "VAT" | "MICRO" | "PROFIT";
export const TAX_RATE_CATEGORIES: TaxRateCategory[] = ["VAT", "MICRO", "PROFIT"];

export interface PlatformTaxRate {
  id: string;
  category: TaxRateCategory;
  rate: number;
  validFrom: string; // ISO date (yyyy-MM-dd)
}

export interface PlatformTreasuryAccount {
  id: string;
  residence: string;
  validFrom: string; // ISO date
  ibanCam: string | null;
  ibanImpozite: string | null;
  ibanCass: string | null;
  ibanCas: string | null;
  ibanTva: string | null;
}

export const referenceApi = {
  listRates: () => api<PlatformTaxRate[]>("/api/v1/admin/reference/tax-rates"),
  addRate: (input: { category: TaxRateCategory; rate: number; validFrom: string }) =>
    api<PlatformTaxRate>("/api/v1/admin/reference/tax-rates", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  deleteRate: (id: string) =>
    api<void>(`/api/v1/admin/reference/tax-rates/${id}`, { method: "DELETE" }),

  listTreasury: () => api<PlatformTreasuryAccount[]>("/api/v1/admin/reference/treasury-accounts"),
  addTreasury: (input: { residence: string; validFrom: string } & TreasuryIbans) =>
    api<PlatformTreasuryAccount>("/api/v1/admin/reference/treasury-accounts", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  updateTreasury: (id: string, ibans: TreasuryIbans) =>
    api<PlatformTreasuryAccount>(`/api/v1/admin/reference/treasury-accounts/${id}`, {
      method: "PUT",
      body: JSON.stringify(ibans),
    }),
  deleteTreasury: (id: string) =>
    api<void>(`/api/v1/admin/reference/treasury-accounts/${id}`, { method: "DELETE" }),
};
