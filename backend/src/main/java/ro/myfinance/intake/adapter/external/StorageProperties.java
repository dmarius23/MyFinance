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
            localBaseDir = System.getProperty("java.io.tmpdir") + "/myfinance-docs";
        }
        if (supabaseBucket == null || supabaseBucket.isBlank()) {
            supabaseBucket = "documents";
        }
    }
}
