package com.example.trading.fundamentals;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two writers of {@code annual_fundamentals} and what happens where they overlap
 * (SPEC §32.1, B-046).
 *
 * <p>{@code source=IMPORT} is the user's CSV; {@code source=XBRL} is the annual filing,
 * written by every screening run. The filing outranks a third-party rendering of the same
 * year — but only for the figures it actually carries. NSE's tagging coverage varies by
 * company and by taxonomy, and an untagged field arriving as a blanket null used to erase
 * a real imported figure permanently: the row is marked XBRL either way, and the import
 * path deliberately refuses to overwrite an XBRL row, so nothing could ever restore it.
 */
class FundamentalsTwoWriterTest {

    private static NseDataService.BalanceSheetData filing() {
        NseDataService.BalanceSheetData bs = new NseDataService.BalanceSheetData();
        bs.setFinancialYear("01-Apr-2024 To 31-Mar-2025");
        bs.setRevenue(1500.0);
        bs.setNetProfit(120.0);
        return bs;   // everything else deliberately untagged
    }

    private static AnnualFundamentalsEntity imported() {
        return AnnualFundamentalsEntity.builder()
                .symbol("NSE:ACME").fiscalYear(2025).source("IMPORT")
                .sales(1450.0)
                .netProfit(110.0)
                .borrowings(550.0)
                .receivables(260.0)
                .shareCount(50.0)
                .operatingCashFlow(190.0)
                .build();
    }

    @Test
    @DisplayName("B-046: the filing updates what it reports and leaves the rest of the import intact")
    void xbrlDoesNotBlankImportedFigures() {
        AnnualFundamentalsRepository repo = mock(AnnualFundamentalsRepository.class);
        NseDataService nse = mock(NseDataService.class);
        AnnualFundamentalsEntity row = imported();

        when(repo.findBySymbolAndFiscalYear(anyString(), anyInt())).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(nse.fetchAnnualFinancials(anyString())).thenReturn(filing());

        assertThat(new FundamentalsHistoryService(repo, nse).recordFromXbrl("NSE:ACME")).isTrue();

        // Reported by the filing -> the filing wins.
        assertThat(row.getSales()).isEqualTo(1500.0);
        assertThat(row.getNetProfit()).isEqualTo(120.0);
        assertThat(row.getSource()).isEqualTo("XBRL");

        // NOT reported by the filing -> the imported figures survive. These were being set
        // to null, which silently disabled the borrowings-based turnaround criteria and both
        // forensic checks that depend on receivables and share count.
        assertThat(row.getBorrowings()).isEqualTo(550.0);
        assertThat(row.getReceivables()).isEqualTo(260.0);
        assertThat(row.getShareCount()).isEqualTo(50.0);
        assertThat(row.getOperatingCashFlow()).isEqualTo(190.0);
    }

    @Test
    @DisplayName("B-046: receivables, share count and net block are written when the filing has them")
    void xbrlWritesTheFieldsNothingUsedToWrite() {
        AnnualFundamentalsRepository repo = mock(AnnualFundamentalsRepository.class);
        NseDataService nse = mock(NseDataService.class);
        AnnualFundamentalsEntity row = imported();

        NseDataService.BalanceSheetData bs = filing();
        bs.setTradeReceivables(300.0);
        bs.setSharesOutstandingCr(52.0);
        bs.setPropertyPlantEquipment(2200.0);

        when(repo.findBySymbolAndFiscalYear(anyString(), anyInt())).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(nse.fetchAnnualFinancials(anyString())).thenReturn(bs);

        new FundamentalsHistoryService(repo, nse).recordFromXbrl("NSE:ACME");

        assertThat(row.getReceivables()).isEqualTo(300.0);
        assertThat(row.getShareCount()).isEqualTo(52.0);
        // Net block is stored so NEXT year's capex-to-depreciation has a prior year to work
        // from — the filing itself carries no comparative balance sheet (B-034/B-048).
        assertThat(row.getNetBlock()).isEqualTo(2200.0);
    }

    private static NseDataService.BalanceSheetData basis(boolean consolidated) {
        NseDataService.BalanceSheetData bs = new NseDataService.BalanceSheetData();
        bs.setConsolidated(consolidated);
        return bs;
    }

    /**
     * NSE's archive does not always carry both bases. Measured on RELIANCE: FY2022 exists only as
     * a standalone filing while the years either side are consolidated, and standalone revenue
     * there is roughly half the group figure. Mixing them manufactures a collapse and a recovery
     * that never happened, and every CAGR, margin trend and turnaround verdict downstream reads it
     * as real. A gap is handled; a fabrication is not detectable.
     */
    @Test
    @DisplayName("A backfilled series settles on one reporting basis, consolidated winning a tie")
    void seriesBasisPrefersTheMajorityAndBreaksTiesToConsolidated() {
        assertThat(FundamentalsHistoryService.seriesBasis(java.util.List.of(
                basis(true), basis(true), basis(false), basis(true))))
                .as("one odd standalone year does not flip a consolidated series")
                .isTrue();

        assertThat(FundamentalsHistoryService.seriesBasis(java.util.List.of(
                basis(false), basis(false), basis(false))))
                .as("a company that never files consolidated is a standalone series")
                .isFalse();

        assertThat(FundamentalsHistoryService.seriesBasis(java.util.List.of(
                basis(true), basis(false))))
                .as("a tie goes to consolidated - a group that reports consolidated is a group")
                .isTrue();
    }

    @Test
    @DisplayName("A year with no stored row is created rather than skipped")
    void newYearIsCreated() {
        AnnualFundamentalsRepository repo = mock(AnnualFundamentalsRepository.class);
        NseDataService nse = mock(NseDataService.class);

        when(repo.findBySymbolAndFiscalYear(anyString(), anyInt())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(nse.fetchAnnualFinancials(anyString())).thenReturn(filing());

        assertThat(new FundamentalsHistoryService(repo, nse).recordFromXbrl("NSE:ACME")).isTrue();
    }
}
