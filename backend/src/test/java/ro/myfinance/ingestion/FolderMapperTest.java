package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.company.domain.Company;
import ro.myfinance.ingestion.application.CloudFolderConnector.RemoteFile;
import ro.myfinance.ingestion.application.FolderMapper;

class FolderMapperTest {

    private Company company(UUID id, String name, String cui) {
        Company c = mock(Company.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getLegalName()).thenReturn(name);
        lenient().when(c.getCui()).thenReturn(cui);
        return c;
    }

    private RemoteFile file(String path) {
        return new RemoteFile("f1", "stat_salarii.pdf", path, "application/pdf", 100, "e1",
                Instant.parse("2026-06-10T00:00:00Z"));
    }

    private RemoteFile file(String path, String name) {
        return new RemoteFile("f1", name, path, "application/pdf", 100, "e1",
                Instant.parse("2026-06-10T00:00:00Z"));
    }

    @Test
    void matchesCompanyByCuiInFolder() {
        UUID id = UUID.randomUUID();
        List<Company> cos = List.of(company(id, "INNOVATECODE IT SRL", "49443957"),
                company(UUID.randomUUID(), "Lumina Verde SRL", "39112764"));
        assertThat(FolderMapper.resolveCompany(file("49443957/2026-05"), cos)).contains(id);
    }

    @Test
    void matchesCompanyByNameInFolder() {
        UUID id = UUID.randomUUID();
        List<Company> cos = List.of(company(id, "INNOVATECODE IT SRL", "49443957"));
        assertThat(FolderMapper.resolveCompany(file("INNOVATECODE IT SRL/2026-05"), cos)).contains(id);
    }

    @Test
    void returnsEmptyWhenNoCompanyMatches() {
        List<Company> cos = List.of(company(UUID.randomUUID(), "INNOVATECODE IT SRL", "49443957"));
        assertThat(FolderMapper.resolveCompany(file("Random Folder/2026-05"), cos)).isEmpty();
    }

    @Test
    void parsesPeriodFromMonthFolder() {
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE/2026-05"))).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE/2026_03"))).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void parsesSeparateYearAndNumberedMonthFolders() {
        // Real layout: <company>/<year>/<MM Monthname>/file
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE IT SRL/2026/04 Aprilie")))
                .isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void parsesRomanianMonthName() {
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE IT SRL/2026/Decembrie")))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void doesNotMistakeAYearFolderForAMonth() {
        // "2026" alone must not be read as month 2 (February); with no month → fall back to modified month.
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE IT SRL/2026"))).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void fallsBackToModifiedMonthWhenNoMonthFolder() {
        assertThat(FolderMapper.resolvePeriod(file("INNOVATECODE"))).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void resolvesTypeFromSubfolder() {
        // company / year / month / type — the type folder is deepest.
        assertThat(FolderMapper.resolveType(file("INNOVATECODE IT SRL/2026/06/payrolls")))
                .contains(ro.myfinance.intake.domain.DocumentType.PAYROLL);
        assertThat(FolderMapper.resolveType(file("INNOVATECODE IT SRL/2026/06/declarations")))
                .contains(ro.myfinance.intake.domain.DocumentType.DECLARATION);
        assertThat(FolderMapper.resolveType(file("INNOVATECODE IT SRL/2026/06/reports")))
                .contains(ro.myfinance.intake.domain.DocumentType.TRIAL_BALANCE);
        assertThat(FolderMapper.resolveType(file("INNOVATECODE IT SRL/2026/06/invoices")))
                .contains(ro.myfinance.intake.domain.DocumentType.INVOICE);
    }

    @Test
    void typeResolutionIgnoresNonTypeSegmentsAndRoundTripsWithWriter() {
        // No type folder → empty (classifier decides).
        assertThat(FolderMapper.resolveType(file("INNOVATECODE IT SRL/2026/06"))).isEmpty();
        // The writer's folder name reads back as the same type (round-trip) for every type.
        for (ro.myfinance.intake.domain.DocumentType t : ro.myfinance.intake.domain.DocumentType.values()) {
            String folder = ro.myfinance.intake.domain.DriveDocLayout.typeFolder(t);
            assertThat(FolderMapper.resolveType(file("Firma SRL/2026/06/" + folder)))
                    .as("round-trip for %s (folder=%s)", t, folder).contains(t);
        }
    }

    // ---- month-first layout (accounting-firm Drive: declarations by CUI, payroll by name in filename) ----

    @Test
    void matchesCompanyByCuiInDeclarationFilename() {
        UUID id = UUID.randomUUID();
        List<Company> cos = List.of(company(id, "MORARU TECH SRL", "44570402"),
                company(UUID.randomUUID(), "Lumina Verde SRL", "39112764"));
        // Declaration filed straight in a month folder — the company is the CUI in the filename.
        assertThat(FolderMapper.resolveCompany(
                file("7. Iulie 2026/D700", "D700_44570402_2026_07 - trecere impozit profit.pdf"), cos))
                .contains(id);
    }

    @Test
    void matchesCompanyByNameInPayrollFilename() {
        UUID id = UUID.randomUUID();
        List<Company> cos = List.of(company(id, "STONEAGE INDUSTRY SRL", "12345678"));
        // Payroll files carry the company NAME (no CUI) in the filename.
        assertThat(FolderMapper.resolveCompany(
                file("7. Iulie 2026/State de plata", "fluturas#_STONEAGE INDUSTRY SRL_2026_07.pdf"), cos))
                .contains(id);
    }

    @Test
    void resolvesPeriodFromNumberedRomanianMonthFolder() {
        assertThat(FolderMapper.resolvePeriod(file("7. Iulie 2026/State de plata", "x.pdf")))
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(FolderMapper.resolvePeriod(file("1. Ianuarie 2026/D 112", "x.pdf")))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void resolvesTrimesterPeriodToQuarterEndMonth() {
        // Interim balance: T1→Mar, T2→Jun, T3→Sep, T4→Dec.
        assertThat(FolderMapper.resolvePeriod(
                file("Bilant interimar T1 an 2026/STONEAGE INDUSTRY SRL/balanta de verificare 2026", "b.pdf")))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(FolderMapper.resolvePeriod(
                file("Bilant interimar T2 an 2026/STONEAGE INDUSTRY SRL/balanta de verificare 2026", "b.pdf")))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void resolvesTypeForDeclarationFoldersPayrollFilenamesAndBalance() {
        // Declaration form-code folders (with/without spaces).
        assertThat(FolderMapper.resolveType(file("7. Iulie 2026/D700", "x.pdf")))
                .contains(ro.myfinance.intake.domain.DocumentType.DECLARATION);
        assertThat(FolderMapper.resolveType(file("7. Iulie 2026/D 112", "x.pdf")))
                .contains(ro.myfinance.intake.domain.DocumentType.DECLARATION);
        assertThat(FolderMapper.resolveType(file("7. Iulie 2026/D100 Chirie", "x.pdf")))
                .contains(ro.myfinance.intake.domain.DocumentType.DECLARATION);
        // Payroll folder resolves by folder name (statedeplata alias). Loose-filename typing is covered
        // separately in DriveDocLayoutTest (resolveType is folder-only; the service adds filename typing).
        assertThat(FolderMapper.resolveType(file("7. Iulie 2026/State de plata", "fluturas#_X SRL_2026_07.pdf")))
                .contains(ro.myfinance.intake.domain.DocumentType.PAYROLL);
        // Trial balance from a descriptive "balanta de verificare" folder.
        assertThat(FolderMapper.resolveType(
                file("Bilant interimar T2 an 2026/STONEAGE INDUSTRY SRL/balanta de verificare 2026", "b.pdf")))
                .contains(ro.myfinance.intake.domain.DocumentType.TRIAL_BALANCE);
    }
}
