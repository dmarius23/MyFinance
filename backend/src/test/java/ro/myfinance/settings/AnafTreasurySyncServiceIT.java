package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.adapter.persistence.TreasurySyncRunRepository;
import ro.myfinance.settings.application.AnafIbanSource;
import ro.myfinance.settings.application.AnafTreasurySyncService;
import ro.myfinance.settings.application.TreasuryIbans;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.SyncChange;
import ro.myfinance.settings.domain.SyncRunStatus;
import ro.myfinance.settings.domain.TreasurySyncItem;
import ro.myfinance.settings.domain.TreasurySyncRun;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * End-to-end sync over a real Postgres with a fake {@link AnafIbanSource} (no network): create run ->
 * execute (crawl + diff + stage) -> apply (write effective-dated rows). Asserts the field mapping
 * (5503 -> impozite/CASS/CAS, TVA intern/extern separate), the diff verdicts, and idempotent re-sync.
 * The staging tables are global (no RLS), so there is no cross-tenant test here — intentional (V39).
 */
class AnafTreasurySyncServiceIT extends AbstractPostgresIT {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeAnafIbanSource fakeAnafIbanSource() {
            return new FakeAnafIbanSource();
        }
    }

    /** A controllable stand-in for the HTTP scraper. */
    static class FakeAnafIbanSource implements AnafIbanSource {
        volatile List<TreasuryIbans> results = List.of();

        @Override
        public List<TreasuryIbans> fetchAll() {
            return results;
        }
    }

    @Autowired AnafTreasurySyncService sync;
    @Autowired FakeAnafIbanSource fakeSource;
    @Autowired PlatformTreasuryAccountRepository treasuryRepo;
    @Autowired TreasurySyncRunRepository runRepo;

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 7, 1);
    private final List<UUID> createdRuns = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdRuns.forEach(runRepo::deleteById); // FK cascade removes items
        createdRuns.clear();
        treasuryRepo.findAll().stream()
                .filter(a -> a.getResidence() != null && a.getResidence().startsWith("SyncTest-"))
                .forEach(a -> treasuryRepo.deleteById(a.getId()));
    }

    private TreasuryIbans treasury(String residence) {
        return new TreasuryIbans("Alba", "TREZ002", residence, "https://anaf/iban_TREZ001_TREZ002.pdf",
                "RO31TREZ0025503XXXXXXXXX", "RO02TREZ00220A470300XXXX",
                "RO77TREZ00220A100101XTVA", "RO24TREZ00220A100102XTVA", null);
    }

    private TreasurySyncRun runOnce(List<TreasuryIbans> scraped) {
        fakeSource.results = scraped;
        TreasurySyncRun run = sync.createRun(null, EFFECTIVE);
        createdRuns.add(run.getId());
        sync.execute(run.getId());
        return sync.get(run.getId());
    }

    @Test
    void addsThenAppliesTreasuryRowsWithTheCorrectFieldMapping() {
        String residence = "SyncTest-" + UUID.randomUUID();
        TreasurySyncRun run = runOnce(List.of(treasury(residence)));

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.READY_FOR_REVIEW);
        assertThat(run.getTreasuriesTotal()).isEqualTo(1);
        assertThat(run.getParseFailed()).isZero();

        List<TreasurySyncItem> items = sync.itemsFor(run.getId());
        assertThat(items).singleElement()
                .satisfies(i -> assertThat(i.getChange()).isEqualTo(SyncChange.ADDED));

        sync.apply(run.getId());
        assertThat(sync.get(run.getId()).getStatus()).isEqualTo(SyncRunStatus.APPLIED);

        PlatformTreasuryAccount acc = treasuryRepo.findByResidenceAndValidFrom(residence, EFFECTIVE).orElseThrow();
        assertThat(acc.getIbanImpozite()).isEqualTo("RO31TREZ0025503XXXXXXXXX");
        assertThat(acc.getIbanCass()).isEqualTo("RO31TREZ0025503XXXXXXXXX");
        assertThat(acc.getIbanCas()).isEqualTo("RO31TREZ0025503XXXXXXXXX");
        assertThat(acc.getIbanCam()).isEqualTo("RO02TREZ00220A470300XXXX");
        assertThat(acc.getIbanTva()).isEqualTo("RO77TREZ00220A100101XTVA");
        assertThat(acc.getIbanTvaExtern()).isEqualTo("RO24TREZ00220A100102XTVA");
    }

    @Test
    void reSyncAfterApplyReportsUnchanged() {
        String residence = "SyncTest-" + UUID.randomUUID();
        TreasurySyncRun first = runOnce(List.of(treasury(residence)));
        sync.apply(first.getId());

        TreasurySyncRun second = runOnce(List.of(treasury(residence)));
        assertThat(sync.itemsFor(second.getId())).singleElement()
                .satisfies(i -> assertThat(i.getChange()).isEqualTo(SyncChange.UNCHANGED));
    }

    @Test
    void scraperErrorsBecomeErrorItemsAndDoNotAbortTheRun() {
        String residence = "SyncTest-" + UUID.randomUUID();
        TreasuryIbans failed = TreasuryIbans.error("Cluj", "TREZ115",
                "https://anaf/iban_TREZ115_TREZ116.pdf", "HTTP 500");
        TreasurySyncRun run = runOnce(List.of(treasury(residence), failed));

        assertThat(run.getStatus()).isEqualTo(SyncRunStatus.READY_FOR_REVIEW);
        assertThat(run.getTreasuriesTotal()).isEqualTo(2);
        assertThat(run.getParseFailed()).isEqualTo(1);
        assertThat(sync.itemsFor(run.getId()))
                .anySatisfy(i -> assertThat(i.getChange()).isEqualTo(SyncChange.ERROR))
                .anySatisfy(i -> assertThat(i.getChange()).isEqualTo(SyncChange.ADDED));
    }
}
