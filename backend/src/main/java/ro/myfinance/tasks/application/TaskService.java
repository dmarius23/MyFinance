package ro.myfinance.tasks.application;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.notifications.application.NotificationService;
import ro.myfinance.tasks.adapter.persistence.TaskRepository;
import ro.myfinance.tasks.domain.TaskItem;

/**
 * MOD-10 Internal Tasks — firm-staff to-do CRUD. Tenant-scoped via RLS. Assigning a task to another
 * staff member raises an in-app nudge. Views resolve the assignee and company names and an overdue flag.
 */
@Service
@Transactional
public class TaskService {

    private final TaskRepository tasks;
    private final AppUserRepository users;
    private final CompanyRepository companies;
    private final NotificationService notifications;

    public TaskService(TaskRepository tasks, AppUserRepository users, CompanyRepository companies,
                       NotificationService notifications) {
        this.tasks = tasks;
        this.users = users;
        this.companies = companies;
        this.notifications = notifications;
    }

    public record TaskView(UUID id, String title, String details, UUID assigneeId, String assigneeName,
                           UUID companyId, String companyName, LocalDate dueDate, TaskItem.Status status,
                           boolean overdue, Instant createdAt) {
    }

    public record TaskInput(String title, String details, UUID assigneeId, UUID companyId, LocalDate dueDate,
                            TaskItem.Status status) {
    }

    @Transactional(readOnly = true)
    public List<TaskView> list() {
        List<TaskItem> all = tasks.findAllByOrderByCreatedAtDesc();
        Map<UUID, String> userNames = users.findAllById(all.stream().map(TaskItem::getAssigneeId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getName));
        Map<UUID, String> companyNames = companies.findAllById(all.stream().map(TaskItem::getCompanyId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Company::getId, Company::getLegalName));
        return all.stream().map(t -> view(t, userNames, companyNames)).toList();
    }

    public TaskView create(TaskInput in) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        UUID createdBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        require(in.title());
        TaskItem t = tasks.save(new TaskItem(tenantId, in.title().trim(), in.details(),
                in.assigneeId(), in.companyId(), in.dueDate(), createdBy));
        if (in.status() != null && in.status() != TaskItem.Status.TODO) {
            t.setStatus(in.status());
        }
        notifyIfAssigned(in.assigneeId(), t);
        return view(t);
    }

    public TaskView update(UUID id, TaskInput in) {
        require(in.title());
        TaskItem t = get(id);
        UUID before = t.getAssigneeId();
        t.edit(in.title().trim(), in.details(), in.assigneeId(), in.companyId(), in.dueDate(),
                in.status() == null ? t.getStatus() : in.status());
        if (!Objects.equals(before, in.assigneeId())) {
            notifyIfAssigned(in.assigneeId(), t);
        }
        return view(t);
    }

    public TaskView changeStatus(UUID id, TaskItem.Status status) {
        TaskItem t = get(id);
        t.setStatus(status);
        return view(t);
    }

    public void delete(UUID id) {
        tasks.delete(get(id));
    }

    private void notifyIfAssigned(UUID assigneeId, TaskItem t) {
        if (assigneeId == null) {
            return;
        }
        String companyName = t.getCompanyId() == null ? null
                : companies.findById(t.getCompanyId()).map(Company::getLegalName).orElse(null);
        notifications.taskAssigned(assigneeId, t.getTitle(), t.getCompanyId(), companyName);
    }

    private TaskItem get(UUID id) {
        return tasks.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
    }

    private static void require(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
    }

    private TaskView view(TaskItem t) {
        String assignee = t.getAssigneeId() == null ? null
                : users.findById(t.getAssigneeId()).map(AppUser::getName).orElse(null);
        String company = t.getCompanyId() == null ? null
                : companies.findById(t.getCompanyId()).map(Company::getLegalName).orElse(null);
        return view(t, assignee == null ? Map.of() : Map.of(t.getAssigneeId(), assignee),
                company == null ? Map.of() : Map.of(t.getCompanyId(), company));
    }

    private static TaskView view(TaskItem t, Map<UUID, String> users, Map<UUID, String> companies) {
        boolean overdue = t.getDueDate() != null && t.getStatus() != TaskItem.Status.DONE
                && t.getDueDate().isBefore(LocalDate.now());
        return new TaskView(t.getId(), t.getTitle(), t.getDetails(), t.getAssigneeId(),
                t.getAssigneeId() == null ? null : users.get(t.getAssigneeId()),
                t.getCompanyId(), t.getCompanyId() == null ? null : companies.get(t.getCompanyId()),
                t.getDueDate(), t.getStatus(), overdue, t.getCreatedAt());
    }
}
