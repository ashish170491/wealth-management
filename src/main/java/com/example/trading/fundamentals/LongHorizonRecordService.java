package com.example.trading.fundamentals;

import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The two long-horizon reads that need a decade of accounts: the capital-allocation record
 * (SPEC §42) and the compounding track record (SPEC §43).
 *
 * <p>This is the only class in the pair that touches a repository. {@link CapitalAllocationRecord}
 * and {@link CompoundingPersistence} are pure and stay that way, so the rules can be tested
 * without a database — the same split as {@code CoreHoldingService} and {@code DurabilityScorer}.
 *
 * <p><b>DB-only.</b> Two queries per symbol and no network call, so it is safe on a page load.
 * That is verified rather than asserted: a "DB-only" javadoc is load-bearing and has been wrong
 * before (B-039, where a dashboard-safe label sat on a method that made an XBRL fetch on a cold
 * cache, every first call of the day).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongHorizonRecordService {

    private final AnnualFundamentalsRepository annualRepository;
    private final MultibaggerScoreRepository scoreRepository;

    /**
     * @param resolvedSymbol which symbol variant actually answered — recorded, never assumed
     *                       (Gotcha 84: 22 of 33 holdings are BSE-prefixed while all analytical
     *                       history is keyed on the NSE symbol)
     */
    @Data
    @Builder
    public static class LongHorizonView {
        private String symbol;
        private String resolvedSymbol;
        private int yearsOfAccounts;
        private boolean lender;
        private CapitalAllocationRecord.Result capitalAllocation;
        private CompoundingPersistence.Result trackRecord;
        private String coverageNote;
    }

    public LongHorizonView view(String symbol) {
        String resolved = null;
        List<AnnualFundamentalsEntity> history = List.of();
        for (String candidate : SymbolVariants.candidates(symbol)) {
            List<AnnualFundamentalsEntity> rows = annualRepository.findHistory(candidate);
            if (!rows.isEmpty()) {
                resolved = candidate;
                history = rows;
                break;      // First hit wins; never blend two symbols' histories.
            }
        }
        boolean lender = isLender(symbol, resolved);

        return LongHorizonView.builder()
                .symbol(symbol)
                .resolvedSymbol(resolved)
                .yearsOfAccounts(history.size())
                .lender(lender)
                .capitalAllocation(CapitalAllocationRecord.analyse(history))
                .trackRecord(CompoundingPersistence.analyse(history, lender))
                .coverageNote(coverageNote(history.size()))
                .build();
    }

    /**
     * Is this a bank or lender?
     *
     * <p>Read from {@code capexVerdict = NA_FINANCIAL} on the latest screening row, which is the
     * marker that actually fires. {@code capitalEfficiencyVerdict} looks like the natural field
     * and is not: it puts NA_FINANCIAL on unpersisted sub-verdicts and grades a bank SOLID on
     * return on equity, so a not-applicable branch built on it fires zero times and every bank
     * reads as a gap instead (Gotcha 88). Verified against that finding rather than inferred from
     * the field name.
     *
     * <p>Defaults to false when nothing has been screened: treating an unknown business as a
     * lender would suppress the leverage check, which is the one that most needs to run on a
     * company nobody has looked at.
     */
    private boolean isLender(String symbol, String resolved) {
        for (String candidate : SymbolVariants.candidates(resolved != null ? resolved : symbol)) {
            List<MultibaggerScoreEntity> rows = scoreRepository.findHistoryBySymbol(candidate);
            if (rows.isEmpty()) continue;
            for (MultibaggerScoreEntity row : rows) {
                if ("NA_FINANCIAL".equals(row.getCapexVerdict())) return true;
            }
            return false;
        }
        return false;
    }

    private static String coverageNote(int years) {
        if (years == 0) {
            return "No annual accounts on file for this company yet. The universe backfill "
                    + "(SPEC §32.6) works through the screening universe on weekday batches; until "
                    + "it reaches this symbol, nothing here has been checked — which is not the "
                    + "same as nothing being wrong.";
        }
        if (years < CompoundingPersistence.MIN_YEARS) {
            return years + " year(s) of accounts on file. A track record needs at least "
                    + CompoundingPersistence.MIN_YEARS + ", so no persistence claim is made either "
                    + "way. Capital allocation needs " + CapitalAllocationRecord.MIN_YEARS + ".";
        }
        return years + " years of accounts on file. Balance-sheet checks may still read as "
                + "unmeasured for the older years: NSE's archive filings before 2022 often carry "
                + "the profit and loss account without the balance sheet (SPEC §32.5).";
    }
}
