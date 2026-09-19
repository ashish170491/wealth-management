package com.example.trading.universe.theme;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How much of each policy-backed theme this app actually looks at (SPEC §51.3).
 *
 * <p><b>This class is the answer to the question that started the feature.</b> "Are we tracking
 * the semiconductor names?" is not answered by a theme column — a column can only describe stocks
 * already in the universe, so a theme nobody screens renders as an empty column and reads as a
 * theme with no stocks in it. That is Gotcha 44 in its most flattering form: an absence of
 * findings presented as a clean result. The only honest answer counts members the universe does
 * <em>not</em> reach, which means the denominator has to come from the map rather than from the
 * screening run.
 *
 * <p><b>Three states, never two.</b> A member is {@link Status#SCREENED} (the app analyses it),
 * {@link Status#NOT_SCREENED} (a confirmed NSE name the universe does not reach — the gap worth
 * acting on) or {@link Status#UNVERIFIED} (the ticker is outside NSE's index constituent lists so
 * this app cannot confirm it exists). Folding the third into either of the others is what would
 * let a typo inflate a coverage figure, so an unverified member counts toward neither the
 * numerator nor the denominator of {@link Coverage#coveragePercent()} and is reported on its own.
 *
 * <p><b>Screened outranks verified, and the order is load-bearing.</b> {@code verified} only asks
 * whether the sector table names the ticker; the screening universe resolving it is strictly
 * better evidence. Testing verified first reported a stock the app screens and scores as one whose
 * ticker could not be confirmed — found by running the screen, not in review.
 *
 * <p>Pure: no repository, no clock, no I/O. Computes no score and ranks nothing.
 */
public final class ThemeCoverage {

    private ThemeCoverage() {
    }

    /** What the app can say about one tagged business. */
    public enum Status {
        /** In the screening universe, so every pillar runs on it in the ordinary way. */
        SCREENED,
        /** A confirmed listing the screening universe does not reach. The actionable gap. */
        NOT_SCREENED,
        /** Outside NSE's index lists, so this app cannot confirm the ticker. Never counted as covered. */
        UNVERIFIED
    }

    /**
     * One member of a theme with its status.
     *
     * @param held whether the investor already owns it — the tag is the same either way, this is
     *             only so a screen can say "you own two of these" without a second join
     */
    public record Member(String symbol, ThemeCatalog theme, String policy, String role,
                         Status status, boolean held) {
    }

    /**
     * One theme's coverage.
     *
     * @param covered      members in the screening universe
     * @param gaps         confirmed listings the universe does not reach
     * @param unverified   members whose ticker could not be confirmed here
     * @param held         members the investor owns
     */
    public record Coverage(ThemeCatalog theme, int tagged, int covered, int gaps, int unverified,
                           int held, List<Member> members) {

        /**
         * Share of the <b>confirmable</b> members the app screens, or null when there are none.
         *
         * <p>Null rather than zero, and unverified members leave the denominator entirely: a
         * theme made up wholly of names this app cannot confirm has no coverage figure, which is
         * a different statement from having a coverage of nothing (Gotcha 21, Gotcha 68).
         */
        public Double coveragePercent() {
            int denominator = covered + gaps;
            if (denominator == 0) {
                return null;
            }
            return covered * 100.0 / denominator;
        }

        /** The members worth acting on, in file order. */
        public List<Member> gapMembers() {
            return members.stream().filter(m -> m.status() == Status.NOT_SCREENED).toList();
        }

        /**
         * One sentence naming what this figure does and does not claim.
         *
         * <p>Mandatory on every surface. "8 of 8 covered" invites the reader to conclude the
         * theme is fully researched, when all it says is that eight businesses someone typed into
         * a file are in the screening universe — the map's own completeness is unmeasured and
         * unmeasurable, because there is no published list of "every Indian semiconductor stock"
         * to check it against.
         */
        public String caveat() {
            StringBuilder sb = new StringBuilder();
            sb.append("Counted against the ").append(tagged)
                    .append(tagged == 1 ? " business" : " businesses")
                    .append(" this app's own theme map names, which is a hand-kept list and not a "
                            + "complete census of the sector — a name nobody has added is invisible "
                            + "to this figure.");
            if (unverified > 0) {
                sb.append(" ").append(unverified)
                        .append(unverified == 1 ? " name is" : " names are")
                        .append(" outside NSE's index lists so the ticker could not be confirmed "
                                + "here; they are excluded from the percentage rather than counted "
                                + "either way.");
            }
            sb.append(" Being screened means the app analyses the business, never that it is worth "
                    + "owning.");
            return sb.toString();
        }
    }

    /**
     * Coverage for one theme.
     *
     * @param screened bare symbols the screening universe reaches, already normalised by the caller
     * @param heldBare bare symbols the investor holds, already normalised by the caller
     */
    public static Coverage forTheme(ThemeCatalog theme, List<UniverseThemes.Tag> tags,
                                    Set<String> screened, Set<String> heldBare) {
        List<Member> members = new ArrayList<>();
        int covered = 0;
        int gaps = 0;
        int unverified = 0;
        int held = 0;

        for (UniverseThemes.Tag t : tags) {
            Status status;
            // BEING SCREENED OUTRANKS THE SECTOR TABLE, and the order here is the whole point.
            // `verified` only asks whether universe-sectors.csv (seeded from NSE's index lists)
            // names the ticker. The screening universe resolving it is strictly better evidence:
            // the app fetched candles for it and scored it. Checking `verified` first told the
            // investor that CENTUM - a stock this app screens and has a composite for - was one
            // whose ticker could not be confirmed, and dropped it out of the coverage figure.
            // Found by running the screen, not in review.
            if (screened != null && screened.contains(t.symbol())) {
                status = Status.SCREENED;
                covered++;
            } else if (!t.verified()) {
                status = Status.UNVERIFIED;
                unverified++;
            } else {
                status = Status.NOT_SCREENED;
                gaps++;
            }
            boolean owned = heldBare != null && heldBare.contains(t.symbol());
            if (owned) {
                held++;
            }
            members.add(new Member(t.symbol(), t.theme(), t.policy(), t.role(), status, owned));
        }

        return new Coverage(theme, tags.size(), covered, gaps, unverified, held, List.copyOf(members));
    }

    /** Every populated theme's coverage, in catalogue order. */
    public static List<Coverage> all(Set<String> screened, Set<String> heldBare) {
        List<Coverage> out = new ArrayList<>();
        for (ThemeCatalog theme : UniverseThemes.populatedThemes()) {
            out.add(forTheme(theme, UniverseThemes.membersOf(theme), screened, heldBare));
        }
        return out;
    }

    /**
     * Normalise a mixed bag of symbols — prefixed, bare, any case — into the bare upper-case form
     * the map is keyed on.
     *
     * <p>Exists so every caller normalises the same way. A screening universe compared prefixed
     * against a bare map finds nothing and reports a coverage of zero across every theme, which
     * looks exactly like a feature nobody wired up.
     */
    public static Set<String> normalise(Iterable<String> symbols) {
        Set<String> out = new LinkedHashSet<>();
        if (symbols == null) {
            return out;
        }
        for (String s : symbols) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String v = s.trim();
            int colon = v.indexOf(':');
            if (colon >= 0) {
                v = v.substring(colon + 1);
            }
            v = v.trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
