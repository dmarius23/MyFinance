package ro.myfinance.common.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Active only in the {@code worker} profile ({@link ro.myfinance.WorkerApplication}). The real
 * blocking consumers (one per {@link Job.JobType}) are added with the modules that own them
 * (MOD-04/05/07). For the foundation this just proves the worker process boots with the shared
 * context (DB, Redis, config) and no web server.
 */
@Component
@Profile("worker")
public class WorkerConsumers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerConsumers.class);

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        log.info("MyFinance worker started — queue consumers will be registered by MOD-04/05/07.");
        // TODO(MOD-04): start blocking BRPOP loops per JobType with backoff + DLQ; drain the outbox.
    }
}
