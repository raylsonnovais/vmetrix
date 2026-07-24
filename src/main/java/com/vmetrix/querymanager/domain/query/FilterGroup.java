package com.vmetrix.querymanager.domain.query;

import java.util.List;

/**
 * Composite node: an {@link LogicalOperator operator} applied to a list of child {@link FilterNode}s,
 * each of which may itself be a group, enabling arbitrarily nested boolean logic.
 *
 * @param operator   how the children combine (AND / OR)
 * @param conditions the child nodes (conditions and/or sub-groups)
 */
public record FilterGroup(LogicalOperator operator, List<FilterNode> conditions) implements FilterNode {

    public FilterGroup {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
