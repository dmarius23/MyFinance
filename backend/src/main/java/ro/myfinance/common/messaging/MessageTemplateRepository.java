package ro.myfinance.common.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, MessageTemplate.Key> {

    Optional<MessageTemplate> findByTenantIdAndKind(UUID tenantId, MessageTemplateKind kind);

    List<MessageTemplate> findByTenantId(UUID tenantId);

    void deleteByTenantIdAndKind(UUID tenantId, MessageTemplateKind kind);
}
