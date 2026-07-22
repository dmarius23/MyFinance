package ro.myfinance.settings.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.settings.domain.SyncChange;
import ro.myfinance.settings.domain.SyncRunStatus;
import ro.myfinance.settings.domain.TreasurySyncItem;
import ro.myfinance.settings.domain.TreasurySyncRun;

/** DTOs for the SUPER_ADMIN ANAF treasury-IBAN sync API. */
public final class AnafSyncDtos {

    private AnafSyncDtos() {
    }

    /** Optional body for starting a sync; {@code effectiveFrom} defaults to today when omitted. */
    public record StartSyncRequest(LocalDate effectiveFrom) {
    }

    public record SyncRunResponse(UUID id, SyncRunStatus status, LocalDate effectiveFrom, int countiesTotal,
                                  int treasuriesTotal, int parsedOk, int parseFailed, String notes,
                                  Instant startedAt, Instant finishedAt, Instant appliedAt) {
        public static SyncRunResponse from(TreasurySyncRun r) {
            return new SyncRunResponse(r.getId(), r.getStatus(), r.getEffectiveFrom(), r.getCountiesTotal(),
                    r.getTreasuriesTotal(), r.getParsedOk(), r.getParseFailed(), r.getNotes(),
                    r.getStartedAt(), r.getFinishedAt(), r.getAppliedAt());
        }
    }

    public record SyncItemResponse(UUID id, String county, String treasuryCode, String residence,
                                   String sourceUrl, String iban5503, String ibanCam, String ibanTvaIntern,
                                   String ibanTvaExtern, SyncChange change, String error) {
        public static SyncItemResponse from(TreasurySyncItem i) {
            return new SyncItemResponse(i.getId(), i.getCounty(), i.getTreasuryCode(), i.getResidence(),
                    i.getSourceUrl(), i.getIban5503(), i.getIbanCam(), i.getIbanTvaIntern(),
                    i.getIbanTvaExtern(), i.getChange(), i.getError());
        }
    }
}
