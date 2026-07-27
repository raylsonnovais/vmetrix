package com.vmetrix.querymanager.infrastructure.rest;

import com.vmetrix.querymanager.infrastructure.metadata.MetadataCatalogProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the metadata-driven claim: a brand-new entity added <em>only</em> to the {@code META_*}
 * tables becomes queryable after a reload, with zero Java change. The new entity ("widget") exists
 * nowhere in code — the loader, catalog, validator, join resolver and SQL generator are all generic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetadataDrivenIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MetadataCatalogProvider catalogProvider;

    @Test
    void aNewEntityAddedOnlyToMetadataBecomesQueryableAfterReload() throws Exception {
        try {
            // 1. describe a new entity purely in the metadata tables — no model table, no Java
            jdbc.update("INSERT INTO META_ENTITY (ENTITY, PHYSICAL_TABLE, DEFAULT_ALIAS, DESCRIPTION) "
                    + "VALUES ('widget', 'WIDGET', 'w', 'added at runtime')");
            jdbc.update("INSERT INTO META_FIELD (ENTITY, NAME, PHYSICAL_NAME, DATA_TYPE, PK, FK_ENTITY, FK_FIELD, FILTERABLE, SELECTABLE) "
                    + "VALUES ('widget', 'widgetId', 'WIDGET_ID', 'number', 1, NULL, NULL, 1, 1)");
            jdbc.update("INSERT INTO META_FIELD (ENTITY, NAME, PHYSICAL_NAME, DATA_TYPE, PK, FK_ENTITY, FK_FIELD, FILTERABLE, SELECTABLE) "
                    + "VALUES ('widget', 'widgetName', 'WIDGET_NAME', 'string', 0, NULL, NULL, 1, 1)");

            // 2. reload the catalog through the endpoint — now four entities are known
            mvc.perform(post("/api/metadata/reload"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entities").value(4));

            // 3. build a query against the new entity — SQL is generated with no code change
            String body = """
                    {
                      "select": [ { "entity": "widget", "field": "widgetName" } ],
                      "filters": { "entity": "widget", "field": "widgetId", "comparator": "greaterThan", "value": 10 }
                    }
                    """;
            mvc.perform(post("/api/query/build").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sql").value(
                            "SELECT w.WIDGET_NAME FROM WIDGET w WHERE w.WIDGET_ID > :p1 FETCH FIRST 100 ROWS ONLY"))
                    .andExpect(jsonPath("$.parameters.p1").value(10))
                    .andExpect(jsonPath("$.resolvedTables").value("WIDGET"));
        } finally {
            // restore the shared in-memory metadata so other suites see the original three entities
            jdbc.update("DELETE FROM META_FIELD WHERE ENTITY = 'widget'");
            jdbc.update("DELETE FROM META_ENTITY WHERE ENTITY = 'widget'");
            catalogProvider.reload();
        }
    }
}
