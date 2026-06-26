package ro.myfinance.taxpayments.application;

import java.math.BigDecimal;
import java.time.Instant;
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
import ro.myfinance.settings.adapter.persistence.ResidenceTreasuryAccountRepository;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.adapter.persistence.TaxEmailRepository;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxDeclaration;
import ro.myfinance.taxpayments.domain.TaxEmail;
import ro.myfinance.taxpayments.domain.TaxObligation;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.DeclarationSummary;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.EmailView;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.Unconfigured;

/**
 * MOD-07 — computes the Tax &amp; Payments view from the stored declarations (and re-derives payment
 * lines from their PDFs). Read-only: no money figure is persisted or emailed here. Tenant-scoped via RLS.
 */
@Service
@Transactional(readOnly = true)
public class TaxPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TaxPaymentService.class);

    private final CompanyRepository companies;
    private final DocumentService documents;
    private final ResidenceTreasuryAccountRepository treasury;
    private final TaxDeclarationRepository declarations;
    private final TaxEmailRepository emails;
    private final AnafDeclarationExtractor extractor;
    private final PaymentCalculator calculator;
    private final PaymentEmailBuilder emailBuilder;

    public TaxPaymentService(CompanyRepository companies, DocumentService documents,
                             ResidenceTreasuryAccountRepository treasury, TaxDeclarationRepository declarations,
                             TaxEmailRepository emails, AnafDeclarationExtractor extractor,
                             PaymentCalculator calculator, PaymentEmailBuilder emailBuilder) {
        this.companies = companies;
        this.documents = documents;
        this.treasury = treasury;
        this.declarations = declarations;
        this.emails = emails;
        this.extractor = extractor;
        this.calculator = calculator;
        this.emailBuilder = emailBuilder;
    }

    /** The result of computing payment lines + email body over a set of declarations. */
    public record Computation(String beneficiary, List<PaymentLine> lines, List<Unconfigured> unconfigured,
                              BigDecimal total, String body) {
    }

    /** The monthly list: one row per company with its uploaded declarations and last-email status. */
    public List<ro.myfinance.taxpayments.domain.TaxPaymentRow> list(LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        Map<UUID, List<TaxDeclaration>> declByCompany = new java.util.HashMap<>();
        for (TaxDeclaration d : declarations.findByPeriodMonth(month)) {
            declByCompany.computeIfAbsent(d.getCompanyId(), k -> new ArrayList<>()).add(d);
        }
        Map<UUID, List<TaxEmail>> emailByCompany = new java.util.HashMap<>();
        for (TaxEmail e : emails.findByPeriodMonth(month)) {
            emailByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }

        List<ro.myfinance.taxpayments.domain.TaxPaymentRow> rows = new ArrayList<>();
        for (Company c : companies.findAll()) {
            List<ro.myfinance.taxpayments.domain.TaxPaymentRow.DeclarationCell> cells = new ArrayList<>();
            for (TaxDeclaration d : declByCompany.getOrDefault(c.getId(), List.of())) {
                if (d.isDuplicate()) {
                    continue; // the list shows the canonical declaration per type
                }
                cells.add(new ro.myfinance.taxpayments.domain.TaxPaymentRow.DeclarationCell(
                        d.getId(), d.getType(), d.getComputedTotal(), d.isMismatch()));
            }
            List<TaxEmail> es = emailByCompany.getOrDefault(c.getId(), List.of());
            Instant last = es.stream().map(TaxEmail::getSentAt).max(Instant::compareTo).orElse(null);
            rows.add(new ro.myfinance.taxpayments.domain.TaxPaymentRow(c.getId(), c.getLegalName(),
                    c.getCui(), c.getLocality(), cells, last, es.size()));
        }
        rows.sort(java.util.Comparator.comparing(r -> r.companyName() == null ? "" : r.companyName().toLowerCase()));
        return rows;
    }

    public TaxPaymentSummary summary(UUID companyId, LocalDate period) {
        Company company = company(companyId);
        LocalDate month = period.withDayOfMonth(1);
        List<TaxDeclaration> decls = declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(companyId, month);
        List<TaxEmail> sent = emails.findByCompanyIdAndPeriodMonthOrderBySentAtDesc(companyId, month);

        List<DeclarationSummary> declViews = new ArrayList<>();
        for (TaxDeclaration d : decls) {
            int count = 0;
            Instant last = null;
            for (TaxEmail e : sent) {
                if (e.getDeclarationIds().contains(d.getId())) {
                    count++;
                    if (last == null) {
                        last = e.getSentAt(); // list is sorted newest-first
                    }
                }
            }
            declViews.add(new DeclarationSummary(d.getId(), d.getDocumentId(), d.getType(),
                    d.getComputedTotal(), d.getDeclaredTotal(), d.isMismatch(), d.isDuplicate(), count, last));
        }

        Computation c = compute(company, decls);
        List<EmailView> history = sent.stream().map(EmailView::from).toList();
        return new TaxPaymentSummary(companyId, company.getLegalName(), company.getCui(), month,
                c.beneficiary(), declViews, c.lines(), c.unconfigured(), c.total(), c.body(), history);
    }

    /** Compute the default email body + lines for a chosen subset of declarations (for the editor). */
    public Computation composeFor(UUID companyId, List<UUID> declarationIds) {
        Company company = company(companyId);
        List<TaxDeclaration> chosen = declarations.findAllById(declarationIds).stream()
                .filter(d -> d.getCompanyId().equals(companyId))
                .toList();
        if (chosen.isEmpty()) {
            throw new NotFoundException("No declarations selected");
        }
        return compute(company, chosen);
    }

    String beneficiaryFor(UUID companyId) {
        return beneficiary(company(companyId).getLocality());
    }

    // ---- core computation -------------------------------------------------------------------------

    private Computation compute(Company company, List<TaxDeclaration> decls) {
        List<ParsedDeclaration> parsed = new ArrayList<>();
        for (TaxDeclaration d : decls) {
            if (d.isDuplicate()) {
                continue; // never count a duplicate declaration in the payment total / email
            }
            if (d.isWrongParty()) {
                continue; // a declaration filed for a different CUI is not this company's liability
            }
            try {
                parsed.add(extractor.extract(documents.getContent(d.getDocumentId()).bytes()));
            } catch (RuntimeException e) {
                log.warn("Failed to re-extract declaration {} (doc {})", d.getId(), d.getDocumentId(), e);
            }
        }
        Map<TaxCategory, String> ibans = resolveIbans(company.getLocality());
        List<PaymentLine> all = calculator.compute(parsed, ibans);
        List<PaymentLine> configured = all.stream().filter(l -> !l.iban().isBlank()).toList();

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
        YearMonth period = decls.isEmpty() ? null : YearMonth.from(decls.get(0).getPeriodMonth());
        String body = (beneficiary != null && !configured.isEmpty() && period != null)
                ? emailBuilder.build(company.getLegalName(), company.getCui(), period, beneficiary, configured)
                : null;
        return new Computation(beneficiary, configured, unconfigured, total, body);
    }

    private Company company(UUID companyId) {
        return companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
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
