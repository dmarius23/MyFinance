package ro.myfinance.common.whatsapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.common.email.EmailKind;

/** Tenant-scoped WhatsApp template registry. */
public interface TenantWhatsAppTemplateRepository extends JpaRepository<TenantWhatsAppTemplate, UUID> {

    List<TenantWhatsAppTemplate> findAllByOrderByKindAscLanguageAsc();

    Optional<TenantWhatsAppTemplate> findByKindAndLanguage(EmailKind kind, String language);
}
