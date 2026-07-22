package ro.myfinance.common.async;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async execution for the post-upload document pipeline (extraction, reconciliation, report/declaration
 * ingest, Drive mirror). These run on {@link #DOCUMENT_PIPELINE} off the request thread, after the upload
 * transaction commits, so the HTTP response returns immediately.
 *
 * <p>{@code myfinance.async.inline=true} swaps in a synchronous executor — used in tests so the pipeline
 * runs inline (deterministic) while still executing in its own post-commit transaction.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Bean name of the document-pipeline executor referenced by {@code @Async}. */
    public static final String DOCUMENT_PIPELINE = "documentPipelineExecutor";

    @Bean(name = DOCUMENT_PIPELINE)
    public Executor documentPipelineExecutor(
            @Value("${myfinance.async.inline:false}") boolean inline) {
        if (inline) {
            return new SyncTaskExecutor(); // tests: run inline, still in a fresh post-commit transaction
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-pipeline-");
        executor.setTaskDecorator(new TenantContextTaskDecorator());
        // Graceful shutdown: let in-flight document work finish so nothing is lost on deploy.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
