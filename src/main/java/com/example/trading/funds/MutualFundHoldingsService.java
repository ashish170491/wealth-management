package com.example.trading.funds;

import com.example.trading.broker.BrokerClient;
import com.example.trading.funds.MutualFundHoldingEntity.SchemeResolvedFrom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the investor's mutual fund book from the broker and keeps {@code mf_holding} current
 * (SPEC §52.3).
 *
 * <p>Three rules carried over from the equity sync, and one deliberate departure.
 *
 * <ul>
 *   <li><b>An empty broker response never empties the table.</b> The same guard as
 *       {@code syncHoldingsFromBroker}: a failed or empty fetch is logged and the pass returns,
 *       because a token problem must not be recorded as "the investor sold everything" (B-016).</li>
 *   <li><b>Money figures are computed here.</b> The broker returns {@code pnl: 0} on rows whose
 *       average and last prices plainly differ, so the field is not trusted.</li>
 *   <li><b>A zero cost basis is unknown, not free.</b> Units transferred in rather than bought come
 *       back with {@code average_price: 0.0}; treating that as a real cost reports an infinite
 *       gain, so invested value and P&amp;L stay <b>null</b> for that row and the UI says
 *       "not measured" (Gotcha 21).</li>
 *   <li><b>The departure: exits are soft.</b> {@code holdings} hard-deletes because a stale equity
 *       row pollutes every report; here the exit history <em>is</em> part of the record of what
 *       this portfolio did.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MutualFundHoldingsService {

    private static final DateTimeFormatter KITE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BrokerClient brokerClient;
    private final MutualFundHoldingRepository holdingRepository;
    private final MutualFundSchemeRepository schemeRepository;

    /**
     * @param unresolved holdings whose ISIN matched no AMFI scheme — reported, never guessed
     */
    public record SyncResult(int fetched, int upserted, int exited, int unresolved, String note) {}

    @Transactional
    public SyncResult sync() {
        List<Map<String, Object>> rows;
        try {
            rows = brokerClient.getMutualFundHoldings();
        } catch (Exception e) {
            log.warn("Broker mutual fund fetch failed ({}) - leaving mf_holding untouched. "
                    + "Recording an exit here would turn a token problem into a false sale.",
                    e.getMessage());
            return new SyncResult(0, 0, 0, 0, "broker fetch failed: " + e.getMessage());
        }

        if (rows == null || rows.isEmpty()) {
            log.warn("Broker returned no mutual fund holdings - skipping reconciliation. "
                    + "An empty response and an emptied portfolio must not look alike (B-016).");
            return new SyncResult(0, 0, 0, 0, "broker returned no rows; nothing reconciled");
        }

        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new HashSet<>();
        int upserted = 0;
        int unresolved = 0;

        for (Map<String, Object> row : rows) {
            String isin = str(row.get("tradingsymbol"));
            if (isin == null) {
                log.warn("Skipping a mutual fund row with no ISIN: {}", row);
                continue;
            }
            // The broker sends null or "" here; a single dash keeps two genuine folios apart under
            // the unique constraint rather than collapsing them into one position.
            String folio = Optional.ofNullable(str(row.get("folio"))).orElse("-");
            seen.add(isin + "|" + folio);

            MutualFundHoldingEntity e = holdingRepository.findByIsinAndFolio(isin, folio)
                    .orElseGet(() -> MutualFundHoldingEntity.builder().isin(isin).folio(folio).build());

            BigDecimal units = decimal(row.get("quantity"));
            BigDecimal avg = positiveOrNull(decimal(row.get("average_price")));
            BigDecimal last = positiveOrNull(decimal(row.get("last_price")));

            e.setBrokerFundName(str(row.get("fund")));
            e.setUnits(units == null ? BigDecimal.ZERO : units);
            e.setAveragePrice(avg);
            e.setLastPrice(last);
            e.setLastPriceDate(parseDate(str(row.get("last_price_date"))));
            e.setPledgedUnits(decimal(row.get("pledged_quantity")));
            e.setActive(true);
            e.setExitedOn(null);
            e.setLastSyncedAt(now);

            // Null rather than zero wherever a leg is missing - an unknown cost is not a free one.
            BigDecimal invested = (units != null && avg != null) ? units.multiply(avg) : null;
            BigDecimal current = (units != null && last != null) ? units.multiply(last) : null;
            e.setInvestedValue(round2(invested));
            e.setCurrentValue(round2(current));
            if (invested != null && current != null && invested.signum() > 0) {
                BigDecimal pnl = current.subtract(invested);
                e.setPnl(round2(pnl));
                e.setPnlPercent(pnl.multiply(BigDecimal.valueOf(100))
                        .divide(invested, 4, RoundingMode.HALF_UP));
            } else {
                e.setPnl(null);
                e.setPnlPercent(null);
            }

            Resolution res = resolveScheme(isin);
            e.setAmfiSchemeCode(res.schemeCode());
            e.setSchemeResolvedFrom(res.from());
            if (res.from() == SchemeResolvedFrom.UNRESOLVED) {
                unresolved++;
            }

            holdingRepository.save(e);
            upserted++;
        }

        int exited = 0;
        LocalDate today = LocalDate.now();
        for (MutualFundHoldingEntity held : holdingRepository.findByActiveTrue()) {
            if (!seen.contains(held.getIsin() + "|" + held.getFolio())) {
                held.setActive(false);
                held.setExitedOn(today);
                holdingRepository.save(held);
                exited++;
            }
        }

        log.info("Mutual fund holdings sync: {} fetched, {} upserted, {} marked exited, {} unresolved",
                rows.size(), upserted, exited, unresolved);
        if (unresolved > 0) {
            log.warn("{} mutual fund holdings could not be matched to an AMFI scheme. They are kept "
                    + "and reported as unresolved rather than dropped or guessed.", unresolved);
        }
        return new SyncResult(rows.size(), upserted, exited, unresolved, null);
    }

    private record Resolution(Long schemeCode, SchemeResolvedFrom from) {}

    /**
     * ISIN to scheme.
     *
     * <p>AMFI's ISINs are <b>not unique</b> — five collide in the live file — so this is Gotcha
     * 107's rule rather than a plain lookup: among candidates, an open-ended scheme outranks a dead
     * close-ended one, and ties break on the lowest scheme code so the answer is stable across runs
     * rather than depending on row order.
     */
    private Resolution resolveScheme(String isin) {
        List<MutualFundSchemeEntity> growth = schemeRepository.findByIsinGrowth(isin);
        Optional<MutualFundSchemeEntity> best = pick(growth);
        if (best.isPresent()) {
            return new Resolution(best.get().getAmfiSchemeCode(), SchemeResolvedFrom.ISIN_GROWTH);
        }
        Optional<MutualFundSchemeEntity> reinvest = pick(schemeRepository.findByIsinReinvest(isin));
        if (reinvest.isPresent()) {
            return new Resolution(reinvest.get().getAmfiSchemeCode(), SchemeResolvedFrom.ISIN_REINVEST);
        }
        return new Resolution(null, SchemeResolvedFrom.UNRESOLVED);
    }

    private Optional<MutualFundSchemeEntity> pick(List<MutualFundSchemeEntity> candidates) {
        return candidates.stream()
                .min(Comparator
                        .comparing((MutualFundSchemeEntity s) ->
                                s.getStructure() == SchemeStructure.OPEN_ENDED ? 0 : 1)
                        .thenComparing(MutualFundSchemeEntity::getAmfiSchemeCode));
    }

    // ------------------------------------------------------------------ parsing

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A zero price from the broker means "no cost basis on record", not a price of zero. */
    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v == null || v.signum() <= 0) ? null : v;
    }

    /** The broker sends an empty string here on some rows - that is null, never an epoch date. */
    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, KITE_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Active fund positions. The sibling of {@code findActive()}, soft-deleted rather than purged. */
    public List<MutualFundHoldingEntity> activeHoldings() {
        return new ArrayList<>(holdingRepository.findByActiveTrue());
    }

    /**
     * Retries scheme resolution for holdings that had none, and returns how many were rescued.
     *
     * <p><b>Why this exists — an ordering flaw found by running it, not by reading it.</b> The NAV
     * sync persists the screenable universe <em>plus every scheme the investor owns</em>, but on a
     * first install nothing is owned yet, so the schemes behind the investor's own funds are not
     * written. The holdings sync then resolves nothing, and the screen reports six unresolved
     * holdings — which reads as <em>"AMFI has no record of the funds you hold"</em>, a false
     * statement about the data caused purely by which pass ran first. Measured on the live book:
     * 6 of 6 unresolved on the first pair of runs, 0 of 6 after the second.
     *
     * <p>Called at the end of the NAV sync, so a newly-persisted scheme rescues its holding in the
     * same pass rather than a day later. Idempotent, and it never un-resolves anything.
     */
    @Transactional
    public int resolveUnmatched() {
        int rescued = 0;
        for (MutualFundHoldingEntity h : holdingRepository.findByActiveTrue()) {
            if (h.getAmfiSchemeCode() != null) {
                continue;
            }
            Resolution res = resolveScheme(h.getIsin());
            if (res.schemeCode() != null) {
                h.setAmfiSchemeCode(res.schemeCode());
                h.setSchemeResolvedFrom(res.from());
                holdingRepository.save(h);
                rescued++;
            }
        }
        if (rescued > 0) {
            log.info("Resolved {} previously-unmatched mutual fund holding(s) against newly "
                    + "persisted schemes", rescued);
        }
        return rescued;
    }
}
