package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.domain.metadata.DataType;
import com.vmetrix.querymanager.domain.metadata.EntityMeta;
import com.vmetrix.querymanager.domain.metadata.MetadataCatalog;
import com.vmetrix.querymanager.domain.query.Comparator;
import com.vmetrix.querymanager.infrastructure.metadata.MetadataCatalogProvider;
import com.vmetrix.querymanager.infrastructure.rest.dto.EntityMetadataResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.EntityMetadataResponse.FieldResponse;
import com.vmetrix.querymanager.infrastructure.rest.dto.EntityMetadataResponse.RelationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata endpoints. Everything here is a projection of the in-memory catalog — no model knowledge is
 * hardcoded, so a new entity/field/comparator in the {@code META_*} tables shows up after a reload.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final MetadataCatalogProvider catalogProvider;

    public MetadataController(MetadataCatalogProvider catalogProvider) {
        this.catalogProvider = catalogProvider;
    }

    @GetMapping("/entities")
    public List<EntityMetadataResponse> entities() {
        return catalogProvider.current().entities().stream()
                .map(MetadataController::toEntityResponse)
                .toList();
    }

    @GetMapping("/comparators")
    public Map<String, List<String>> comparators() {
        MetadataCatalog catalog = catalogProvider.current();
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (DataType type : DataType.values()) {
            byType.put(type.wireName(),
                    catalog.comparatorsFor(type).stream().map(Comparator::wireName).toList());
        }
        return byType;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        catalogProvider.reload();
        MetadataCatalog catalog = catalogProvider.current();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reloaded", true);
        summary.put("entities", catalog.entities().size());
        summary.put("relations", catalog.relations().size());
        return summary;
    }

    private static EntityMetadataResponse toEntityResponse(EntityMeta entity) {
        List<FieldResponse> fields = entity.fields().stream()
                .map(field -> new FieldResponse(
                        field.name(),
                        field.physicalName(),
                        field.dataType().wireName(),
                        field.primaryKey(),
                        field.filterable(),
                        field.selectable()))
                .toList();
        List<RelationResponse> relations = entity.relations().stream()
                .map(relation -> new RelationResponse(
                        relation.alias(),
                        relation.targetEntity(),
                        relation.joinType().name(),
                        relation.sourceField(),
                        relation.targetField()))
                .toList();
        return new EntityMetadataResponse(
                entity.name(), entity.physicalTable(), entity.defaultAlias(), fields, relations);
    }
}
