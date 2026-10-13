package com.example.trading.jobs;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Scheduled-job status (SPEC §56.7). Every GET is DB-only plus the scheduler's in-memory
 * registry - no broker, no NSE, no email - so all three are safe on a page load (Gotcha 17, 39).
 *
 * <p>The one POST re-runs a job whose latest run went wrong today (SPEC §56.9). It is not a
 * generic "run now": it is offered only after a failure, and every request passes through
 * {@link JobRerunPolicy}, which holds the refusal windows (B-049) in one place.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobStatusController {

    private final JobStatusService service;
    private final JobRerunService reruns;

    public JobStatusController(JobStatusService service, JobRerunService reruns) {
        this.service = service;
        this.reruns = reruns;
    }

    /**
     * Re-runs one job whose latest run went wrong today. 202 when started (the run then shows as
     * running on the page), 409 with the reason in the body when refused, 404 when unknown.
     * Refusals carry their reason because a bare 409 hides exactly the text the guard exists to
     * deliver (Gotcha 60).
     */
    @PostMapping("/rerun")
    public ResponseEntity<Map<String, Object>> rerun(@RequestParam String job) {
        String key = service.resolveKey(job).orElse(null);
        if (key == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "No job named " + job + "."));
        }
        try {
            JobRerunService.Started s = reruns.start(key);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "runId", s.runId(), "jobKey", s.jobKey(), "label", s.label(), "note", s.note()));
        } catch (JobRerunService.RerunRefused e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", e.getMessage()));
        }
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
