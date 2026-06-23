package ro.myfinance.tasks.adapter.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.tasks.application.TaskService;
import ro.myfinance.tasks.application.TaskService.TaskInput;
import ro.myfinance.tasks.application.TaskService.TaskView;
import ro.myfinance.tasks.domain.TaskItem;

/** MOD-10 Internal Tasks — firm-staff to-do board. */
@RestController
@RequestMapping("/api/v1/tasks")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<TaskView> list() {
        return tasks.list();
    }

    /** Per-user task load — admin oversight of who has what. Admin only. */
    @GetMapping("/by-user")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public List<TaskService.UserTaskLoad> byUser() {
        return tasks.loadByUser();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@RequestBody TaskInput input) {
        return tasks.create(input);
    }

    @PutMapping("/{id}")
    public TaskView update(@PathVariable UUID id, @RequestBody TaskInput input) {
        return tasks.update(id, input);
    }

    @PatchMapping("/{id}/status")
    public TaskView changeStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return tasks.changeStatus(id, req.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tasks.delete(id);
    }

    public record StatusRequest(TaskItem.Status status) {
    }
}
