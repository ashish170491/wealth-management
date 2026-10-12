package com.example.trading.portfolio.goal;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One thing the investor is saving for, with a date (SPEC §53).
 *
 * <p><b>The date is the only required field, and it is required for a reason.</b> A goal without a
 * date is not a goal, it is a wish - and the whole point of this module is that a date makes the
 * one decision that needs no forecast: how much of the money can be somewhere it might be worth
 * less on the day you need it. Everything else here is optional.
 *
 * <p><b>Every row on this table is stated by the investor.</b> There is no seeder and there never
 * will be one, which is what makes this table different from {@code portfolio_profile}: B-121 put
 * ten permanent drift warnings on the landing page against sector targets nobody had chosen, and
 * the fix there was {@code targets_stated} (Gotcha 68, B-057, B-097 - a default is not a
 * statement). Here the equivalent flag would be the constant {@code true}, and a constant on screen
 * is the shape of a measurement nobody took (B-060), so the rule is enforced by the absence of a
 * writer rather than by a column.
 *
 * <p>{@code earmarkedValue} is the money the investor says is already set aside for this goal, with
 * the date they said it. It is deliberately <b>not</b> live: this app cannot tell which of your
 * units belong to which goal, and inventing that link would be worse than leaving it blank. A null
 * earmark makes the shortfall {@code NOT_MEASURED}, never zero.
 */
@Entity
@Table(name = "investment_goal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentGoalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** When the money is needed. The one field that decides everything downstream. */
    @Column(nullable = false)
    private LocalDate targetDate;

    /** What it is expected to cost, in rupees. Null is allowed - a dated goal is still a goal. */
    @Column(precision = 18, scale = 2)
    private BigDecimal targetAmount;

    /** What the investor says is already set aside for this, in rupees. Null means not stated. */
    @Column(precision = 18, scale = 2)
    private BigDecimal earmarkedValue;

    /** The day the investor stated {@code earmarkedValue}, so a stale figure is visibly stale. */
    private LocalDate earmarkedAsOf;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
