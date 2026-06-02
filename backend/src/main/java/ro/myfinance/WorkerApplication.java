package ro.myfinance;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Worker entrypoint — same codebase, no web server. Consumes the job queue
 * (extract-document, reconcile-period, send-email, …) and drains the outbox.
 *
 * <p>Reuses {@link MyFinanceApplication}'s configuration (so there is a single
 * {@code @SpringBootConfiguration}) but disables the web server and activates the
 * {@code worker} profile, under which the queue consumers come up.
 *
 * <p>Run in dev: {@code mvn spring-boot:run -Dspring-boot.run.main-class=ro.myfinance.WorkerApplication}.
 */
public class WorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(MyFinanceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("worker")
                .run(args);
    }
}
