package ro.myfinance.common.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Guards the S10 removal of per-endpoint {@code @DateTimeFormat(iso = ISO.DATE)}: proves the app's
 * {@code spring.mvc.format.date=iso} default binds a bare {@code @RequestParam LocalDate} from an ISO
 * string and still rejects a non-ISO value — through the real Boot MVC conversion service, not a stub.
 */
@AutoConfigureMockMvc
class PeriodDateBindingIT extends AbstractPostgresIT {

    @TestConfiguration
    static class ProbeConfig {
        /** Auto-registered as a nested bean of this @TestConfiguration (do not also declare a @Bean). */
        @RestController
        static class PeriodProbeController {
            /** Deliberately NO @DateTimeFormat — binding must come from the global ISO date default. */
            @GetMapping("/test/period-binding")
            String echo(@RequestParam("period") LocalDate period) {
                return period.toString();
            }
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void bindsIsoDateWithoutPerFieldFormatAnnotation() throws Exception {
        mvc.perform(get("/test/period-binding").param("period", "2026-08-15").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string("2026-08-15"));
    }

    @Test
    void rejectsNonIsoDate() throws Exception {
        mvc.perform(get("/test/period-binding").param("period", "15/08/2026").with(jwt()))
                .andExpect(status().isBadRequest());
    }
}
