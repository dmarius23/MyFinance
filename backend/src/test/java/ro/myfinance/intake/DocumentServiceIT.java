package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.support.AbstractPostgresIT;

class DocumentServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000d1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000d2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-DOC-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
    }

    @Test
    void uploadsClassifiesListsAndDownloads() {
        UUID companyId = asTenantWithCompany(TENANT_A);

        Document doc = documents.upload(companyId, LocalDate.of(2026, 6, 15), "receipt.jpg", "image/jpeg", png());

        assertThat(doc.getType().name()).isEqualTo("RECEIPT");
        assertThat(doc.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(documents.list(companyId, null)).hasSize(1);
        assertThat(documents.getContent(doc.getId()).bytes()).isEqualTo(png());
    }

    @Test
    void rejectsUnsupportedType() {
        UUID companyId = asTenantWithCompany(TENANT_A);
        assertThatThrownBy(() ->
                documents.upload(companyId, LocalDate.of(2026, 6, 1), "x.exe", "application/octet-stream", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesDocument() {
        UUID companyId = asTenantWithCompany(TENANT_A);
        Document doc = documents.upload(companyId, LocalDate.of(2026, 6, 1), "r.png", "image/png", png());
        documents.delete(doc.getId());
        assertThat(documents.list(companyId, null)).isEmpty();
    }

    @Test
    void tenantBCannotSeeOrDeleteTenantADocuments() {
        UUID companyA = asTenantWithCompany(TENANT_A);
        Document docA = documents.upload(companyA, LocalDate.of(2026, 6, 1), "a.png", "image/png", png());

        asTenantWithCompany(TENANT_B);
        assertThat(documents.list(companyA, null)).isEmpty();
        assertThatThrownBy(() -> documents.delete(docA.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> documents.getContent(docA.getId())).isInstanceOf(NotFoundException.class);
    }
}
