package ro.myfinance.intake.domain;

/**
 * Where a tenant's document bytes live. Phase 1 implements {@link #SUPABASE_ONLY} and {@link #DRIVE_MIRROR};
 * {@link #DRIVE_PRIMARY} (Drive canonical, Supabase as cache) is Phase 2.
 */
public enum StorageMode {
    /** Bytes only in Supabase Storage (default; today's behaviour). */
    SUPABASE_ONLY,
    /** Supabase is canonical; a copy is pushed to the firm's Google Shared Drive. */
    DRIVE_MIRROR,
    /** Google Drive is canonical; Supabase is an evictable cache. (Phase 2.) */
    DRIVE_PRIMARY;

    /** True when this mode pushes a copy of each document to Google Drive. */
    public boolean writesToDrive() {
        return this == DRIVE_MIRROR || this == DRIVE_PRIMARY;
    }
}
