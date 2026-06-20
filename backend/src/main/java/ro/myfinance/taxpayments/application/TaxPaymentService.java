package ro.myfinance.taxpayments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.settings.adapter.persistence.ResidenceTreasuryAccountRepository;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxObligation;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.DeclarationSummary;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.Unconfigured;

/**
 * MOD-07 — computes the Tax &amp; Payments view for a company + period live from its uploaded ANAF
 * declaration PDFs. Read-only: no money figure is persisted or emailed here. Tenant-scoped via RLS.
 */
@Service
@Transactional(readOnly = true)
public class TaxPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TaxPaymentService.class);

    private final CompanyRepository companies;
    private final DocumentService documents;
    private final ResidenceTreasuryAccountRepository treasury;
    private final AnafDeclarationExtractor extractor;
    private final PaymentCalculator calculator;
    private final PaymentEmailBuilder emailBuilder;

    public TaxPaymentService(CompanyRepository companies, DocumentService documents,
                             ResidenceTreasuryAccountRepository treasury, AnafDeclarationExtractor extractor,
                             PaymentCalculator calculator, PaymentEmailBuilder emailBuilder) {
        this.companies = companies;
        this.documents = documents;
        this.treasury = treasury;
        this.extractor = extractor;
        this.calculator = calculator;
        this.emailBuilder = emailBuilder;
    }

    public TaxPaymentSummary summary(UUID companyId, LocalDate period) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        LocalDate month = period.withDayOfMonth(1);

        List<ParsedDeclaration> parsed = new ArrayList<>();
        List<DeclarationSummary> declViews = new ArrayList<>();
        for (Document d : documents.list(companyId, month)) {
            if (d.getType() != DocumentType.DECLARATION) {
                continue;
            }
            try {
                ParsedDeclaration pd = extractor.extract(documents.getContent(d.getId()).bytes());
                parsed.add(pd);
                declViews.add(new DeclarationSummary(d.getId(), d.getOriginalFilename(), pd.type(),
                        pd.computedTotal(), pd.declaredTotal(), pd.totalsMismatch()));
            } catch (RuntimeException e) {
                log.warn("Failed to extract declaration {} ({})", d.getId(), d.getOriginalFilename(), e);
            }
        }

        Map<TaxCategory, String> ibans = resolveIbans(company.getLocality());
        List<PaymentLine> all = calculator.compute(parsed, ibans);
        List<PaymentLine> configured = all.stream().filter(l -> !l.iban().isBlank()).toList();

        // Payable amount per category, to surface those still missing an IBAN and to total the bill.
        Map<TaxCategory, BigDecimal> payableByCat = new EnumMap<>(TaxCategory.class);
        for (ParsedDeclaration pd : parsed) {
            for (TaxObligation o : pd.obligations()) {
                if (o.payable()) {
                    payableByCat.merge(o.category(), o.amount(), BigDecimal::add);
                }
            }
        }
        List<Unconfigured> unconfigured = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<TaxCategory, BigDecimal> e : payableByCat.entrySet()) {
            total = total.add(e.getValue());
            String iban = ibans.get(e.getKey());
            if (iban == null || iban.isBlank()) {
                unconfigured.add(new Unconfigured(e.getKey(), e.getValue()));
            }
        }

        String beneficiary = beneficiary(company.getLocality());
        String emailBody = (beneficiary != null && !configured.isEmpty())
                ? emailBuilder.build(company.getLegalName(), company.getCui(), YearMonth.from(month),
                        beneficiary, configured)
                : null;

        return new TaxPaymentSummary(companyId, company.getLegalName(), company.getCui(), month,
                beneficiary, declViews, configured, unconfigured, total, emailBody);
    }

    private Map<TaxCategory, String> resolveIbans(String locality) {
        Map<TaxCategory, String> map = new EnumMap<>(TaxCategory.class);
        if (locality == null || locality.isBlank()) {
            return map;
        }
        ResidenceTreasuryAccount a = treasury.findByResidence(locality).orElse(null);
        if (a == null) {
            return map;
        }
        putIfPresent(map, TaxCategory.IMPOZIT, a.getIbanImpozite());
        putIfPresent(map, TaxCategory.CAS, a.getIbanCas());
        putIfPresent(map, TaxCategory.CASS, a.getIbanCass());
        putIfPresent(map, TaxCategory.CAM, a.getIbanCam());
        putIfPresent(map, TaxCategory.TVA, a.getIbanTva());
        return map;
    }

    private static void putIfPresent(Map<TaxCategory, String> map, TaxCategory cat, String iban) {
        if (iban != null && !iban.isBlank()) {
            map.put(cat, iban);
        }
    }

    private static String beneficiary(String locality) {
        return (locality == null || locality.isBlank()) ? null : "Trezoreria " + locality;
    }
}
