package ro.myfinance.intake.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Storage config. type=local uses the filesystem; type=supabase uses Supabase Storage. */
@ConfigurationProperties(prefix = "myfinance.storage")
public record StorageProperties(String type, String localBaseDir, String supabaseBucket) {

    public StorageProperties {
        if (type == null || type.isBlank()) {
            type = "local";
        }
        if (localBaseDir == null || localBaseDir.isBlank()) {
            // Persist under the user's home, NOT java.io.tmpdir — macOS/Linux periodically purge temp,
            // which would silently delete uploaded documents between sessions.
            localBaseDir = System.getProperty("user.home") + "/.myfinance/docs";
        }
        if (supabaseBucket == null || supabaseBucket.isBlank()) {
            supabaseBucket = "documents";
        }
    }
}
