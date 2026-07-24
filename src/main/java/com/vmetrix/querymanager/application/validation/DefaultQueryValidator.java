package com.vmetrix.querymanager.application.validation;

import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedCondition;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedField;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedFilter;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedGroup;
import com.vmetrix.querymanager.application.validation.ValidatedQuery.ResolvedSort;
import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.FieldMeta;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.query.Comparator;
import com.vmetrix.querymanager.domain.query.FilterCondition;
import com.vmetrix.querymanager.domain.query.FilterGroup;
import com.vmetrix.querymanager.domain.query.FilterNode;
import com.vmetrix.querymanager.domain.query.QueryRequest;
import com.vmetrix.querymanager.domain.query.SelectField;
import com.vmetrix.querymanager.domain.query.Sorting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link QueryValidator}: resolves an untrusted {@link QueryRequest} against the catalog and
 * either produces a fully-typed {@link ValidatedQuery} or accumulates <em>all</em> problems into a
 * list of {@link ValidationError}s (never fail-fast). Every rule is driven by the catalog — nothing
 * is hardcoded per entity. Value conversion is delegated to {@link ValueConverter}.
 */
@Component
public class DefaultQueryValidator implements QueryValidator {

    /** Row cap applied when the request omits {@code maxResults}. */
    static final int DEFAULT_MAX_RESULTS = 100;
    /** Hard upper bound; a request above this is rejected (not clamped). */
    static final int MAX_RESULTS_CEILING = 1000;

    private final ValueConverter valueConverter;

    public DefaultQueryValidator(ValueConverter valueConverter) {
        this.valueConverter = valueConverter;
    }

    @Override
    public ValidationResult validate(QueryRequest request, MetadataCatalog catalog) {
        List<ValidationError> errors = new ArrayList<>();
        // First-appearance order of query-facing entity names: select, then filters, then sorting.
        Set<String> referencedEntities = new LinkedHashSet<>();

        List<ResolvedField> select = resolveSelect(request.select(), catalog, errors, referencedEntities);
        ResolvedFilter filter = request.filters() == null
                ? null
                : resolveFilterNode(request.filters(), catalog, errors, referencedEntities);
        List<ResolvedSort> sorting = resolveSorting(request.sorting(), catalog, errors, referencedEntities);
        int maxResults = resolveMaxResults(request.maxResults(), errors);

        if (request.select().isEmpty()) {
            errors.add(ValidationError.of("select must contain at least one field"));
        }

        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }
        return ValidationResult.valid(
                new ValidatedQuery(select, filter, sorting, maxResults, new ArrayList<>(referencedEntities)));
    }

    // --- select -------------------------------------------------------------

    private List<ResolvedField> resolveSelect(
            List<SelectField> fields, MetadataCatalog catalog, List<ValidationError> errors, Set<String> referenced) {

        List<ResolvedField> resolved = new ArrayList<>();
        for (SelectField selectField : fields) {
            referenced.add(selectField.entity());
            Optional<FieldMeta> field = resolveField(
                    selectField.entity(), selectField.field(), catalog, errors);
            if (field.isEmpty()) {
                continue;
            }
            if (!field.get().selectable()) {
                errors.add(ValidationError.forField(selectField.entity(), selectField.field(),
                        "Field " + selectField.field() + " is not selectable"));
                continue;
            }
            resolved.add(new ResolvedField(selectField.entity(), field.get(), selectField.alias()));
        }
        return resolved;
    }

    // --- sorting ------------------------------------------------------------

    private List<ResolvedSort> resolveSorting(
            List<Sorting> sortings, MetadataCatalog catalog, List<ValidationError> errors, Set<String> referenced) {

        List<ResolvedSort> resolved = new ArrayList<>();
        for (Sorting sorting : sortings) {
            referenced.add(sorting.entity());
            Optional<FieldMeta> field = resolveField(sorting.entity(), sorting.field(), catalog, errors);
            field.ifPresent(fieldMeta ->
                    resolved.add(new ResolvedSort(sorting.entity(), fieldMeta, sorting.direction())));
        }
        return resolved;
    }

    // --- filters (Composite walk) ------------------------------------------

    private ResolvedFilter resolveFilterNode(
            FilterNode node, MetadataCatalog catalog, List<ValidationError> errors, Set<String> referenced) {

        if (node instanceof FilterGroup group) {
            return resolveGroup(group, catalog, errors, referenced);
        }
        if (node instanceof FilterCondition condition) {
            return resolveCondition(condition, catalog, errors, referenced);
        }
        // Unreachable: FilterNode is sealed to exactly these two shapes.
        throw new IllegalStateException("Unknown filter node type: " + node.getClass());
    }

    private ResolvedFilter resolveGroup(
            FilterGroup group, MetadataCatalog catalog, List<ValidationError> errors, Set<String> referenced) {

        if (group.conditions().isEmpty()) {
            errors.add(ValidationError.of("Filter group must contain at least one condition"));
            return null;
        }
        List<ResolvedFilter> children = new ArrayList<>();
        for (FilterNode child : group.conditions()) {
            ResolvedFilter resolvedChild = resolveFilterNode(child, catalog, errors, referenced);
            if (resolvedChild != null) {
                children.add(resolvedChild);
            }
        }
        return new ResolvedGroup(group.operator(), children);
    }

    private ResolvedFilter resolveCondition(
            FilterCondition condition, MetadataCatalog catalog, List<ValidationError> errors, Set<String> referenced) {

        referenced.add(condition.entity());
        Optional<FieldMeta> fieldOpt = resolveField(condition.entity(), condition.field(), catalog, errors);
        if (fieldOpt.isEmpty()) {
            return null;
        }
        FieldMeta field = fieldOpt.get();
        if (!field.filterable()) {
            errors.add(ValidationError.forField(condition.entity(), condition.field(),
                    "Field " + condition.field() + " is not filterable"));
            return null;
        }

        Optional<Comparator> comparatorOpt = Comparator.fromWire(condition.comparator());
        if (comparatorOpt.isEmpty()) {
            errors.add(ValidationError.forComparator(condition.entity(), condition.field(), condition.comparator(),
                    "Comparator " + condition.comparator() + " is not a recognised comparator"));
            return null;
        }
        Comparator comparator = comparatorOpt.get();

        if (!catalog.supports(field.dataType(), comparator)) {
            errors.add(ValidationError.forComparator(condition.entity(), condition.field(), condition.comparator(),
                    "Comparator " + condition.comparator() + " is not valid for "
                            + field.dataType().wireName() + " fields"));
            return null;
        }

        Object convertedValue;
        try {
            convertedValue = convertValue(comparator, field.dataType(), condition.value());
        } catch (ValueConversionException e) {
            errors.add(ValidationError.forField(condition.entity(), condition.field(), e.getMessage()));
            return null;
        }
        return new ResolvedCondition(condition.entity(), field, comparator, convertedValue);
    }

    // --- value conversion by cardinality -----------------------------------

    private Object convertValue(Comparator comparator, DataType type, Object raw) {
        return switch (comparator.cardinality()) {
            case NONE -> {
                if (raw != null) {
                    throw new ValueConversionException(
                            "Comparator " + comparator.wireName() + " does not take a value");
                }
                yield null;
            }
            case SINGLE -> {
                if (raw == null) {
                    throw new ValueConversionException("A value is required");
                }
                if (raw instanceof Collection) {
                    throw new ValueConversionException(
                            "Comparator " + comparator.wireName() + " expects a single value, not a list");
                }
                yield valueConverter.convert(type, raw);
            }
            case LIST -> {
                List<?> elements = asList(raw, comparator);
                if (elements.isEmpty()) {
                    throw new ValueConversionException(
                            "Comparator " + comparator.wireName() + " expects a non-empty list of values");
                }
                yield convertEach(elements, type);
            }
            case RANGE -> {
                List<?> elements = asList(raw, comparator);
                if (elements.size() != 2) {
                    throw new ValueConversionException(
                            "Comparator " + comparator.wireName() + " expects exactly two values [lower, upper]");
                }
                yield convertEach(elements, type);
            }
        };
    }

    private List<Object> convertEach(List<?> elements, DataType type) {
        List<Object> converted = new ArrayList<>(elements.size());
        for (Object element : elements) {
            converted.add(valueConverter.convert(type, element));
        }
        return converted;
    }

    private static List<?> asList(Object raw, Comparator comparator) {
        if (raw instanceof List<?> list) {
            return list;
        }
        throw new ValueConversionException(
                "Comparator " + comparator.wireName() + " expects a list of values");
    }

    // --- shared field resolution -------------------------------------------

    /** Resolves an entity/field pair, adding the precise error and returning empty when it fails. */
    private Optional<FieldMeta> resolveField(
            String entity, String field, MetadataCatalog catalog, List<ValidationError> errors) {

        Optional<EntityMeta> entityMeta = catalog.resolveTargetEntity(entity);
        if (entityMeta.isEmpty()) {
            errors.add(ValidationError.forEntity(entity, "Entity " + entity + " does not exist in the model"));
            return Optional.empty();
        }
        Optional<FieldMeta> fieldMeta = entityMeta.get().findField(field);
        if (fieldMeta.isEmpty()) {
            errors.add(ValidationError.forField(entity, field,
                    "Field " + field + " does not exist on entity " + entity));
            return Optional.empty();
        }
        return fieldMeta;
    }

    // --- maxResults ---------------------------------------------------------

    private int resolveMaxResults(Integer requested, List<ValidationError> errors) {
        if (requested == null) {
            return DEFAULT_MAX_RESULTS;
        }
        if (requested <= 0) {
            errors.add(ValidationError.of("maxResults must be a positive number"));
            return DEFAULT_MAX_RESULTS;
        }
        if (requested > MAX_RESULTS_CEILING) {
            errors.add(ValidationError.of("maxResults must not exceed " + MAX_RESULTS_CEILING));
            return DEFAULT_MAX_RESULTS;
        }
        return requested;
    }
}
