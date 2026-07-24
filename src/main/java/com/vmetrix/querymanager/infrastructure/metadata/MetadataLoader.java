package com.vmetrix.querymanager.infrastructure.metadata;

import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;

/**
 * Reads the {@code META_*} tables and assembles an immutable {@link MetadataCatalog}.
 *
 * <p>This is the only component that knows the metadata is stored in a database. Swapping the backing
 * store (JSON/YAML files, a remote service) means replacing this implementation and nothing else,
 * because everything downstream depends on the {@code MetadataCatalog} abstraction.
 */
public interface MetadataLoader {

    /**
     * Loads a fresh catalog from the backing store.
     *
     * @return a newly built, immutable catalog
     */
    MetadataCatalog load();
}
