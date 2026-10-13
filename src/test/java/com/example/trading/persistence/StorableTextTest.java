package com.example.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins B-172: on a WIN1252 database, text it cannot hold is rewritten rather than failing the
 * write; on a UTF-8 database nothing is ever touched.
 *
 * <p>The first headline is the shape that aborted the news scan on 8, 9 and 13 Oct.
 */
class StorableTextTest {

    @AfterEach
    void passThroughAgain() {
        StorableText.reset();
    }

    @Test
    @DisplayName("WIN1252: a rupee sign is spelled 'Rs' so the figure stays readable")
    void rupeeIsSpelled() {
        assertThat(StorableText.configure("WIN1252")).isTrue();
        assertThat(StorableText.storable("Nomura raises target price to ₹1,926"))
                .isEqualTo("Nomura raises target price to Rs1,926");
    }

    @Test
    @DisplayName("WIN1252: what the encoding does hold is left alone - smart quotes, euro, dashes, accents")
    void encodablePunctuationIsKept() {
        StorableText.configure("WIN1252");
        String text = "“Rally” – €5bn deal … café";
        assertThat(StorableText.storable(text)).isSameAs(text);
    }

    @Test
    @DisplayName("WIN1252: a script it cannot hold becomes '?', one per character, never a failed write")
    void unknownScriptBecomesQuestionMarks() {
        StorableText.configure("WIN1252");
        assertThat(StorableText.storable("Sensex बाजार up")).isEqualTo("Sensex ????? up");
        assertThat(StorableText.storable("Markets 🚀")).isEqualTo("Markets ?");   // one emoji, one '?'
    }

    @Test
    @DisplayName("UTF-8 database: nothing is rewritten, so converting tradingdb later needs no code change")
    void utf8IsPassThrough() {
        assertThat(StorableText.configure("UTF8")).isFalse();
        assertThat(StorableText.active()).isFalse();
        assertThat(StorableText.storable("₹1,926 बाजार")).isEqualTo("₹1,926 बाजार");
    }

    @Test
    @DisplayName("An unrecognised or unreadable encoding is left alone rather than guessed")
    void unknownEncodingIsPassThrough() {
        assertThat(StorableText.configure("EUC_JP")).isFalse();
        assertThat(StorableText.configure(null)).isFalse();
        assertThat(StorableText.storable("₹1")).isEqualTo("₹1");
    }

    @Test
    @DisplayName("Nulls and plain ASCII cost nothing and come back as the same object")
    void cheapPaths() {
        StorableText.configure("WIN1252");
        assertThat(StorableText.storable(null)).isNull();
        String ascii = "NSE:RELIANCE";
        assertThat(StorableText.storable(ascii)).isSameAs(ascii);
        assertThat(new StorableTextConverter().convertToEntityAttribute("Rs1")).isEqualTo("Rs1");
    }
}
