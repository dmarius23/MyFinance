package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;
import ro.myfinance.taxpayments.application.TaxDeclarationListener;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/** The upload listener stores a declaration with its own period (outside-period flag) and wrong-party. */
class TaxDeclarationListenerTest {

    private final TaxDeclarationRepository declarations = mock(TaxDeclarationRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final TaxDeclarationListener listener =
            new TaxDeclarationListener(new AnafDeclarationExtractor(), declarations, companies);

    @BeforeEach
    void setup() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(declarations.findByDocumentId(any())).thenReturn(Optional.empty());
        when(declarations.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anaf/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private TaxDeclaration onUpload(byte[] pdf, String filename, LocalDate uploadPeriod, String companyCui) {
        UUID companyId = UUID.randomUUID();
        Company c = mock(Company.class);
        when(c.getCui()).thenReturn(companyCui);
        when(companies.findById(companyId)).thenReturn(Optional.of(c));
        listener.onDocumentUploaded(new DocumentUploadedEvent(UUID.randomUUID(), companyId, uploadPeriod,
                DocumentType.DECLARATION, filename, pdf));
        ArgumentCaptor<TaxDeclaration> cap = ArgumentCaptor.forClass(TaxDeclaration.class);
        verify(declarations).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void storesOwnPeriodAndNoWrongPartyForMatchingCui() throws IOException {
        byte[] d100 = fixture("D100.pdf");
        assumeTrue(d100 != null, "fixtures missing");
        // Filed under June, but the D100 is for March → outside period; CUI matches the company.
        TaxDeclaration saved = onUpload(d100, "D100.pdf", LocalDate.of(2026, 6, 1), "49443957");
        assertThat(saved.getType()).isEqualTo(DeclarationType.D100);
        assertThat(saved.getDeclPeriod()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(saved.isOutsidePeriod()).isTrue();
        assertThat(saved.isWrongParty()).isFalse();
        assertThat(saved.getComputedTotal()).isEqualByComparingTo("442");
    }

    @Test
    void aMisfiledCopyIsTheDuplicateWhenAnotherCopyExists() throws IOException {
        byte[] d100 = fixture("D100.pdf");
        assumeTrue(d100 != null, "fixtures missing");
        // The D100 is for March but filed under June (outside period) and another copy already exists.
        when(declarations.findByCompanyIdAndTypeAndDeclPeriod(any(), any(), any()))
                .thenReturn(List.of(mock(TaxDeclaration.class)));
        TaxDeclaration saved = onUpload(d100, "D100.pdf", LocalDate.of(2026, 6, 1), "49443957");
        assertThat(saved.isDuplicate()).isTrue();
    }

    @Test
    void theInPeriodCopyIsCanonicalAndDemotesAMisfiledOne() throws IOException {
        byte[] d100 = fixture("D100.pdf");
        assumeTrue(d100 != null, "fixtures missing");
        // A copy of the same D100 (for March) already sits in the WRONG month (June, outside period).
        TaxDeclaration misfiled = mock(TaxDeclaration.class);
        when(misfiled.getDocumentId()).thenReturn(UUID.randomUUID());
        when(misfiled.getPeriodMonth()).thenReturn(LocalDate.of(2026, 6, 1));
        when(misfiled.isDuplicate()).thenReturn(false);
        when(declarations.findByCompanyIdAndTypeAndDeclPeriod(any(), any(), any())).thenReturn(List.of(misfiled));
        // Upload the same D100 under MARCH (its own month): it is canonical, the June copy is demoted.
        TaxDeclaration saved = onUpload(d100, "D100.pdf", LocalDate.of(2026, 3, 1), "49443957");
        assertThat(saved.isOutsidePeriod()).isFalse();
        assertThat(saved.isDuplicate()).isFalse();
        verify(misfiled).markDuplicate();
    }

    @Test
    void flagsWrongPartyWhenCuiDiffers() throws IOException {
        byte[] d300 = fixture("D300.pdf"); // MERIC SRL, CUI 20464846
        assumeTrue(d300 != null, "fixtures missing");
        TaxDeclaration saved = onUpload(d300, "D300.pdf", LocalDate.of(2026, 4, 1), "49443957");
        assertThat(saved.getType()).isEqualTo(DeclarationType.D300);
        assertThat(saved.isWrongParty()).isTrue();        // 20464846 != 49443957
        assertThat(saved.isOutsidePeriod()).isFalse();    // D300 is for April, filed under April
    }
}
