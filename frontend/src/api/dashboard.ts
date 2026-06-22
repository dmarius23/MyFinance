import { api } from "../lib/apiClient";

export type SectionStatus = "DONE" | "PARTIAL" | "NOTHING" | "NA";
export type DashboardStatusFilter = "ALL" | "ATTENTION" | "COMPLETE";

export interface SectionTile {
  done: number;
  partial: number;
  nothing: number;
}

export interface DashboardTiles {
  statements: SectionTile;
  taxes: SectionTile;
  payroll: SectionTile;
  reports: SectionTile;
  newCompanies: number;
  totalCompanies: number;
}

export interface DashboardRow {
  companyId: string;
  legalName: string;
  cui: string;
  responsibleUserId: string | null;
  responsibleName: string | null;
  statements: SectionStatus;
  taxes: SectionStatus;
  payroll: SectionStatus;
  reports: SectionStatus;
  openRequests: number;
  overdue: number;
}

export interface Dashboard {
  tiles: DashboardTiles;
  rows: DashboardRow[];
}

export const dashboardApi = {
  get: (period: string, opts?: { responsible?: string; status?: DashboardStatusFilter }) => {
    const p = new URLSearchParams({ period });
    if (opts?.responsible) p.set("responsible", opts.responsible);
    if (opts?.status && opts.status !== "ALL") p.set("status", opts.status);
    return api<Dashboard>(`/api/v1/dashboard?${p.toString()}`);
  },
};
