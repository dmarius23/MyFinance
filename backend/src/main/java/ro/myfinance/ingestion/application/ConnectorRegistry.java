package ro.myfinance.ingestion.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ro.myfinance.common.web.NotFoundException;

/** Resolves the {@link CloudFolderConnector} for a provider from the connectors wired in the context. */
@Component
public class ConnectorRegistry {

    private final Map<String, CloudFolderConnector> byProvider;

    public ConnectorRegistry(List<CloudFolderConnector> connectors) {
        this.byProvider = connectors.stream()
                .collect(Collectors.toMap(c -> c.provider().toUpperCase(), Function.identity(), (a, b) -> a));
    }

    public CloudFolderConnector forProvider(String provider) {
        CloudFolderConnector c = provider == null ? null : byProvider.get(provider.toUpperCase());
        if (c == null) {
            throw new NotFoundException("No connector available for provider " + provider
                    + " (configured: " + byProvider.keySet() + ")");
        }
        return c;
    }

    public java.util.Set<String> available() {
        return byProvider.keySet();
    }
}
