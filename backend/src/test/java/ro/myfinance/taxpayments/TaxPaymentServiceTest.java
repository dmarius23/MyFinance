package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.settings.application.PlatformTreasuryService;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;
import ro.myfinance.taxpayments.application.PaymentCalculator;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.TaxDeclaration;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;

/**
 * End-to-end service test on the real D100 + D112 fixtures (repos mocked, no DB/HTTP). Declarations are
 * read from the store; payment lines are re-derived from their PDFs and grouped by treasury IBAN.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaxPaymentServiceTest {

    private static final String CONT_UNIC = "RO14TREZ2165503XXXXXXXXX";
    private static final String CAM_IBAN = "RO54TREZ21620A470300XXXX";
    private static final LocalDate MONTH = LocalDate.of(2026, 3, 1);

    @Mock CompanyRepository companies;
    @Mock DocumentService documentService;
    @Mock PlatformTreasuryService treasury;
    @Mock TaxDeclarationRepository declarations;
    @Mock EmailHistoryRepository emails;

    private TaxPaymentService service() {
        return new TaxPaymentService(companies, documentService, treasury, declarations, emails,
                new AnafDeclarationExtractor(), new PaymentCalculator(), new PaymentEmailBuilder());
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anaf/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private TaxDeclaration storedDecl(UUID docId, DeclarationType type) {
        TaxDeclaration d = mock(TaxDeclaration.class);
        when(d.getId()).thenReturn(UUID.randomUUID());
        when(d.getDocumentId()).thenReturn(docId);
        when(d.getCompanyId()).thenReturn(null); // set per-test where needed
        when(d.getType()).thenReturn(type);
        when(d.getPeriodMonth()).thenReturn(MONTH);
        when(d.getComputedTotal()).thenReturn(java.math.BigDecimal.ZERO);
        return d;
    }

    private Company company(String locality) {
        Company c = mock(Company.class);
        when(c.getLocality()).thenReturn(locality);
        when(c.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(c.getCui()).thenReturn("49443957");
        return c;
    }

    @Test
    void computesGroupedPaymentLinesFromStoredDeclarations() throws IOException {
        byte[] d100 = fixture("D100.pdf");
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d100 != null && d112 != null, "ANAF fixtures missing (PII, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company company = company("Cluj");
        when(companies.findById(companyId)).thenReturn(Optional.of(company));

        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        TaxDeclaration sd1 = storedDecl(doc1, DeclarationType.D100);
        TaxDeclaration sd2 = storedDecl(doc2, DeclarationType.D112);
        when(declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(eq(companyId), any()))
                .thenReturn(List.of(sd1, sd2));
        when(emails.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(eq(EmailKind.TAX), eq(companyId), any())).thenReturn(List.of());
        when(documentService.getContent(doc1)).thenReturn(new DocumentService.DocumentContent(mock(Document.class), d100));
        when(documentService.getContent(doc2)).thenReturn(new DocumentService.DocumentContent(mock(Document.class), d112));

        PlatformTreasuryAccount acct = mock(PlatformTreasuryAccount.class);
        when(acct.getIbanImpozite()).thenReturn(CONT_UNIC);
        when(acct.getIbanCas()).thenReturn(CONT_UNIC);
        when(acct.getIbanCass()).thenReturn(CONT_UNIC);
        when(acct.getIbanCam()).thenReturn(CAM_IBAN);
        when(treasury.accountFor("Cluj", MONTH)).thenReturn(Optional.of(acct));

        TaxPaymentSummary s = service().summary(companyId, MONTH);

        assertThat(s.declarations()).hasSize(2);
        var contUnic = s.paymentLines().stream().filter(l -> l.iban().equals(CONT_UNIC)).findFirst().orElseThrow();
        assertThat(contUnic.amount()).isEqualByComparingTo("2127"); // 442 + 264 + 1015 + 406
        var cam = s.paymentLines().stream().filter(l -> l.iban().equals(CAM_IBAN)).findFirst().orElseThrow();
        assertThat(cam.amount()).isEqualByComparingTo("91");
        assertThat(s.totalToPay()).isEqualByComparingTo("2218");
        assertThat(s.unconfigured()).isEmpty();
        assertThat(s.emailBody()).contains("2127 lei in contul " + CONT_UNIC).contains("91 lei in contul " + CAM_IBAN);
    }

    @Test
    void wrongPartyDeclarationIsExcludedFromTheTotal() throws IOException {
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d112 != null, "ANAF fixtures missing (PII, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company company = company("Cluj");
        when(companies.findById(companyId)).thenReturn(Optional.of(company));
        UUID doc = UUID.randomUUID();
        TaxDeclaration sd = storedDecl(doc, DeclarationType.D112);
        when(sd.isWrongParty()).thenReturn(true); // filed for a different CUI
        when(declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(eq(companyId), any()))
                .thenReturn(List.of(sd));
        when(emails.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(eq(EmailKind.TAX), eq(companyId), any())).thenReturn(List.of());

        TaxPaymentSummary s = service().summary(companyId, MONTH);

        assertThat(s.declarations()).hasSize(1);          // still listed so the accountant sees it
        assertThat(s.paymentLines()).isEmpty();           // but never drives a payment
        assertThat(s.unconfigured()).isEmpty();
        assertThat(s.totalToPay()).isEqualByComparingTo("0");
    }

    @Test
    void flagsUnconfiguredCategoriesWhenNoTreasuryRow() throws IOException {
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d112 != null, "ANAF fixtures missing (PII, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company company = company("Nowhere");
        when(companies.findById(companyId)).thenReturn(Optional.of(company));
        UUID doc = UUID.randomUUID();
        TaxDeclaration sd = storedDecl(doc, DeclarationType.D112);
        when(declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(eq(companyId), any()))
                .thenReturn(List.of(sd));
        when(emails.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(eq(EmailKind.TAX), eq(companyId), any())).thenReturn(List.of());
        when(documentService.getContent(doc)).thenReturn(new DocumentService.DocumentContent(mock(Document.class), d112));
        when(treasury.accountFor("Nowhere", MONTH)).thenReturn(Optional.empty());

        TaxPaymentSummary s = service().summary(companyId, MONTH);
        assertThat(s.paymentLines()).isEmpty();
        assertThat(s.unconfigured()).hasSize(4); // IMPOZIT, CAS, CASS, CAM
        assertThat(s.totalToPay()).isEqualByComparingTo("1776");
        assertThat(s.emailBody()).isNull();
    }
}
