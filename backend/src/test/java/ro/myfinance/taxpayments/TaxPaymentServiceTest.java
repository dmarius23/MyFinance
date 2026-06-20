package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.settings.adapter.persistence.ResidenceTreasuryAccountRepository;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;
import ro.myfinance.taxpayments.application.PaymentCalculator;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;

/**
 * End-to-end service test on the real D100 + D112 fixtures (repos mocked, no DB/HTTP). Proves
 * extract → resolve treasury IBANs → group by IBAN → email, matching the accountant's grouping.
 * Skips when the PII fixtures are absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaxPaymentServiceTest {

    private static final String CONT_UNIC = "RO14TREZ2165503XXXXXXXXX";
    private static final String CAM_IBAN = "RO54TREZ21620A470300XXXX";

    @Mock CompanyRepository companies;
    @Mock DocumentService documentService;
    @Mock ResidenceTreasuryAccountRepository treasury;

    private final TaxPaymentService service() {
        return new TaxPaymentService(companies, documentService, treasury,
                new AnafDeclarationExtractor(), new PaymentCalculator(), new PaymentEmailBuilder());
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anaf/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private Document declaration(UUID id, String filename) {
        Document d = org.mockito.Mockito.mock(Document.class);
        when(d.getId()).thenReturn(id);
        when(d.getType()).thenReturn(DocumentType.DECLARATION);
        when(d.getOriginalFilename()).thenReturn(filename);
        return d;
    }

    @Test
    void computesGroupedPaymentLinesFromRealDeclarations() throws IOException {
        byte[] d100 = fixture("D100.pdf");
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d100 != null && d112 != null, "ANAF fixtures missing (PII, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company c = org.mockito.Mockito.mock(Company.class);
        when(c.getLocality()).thenReturn("Cluj");
        when(c.getLegalName()).thenReturn("INNOVATECODE IT SRL");
        when(c.getCui()).thenReturn("49443957");
        when(companies.findById(companyId)).thenReturn(Optional.of(c));

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Document doc1 = declaration(id1, "D100.pdf");
        Document doc2 = declaration(id2, "D112.pdf");
        when(documentService.list(eq(companyId), any())).thenReturn(List.of(doc1, doc2));
        when(documentService.getContent(id1)).thenReturn(new DocumentService.DocumentContent(doc1, d100));
        when(documentService.getContent(id2)).thenReturn(new DocumentService.DocumentContent(doc2, d112));

        // impozit + CAS + CASS share the cont-unic IBAN; CAM is its own.
        ResidenceTreasuryAccount acct = org.mockito.Mockito.mock(ResidenceTreasuryAccount.class);
        when(acct.getIbanImpozite()).thenReturn(CONT_UNIC);
        when(acct.getIbanCas()).thenReturn(CONT_UNIC);
        when(acct.getIbanCass()).thenReturn(CONT_UNIC);
        when(acct.getIbanCam()).thenReturn(CAM_IBAN);
        lenient().when(acct.getIbanTva()).thenReturn(null);
        when(treasury.findByResidence("Cluj")).thenReturn(Optional.of(acct));

        TaxPaymentSummary s = service().summary(companyId, LocalDate.of(2026, 3, 1));

        assertThat(s.declarations()).hasSize(2);
        assertThat(s.beneficiary()).isEqualTo("Trezoreria Cluj");
        // impozit 442 (D100) + impozit 264 + CAS 1015 + CASS 406 (D112) = 2127 on the cont-unic IBAN
        var contUnic = s.paymentLines().stream().filter(l -> l.iban().equals(CONT_UNIC)).findFirst().orElseThrow();
        assertThat(contUnic.amount()).isEqualByComparingTo("2127");
        var cam = s.paymentLines().stream().filter(l -> l.iban().equals(CAM_IBAN)).findFirst().orElseThrow();
        assertThat(cam.amount()).isEqualByComparingTo("91");
        assertThat(s.totalToPay()).isEqualByComparingTo("2218");
        assertThat(s.unconfigured()).isEmpty();
        assertThat(s.emailBody()).contains("2127 lei in contul " + CONT_UNIC)
                .contains("91 lei in contul " + CAM_IBAN)
                .contains("CUI Beneficiar: 49443957");
    }

    @Test
    void flagsUnconfiguredCategoriesWhenNoTreasuryRow() throws IOException {
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d112 != null, "ANAF fixtures missing (PII, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company c = org.mockito.Mockito.mock(Company.class);
        when(c.getLocality()).thenReturn("Nowhere");
        when(c.getLegalName()).thenReturn("X SRL");
        when(c.getCui()).thenReturn("49443957");
        when(companies.findById(companyId)).thenReturn(Optional.of(c));

        UUID id = UUID.randomUUID();
        Document doc = declaration(id, "D112.pdf");
        when(documentService.list(eq(companyId), any())).thenReturn(List.of(doc));
        when(documentService.getContent(id)).thenReturn(new DocumentService.DocumentContent(doc, d112));
        when(treasury.findByResidence("Nowhere")).thenReturn(Optional.empty());

        TaxPaymentSummary s = service().summary(companyId, LocalDate.of(2026, 3, 1));
        // No IBANs configured → every payable category is unconfigured, no lines, but total still computed.
        assertThat(s.paymentLines()).isEmpty();
        assertThat(s.unconfigured()).hasSize(4); // IMPOZIT, CAS, CASS, CAM
        assertThat(s.totalToPay()).isEqualByComparingTo("1776");
        assertThat(s.emailBody()).isNull();
    }
}
