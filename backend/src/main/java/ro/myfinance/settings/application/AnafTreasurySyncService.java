package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.adapter.persistence.TreasurySyncItemRepository;
import ro.myfinance.settings.adapter.persistence.TreasurySyncRunRepository;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.SyncChange;
import ro.myfinance.settings.domain.SyncRunStatus;
import ro.myfinance.settings.domain.TreasurySyncItem;
import ro.myfinance.settings.domain.TreasurySyncRun;

/**
 * MOD-07 reference-data sync: drive an {@link AnafIbanSource} crawl, diff each treasury against the live
 * {@code platform_treasury_account} rows, stage the result as a reviewable {@link TreasurySyncRun}, and —
 * after SUPER_ADMIN approval — apply the ADDED/CHANGED rows (appended effective-dated). The crawl runs
 * outside any DB transaction (it is minutes long); only the persistence steps are transactional.
 */
@Service
public class AnafTreasurySyncService {

    private static final Logger log = LoggerFactory.getLogger(AnafTreasurySyncService.class);
    private static final List<SyncChange> APPLICABLE = List.of(SyncChange.ADDED, SyncChange.CHANGED);

    private final AnafIbanSource source;
    private final PlatformTreasuryAccountRepository liveAccounts;
    private final PlatformReferenceAdminService admin;
    private final TreasurySyncRunRepository runs;
    private final TreasurySyncItemRepository items;
    private final Executor executor;

    public AnafTreasurySyncService(AnafIbanSource source, PlatformTreasuryAccountRepository liveAccounts,
                                   PlatformReferenceAdminService admin, TreasurySyncRunRepository runs,
                                   TreasurySyncItemRepository items,
                                   @Qualifier(AsyncConfig.ANAF_SYNC) Executor executor) {
        this.source = source;
        this.liveAccounts = liveAccounts;
        this.admin = admin;
        this.runs = runs;
        this.items = items;
        this.executor = executor;
    }

    /**
     * Kick off a sync: persist the RUNNING run (returned immediately) and dispatch the crawl off the request
     * thread. In tests ({@code myfinance.async.inline=true}) the crawl runs synchronously before this returns.
     */
    public TreasurySyncRun startSync(UUID startedBy, LocalDate effectiveFrom) {
        UUID runId = createRun(startedBy, effectiveFrom).getId();
        executor.execute(() -> execute(runId));
        // Re-read so the caller sees the freshest state: RUNNING when the crawl is still on a background
        // thread (async prod), or the finished state when it ran inline (tests).
        return get(runId);
    }

    /** Create the RUNNING run record (fast, in the request thread); the crawl itself runs via {@link #execute}. */
    @Transactional
    public TreasurySyncRun createRun(UUID startedBy, LocalDate effectiveFrom) {
        LocalDate effective = effectiveFrom != null ? effectiveFrom : LocalDate.now();
        return runs.save(new TreasurySyncRun(startedBy, effective));
    }

    /**
     * Crawl ANAF, diff every treasury against the live table, persist the items and open the run for review.
     * Never throws for a single county/PDF failure (those are ERROR items); only an unrecoverable crawl
     * error (e.g. index unreachable) marks the run FAILED. Intended to run in the worker.
     */
    public void execute(UUID runId) {
        TreasurySyncRun run = run(runId);
        if (run.getStatus() != SyncRunStatus.RUNNING) {
            log.warn("ANAF sync run {} is {}, not RUNNING — skipping execute", runId, run.getStatus());
            return;
        }
        List<TreasuryIbans> scraped;
        try {
            scraped = source.fetchAll();
        } catch (RuntimeException e) {
            log.error("ANAF sync run {} failed to crawl: {}", runId, e.getMessage());
            fail(runId, e.getMessage());
            return;
        }
        persistResults(runId, run.getEffectiveFrom(), scraped);
    }

    // Called via self-invocation from execute(), so @Transactional would not apply — persist explicitly instead.
    private void persistResults(UUID runId, LocalDate effectiveFrom, List<TreasuryIbans> scraped) {
        TreasurySyncRun run = run(runId);
        List<TreasurySyncItem> toSave = new ArrayList<>();
        java.util.Set<String> counties = new java.util.HashSet<>();
        int failed = 0;
        for (TreasuryIbans s : scraped) {
            if (s.county() != null) {
                counties.add(s.county());
            }
            if (!s.ok()) {
                toSave.add(TreasurySyncItem.error(runId, s.county(), s.treasuryCode(), s.sourceUrl(), s.error()));
                failed++;
            } else if (s.residence() == null || s.residence().isBlank()) {
                toSave.add(TreasurySyncItem.error(runId, s.county(), s.treasuryCode(), s.sourceUrl(),
                        "No residence parsed from the PDF header"));
                failed++;
            } else {
                PlatformTreasuryAccount live = liveAccounts
                        .findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc(s.residence(), effectiveFrom)
                        .orElse(null);
                toSave.add(new TreasurySyncItem(runId, s.county(), s.treasuryCode(), s.residence(), s.sourceUrl(),
                        s.iban5503(), s.ibanCam(), s.ibanTvaIntern(), s.ibanTvaExtern(), classify(s, live)));
            }
        }
        items.saveAll(toSave);
        run.markReadyForReview(counties.size(), scraped.size(), scraped.size() - failed, failed);
        runs.save(run);
        log.info("ANAF sync run {}: {} treasuries, {} parse failures, ready for review",
                runId, scraped.size(), failed);
    }

    /**
     * Apply the approved diff: append (or idempotently re-set) an effective-dated treasury row for every
     * ADDED/CHANGED item. The single 5503 IBAN fills impozite/CASS/CAS (cont unic); TVA splits intern/extern.
     */
    @Transactional
    public TreasurySyncRun apply(UUID runId) {
        TreasurySyncRun run = run(runId);
        if (run.getStatus() != SyncRunStatus.READY_FOR_REVIEW) {
            throw new ConflictException("Run " + runId + " is " + run.getStatus() + ", not READY_FOR_REVIEW");
        }
        List<TreasurySyncItem> applicable =
                items.findByRunIdAndChangeInOrderByCountyAscResidenceAsc(runId, APPLICABLE);
        for (TreasurySyncItem item : applicable) {
            admin.upsertTreasuryAccount(item.getResidence(), run.getEffectiveFrom(),
                    item.getIbanCam(), item.getIban5503(), item.getIban5503(), item.getIban5503(),
                    item.getIbanTvaIntern(), item.getIbanTvaExtern());
        }
        run.markApplied();
        log.info("ANAF sync run {} applied: {} treasury rows written", runId, applicable.size());
        return run;
    }

    @Transactional
    public TreasurySyncRun cancel(UUID runId) {
        TreasurySyncRun run = run(runId);
        if (run.getStatus() == SyncRunStatus.APPLIED) {
            throw new ConflictException("Run " + runId + " is already APPLIED");
        }
        run.markCancelled();
        return run;
    }

    // Self-invoked from execute() — persist explicitly (see persistResults).
    private void fail(UUID runId, String reason) {
        TreasurySyncRun run = run(runId);
        run.markFailed(reason);
        runs.save(run);
    }

    @Transactional(readOnly = true)
    public TreasurySyncRun get(UUID runId) {
        return run(runId);
    }

    @Transactional(readOnly = true)
    public List<TreasurySyncRun> listRuns() {
        return runs.findAllByOrderByStartedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<TreasurySyncItem> itemsFor(UUID runId) {
        return items.findByRunIdOrderByCountyAscResidenceAsc(runId);
    }

    /** Items filtered to the given change verdicts (e.g. the reviewable ADDED+CHANGED subset). */
    @Transactional(readOnly = true)
    public List<TreasurySyncItem> itemsFor(UUID runId, Collection<SyncChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return itemsFor(runId);
        }
        return items.findByRunIdAndChangeInOrderByCountyAscResidenceAsc(runId, changes);
    }

    /** How a scraped treasury compares to its live row — pure, so it is unit-tested directly. */
    static SyncChange classify(TreasuryIbans scraped, PlatformTreasuryAccount live) {
        if (live == null) {
            return SyncChange.ADDED;
        }
        boolean same = eq(live.getIbanImpozite(), scraped.iban5503())
                && eq(live.getIbanCass(), scraped.iban5503())
                && eq(live.getIbanCas(), scraped.iban5503())
                && eq(live.getIbanCam(), scraped.ibanCam())
                && eq(live.getIbanTva(), scraped.ibanTvaIntern())
                && eq(live.getIbanTvaExtern(), scraped.ibanTvaExtern());
        return same ? SyncChange.UNCHANGED : SyncChange.CHANGED;
    }

    private static boolean eq(String live, String scraped) {
        return Objects.equals(blankToNull(live), blankToNull(scraped));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private TreasurySyncRun run(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("Sync run not found: " + runId));
    }
}
