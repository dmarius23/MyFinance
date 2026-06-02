package ro.myfinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Web entrypoint — the always-on modular monolith serving the {@code /api/v1} surface.
 * Async work runs in a separate process; see {@link WorkerApplication}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MyFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyFinanceApplication.class, args);
    }
}
