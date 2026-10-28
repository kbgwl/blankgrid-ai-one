package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.repo.TaskRepository;
import com.blankgrid.aione.service.TaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskRepository taskRepo;

    public TaskController(TaskService taskService, TaskRepository taskRepo) {
        this.taskService = taskService;
        this.taskRepo = taskRepo;
    }

    public static class SubmitBody {
        public Long employeeId;
        public String instruction;
        public List<Long> contextTaskIds;
    }

    @PostMapping
    public Task submit(@RequestBody SubmitBody body) {
        return taskService.submit(body.employeeId, body.instruction, body.contextTaskIds);
    }

    @GetMapping
    public List<Task> list(@RequestParam(required = false) Long employeeId) {
        return employeeId == null ? taskService.recentTasks() : taskService.tasksOf(employeeId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return taskService.taskDetail(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        return Map.of("ok", taskService.cancel(id));
    }
}
