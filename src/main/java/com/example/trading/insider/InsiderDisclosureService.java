package com.example.trading.insider;

import com.example.trading.ai.NseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ingests SEBI insider disclosures into {@link InsiderDisclosureEntity} (SPEC §28, F1).
 *
 * <p>The current insider signal in the screener is the <b>quarterly</b> shareholding pattern —
 * up to three months stale by the time it is read. PIT Regulation 7(2) filings are published
 * daily, and open-market promoter buying is the most reliable early tell available for free.
 *
 * <h2>Mode filtering is the whole feature</h2>
 * Only {@code MARKET_PURCHASE} / {@code MARKET_SALE} carry signal. Pledge creation and
 * revocation, ESOP allotments, inter-se promoter transfers and gifts are recorded (so the
 * ledger stays complete and auditable) but never scored. A screen that counts a pledge
 * creation as "promoter buying" is worse than no screen at all: a pledge is a promoter
 * borrowing against the company, which is the opposite signal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsiderDisclosureService {

    private final NseDataService nseDataService;
    private final InsiderDisclosureRepository repository;

    /**
     * PIT rows use "13-Feb-2026" and the archive CSVs use "25-AUG-2026" — same pattern,
     * different casing. A case-sensitive formatter parses one and silently fails the other,
     * which would drop every bulk deal on the floor as "undateable".
     */
    private static final DateTimeFormatter NSE_DATE = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    /** Categories whose open-market trades count as an insider signal. */
    private static final Set<String> SIGNAL_CATEGORIES =
            Set.of("PROMOTER", "PROMOTER_GROUP", "DIRECTOR", "KMP", "RELATIVE");

    /** Modes that represent a real conviction trade on the open market. */
    static final String MODE_BUY = "MARKET_PURCHASE";
    static final String MODE_SELL = "MARKET_SALE";

    /**
     * Fetch and persist PIT disclosures for one symbol. Idempotent: rows already stored
     * (matched on their natural-key hash) are skipped, so re-runs and crash recovery never
     * duplicate.
     *
     * @param currentPrice used only to derive a rupee value when the filing omits one;
     *                     pass &lt;= 0 if unknown (value is then left null, not guessed)
     * @return number of NEW rows persisted
     */
    public int capturePitForSymbol(String symbol, double currentPrice) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        List<Map<String, Object>> raw = nseDataService.fetchPitDisclosures(tradingSymbol);
        if (raw.isEmpty()) return 0;

        List<InsiderDisclosureEntity> candidates = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            InsiderDisclosureEntity e = toEntity(symbol, tradingSymbol, row, currentPrice);
            if (e != null) candidates.add(e);
        }
        return persistNew(candidates);
    }

    /**
     * Fetch today's all-market bulk and block deals. One call each covers the entire
     * universe — unlike PIT, which NSE only serves per-symbol (see
     * {@link NseDataService#fetchPitDisclosures}).
     *
     * @return number of NEW rows persisted
     */
    public int captureDeals() {
        int saved = 0;
        for (boolean block : new boolean[]{false, true}) {
            List<Map<String, String>> rows = nseDataService.fetchDealsCsv(block);
            List<InsiderDisclosureEntity> candidates = new ArrayList<>();
            for (Map<String, String> r : rows) {
                InsiderDisclosureEntity e = dealToEntity(r, block);
                if (e != null) candidates.add(e);
            }
            int n = persistNew(candidates);
            log.info("Insider capture: {} deals — {} rows fetched, {} new", block ? "block" : "bulk", rows.size(), n);
            saved += n;
        }
        return saved;
    }

    /**
     * Persist only rows whose hash is not already stored. Batched existence check so a
     * 200-row filing is one query, not 200.
     */
    private int persistNew(List<InsiderDisclosureEntity> candidates) {
        if (candidates.isEmpty()) return 0;
        Set<String> hashes = new HashSet<>();
        for (InsiderDisclosureEntity e : candidates) hashes.add(e.getDisclosureHash());
        Set<String> existing = new HashSet<>(repository.findExistingHashes(hashes));

        List<InsiderDisclosureEntity> fresh = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        for (InsiderDisclosureEntity e : candidates) {
            // The feed itself can repeat a row within one response; dedupe in-batch too,
            // otherwise the unique constraint rejects the whole save.
            if (existing.contains(e.getDisclosureHash()) || !seenInBatch.add(e.getDisclosureHash())) continue;
            fresh.add(e);
        }
        if (fresh.isEmpty()) return 0;
        try {
            repository.saveAll(fresh);
        } catch (Exception ex) {
            log.warn("Insider capture: batch save failed ({}), falling back to per-row", ex.getMessage());
            int ok = 0;
            int failed = 0;
            String firstError = null;
            for (InsiderDisclosureEntity e : fresh) {
                try {
                    repository.save(e);
                    ok++;
                } catch (Exception rowEx) {
                    // A losing race on the unique constraint is expected and harmless. Any
                    // OTHER cause means rows are being dropped, and a silent drop here is how
                    // a "successful" capture can quietly lose half its data (B-026 lesson) —
                    // so persisted and attempted are always reported together.
                    failed++;
                    if (firstError == null) firstError = rowEx.getMessage();
                }
            }
            if (failed > 0) {
                log.error("Insider capture: {} of {} rows could not be persisted. First cause: {}",
                        failed, fresh.size(), firstError);
            }
            return ok;
        }
        return fresh.size();
    }

    // ------------------------------------------------------------------
    // PIT V2.0 ingestion via the all-market filing index (B-089)
    // ------------------------------------------------------------------

    /** What one PIT pass did. Attempted and persisted are reported together (B-026). */
    public record PitCaptureSummary(int filingsInFeed, int filingsForUniverse, int filingsFetched,
                                    int newRows, int alreadyIngested, boolean truncated,
                                    LocalDate newestFilingDate) {}

    /**
     * Ingest every new PIT filing for the symbols we care about, from the all-market index.
     *
     * <p>Replaces the per-symbol probing loop (B-089/B-090). NSE now publishes one index
     * covering the whole market, so there is no symbol budget to ration and no rotation to get
     * wrong: <b>every</b> tracked symbol is checked on every run, and the only per-filing cost
     * is one XBRL fetch for a filing we have never ingested.
     *
     * <p>Filings are keyed by {@code appId}, which is unique per filing across the feed
     * (verified over 2,381 rows), so a re-run costs one index call and nothing else.
     *
     * <p>{@code newestFilingDate} is the newest broadcast date in the <b>whole feed</b>, not
     * just our universe - it is the input to the staleness check, and a feed that has stopped
     * advancing must be visible even in a week when none of our own stocks filed anything.
     * That is the reading that would have caught B-089 in May instead of September.
     *
     * @param wantedTradingSymbols unqualified NSE trading symbols (no {@code NSE:} prefix)
     * @param maxFilings           hard cap on XBRL fetches this run
     * @param deadlineMs           wall-clock stop; fetches cease at this point and the summary
     *                             reports {@code truncated}. Checked <i>between</i> filings, so
     *                             a run that starts in time cannot overrun the window it was
     *                             given (B-082's rule).
     */
    public PitCaptureSummary capturePitFromIndex(Set<String> wantedTradingSymbols,
                                                 int maxFilings, long deadlineMs) {
        List<Map<String, Object>> index = nseDataService.fetchPitFilingIndex();
        if (index.isEmpty()) {
            return new PitCaptureSummary(0, 0, 0, 0, 0, false, null);
        }

        LocalDate newest = null;
        for (Map<String, Object> row : index) {
            LocalDate d = parseIsoOrNseDate(firstToken(str(row, "broadcastDateTime")));
            if (d != null && (newest == null || d.isAfter(newest))) newest = d;
        }

        Set<String> ingested = new HashSet<>(repository.findIngestedFilingAppIds());
        int forUniverse = 0;
        int fetched = 0;
        int newRows = 0;
        int already = 0;
        boolean truncated = false;

        for (Map<String, Object> row : index) {
            String tradingSymbol = str(row, "symbol");
            if (tradingSymbol == null || tradingSymbol.isBlank()) continue;
            tradingSymbol = tradingSymbol.trim();
            if (!wantedTradingSymbols.contains(tradingSymbol)) continue;
            forUniverse++;

            String appId = str(row, "appId");
            if (appId != null && ingested.contains(appId)) {
                already++;
                continue;
            }
            if (fetched >= maxFilings || System.currentTimeMillis() >= deadlineMs) {
                truncated = true;
                continue;   // keep counting what we did not reach, rather than breaking blind
            }

            String xmlUrl = str(row, "xmlFileName");
            LocalDate broadcast = parseIsoOrNseDate(firstToken(str(row, "broadcastDateTime")));
            try {
                NseDataService.pace();
                fetched++;
                newRows += capturePitFiling("NSE:" + tradingSymbol, tradingSymbol, appId, xmlUrl, broadcast);
            } catch (Exception e) {
                log.warn("Insider capture: filing {} for {} failed ({}) - its trades are absent "
                        + "from the pulse, which reads as 'no insider traded'.",
                        appId, tradingSymbol, e.getMessage());
            }
        }

        if (truncated) {
            log.info("Insider capture: stopped at {} filings (cap {}) - the rest are picked up "
                    + "next run; nothing is lost because ingestion is keyed on appId.",
                    fetched, maxFilings);
        }
        return new PitCaptureSummary(index.size(), forUniverse, fetched, newRows, already, truncated, newest);
    }

    /** Fetch and persist one filing's disclosed transactions. Idempotent on the row hash. */
    int capturePitFiling(String symbol, String tradingSymbol, String appId,
                         String xmlUrl, LocalDate broadcastDate) {
        List<Map<String, String>> facts = nseDataService.fetchPitFilingDisclosures(xmlUrl);
        if (facts.isEmpty()) return 0;
        List<InsiderDisclosureEntity> candidates = new ArrayList<>();
        for (Map<String, String> f : facts) {
            InsiderDisclosureEntity e = xbrlToEntity(symbol, tradingSymbol, appId, f, broadcastDate);
            if (e != null) candidates.add(e);
        }
        return persistNew(candidates);
    }

    /**
     * Map one disclosed transaction from a PIT V2.0 XBRL filing.
     *
     * <p>The normalisers are shared with the old JSON feed on purpose: the XBRL spells the
     * same concepts the same way ("Market Purchase", "Promoter", "Buy"), so one vocabulary
     * covers both eras and a mode filter fixed in one place stays fixed in both. The hash is
     * built over the same natural key too, which is what lets the pre-May archive and the
     * post-May feed share a table without double-counting a trade published in both.
     */
    InsiderDisclosureEntity xbrlToEntity(String symbol, String tradingSymbol, String appId,
                                         Map<String, String> f, LocalDate broadcastDate) {
        String mode = normaliseMode(f.get("ModeOfAcquisitionOrDisposal"));
        String category = normaliseCategory(f.get("CategoryOfPerson"));
        String person = f.get("NameOfThePerson");
        Double qty = num(f.get("SecuritiesAcquiredOrDisposedNumberOfSecurity"));
        Double value = num(f.get("SecuritiesAcquiredOrDisposedValueOfSecurity"));

        LocalDate txDate = parseIsoOrNseDate(
                f.get("DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyFromDate"));
        if (txDate == null) {
            txDate = parseIsoOrNseDate(
                    f.get("DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyToDate"));
        }
        LocalDate discDate = parseIsoOrNseDate(f.get("DateOfFiling"));
        if (discDate == null) discDate = broadcastDate;
        if (txDate == null) txDate = discDate;
        if (txDate == null) return null;   // undateable row is unusable for a rolling window

        txDate = resolveTransactionDate(symbol, person, txDate, discDate);
        if (txDate == null) return null;

        String type = normaliseType(f.get("SecuritiesAcquiredOrDisposedTransactionType"), mode);

        // The XBRL reports the post-trade holding as a FRACTION of equity (BERGEPAINT:
        // 859,788 shares of ~1.16bn recorded as 0.0007), while this column has always held a
        // percentage from the old feed. Converted so one column means one thing. Nothing reads
        // it today, so a filer who reports a true percentage instead would not distort any
        // output - but a future consumer must verify the unit before trusting it.
        Double pctAfter = num(f.get("SecuritiesHeldPostAcquistionOrDisposalPercentageOfShareholding"));
        if (pctAfter != null) pctAfter = pctAfter * 100.0;

        return InsiderDisclosureEntity.builder()
                .symbol(symbol)
                .tradingSymbol(tradingSymbol)
                .personName(person)
                .personCategory(category)
                .transactionType(type)
                .mode(mode)
                .quantity(qty)
                .value(value)
                .pctOfEquityAfter(pctAfter)
                .transactionDate(txDate)
                .disclosureDate(discDate)
                .source("PIT")
                .filingAppId(appId)
                .disclosureHash(hash("PIT", symbol, person, txDate, mode, qty, type))
                .build();
    }

    /**
     * Apply the future-date guard (B-031) and return the date to file the trade under.
     *
     * <p>Extracted so both the pre-May JSON path and the PIT V2.0 XBRL path run the identical
     * rule. A guard that exists in two copies is a guard that gets fixed in one of them
     * (the {@code ForensicSeverity} lesson, Gotcha 77).
     *
     * <p>NSE publishes filings whose transaction date is after their own disclosure date and
     * after today - observed 2026-08-25, three SOLARINDS rows dated 09-Nov-2026 but disclosed
     * 11-Mar-2026. A future-dated row never ages out of a trailing window, so it would count
     * as "recent insider activity" indefinitely.
     *
     * @return the usable transaction date, or null when the row must be dropped
     */
    LocalDate resolveTransactionDate(String symbol, String person, LocalDate txDate, LocalDate discDate) {
        LocalDate today = LocalDate.now();
        if (txDate == null || !txDate.isAfter(today)) return txDate;
        if (discDate != null && !discDate.isAfter(today)) {
            log.debug("Insider row for {} dated {} in the future; using disclosure date {}",
                    symbol, txDate, discDate);
            return discDate;
        }
        log.warn("Dropping insider disclosure for {} ({}): transaction date {} is in the future "
                + "and no usable disclosure date - bad source data from NSE.", symbol, person, txDate);
        return null;
    }

    /**
     * Parse an NSE date in either era's format.
     *
     * <p>The JSON feed writes "13-Feb-2026"; the XBRL writes ISO "2026-02-13". ISO is tried
     * first because it is unambiguous.
     */
    static LocalDate parseIsoOrNseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return LocalDate.parse(t);
        } catch (Exception ignored) {
            return parseDate(t);
        }
    }

    // ---- Normalisation ----

    InsiderDisclosureEntity toEntity(String symbol, String tradingSymbol,
                                     Map<String, Object> row, double currentPrice) {
        String mode = normaliseMode(str(row, "acqMode"));
        String category = normaliseCategory(str(row, "personCategory"));
        String person = str(row, "acqName");
        Double qty = num(str(row, "secAcq"));
        Double value = num(str(row, "secVal"));
        if ((value == null || value <= 0) && qty != null && currentPrice > 0) {
            value = qty * currentPrice;
        }
        LocalDate txDate = parseDate(str(row, "acqfromDt"));
        LocalDate discDate = parseDate(firstToken(str(row, "date")));
        if (txDate == null) txDate = discDate;
        if (txDate == null) return null;   // undateable row is unusable for a rolling window

        txDate = resolveTransactionDate(symbol, person, txDate, discDate);
        if (txDate == null) return null;   // future-dated, no usable fallback (B-031)

        String type = normaliseType(str(row, "tdpTransactionType"), mode);

        return InsiderDisclosureEntity.builder()
                .symbol(symbol)
                .tradingSymbol(tradingSymbol)
                .personName(person)
                .personCategory(category)
                .transactionType(type)
                .mode(mode)
                .quantity(qty)
                .value(value)
                .pctOfEquityAfter(num(str(row, "afterAcqSharesPer")))
                .transactionDate(txDate)
                .disclosureDate(discDate)
                .source("PIT")
                .disclosureHash(hash("PIT", symbol, person, txDate, mode, qty, type))
                .build();
    }

    InsiderDisclosureEntity dealToEntity(Map<String, String> row, boolean block) {
        String sym = row.get("Symbol");
        if (sym == null || sym.isBlank()) return null;
        LocalDate date = parseDate(row.get("Date"));
        if (date == null) return null;
        String bs = row.getOrDefault("Buy/Sell", "");
        String type = bs.toUpperCase(Locale.ROOT).startsWith("B") ? "BUY" : "SELL";
        Double qty = num(row.get("Quantity Traded"));
        Double price = num(row.get("Trade Price / Wght. Avg. Price"));
        Double value = (qty != null && price != null) ? qty * price : null;
        String client = row.getOrDefault("Client Name", "");
        String source = block ? "BLOCK" : "BULK";

        return InsiderDisclosureEntity.builder()
                .symbol("NSE:" + sym.trim())
                .tradingSymbol(sym.trim())
                .personName(client)
                .personCategory("INSTITUTION")
                .transactionType(type)
                .mode("BULK_DEAL")
                .quantity(qty)
                .value(value)
                .transactionDate(date)
                .disclosureDate(date)
                .source(source)
                .disclosureHash(hash(source, "NSE:" + sym.trim(), client, date, "BULK_DEAL", qty, type))
                .build();
    }

    static String normaliseMode(String acqMode) {
        if (acqMode == null) return "OTHER";
        String m = acqMode.toLowerCase(Locale.ROOT);
        if (m.contains("market purchase")) return MODE_BUY;
        if (m.contains("market sale") || m.contains("market sell")) return MODE_SELL;
        // "Revokation of Pledge" is NSE's spelling, not a typo on our side.
        if (m.contains("revok") || m.contains("revoc")) return "PLEDGE_REVOCATION";
        if (m.contains("pledge") || m.contains("encumbr")) return "PLEDGE_CREATION";
        if (m.contains("inter-se") || m.contains("inter se")) return "INTER_SE";
        if (m.contains("esop") || m.contains("allot")) return "ESOP";
        if (m.contains("gift")) return "GIFT";
        if (m.contains("off market") || m.contains("off-market")) return "OFF_MARKET";
        return "OTHER";
    }

    static String normaliseCategory(String cat) {
        if (cat == null) return "OTHER";
        String c = cat.toLowerCase(Locale.ROOT);
        if (c.contains("promoter group")) return "PROMOTER_GROUP";
        if (c.contains("promoter")) return "PROMOTER";
        if (c.contains("director")) return "DIRECTOR";
        if (c.contains("key managerial") || c.contains("kmp")) return "KMP";
        if (c.contains("relative")) return "RELATIVE";
        if (c.contains("employee")) return "EMPLOYEE";
        return "OTHER";
    }

    /**
     * Buy or sell, from NSE's "type of disclosure" field with the acquisition mode as fallback.
     *
     * <p><b>B-040</b>: NSE writes {@code "-"} (and occasionally {@code "NA"}) where a field is
     * simply absent — {@link #num(String)} has always treated that as null. Here it was read as
     * a real value, and since {@code "-"} does not start with "s" every such row became a
     * <b>BUY</b>. A {@code MARKET_SALE} with a blanked type therefore <i>added</i> to net
     * insider buying, which is the one direction the error must never go: the pulse exists to
     * spot promoters accumulating, and this let promoters selling look like promoters buying.
     * The mode is the more reliable field of the two, so an absent type defers to it.
     */
    static String normaliseType(String tdp, String mode) {
        if (isMeaningful(tdp)) {
            return tdp.trim().toLowerCase(Locale.ROOT).startsWith("s") ? "SELL" : "BUY";
        }
        return MODE_SELL.equals(mode) ? "SELL" : "BUY";
    }

    /** False for null, blank, and NSE's absent-value placeholders. */
    private static boolean isMeaningful(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.trim();
        return !"-".equals(t) && !"--".equals(t) && !t.equalsIgnoreCase("na") && !t.equalsIgnoreCase("n/a");
    }

    /** True when this row is an open-market trade by someone whose trades mean something. */
    static boolean isSignalRow(InsiderDisclosureEntity e) {
        return (MODE_BUY.equals(e.getMode()) || MODE_SELL.equals(e.getMode()))
                && SIGNAL_CATEGORIES.contains(e.getPersonCategory());
    }

    /**
     * Stable, fixed-length digest of a disclosure's natural key.
     *
     * <p>Must be a hash, not the concatenated key itself: person names include long entity
     * names ("Sohan Devi Nand Lal Nuwal Family Trust" and worse), and the raw concatenation
     * overflowed {@code varchar(128)} on real NSE data, failing the batch insert. SHA-256
     * hex is always 64 characters whatever the inputs.
     */
    private static String hash(String source, String symbol, String person, LocalDate date,
                               String mode, Double qty, String type) {
        String key = source + "|" + symbol + "|" + (person == null ? "" : person.trim().toLowerCase(Locale.ROOT))
                + "|" + date + "|" + mode + "|" + (qty == null ? "" : String.valueOf(qty.longValue()))
                + "|" + type;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static String firstToken(String s) {
        if (s == null) return null;
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(0, sp) : s;
    }

    static Double num(String s) {
        if (s == null || s.isBlank() || "-".equals(s.trim())) return null;
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), NSE_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
