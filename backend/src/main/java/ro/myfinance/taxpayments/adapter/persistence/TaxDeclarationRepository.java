package ro.myfinance.taxpayments.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

public interface TaxDeclarationRepository extends JpaRepository<TaxDeclaration, UUID> {

    Optional<TaxDeclaration> findByDocumentId(UUID documentId);

    /** Is there already a (canonical) declaration of this type for the same company + fiscal period? */
    boolean existsByCompanyIdAndTypeAndDeclPeriodAndDuplicateFalseAndDocumentIdNot(
            UUID companyId, ro.myfinance.taxpayments.domain.DeclarationType type,
            LocalDate declPeriod, UUID documentId);

    List<TaxDeclaration> findByCompanyIdAndPeriodMonthOrderByTypeAsc(UUID companyId, LocalDate periodMonth);

    List<TaxDeclaration> findByPeriodMonth(LocalDate periodMonth);

    void deleteByDocumentId(UUID documentId);
}
