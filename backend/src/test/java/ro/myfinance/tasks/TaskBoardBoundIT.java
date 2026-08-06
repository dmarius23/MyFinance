package ro.myfinance.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.tasks.application.TaskService;
import ro.myfinance.tasks.application.TaskService.TaskInput;
import ro.myfinance.tasks.application.TaskService.TaskView;
import ro.myfinance.tasks.domain.TaskItem;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Covers the board-bounding of the ever-growing DONE column (S16): the default board caps DONE to the
 * 50 most recent while keeping all open work, and {@code allDone=true} returns the full history.
 */
class TaskBoardBoundIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-1111-0000-0000-00000000000a");

    @Autowired TaskService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void defaultBoardCapsDoneButAllDoneReturnsEverything() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing",
                TENANT);

        int doneCount = 52;
        for (int i = 0; i < doneCount; i++) {
            service.create(new TaskInput("done-" + i, null, null, null, null, TaskItem.Status.DONE));
        }
        service.create(new TaskInput("open-1", null, null, null, null, TaskItem.Status.TODO));
        service.create(new TaskInput("open-2", null, null, null, null, TaskItem.Status.IN_PROGRESS));

        List<TaskView> board = service.list(false);
        long doneOnBoard = board.stream().filter(t -> t.status() == TaskItem.Status.DONE).count();
        long openOnBoard = board.stream().filter(t -> t.status() != TaskItem.Status.DONE).count();
        assertThat(doneOnBoard).isEqualTo(50); // capped
        assertThat(openOnBoard).isEqualTo(2);  // all open work kept

        List<TaskView> all = service.list(true);
        long doneAll = all.stream().filter(t -> t.status() == TaskItem.Status.DONE).count();
        assertThat(doneAll).isEqualTo(doneCount); // full history
        assertThat(all).hasSize(doneCount + 2);
    }
}
