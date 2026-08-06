package ro.myfinance.tasks.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.tasks.domain.TaskItem;

public interface TaskRepository extends JpaRepository<TaskItem, UUID> {

    List<TaskItem> findAllByOrderByCreatedAtDesc();

    /** Open work (everything except the given status) — keeps TODO/IN_PROGRESS complete on the board. */
    List<TaskItem> findByStatusNotOrderByCreatedAtDesc(TaskItem.Status status);

    /** The most recent tasks in a status — bounds the ever-growing DONE column on the board. */
    List<TaskItem> findTop50ByStatusOrderByCreatedAtDesc(TaskItem.Status status);
}
