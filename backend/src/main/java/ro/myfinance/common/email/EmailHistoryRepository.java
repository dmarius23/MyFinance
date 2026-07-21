package ro.myfinance.common.email;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The single email-history store. All finders are scoped by {@link EmailKind} so each module reads only
 * its own sends. RLS keeps rows tenant-isolated at the database layer.
 */
public interface EmailHistoryRepository extends JpaRepository<EmailHistory, UUID> {

    /** History for one company/period of a given kind, newest first (the notification log + resend). */
    List<EmailHistory> findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
            EmailKind kind, UUID companyId, LocalDate periodMonth);

    /** All sends of a kind for a period (for the per-company "last sent" summaries), newest first. */
    List<EmailHistory> findByKindAndPeriodMonthOrderBySentAtDesc(EmailKind kind, LocalDate periodMonth);
}
