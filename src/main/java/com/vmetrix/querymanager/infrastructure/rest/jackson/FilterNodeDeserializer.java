package com.vmetrix.querymanager.infrastructure.rest.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.vmetrix.querymanager.domain.query.FilterCondition;
import com.vmetrix.querymanager.domain.query.FilterGroup;
import com.vmetrix.querymanager.domain.query.FilterNode;
import com.vmetrix.querymanager.domain.query.LogicalOperator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserialises the polymorphic filter tree without annotating the domain.
 *
 * <p>Each node's shape is <em>deduced</em> from the properties present: a node with {@code operator}
 * (and {@code conditions}) is a {@link FilterGroup}; a node with {@code comparator} is a
 * {@link FilterCondition}. A node with both, or neither, is rejected with a clear message, which the
 * REST advice turns into a structured 400 rather than a 500.
 *
 * <p>Deliberately, {@code comparator} is kept as a raw {@link String} and {@code value} as a raw
 * {@link Object}: an unknown comparator or a malformed value is <strong>not</strong> a deserialization
 * failure here — it must reach the validator and become an accumulated, structured error (spec 5.4).
 */
public class FilterNodeDeserializer extends JsonDeserializer<FilterNode> {

    @Override
    public FilterNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.readValueAsTree();
        return toFilterNode(node, parser);
    }

    private FilterNode toFilterNode(JsonNode node, JsonParser parser) throws IOException {
        if (node == null || !node.isObject()) {
            throw JsonMappingException.from(parser, "A filter node must be a JSON object");
        }
        boolean hasOperator = node.hasNonNull("operator");
        boolean hasComparator = node.hasNonNull("comparator");
        if (hasOperator == hasComparator) {
            throw JsonMappingException.from(parser,
                    "A filter node must be either a group (with 'operator' and 'conditions') or a condition "
                            + "(with 'entity', 'field' and 'comparator'), but it has "
                            + (hasOperator ? "both" : "neither"));
        }
        return hasOperator ? toGroup(node, parser) : toCondition(node, parser);
    }

    private FilterNode toGroup(JsonNode node, JsonParser parser) throws IOException {
        String operatorToken = node.get("operator").asText();
        LogicalOperator operator = LogicalOperator.fromWire(operatorToken).orElse(null);
        if (operator == null) {
            throw JsonMappingException.from(parser, "Unknown filter operator '" + operatorToken + "'");
        }
        JsonNode conditions = node.get("conditions");
        if (conditions == null || !conditions.isArray()) {
            throw JsonMappingException.from(parser, "A filter group requires a 'conditions' array");
        }
        List<FilterNode> children = new ArrayList<>();
        for (JsonNode child : conditions) {
            children.add(toFilterNode(child, parser));
        }
        return new FilterGroup(operator, children);
    }

    private FilterNode toCondition(JsonNode node, JsonParser parser) throws IOException {
        String entity = text(node, "entity");
        String field = text(node, "field");
        String comparator = text(node, "comparator"); // stays a String; the validator resolves it
        Object value = node.has("value")
                ? parser.getCodec().treeToValue(node.get("value"), Object.class)
                : null;
        return new FilterCondition(entity, field, comparator, value);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}
