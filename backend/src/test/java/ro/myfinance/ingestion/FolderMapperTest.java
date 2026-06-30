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
}
