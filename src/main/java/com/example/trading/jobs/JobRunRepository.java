package com.example.trading.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    List<JobRunEntity> findByStartedAtGreaterThanEqualOrderByStartedAtAsc(LocalDateTime from);

    List<JobRunEntity> findByStartedAtBetweenOrderByStartedAtAsc(LocalDateTime from, LocalDateTime to);

    List<JobRunEntity> findByJobKeyAndStartedAtGreaterThanEqualOrderByStartedAtDesc(String jobKey, LocalDateTime from);

    List<JobRunEntity> findByOutcome(String outcome);

    /**
     * The newest run of every job, all time - not just inside the page's window, because a
     * quarterly job's last run is months old and is still the answer to "when did it last run".
     */
    @Query("select r from JobRunEntity r where r.startedAt = "
            + "(select max(r2.startedAt) from JobRunEntity r2 where r2.jobKey = r.jobKey)")
    List<JobRunEntity> findLatestPerJob();

    /** The newest run of every job that actually did its work, i.e. was not a SKIP. */
    @Query("select r from JobRunEntity r where r.outcome <> 'SKIPPED' and r.startedAt = "
            + "(select max(r2.startedAt) from JobRunEntity r2 where r2.jobKey = r.jobKey and r2.outcome <> 'SKIPPED')")
    List<JobRunEntity> findLatestRealRunPerJob();

    @Query("select max(r.startedAt) from JobRunEntity r where r.jobKey = :key and r.outcome = 'SUCCEEDED'")
    LocalDateTime lastSucceededAt(@Param("key") String key);

    @Query("select r.jobKey, max(r.startedAt) from JobRunEntity r where r.outcome = 'SUCCEEDED' group by r.jobKey")
    List<Object[]> lastSucceededPerJob();

    Optional<JobRunEntity> findFirstByOrderByStartedAtDesc();

    /** The day before / after a given instant that has any run, for the timeline's arrows. */
    Optional<JobRunEntity> findFirstByStartedAtLessThanOrderByStartedAtDesc(LocalDateTime before);

    Optional<JobRunEntity> findFirstByStartedAtGreaterThanEqualOrderByStartedAtAsc(LocalDateTime from);

    @Modifying
    @Transactional
    int deleteByStartedAtBefore(LocalDateTime cutoff);
}
