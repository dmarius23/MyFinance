package ro.myfinance.tasks.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** MOD-10 internal task — a firm-staff to-do with status, optional assignee/company/due date. */
@Entity
@Table(name = "task")
public class TaskItem {

    public enum Status { TODO, IN_PROGRESS, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    private String details;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.TODO;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TaskItem() {
    }

    public TaskItem(UUID tenantId, String title, String details, UUID assigneeId, UUID companyId,
                    LocalDate dueDate, UUID createdBy) {
        this.tenantId = tenantId;
        this.title = title;
        this.details = details;
        this.assigneeId = assigneeId;
        this.companyId = companyId;
        this.dueDate = dueDate;
        this.createdBy = createdBy;
    }

    public void edit(String title, String details, UUID assigneeId, UUID companyId, LocalDate dueDate, Status status) {
        this.title = title;
        this.details = details;
        this.assigneeId = assigneeId;
        this.companyId = companyId;
        this.dueDate = dueDate;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDetails() { return details; }
    public UUID getAssigneeId() { return assigneeId; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getDueDate() { return dueDate; }
    public Status getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
