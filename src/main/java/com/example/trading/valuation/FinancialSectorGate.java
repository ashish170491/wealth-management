package com.example.trading.valuation;

import com.example.trading.ai.NseDataService;
import com.example.trading.portfolio.SectorMapping;

import java.util.Set;

/**
 * Gate G1b/G1c: lenders and other financials are outside this lens, and so is anything whose sector
 * nobody recorded (SPEC §57.3.4, decision D3). Pure.
 *
 * <p><b>Composed, not copied.</b> This codebase already carried five copies of "who is a lender" and
 * B-144 / Gotcha 151 are what that cost: fix it in one place and it stays wrong in the rest. So this
 * class adds <b>no new pattern</b>. It asks the three authorities that exist:
 * <ol>
 *   <li>{@code capexVerdict = NA_FINANCIAL} on a stored screening row - the marker that actually
 *       fires for banks (Gotcha 88), read here for any spelling of the symbol;</li>
 *   <li>{@link NseDataService#isLenderIndustry} - the industry test, which also catches the
 *       fee-based financials (asset managers, registrars). They are excluded here too: a log-linear
 *       earnings trend and a price-to-earnings table do not describe a business whose product is
 *       balance-sheet risk, and the difference between a fee business and a lender is a measured cut
 *       (equity over assets) that this lens has no need to draw;</li>
 *   <li>{@link SectorMapping#resolve} - the shared sector vocabulary, where a placeholder such as
 *       {@code GENERAL} or {@code Other} is <i>unclassified</i> and never a bucket (Gotcha 110).</li>
 * </ol>
 * An unclassified business is refused rather than assumed to be an industrial: treating an unknown
 * sector as "not a lender" would let a bank through on a missing label.
 */
public final class FinancialSectorGate {

    private FinancialSectorGate() {
    }

    /** Buckets {@link SectorMapping} uses for lenders and insurers. */
    private static final Set<String> FINANCIAL_BUCKETS = Set.of("BANKING", "FINANCIALS");

    /** A refusal, or none. {@code reason} is null when the stock may be read. */
    public record Result(ValuationReading.Reason reason, String text) {
        public boolean passes() {
            return reason == null;
        }
    }

    private static final Result PASS = new Result(null, null);

    /**
     * @param naFinancialMarker true when any stored screening row, under any spelling, carries
     *                          {@code capexVerdict = NA_FINANCIAL}
     */
    public static Result check(String symbol, String rawIndustry, boolean naFinancialMarker) {
        if (naFinancialMarker) {
            return new Result(ValuationReading.Reason.UNSUPPORTED_SECTOR,
                    "The screening marks this business as a financial. Banks, lenders and insurers are outside "
                            + "this lens: their earnings and share counts are not comparable with an industrial's.");
        }
        if (NseDataService.isLenderIndustry(rawIndustry)) {
            return new Result(ValuationReading.Reason.UNSUPPORTED_SECTOR,
                    "The industry on record (" + rawIndustry.trim() + ") is a financial one. Banks, lenders, "
                            + "insurers and fee-based financial businesses are outside this lens.");
        }
        String sector = SectorMapping.resolve(symbol, rawIndustry);
        if (SectorMapping.isUnknown(sector)) {
            return new Result(ValuationReading.Reason.UNCLASSIFIED,
                    "No sector is recorded for this business"
                            + (rawIndustry == null || rawIndustry.isBlank() ? "" : " (the industry field reads \""
                            + rawIndustry.trim() + "\", a placeholder)")
                            + ". It cannot be confirmed as outside the financial sector, so it is not read.");
        }
        if (FINANCIAL_BUCKETS.contains(sector)) {
            return new Result(ValuationReading.Reason.UNSUPPORTED_SECTOR,
                    "The sector (" + sector + ") is a financial one and is outside this lens.");
        }
        return PASS;
    }
}
