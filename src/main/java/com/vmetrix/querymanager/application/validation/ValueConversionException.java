package com.vmetrix.querymanager.application.validation;

/**
 * Thrown by {@link ValueConverter} when a raw filter value cannot be converted to its declared data
 * type. The validator catches it and turns the message into a structured {@link ValidationError};
 * it is never allowed to propagate to the database.
 */
public class ValueConversionException extends RuntimeException {

    public ValueConversionException(String message) {
        super(message);
    }
}
