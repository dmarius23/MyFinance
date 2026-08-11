package ro.myfinance.common.email;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The single message-history store, shared by email and WhatsApp. Finders are scoped by {@link EmailKind}
 * (so each module reads only its own sends) AND by {@link MessageChannel} (so email views don't pick up
 * WhatsApp rows and vice-versa). RLS keeps rows tenant-isolated at the database layer. The email finders
 * keep their original names/signatures — a {@code @Query} pins them to the EMAIL channel — so existing
 * callers are unchanged.
 */
public interface EmailHistoryRepository extends JpaRepository<EmailHistory, UUID> {

    /** EMAIL history for one company/period of a given kind, newest first (the notification log + resend). */
    @Query("select e from EmailHistory e where e.kind = :kind"
            + " and e.channel = ro.myfinance.common.email.MessageChannel.EMAIL"
            + " and e.companyId = :companyId and e.periodMonth = :periodMonth order by e.sentAt desc")
    List<EmailHistory> findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
            @Param("kind") EmailKind kind, @Param("companyId") UUID companyId,
            @Param("periodMonth") LocalDate periodMonth);

    /** All EMAIL sends of a kind for a period (for the per-company "last sent" summaries), newest first. */
    @Query("select e from EmailHistory e where e.kind = :kind"
            + " and e.channel = ro.myfinance.common.email.MessageChannel.EMAIL"
            + " and e.periodMonth = :periodMonth order by e.sentAt desc")
    List<EmailHistory> findByKindAndPeriodMonthOrderBySentAtDesc(
            @Param("kind") EmailKind kind, @Param("periodMonth") LocalDate periodMonth);

    /** Channel-scoped history for one company/period + kind (used by the WhatsApp log + last-sent). */
    List<EmailHistory> findByChannelAndKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
            MessageChannel channel, EmailKind kind, UUID companyId, LocalDate periodMonth);

    /** Channel-scoped sends of a kind for a period (per-company "last sent" summaries), newest first. */
    List<EmailHistory> findByChannelAndKindAndPeriodMonthOrderBySentAtDesc(
            MessageChannel channel, EmailKind kind, LocalDate periodMonth);
}
