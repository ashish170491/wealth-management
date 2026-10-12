package com.example.trading.jobs;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One start of the application process (SPEC §56.4).
 *
 * <p>This exists so the page can tell two facts apart that otherwise look identical - "the job did
 * not run" and "the app was not running". This app is started at 09:00 and stopped at ~15:35 by
 * Windows tasks, and is sometimes restarted mid-day, so a cron fire with no run row is usually the
 * second. Calling it "missed" would put a permanent false alarm on the page; ignoring it would hide
 * the real case, a fire lost while the JVM was alive (a run overran its own slot, or the pool
 * dropped it).
 *
 * <p>{@link #lastSeenAt} is advanced whenever any job finishes (at most once a minute) and on a
 * clean shutdown. A process killed outright therefore reads as running only up to its last
 * finished job - which errs toward "app not running", never toward a false "missed".
 */
@Entity
@Table(name = "app_boot", indexes = @Index(name = "idx_app_boot_started", columnList = "startedAt"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppBootEntity {

    @Id
    @Column(length = 40)
    private String bootId;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    private Long pid;
}
