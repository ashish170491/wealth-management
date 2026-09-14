package com.example.trading.learning;

import com.example.trading.learning.WeightVariant.Dimension;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.persistence.ShadowCompositeEntity;
import com.example.trading.persistence.ShadowCompositeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes and persists what each pre-registered weight variant would have scored (SPEC §38.7).
 *
 * <p><b>Contains no model and changes no score.</b> Nothing in this class is read by the
 * screener, any report, any recommendation or any threshold. It writes to one table that only
 * the walk-forward harness reads. If it were deleted the app would behave identically, which is
 * the property that makes it safe to run on every screening date.
 *
 * <p><b>Runs after the screening, and back-fills.</b> {@link #captureForDate} is called at the
 * end of a full screening run; {@link #backfill} reconstructs every past date from
 * {@code multibagger_scores}. The back-fill matters more than it sounds: without it the
 * evidence clock starts on the day this ships, and the first honest verdict on a weight change
 * would be a year away instead of arriving with the 180-day outcomes that mature around
 * October 2026.
 *
 * <p><b>The adjustment is recovered, not recomputed.</b> For each stock the live variant's
 * weighted base is computed from the persisted sub-scores, and everything the engine did
 * afterwards is {@code storedComposite − liveBase}. That constant is re-applied to every
 * variant, so the live variant reproduces the stored composite exactly and each alternative
 * differs from it by precisely the reweighting. Rows where the arithmetic cannot be exact — a
 * composite sitting on the 0 or 100 clamp, or carrying the HIGH_RISK / auditor cap at 54 — are
 * written with {@code reconstructionExact=false} rather than silently averaged in.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShadowCompositeService {

    /**
     * The hard cap a HIGH_RISK balance sheet or an auditor problem forces (SPEC §12.5).
     * A composite landing exactly here may have been capped, and a capped composite is not
     * {@code base + adjustment} for any base — so the reconstruction is flagged inexact.
     */
    static final int HARD_CAP = 54;

    private final MultibaggerScoreRepository scoreRepository;
    private final ShadowCompositeRepository shadowRepository;
    private final WeightVariantRegistry variants;

    /**
     * Compute and persist every variant's composite for one screening date.
     *
     * <p>Never throws into the caller: a measurement of a run must not be able to break the run
     * (the rule {@code ScreeningCoverageService} already follows). A failure is logged at WARN
     * naming what its absence will look like later, because a silently missing date reads as
     * "the variants were not tried that week" rather than "they were tried and not recorded".
     *
     * @return number of rows written, 0 on any failure or when the date has no scores
     */
    public int captureForDate(LocalDate date) {
        if (date == null) return 0;
        try {
            List<MultibaggerScoreEntity> rows = scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(date);
            if (rows.isEmpty()) {
                log.warn("Shadow composites skipped for {}: no screening rows exist for that date. "
                        + "This date will later read as 'variants not evaluated' rather than "
                        + "'evaluated and found nothing'.", date);
                return 0;
            }
            int written = writeFor(date, rows);
            log.info("Shadow composites [{}]: {} rows across {} variants over {} stocks "
                            + "(variant-set rev {}). Steers nothing — recorded for SPEC §38.7.",
                    date, written, variants.all().size(), rows.size(),
                    WeightVariantRegistry.VARIANT_SET_REVISION);
            return written;
        } catch (Exception e) {
            log.warn("Shadow composite capture failed for {} ({}). No alternative-weighting record "
                    + "exists for this run, so it will be missing from the walk-forward sample "
                    + "without that absence being visible in the results.", date, e.getMessage());
            return 0;
        }
    }

    /**
     * Reconstruct shadow composites for every past screening date that has none.
     *
     * <p>Idempotent and skip-based: a date that already has rows is left alone, so this can be
     * re-run freely. Pass {@code force} to recompute — needed after the variant set changes,
     * because a stored row named {@code quality-tilt} under an older revision is a different
     * vector and pooling the two is exactly the error {@code variantSetRevision} exists to
     * expose.
     *
     * <p>Pure database work: no Kite call, no NSE call. It can run at any hour without touching
     * the shared broker budget that has twice starved the afternoon jobs (B-014, B-049).
     *
     * @return a short summary suitable for returning from an endpoint
     */
    public BackfillSummary backfill(boolean force) {
        List<LocalDate> allDates = scoreRepository.findScreeningDates();
        Set<LocalDate> done = force ? Set.of() : new HashSet<>(shadowRepository.findComputedDates());

        int datesProcessed = 0;
        int datesSkipped = 0;
        int rowsWritten = 0;

        // findScreeningDates() is DESC; walk oldest-first so a partial run leaves a contiguous
        // history rather than a hole in the middle, which a block-based harness would silently
        // treat as a shorter sample.
        List<LocalDate> ordered = new ArrayList<>(allDates);
        ordered.sort(Comparator.naturalOrder());

        for (LocalDate date : ordered) {
            if (done.contains(date)) {
                datesSkipped++;
                continue;
            }
            try {
                List<MultibaggerScoreEntity> rows =
                        scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(date);
                if (rows.isEmpty()) continue;
                rowsWritten += writeFor(date, rows);
                datesProcessed++;
            } catch (Exception e) {
                log.warn("Shadow back-fill failed for {} ({}). That date stays absent from the "
                        + "walk-forward sample.", date, e.getMessage());
            }
        }

        log.info("Shadow back-fill complete: {} dates reconstructed, {} already present, {} rows "
                        + "written. Nothing was scored or re-screened — this is arithmetic over "
                        + "existing rows.",
                datesProcessed, datesSkipped, rowsWritten);
        return new BackfillSummary(allDates.size(), datesProcessed, datesSkipped, rowsWritten,
                variants.all().size(), WeightVariantRegistry.VARIANT_SET_REVISION);
    }

    /** @param totalScreeningDates every date in {@code multibagger_scores}, whether or not reconstructed */
    public record BackfillSummary(int totalScreeningDates, int datesReconstructed, int datesAlreadyPresent,
                                  int rowsWritten, int variants, int variantSetRevision) {
    }

    // ---------------------------------------------------------------- internals

    /** Replace-then-write one date across every variant. */
    private int writeFor(LocalDate date, List<MultibaggerScoreEntity> rows) {
        shadowRepository.deleteByScreeningDate(date);

        WeightVariant live = variants.live();
        List<ShadowCompositeEntity> out = new ArrayList<>();

        for (WeightVariant variant : variants.all()) {
            List<ShadowCompositeEntity> forVariant = new ArrayList<>();

            for (MultibaggerScoreEntity row : rows) {
                Map<Dimension, Integer> subScores = WeightVariant.subScores(row);

                // The live base is the reference point for the whole reconstruction: it is what
                // the engine's own weighting produced from these same sub-scores, so the
                // difference to the stored composite is everything that happened afterwards.
                Integer liveBase = live.weightedBase(subScores);
                if (liveBase == null) continue;      // nothing measurable: no variant can rank it

                int adjustment = row.getCompositeScore() - liveBase;

                Integer base = variant.weightedBase(subScores);
                Integer composite = WeightVariant.compose(base, adjustment);
                if (composite == null) continue;     // this variant does not rank this stock

                forVariant.add(ShadowCompositeEntity.builder()
                        .screeningDate(date)
                        .symbol(row.getSymbol())
                        .variantName(variant.name())
                        .variantSetRevision(WeightVariantRegistry.VARIANT_SET_REVISION)
                        .scoringVersion(row.getScoringVersion())
                        .composite(composite)
                        .weightedBase(base)
                        .adjustment(adjustment)
                        .dimensionsMeasured(variant.dimensionsMeasured(subScores))
                        .reconstructionExact(isReconstructionExact(row))
                        .build());
            }

            assignPercentileRanks(forVariant);
            out.addAll(forVariant);
        }

        shadowRepository.saveAll(out);
        return out.size();
    }

    /**
     * Whether {@code base + adjustment} is a faithful reconstruction for this row.
     *
     * <p>False in three cases, all of which break additivity rather than merely bending it:
     * a composite at 100 or 0 means the bonus chain clamped and the recovered adjustment is
     * the clamped movement, not the intended one; and a composite at exactly the hard cap may
     * be {@code min(x, 54)} for an unknown {@code x}, which is not of the form
     * {@code base + constant} at all.
     *
     * <p>Deliberately conservative: a stock capped at 54 whose true pre-cap composite happened
     * to be 54 is flagged too. Over-flagging costs sample size in a place where sample size is
     * already the binding constraint; under-flagging feeds wrong numbers into the one panel
     * built to be trustworthy.
     */
    static boolean isReconstructionExact(MultibaggerScoreEntity row) {
        int c = row.getCompositeScore();
        if (c <= 0 || c >= 100) return false;
        if (c == HARD_CAP && looksCapped(row)) return false;
        return true;
    }

    /** A HIGH_RISK balance sheet or a disclosed auditor problem forces the cap (SPEC §12.5, §32.4). */
    private static boolean looksCapped(MultibaggerScoreEntity row) {
        if ("HIGH_RISK".equals(row.getFinancialQualityVerdict())) return true;
        String flags = row.getForensicFlags();
        return flags != null && flags.toUpperCase().contains("AUDITOR");
    }

    /**
     * Cross-sectional percentile within one variant on one date; 100 = best, ties share the best
     * rank.
     *
     * <p>Mirrors {@code MultibaggerScreenerService.assignPercentileRanks} on purpose. Rank is
     * the quantity a weighting comparison actually turns on: composites are re-scaled by every
     * scoring change and by broad market moves (B-064), so two variants' raw levels are not
     * comparable while their orderings are.
     */
    static void assignPercentileRanks(List<ShadowCompositeEntity> rows) {
        if (rows == null || rows.size() < 2) {
            if (rows != null) rows.forEach(r -> r.setPercentileRank(null));
            return;
        }
        List<ShadowCompositeEntity> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(ShadowCompositeEntity::getComposite).reversed());

        int n = sorted.size();
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && sorted.get(j + 1).getComposite().equals(sorted.get(i).getComposite())) {
                j++;
            }
            // Best position in the tie, so an 18-way tie at the top all read 100 rather than
            // being ordered arbitrarily by whatever the sort happened to do.
            double pct = n == 1 ? 100.0 : 100.0 * (n - 1 - i) / (n - 1);
            for (int k = i; k <= j; k++) {
                sorted.get(k).setPercentileRank(pct);
            }
            i = j + 1;
        }
    }
}
