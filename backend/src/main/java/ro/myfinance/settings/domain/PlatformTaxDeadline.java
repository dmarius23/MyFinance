package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The statutory day-of-month on which salary taxes fall due, in the month following the period.
 * Effective-dated. GLOBAL reference data — no {@code tenant_id}, no RLS (see V63). Managed by
 * SUPER_ADMIN; tenants read only.
 *
 * <p>Only the day is stored. The "month following the period" offset is intrinsic to monthly filing and
 * stays in code: a configurable offset of zero would silently claim April's taxes are due in April.
 */
@Entity
@Table(name = "platform_tax_deadline")
public class PlatformTaxDeadline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "day_of_month", nullable = false)
    private short dayOfMonth;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformTaxDeadline() {
    }

    public PlatformTaxDeadline(short dayOfMonth, LocalDate validFrom) {
        this.dayOfMonth = dayOfMonth;
        this.validFrom = validFrom;
    }

    /** The day is editable in place; the effective date is immutable (add a new row instead). */
    public void setDayOfMonth(short dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public UUID getId() { return id; }
    public short getDayOfMonth() { return dayOfMonth; }
    public LocalDate getValidFrom() { return validFrom; }
    public Instant getCreatedAt() { return createdAt; }
}
