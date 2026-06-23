package ro.myfinance.tasks.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.tasks.domain.TaskItem;

public interface TaskRepository extends JpaRepository<TaskItem, UUID> {

    List<TaskItem> findAllByOrderByCreatedAtDesc();
}
