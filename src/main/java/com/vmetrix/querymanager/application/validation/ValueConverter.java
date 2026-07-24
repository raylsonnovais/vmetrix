package com.vmetrix.querymanager.application.validation;

import com.vmetrix.querymanager.domain.metadata.DataType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Converts a single raw filter value into the typed form implied by a field's {@link DataType}.
 *
 * <p>Kept as a small, stateless, standalone unit so the conversion rules can be tested directly and
 * reused for every element of a list/range value. On any failure it throws
 * {@link ValueConversionException} with a caller-friendly message; it never returns a partially
 * converted or raw value.
 *
 * <p>Conversion rules (per the decisions recorded in {@code docs/ai-log.md}):
 * <ul>
 *   <li>{@code string} → {@link String};</li>
 *   <li>{@code number} → {@link BigDecimal} (from a JSON number or a numeric string);</li>
 *   <li>{@code date} → {@link LocalDate} (ISO-8601);</li>
 *   <li>{@code timestamp} → {@link LocalDateTime} (ISO local date-time, or a plain ISO date taken as
 *       start of day), with no timezone conversion since the column is timezone-naive.</li>
 * </ul>
 */
@Component
public class ValueConverter {

    /**
     * Converts one scalar value to the given type.
     *
     * @throws ValueConversionException if {@code raw} cannot represent a value of {@code type}
     */
    public Object convert(DataType type, Object raw) {
        return switch (type) {
            case STRING -> asString(raw);
            case NUMBER -> asNumber(raw);
            case DATE -> asDate(raw);
            case TIMESTAMP -> asTimestamp(raw);
        };
    }

    private String asString(Object raw) {
        if (raw instanceof String s) {
            return s;
        }
        throw new ValueConversionException("expected a string value but got " + describe(raw));
    }

    private BigDecimal asNumber(Object raw) {
        try {
            if (raw instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (raw instanceof String s) {
                return new BigDecimal(s.trim());
            }
        } catch (NumberFormatException e) {
            throw new ValueConversionException("'" + raw + "' is not a valid number");
        }
        throw new ValueConversionException("expected a number value but got " + describe(raw));
    }

    private LocalDate asDate(Object raw) {
        if (raw instanceof String s) {
            try {
                return LocalDate.parse(s.trim());
            } catch (DateTimeParseException e) {
                throw new ValueConversionException("'" + s + "' is not a valid ISO-8601 date");
            }
        }
        throw new ValueConversionException("expected a date value but got " + describe(raw));
    }

    private LocalDateTime asTimestamp(Object raw) {
        if (raw instanceof String s) {
            String value = s.trim();
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                // fall back to a plain date, taken as the start of that day
            }
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException e) {
                throw new ValueConversionException("'" + s + "' is not a valid ISO-8601 timestamp");
            }
        }
        throw new ValueConversionException("expected a timestamp value but got " + describe(raw));
    }

    private static String describe(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }
}
