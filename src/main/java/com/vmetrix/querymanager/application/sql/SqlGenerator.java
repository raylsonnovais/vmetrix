package com.vmetrix.querymanager.application.sql;

import com.vmetrix.querymanager.application.validation.ValidatedQuery;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;

/**
 * Turns a {@link ValidatedQuery} into {@link GeneratedSql}.
 *
 * <p>By accepting only a {@code ValidatedQuery} — never a raw request — the type system guarantees no
 * unvalidated input can reach SQL generation. Internally the generator resolves joins (delegating to
 * {@code JoinResolver}), walks the filter Composite to render the {@code WHERE} tree with correct
 * parenthesisation, and uses a small SQL <strong>Builder</strong> to assemble
 * {@code SELECT / FROM / JOIN / WHERE / ORDER BY / FETCH}. Each comparator renders its own SQL
 * fragment and allocates its own named bind parameters (a <strong>Strategy</strong> on the comparator).
 *
 * <p>Only physical identifiers from the catalog are ever concatenated into the SQL; every value goes
 * through a named bind parameter.
 */
public interface SqlGenerator {

    /**
     * Generates SQL for a validated query.
     *
     * @param query   the validated, fully-resolved query
     * @param catalog the metadata (for physical names, aliases and relations)
     * @return the SQL text, bind parameters and resolution details
     */
    GeneratedSql generate(ValidatedQuery query, MetadataCatalog catalog);
}
