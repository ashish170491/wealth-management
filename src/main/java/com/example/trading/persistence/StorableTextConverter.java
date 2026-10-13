package com.example.trading.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Applies {@link StorableText} to every string column on the way into the database (B-172).
 *
 * <p>Auto-applied, so no entity needs annotating and a new one is covered without anyone
 * remembering to. Reads pass through untouched. On a UTF-8 database it does nothing at all.
 *
 * <p>One consequence worth knowing: a value containing a rupee sign is stored as "Rs", so it can
 * grow by a character per rupee sign. A writer that truncates to a column's width should call
 * {@link StorableText#storable} before truncating, as the news scan does.
 */
@Converter(autoApply = true)
public class StorableTextConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return StorableText.storable(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
