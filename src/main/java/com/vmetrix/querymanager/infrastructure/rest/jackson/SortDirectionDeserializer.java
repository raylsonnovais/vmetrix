package com.vmetrix.querymanager.infrastructure.rest.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.vmetrix.querymanager.domain.query.SortDirection;

import java.io.IOException;

/**
 * Maps the API sort tokens ({@code "asc"}/{@code "desc"}) to {@link SortDirection} via its wire names,
 * so the domain enum needs no Jackson annotations. An unknown token is a clear 400.
 */
public class SortDirectionDeserializer extends JsonDeserializer<SortDirection> {

    @Override
    public SortDirection deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String token = parser.getValueAsString();
        SortDirection direction = SortDirection.fromWire(token).orElse(null);
        if (direction == null) {
            throw JsonMappingException.from(parser, "Unknown sort direction '" + token + "'");
        }
        return direction;
    }
}
