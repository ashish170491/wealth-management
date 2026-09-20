package com.example.trading.funds;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One published NAV for one scheme on one date (SPEC 52.4).
 *
 * <p>Daily for schemes the investor owns; month-end for the screenable universe. Retained forever:
 * this table is what makes the survivorship ledger possible, because a scheme that stops appearing
 * is visible only against the months it used to report (SPEC 52.7).
 *
 * <p><b>A row is only ever written for a NAV that was actually published.</b> An absent NAV is not
 * stored as 0 and not stored at all - and the difference between "no row" and "a row of zero" is
 * the difference between a fund nobody priced that day and a fund that lost everything.
 */
@Entity
@Table(name = "mf_nav_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mf_nav_scheme_date", columnNames = {"amfiSchemeCode", "navDate"}),
        indexes = @Index(name = "idx_mf_nav_date", columnList = "navDate"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutualFundNavEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long amfiSchemeCode;

    @Column(nullable = false)
    private LocalDate navDate;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal nav;
}
