package ro.myfinance.dashboard.domain;

import java.util.List;
import java.util.UUID;

/**
 * MOD-11 read-model: the monthly companies overview — section summary tiles plus a per-company status
 * row (a status per section + responsible accountant + open requests + overdue). Pure aggregate, derived
 * from the per-module summaries; no persistence of its own.
 */
public record DashboardView(Tiles tiles, List<CompanyRow> rows) {

    /** Per-section completion for one company in the month. NA = the section doesn't apply (e.g. payroll without employees). */
    public enum Status { DONE, PARTIAL, NOTHING, NA }

    /** Company counts for one section. applicable = done + partial + nothing (NA excluded). */
    public record SectionTile(int done, int partial, int nothing) {
        public int applicable() {
            return done + partial + nothing;
        }
    }

    public record Tiles(SectionTile statements, SectionTile taxes, SectionTile payroll, SectionTile reports,
                        int newCompanies, int totalCompanies) {
    }

    public record CompanyRow(UUID companyId, String legalName, String cui,
                             UUID responsibleUserId, String responsibleName,
                             Status statements, Status taxes, Status payroll, Status reports,
                             int openRequests, int overdue) {
    }
}
