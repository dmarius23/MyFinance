package ro.myfinance.intake.adapter.external;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.intake.application.DocumentStorage;

/**
 * Selects the storage adapter from config. type=supabase (and Supabase configured) → Supabase Storage;
 * otherwise local filesystem. Explicit factory (not @ConditionalOnProperty) so a blank/absent value
 * falls back cleanly to local.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class DocumentStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageConfig.class);

    @Bean
    DocumentStorage documentStorage(StorageProperties props, SupabaseProperties supabase,
                                    RestClient.Builder builder) {
        if ("supabase".equalsIgnoreCase(props.type()) && supabase.isConfigured()) {
            log.info("Using Supabase Storage (bucket={})", props.supabaseBucket());
            return new SupabaseDocumentStorage(supabase, props.supabaseBucket(), builder);
        }
        log.info("Using local filesystem document storage (baseDir={})", props.localBaseDir());
        return new LocalFsDocumentStorage(Path.of(props.localBaseDir()));
    }
}
