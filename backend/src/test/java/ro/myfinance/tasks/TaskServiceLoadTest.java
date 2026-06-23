package ro.myfinance.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.notifications.application.NotificationService;
import ro.myfinance.tasks.adapter.persistence.TaskRepository;
import ro.myfinance.tasks.application.TaskService;
import ro.myfinance.tasks.application.TaskService.UserTaskLoad;
import ro.myfinance.tasks.domain.TaskItem;
import ro.myfinance.tasks.domain.TaskItem.Status;

/** Per-user task tallies: status buckets, overdue (past due & not DONE), and the unassigned bucket. */
class TaskServiceLoadTest {

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskService service = new TaskService(tasks, mock(AppUserRepository.class),
            mock(CompanyRepository.class), mock(NotificationService.class));

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final LocalDate yesterday = LocalDate.now().minusDays(1);

    private TaskItem task(UUID assignee, Status status, LocalDate due) {
        TaskItem t = new TaskItem(UUID.randomUUID(), "x", null, assignee, null, due, null);
        if (status != Status.TODO) {
            t.setStatus(status);
        }
        return t;
    }

    private UserTaskLoad of(List<UserTaskLoad> loads, UUID id) {
        return loads.stream().filter(l -> java.util.Objects.equals(l.assigneeId(), id)).findFirst().orElseThrow();
    }

    @Test
    void talliesByUserWithOverdueAndUnassigned() {
        when(tasks.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                task(a, Status.TODO, yesterday),       // A overdue
                task(a, Status.TODO, null),
                task(a, Status.IN_PROGRESS, null),
                task(a, Status.DONE, yesterday),       // past due but DONE → not overdue
                task(b, Status.TODO, yesterday),       // B overdue
                task(null, Status.IN_PROGRESS, null))); // unassigned

        List<UserTaskLoad> loads = service.loadByUser();
        assertThat(loads).hasSize(3);

        UserTaskLoad la = of(loads, a);
        assertThat(la.todo()).isEqualTo(2);
        assertThat(la.inProgress()).isEqualTo(1);
        assertThat(la.done()).isEqualTo(1);
        assertThat(la.overdue()).isEqualTo(1); // the DONE-past-due one is NOT counted

        UserTaskLoad lb = of(loads, b);
        assertThat(lb.todo()).isEqualTo(1);
        assertThat(lb.overdue()).isEqualTo(1);

        UserTaskLoad un = of(loads, null);
        assertThat(un.inProgress()).isEqualTo(1);
        assertThat(un.overdue()).isZero();
    }
}
