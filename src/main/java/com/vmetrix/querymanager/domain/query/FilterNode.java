package com.vmetrix.querymanager.domain.query;

/**
 * A node in the filter tree — the <strong>Composite</strong> pattern that models the spec's nested
 * AND/OR filter groups.
 *
 * <p>A node is either a {@link FilterGroup} (a boolean combination of child nodes) or a
 * {@link FilterCondition} (a leaf comparing a field to a value). The tree is sealed so exhaustive
 * pattern matching over the two shapes is checked by the compiler — validation and SQL generation
 * both walk it that way.
 */
public sealed interface FilterNode permits FilterGroup, FilterCondition {
}
