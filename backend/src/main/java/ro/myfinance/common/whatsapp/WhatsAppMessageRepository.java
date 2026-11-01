package ro.myfinance.common.whatsapp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.common.email.EmailKind;

/** Tenant-isolated persistence for WhatsApp messages. */
public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, UUID> {

    Optional<WhatsAppMessage> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<WhatsAppMessage> findByProviderMessageId(String providerMessageId);

    long countByTenantIdAndSentAtGreaterThanEqualAndSentAtLessThan(
            UUID tenantId, Instant fromInclusive, Instant toExclusive);

    List<WhatsAppMessage> findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
            EmailKind kind, UUID companyId, LocalDate periodMonth);

    List<WhatsAppMessage> findByKindAndPeriodMonthOrderBySentAtDesc(
            EmailKind kind, LocalDate periodMonth);
}
