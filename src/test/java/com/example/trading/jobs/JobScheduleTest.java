package com.example.trading.jobs;

import com.example.trading.scheduler.MarketHoursService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the plain-English schedule and the fire arithmetic (SPEC §56.5), on the real crons this
 * app registers. Reference: Monday 2026-10-12 08:00 IST.
 */
class JobScheduleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ZonedDateTime MON_0800 = ZonedDateTime.of(2026, 10, 12, 8, 0, 0, 0, IST);

    private static JobSchedule d(String cron) {
        return JobSchedule.describe(cron, IST, MON_0800);
    }

    @Test
    @DisplayName("Single daily fire")
    void single() {
        JobSchedule s = d("0 0 14 * * MON-FRI");
        assertThat(s.text()).isEqualTo("Weekdays at 14:00");
        assertThat(s.outsideWindowOnRunDay()).isZero();
        assertThat(s.runsPerYear()).isBetween(258, 263);
    }

    @Test
    @DisplayName("A short list is listed")
    void list() {
        assertThat(d("0 0 11,13,15 * * MON-FRI").text()).isEqualTo("Weekdays at 11:00, 13:00 and 15:00");
    }

    @Test
    @DisplayName("The 15-minute headline capture: two of its 28 daily fires are refused by the guard by design")
    void everyFifteen() {
        JobSchedule s = d("0 */15 9-15 * * MON-FRI");
        assertThat(s.text()).isEqualTo("Weekdays, every 15 min from 09:00 to 15:45");
        assertThat(s.timesOnRunDay()).hasSize(28);
        assertThat(s.outsideWindowOnRunDay()).isEqualTo(2);   // 09:00 and 15:45
    }

    @Test
    @DisplayName("Described mid-morning, the schedule is still the whole day - not the rest of today")
    void describedMidDay() {
        ZonedDateTime tenOhFive = MON_0800.withHour(10).withMinute(5);
        assertThat(JobSchedule.describe("0 */15 9-15 * * MON-FRI", IST, tenOhFive).text())
                .isEqualTo("Weekdays, every 15 min from 09:00 to 15:45");
        assertThat(JobSchedule.describe("0 0 10,12,14 * * MON-FRI", IST, tenOhFive).text())
                .isEqualTo("Weekdays at 10:00, 12:00 and 14:00");
        // Even after the day's last fire.
        assertThat(JobSchedule.describe("0 0 9 * * MON-FRI", IST, MON_0800.withHour(18)).text())
                .isEqualTo("Weekdays at 09:00");
    }

    @Test
    @DisplayName("The scheduler's fractionally-early next execution is rounded to the second")
    void nextRunRounded() {
        java.time.Instant early = ZonedDateTime.of(2026, 10, 17, 7, 59, 59, 999_193_500, IST).toInstant();
        assertThat(JobCatalog.nextRun(early)).isEqualTo(LocalDateTime.of(2026, 10, 17, 8, 0));
        assertThat(JobCatalog.nextRun(null)).isNull();
    }

    @Test
    @DisplayName("Hourly is said as hourly")
    void hourly() {
        assertThat(d("0 0 10,11,12,13,14 * * MON-FRI").text()).isEqualTo("Weekdays, every hour from 10:00 to 14:00");
    }

    @Test
    @DisplayName("Saturday jobs are inside the Saturday carve-out, not outside a window")
    void saturday() {
        JobSchedule s = d("0 0 8 * * SAT");
        assertThat(s.text()).isEqualTo("Saturdays at 08:00");
        assertThat(s.outsideWindowOnRunDay()).isZero();
    }

    @Test
    @DisplayName("First-Friday-of-the-quarter is said in words, and fires four times a year")
    void quarterly() {
        JobSchedule s = d("0 26 15 1-7 1,4,7,10 FRI");
        assertThat(s.text()).isEqualTo("First Friday of the month in Jan, Apr, Jul, Oct at 15:26");
        assertThat(s.runsPerYear()).isEqualTo(4);
        assertThat(d("0 25 15 1-7 * FRI").runsPerYear()).isEqualTo(12);
    }

    @Test
    @DisplayName("Spring's disabled marker and garbage are described, never thrown")
    void unparseable() {
        assertThat(d("-").text()).startsWith("Disabled");
        assertThat(d("not a cron").text()).startsWith("Unreadable");
        assertThat(d("not a cron").runsPerYear()).isZero();
    }

    @Test
    @DisplayName("A run is attributed to the latest fire at or before it, and a late start keeps its fire")
    void latestFire() {
        String cron = "0 0 14 * * MON-FRI";
        LocalDateTime fire = LocalDateTime.of(2026, 10, 12, 14, 0);
        assertThat(JobSchedule.latestFireAtOrBefore(cron, IST, fire, Duration.ofHours(6))).isEqualTo(fire);
        assertThat(JobSchedule.latestFireAtOrBefore(cron, IST, fire.plusMinutes(9), Duration.ofHours(6))).isEqualTo(fire);
    }

    @Test
    @DisplayName("Beyond the look-back there is no fire, rather than yesterday's guessed in")
    void noGuessBeyondLookback() {
        LocalDateTime before = LocalDateTime.of(2026, 10, 12, 13, 59);
        assertThat(JobSchedule.latestFireAtOrBefore("0 0 14 * * MON-FRI", IST, before, Duration.ofHours(6))).isNull();
    }

    @Test
    @DisplayName("A weekday cron has no fires across a weekend")
    void weekendEmpty() {
        LocalDateTime fri = LocalDateTime.of(2026, 10, 9, 16, 0);
        LocalDateTime mon = LocalDateTime.of(2026, 10, 12, 9, 0);
        assertThat(JobSchedule.firesBetween("0 0 14 * * MON-FRI", IST, fri, mon)).isEmpty();
    }

    @Test
    @DisplayName("The pure window checks agree with the guard's boundaries, in any zone")
    void windowBoundaries() {
        ZonedDateTime mon = ZonedDateTime.of(2026, 10, 12, 9, 15, 0, 0, IST);
        assertThat(MarketHoursService.isMarketOpenAt(mon)).isTrue();
        assertThat(MarketHoursService.isMarketOpenAt(mon.minusSeconds(1))).isFalse();
        assertThat(MarketHoursService.isMarketOpenAt(mon.withHour(15).withMinute(30))).isTrue();
        assertThat(MarketHoursService.isMarketOpenAt(mon.withHour(15).withMinute(30).plusSeconds(1))).isFalse();
        // 03:45 UTC is 09:15 IST
        assertThat(MarketHoursService.isMarketOpenAt(mon.withZoneSameInstant(ZoneOffset.UTC))).isTrue();
        ZonedDateTime sat = ZonedDateTime.of(2026, 10, 17, 10, 30, 0, 0, IST);
        assertThat(MarketHoursService.isMarketOpenAt(sat)).isFalse();
        assertThat(MarketHoursService.isSaturdayScreeningWindowAt(sat)).isTrue();
        assertThat(MarketHoursService.isSaturdayScreeningWindowAt(sat.plusMinutes(1))).isFalse();
    }
}
