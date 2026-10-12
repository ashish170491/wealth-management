package com.example.trading.funds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The whole month-end archive, read once and shared (SPEC §52.16, §53.2).
 *
 * <p><b>Why this exists, written the day it was needed rather than after an incident.</b> Widening
 * the kept universe to debt and hybrid (§52.15) took {@code mf_nav_history} from ~13,500 rows to
 * several times that, and two new readers want essentially all of it: the horizon screen needs every
 * category's series, and the goal planner needs equity and debt pooled. Measured before this class
 * existed, {@code GET /api/mf/horizon} took <b>4.2 s</b> against {@code api.js}'s 8 s page-load
 * timeout, with the archive only three-quarters back-filled. That is B-115's shape exactly - a
 * page-load endpoint whose cost grows every week the archive deepens, with nothing failing to mark
 * the moment it stops being fast enough - and the honest time to fix it is before it crosses the
 * line, not after the dashboard starts showing its "did not answer in time" banner.
 *
 * <p>Gotcha 97's rule, moved from the broker to the database: <b>one cache, because it is one
 * body of data.</b> Two readers each loading the same rows would double the cost of identical
 * information.
 *
 * <p><b>The version token is the row count, and its one limitation is stated rather than hidden.</b>
 * A NAV point is keyed on (scheme, date), so a daily pull writes rows for a date that was not there
 * before and the count moves. What the count cannot see is a row being <em>corrected</em> in place -
 * which happens exactly once in this module's history, when a re-run with {@code force=true}
 * repairs months a wrong parser read (B-130). That path already requires a deliberate button press,
 * so {@link #invalidate()} is called from it.
 *
 * <p><b>It is bounded and it is read</b>, which is what separates it from B-049's leak: the bound is
 * the size of the archive, one entry exists at a time, and the previous generation is dropped whole
 * rather than accumulated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonthEndSeriesCache {

    private final MutualFundNavRepository navRepository;
    private final MutualFundSchemeRepository schemeRepository;
    private final FundLensService lensService;

    private volatile long version = -1L;
    private volatile Map<Long, List<CategoryDownside.Point>> series = Map.of();

    /**
     * Every measurable scheme's month-end series, keyed by scheme code.
     *
     * <p>"Measurable" is Direct plan and Growth option and not exchange-traded - the same units
     * guard every other reading in this module applies, applied once here so no caller can forget
     * it. An income-distributing series records the return after every payout and a Regular plan
     * records it after a commission; mixing them into a category median compares three scales
     * (B-131, B-047's family).
     */
    public Map<Long, List<CategoryDownside.Point>> all() {
        long now = navRepository.count();
        Map<Long, List<CategoryDownside.Point>> cached = series;
        if (now == version && !cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (now == version && !series.isEmpty()) {
                return series;
            }
            Set<Long> codes = schemeRepository.findAll().stream()
                    .filter(s -> s.getPlan() == SchemePlanKey.Plan.DIRECT)
                    .filter(s -> s.getNavOption() == SchemePlanKey.Option.GROWTH)
                    .filter(s -> s.getCategory() == null || !s.getCategory().isExchangeTraded())
                    .map(MutualFundSchemeEntity::getAmfiSchemeCode)
                    .collect(Collectors.toSet());

            Map<Long, List<CategoryDownside.Point>> built = new LinkedHashMap<>();
            if (!codes.isEmpty()) {
                lensService.monthEndSeries(codes).forEach((code, points) ->
                        built.put(code, points.stream()
                                .map(p -> new CategoryDownside.Point(p.month(), p.nav()))
                                .toList()));
            }
            series = built;
            version = now;
            log.info("Month-end series cache rebuilt: {} schemes, {} stored NAV points.",
                    built.size(), now);
            return built;
        }
    }

    /**
     * Drop the cache.
     *
     * <p>Called by the backfill, because that is the one path that can <em>correct</em> rows in
     * place rather than add them - and a corrected row leaves the count where it was.
     */
    public void invalidate() {
        synchronized (this) {
            series = Map.of();
            version = -1L;
        }
    }
}
