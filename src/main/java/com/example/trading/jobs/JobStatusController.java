package com.example.trading.jobs;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Scheduled-job status (SPEC §56.7). Every endpoint is DB-only plus the scheduler's in-memory
 * registry - no broker, no NSE, no email - so all three are safe on a page load (Gotcha 17, 39).
 * There is deliberately no "run this job now" endpoint here: each job already has its own manual
 * trigger with its own refusal windows (B-049), and a generic one would bypass all of them.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobStatusController {

    private final JobStatusService service;

    public JobStatusController(JobStatusService service) {
        this.service = service;
    }

    /** Every registered job: schedule, last run, typical duration, lost fires, verdict. */
    @GetMapping
    public JobStatusDto.Overview overview(@RequestParam(defaultValue = "14") int days) {
        return service.overview(days);
    }

    /** One job's runs, newest first. {@code job} is the full key or {@code Class.method}. 404 when unknown. */
    @GetMapping("/runs")
    public ResponseEntity<JobStatusDto.History> runs(@RequestParam String job,
                                                     @RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return service.history(job, days, limit)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Every run on one day, for the timeline. Defaults to the latest day with any run. */
    @GetMapping("/timeline")
    public JobStatusDto.Timeline timeline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.timeline(date);
    }
}
