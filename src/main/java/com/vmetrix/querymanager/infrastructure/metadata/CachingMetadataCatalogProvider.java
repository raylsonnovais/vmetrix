package com.vmetrix.querymanager.infrastructure.metadata;

import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Keeps the {@link MetadataCatalog} cached in memory and rebuilds it on demand.
 *
 * <p>The catalog is loaded once at startup and held in a {@code volatile} field — the single, shared,
 * immutable snapshot every request reads (the "metadata caching" bonus). {@link #reload()} rebuilds
 * it from the backing store and swaps the reference in one assignment; because each catalog is fully
 * built before it is published, readers always see a complete snapshot, never a half-built one.
 *
 * <p>{@link DependsOnDatabaseInitialization} guarantees this bean initialises only after Spring Boot
 * has run {@code schema.sql}/{@code data.sql}, so the {@link PostConstruct} load sees the seeded
 * {@code META_*} tables rather than racing the datasource initialisation.
 */
@Component
@DependsOnDatabaseInitialization
public class CachingMetadataCatalogProvider implements MetadataCatalogProvider {

    private final MetadataLoader loader;
    private volatile MetadataCatalog current;

    public CachingMetadataCatalogProvider(MetadataLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    void loadOnStartup() {
        reload();
    }

    @Override
    public MetadataCatalog current() {
        MetadataCatalog snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("Metadata catalog has not been loaded yet");
        }
        return snapshot;
    }

    @Override
    public void reload() {
        this.current = loader.load();
    }
}
