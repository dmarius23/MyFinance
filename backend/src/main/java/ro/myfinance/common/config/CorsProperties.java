package ro.myfinance.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allow-list, e.g. {@code myfinance.cors.allowed-origins=http://localhost:5173}.
 */
@ConfigurationProperties(prefix = "myfinance.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of("http://localhost:5173") : allowedOrigins;
    }
}
