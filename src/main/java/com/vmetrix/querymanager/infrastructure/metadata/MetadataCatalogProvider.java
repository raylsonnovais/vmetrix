package com.vmetrix.querymanager.infrastructure.metadata;

import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;

/**
 * Holds the current {@link MetadataCatalog} in memory and allows it to be rebuilt.
 *
 * <p>The catalog is loaded once at startup (satisfying the "metadata caching" bonus) and kept as the
 * shared, immutable snapshot every request reads. {@link #reload()} rebuilds it from the backing
 * store and swaps the reference atomically, so the {@code POST /api/metadata/reload} endpoint never
 * exposes a half-built catalog.
 */
public interface MetadataCatalogProvider {

    /** The current catalog snapshot. Never {@code null} after startup. */
    MetadataCatalog current();

    /** Rebuilds the catalog from the backing store and atomically replaces the current snapshot. */
    void reload();
}
