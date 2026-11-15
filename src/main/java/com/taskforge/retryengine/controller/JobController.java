
package com.taskforge.retryengine.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.taskforge.retryengine.JobStatus;

import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final int MAX_ATTEMPTS = 3;

    private final ConcurrentMap<String, Map<String, Object>> jobs =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    // Health check
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "message", "Application is running successfully",
                "status", "UP",
                "service", "Reliable Job Retry Engine"
        );
    }

    // Submit a new job
    @PostMapping
    public Map<String, Object> submitJob(
            @RequestBody(required = false) Map<String, Object> request) {

        String id = UUID.randomUUID().toString();

        String taskName = request == null
                ? "Demo Job"
                : String.valueOf(
                        request.getOrDefault("taskName", "Demo Job")
                );

        int successOnAttempt = 1;

        if (request != null &&
                request.containsKey("failUntilAttempt")) {

            Object value = request.get("failUntilAttempt");

            if (!(value instanceof Number)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "failUntilAttempt must be a number"
                );
            }

            successOnAttempt = ((Number) value).intValue();
        }

        if (successOnAttempt < 1 ||
                successOnAttempt > MAX_ATTEMPTS) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "failUntilAttempt must be between 1 and 3"
            );
        }

        // Final variable for safe lambda execution
        final int retryThreshold = successOnAttempt;

        Map<String, Object> job = new ConcurrentHashMap<>();

        job.put("id", id);
        job.put("taskName", taskName);
        job.put("status", JobStatus.PENDING.name());
        job.put("attempt", 0);
        job.put("maxAttempts", MAX_ATTEMPTS);
        job.put("createdAt", LocalDateTime.now().toString());
        job.put("lastError", "");

        jobs.put(id, job);

        scheduler.execute(() -> executeJob(id, retryThreshold));

        return new HashMap<>(job);
    }

    // Execute job and handle retries
    private void executeJob(String id, int retryThreshold) {

        Map<String, Object> job = jobs.get(id);

        if (job == null) {
            return;
        }

        int attempt = ((Number) job.get("attempt")).intValue() + 1;

        job.put("attempt", attempt);
        job.put("status", JobStatus.RUNNING.name());

        System.out.println(
                "Executing job: " + id +
                " | Attempt: " + attempt
        );

        // Simulated successful execution
        if (attempt >= retryThreshold) {

            job.put("status", JobStatus.SUCCESS.name());
            job.put("lastError", "");

            System.out.println("Job completed successfully: " + id);

            return;
        }

        // Simulated execution failure
        job.put("lastError", "Simulated job execution failure");

        if (attempt < MAX_ATTEMPTS) {

            job.put("status", JobStatus.RETRYING.name());

            // Exponential backoff: 1, 2, 4 seconds
            long delaySeconds = 1L << (attempt - 1);

            System.out.println(
                    "Retrying job after " + delaySeconds + " seconds"
            );

            scheduler.schedule(
                    () -> executeJob(id, retryThreshold),
                    delaySeconds,
                    TimeUnit.SECONDS
            );

        } else {

            job.put("status", JobStatus.FAILED.name());

            System.out.println("Job permanently failed: " + id);
        }
    }

    // Get all jobs
    @GetMapping
    public Collection<Map<String, Object>> getAllJobs() {

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> job : jobs.values()) {
            result.add(new HashMap<>(job));
        }

        return result;
    }

    // Get job by ID
    @GetMapping("/{id}")
    public Map<String, Object> getJob(@PathVariable String id) {

        Map<String, Object> job = jobs.get(id);

        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Job not found: " + id
            );
        }

        return new HashMap<>(job);
    }

    // Graceful shutdown
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}

