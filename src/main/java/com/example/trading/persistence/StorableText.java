package com.example.trading.persistence;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites text the database cannot store into text it can (B-172).
 *
 * <p>This app's {@code tradingdb} was created with Windows' default encoding, WIN1252, which has
 * no rupee sign. Postgres rejects a whole write containing one ({@code 22P05 ... has no
 * equivalent in encoding "WIN1252"}), so a single headline reading "₹1,926" aborted the news
 * scan on 8, 9 and 13 Oct. Third-party text reaches a dozen tables here, so this is applied to
 * every string column by {@link StorableTextConverter} rather than at each writer.
 *
 * <p>It is a no-op unless a database encoding that needs it has been detected: on a UTF-8
 * database nothing is ever rewritten, so converting {@code tradingdb} to UTF-8 later switches this
 * off with no code change. The state is static because JPA instantiates the converter itself,
 * outside Spring; {@link SchemaMigrationRunner} sets it at boot, before any scheduled write.
 */
public final class StorableText {

    /** Characters with a sensible spelling in plain text, used before falling back to '?'. */
    private static final Map<Integer, String> SPELLED = Map.of(
            0x20B9, "Rs",      // rupee sign: "₹1,926" -> "Rs1,926" keeps the figure readable
            0x2212, "-",       // minus sign
            0x2010, "-",       // hyphen
            0x2011, "-",       // non-breaking hyphen
            0x2009, " ",       // thin space
            0x202F, " ",       // narrow no-break space
            0x200B, "",        // zero-width space
            0xFEFF, "");       // byte-order mark

    /** Postgres encoding name -> Java charset, for the encodings that cannot hold everything. */
    private static final Map<String, String> PG_TO_JAVA = Map.of(
            "WIN1252", "windows-1252",
            "WIN1250", "windows-1250",
            "WIN1251", "windows-1251",
            "LATIN1", "ISO-8859-1",
            "LATIN9", "ISO-8859-15");

    private static volatile Charset target;

    private StorableText() {
    }

    /**
     * Record the database's encoding. Returns true when rewriting is now active. UTF8 and
     * SQL_ASCII (which stores bytes unchecked) need nothing; an encoding not in the table is left
     * alone too, because guessing its repertoire could corrupt text it would have stored fine.
     */
    public static boolean configure(String serverEncoding) {
        String javaName = serverEncoding == null ? null
                : PG_TO_JAVA.get(serverEncoding.trim().toUpperCase(Locale.ROOT));
        target = javaName == null ? null : Charset.forName(javaName);
        return target != null;
    }

    /** For tests: back to the pass-through state. */
    static void reset() {
        target = null;
    }

    public static boolean active() {
        return target != null;
    }

    /** The text unchanged when it can be stored, otherwise with each offending character replaced. */
    public static String storable(String text) {
        Charset cs = target;
        if (cs == null || text == null || isAscii(text)) return text;
        CharsetEncoder encoder = cs.newEncoder();    // not thread-safe, so one per call
        if (encoder.canEncode(text)) return text;

        StringBuilder out = new StringBuilder(text.length() + 8);
        text.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (encoder.canEncode(ch)) {
                out.append(ch);
            } else {
                out.append(SPELLED.getOrDefault(cp, "?"));
            }
        });
        return out.toString();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }
}
