package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.application.TaxDeclarationService;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.DeclarationView;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/** Declaration manager: list maps flags; delete removes the row and its document. */
class TaxDeclarationServiceTest {

    private final TaxDeclarationRepository declarations = mock(TaxDeclarationRepository.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final TaxDeclarationService service = new TaxDeclarationService(declarations, documents);

    private TaxDeclaration decl(UUID companyId, UUID documentId) {
        TaxDeclaration d = new TaxDeclaration(UUID.randomUUID(), companyId, LocalDate.of(2026, 6, 1), documentId);
        d.apply(DeclarationType.D100, "49443957", new BigDecimal("884"), new BigDecimal("442"), true,
                LocalDate.of(2026, 3, 1), false);
        return d;
    }

    @Test
    void listMapsFlags() {
        UUID companyId = UUID.randomUUID();
        when(declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(any(), any()))
                .thenReturn(List.of(decl(companyId, UUID.randomUUID())));
        List<DeclarationView> views = service.list(companyId, LocalDate.of(2026, 6, 1));
        assertThat(views).hasSize(1);
        DeclarationView v = views.get(0);
        assertThat(v.type()).isEqualTo(DeclarationType.D100);
        assertThat(v.outsidePeriod()).isTrue();   // March decl filed under June
        assertThat(v.mismatch()).isTrue();
        assertThat(v.computedTotal()).isEqualByComparingTo("442");
    }

    @Test
    void deleteRemovesRowAndDocument() {
        UUID companyId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        TaxDeclaration d = decl(companyId, docId);
        when(declarations.findById(any())).thenReturn(Optional.of(d));

        service.delete(companyId, UUID.randomUUID());

        verify(declarations).delete(d);
        verify(documents).delete(docId);
    }

    @Test
    void deleteRejectsOtherCompany() {
        TaxDeclaration d = decl(UUID.randomUUID(), UUID.randomUUID()); // belongs to a different company
        when(declarations.findById(any())).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
