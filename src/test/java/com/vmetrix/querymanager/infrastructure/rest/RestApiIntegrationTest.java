package com.vmetrix.querymanager.infrastructure.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests over the real Spring context (embedded H2 in Oracle mode, schema + seed applied,
 * metadata catalog loaded). Proves the documented API contract: the spec 5.1 example builds byte for
 * byte, invalid payloads return the spec 5.4 structured errors, and every failure keeps its correct
 * HTTP status in one consistent body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestApiIntegrationTest {

    /** The exact SQL the Phase 3 unit test asserts; the endpoint must produce the same text. */
    private static final String EXPECTED_SQL =
            "SELECT t.TXN_DATE, t.AMOUNT, t.CURRENCY, i.TICKER, i.INSTRUMENT_NAME, "
                    + "p.PARTY_NAME AS \"counterpartyName\" "
                    + "FROM TRANSACTION t "
                    + "LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID "
                    + "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID "
                    + "WHERE t.STATUS = :p1 AND t.AMOUNT > :p2 "
                    + "AND (i.ASSET_CLASS IN (:p3) OR p.COUNTRY = :p4) "
                    + "ORDER BY t.TXN_DATE DESC "
                    + "FETCH FIRST 500 ROWS ONLY";

    /** The literal request body from Section 5.1 of the challenge. */
    private static final String SPEC_EXAMPLE_BODY = """
            {
              "select": [
                { "entity": "transaction", "field": "txnDate" },
                { "entity": "transaction", "field": "amount" },
                { "entity": "transaction", "field": "currency" },
                { "entity": "instrument", "field": "ticker" },
                { "entity": "instrument", "field": "instrumentName" },
                { "entity": "counterparty", "field": "partyName", "alias": "counterpartyName" }
              ],
              "filters": {
                "operator": "AND",
                "conditions": [
                  { "entity": "transaction", "field": "status", "comparator": "equals", "value": "SETTLED" },
                  { "entity": "transaction", "field": "amount", "comparator": "greaterThan", "value": 1000000 },
                  { "operator": "OR", "conditions": [
                    { "entity": "instrument", "field": "assetClass", "comparator": "in", "value": ["FIXED_INCOME", "EQUITY"] },
                    { "entity": "counterparty", "field": "country", "comparator": "equals", "value": "CL" }
                  ] }
                ]
              },
              "sorting": [ { "entity": "transaction", "field": "txnDate", "direction": "desc" } ],
              "maxResults": 500
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    void buildsTheSpecExampleEndToEnd() throws Exception {
        mvc.perform(post("/api/query/build").contentType(MediaType.APPLICATION_JSON).content(SPEC_EXAMPLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(EXPECTED_SQL))
                .andExpect(jsonPath("$.parameters.p1").value("SETTLED"))
                .andExpect(jsonPath("$.parameters.p2").value(1000000))
                .andExpect(jsonPath("$.parameters.p3", contains("FIXED_INCOME", "EQUITY")))
                .andExpect(jsonPath("$.parameters.p4").value("CL"))
                .andExpect(jsonPath("$.resolvedTables", contains("TRANSACTION", "INSTRUMENT", "PARTY")))
                .andExpect(jsonPath("$.resolvedJoins", contains(
                        "LEFT JOIN INSTRUMENT i ON t.INSTRUMENT_ID = i.INSTRUMENT_ID",
                        "LEFT JOIN PARTY p ON t.COUNTERPARTY_ID = p.PARTY_ID")))
                .andExpect(jsonPath("$.metadata.columnCount").value(6))
                .andExpect(jsonPath("$.metadata.filterCount").value(4))
                .andExpect(jsonPath("$.metadata.generatedAt").exists());
    }

    @Test
    void validateAccumulatesEveryStructuredError() throws Exception {
        String body = """
                {
                  "select": [ { "entity": "transaction", "field": "status" } ],
                  "filters": { "operator": "AND", "conditions": [
                    { "entity": "transaction", "field": "status", "comparator": "greaterThan", "value": "X" },
                    { "entity": "unknownEntity", "field": "foo", "comparator": "equals", "value": "Y" }
                  ] }
                }
                """;

        mvc.perform(post("/api/query/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[*].message", hasItems(
                        "Comparator greaterThan is not valid for string fields",
                        "Entity unknownEntity does not exist in the model")))
                // locating fields present when applicable
                .andExpect(jsonPath("$.errors[?(@.comparator=='greaterThan')].field", hasItem("status")))
                // and omitted when not: the entity-only error carries just entity + message
                .andExpect(content().string(containsString(
                        "{\"entity\":\"unknownEntity\",\"message\":\"Entity unknownEntity does not exist in the model\"}")));
    }

    @Test
    void unknownComparatorIsAStructuredValidationErrorNotAJacksonError() throws Exception {
        String body = """
                {
                  "select": [ { "entity": "transaction", "field": "status" } ],
                  "filters": { "entity": "transaction", "field": "amount", "comparator": "greaterThanX", "value": 5 }
                }
                """;

        mvc.perform(post("/api/query/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].comparator").value("greaterThanX"))
                .andExpect(jsonPath("$.errors[0].message", containsString("is not a recognised comparator")));
    }

    @Test
    void listsEntitiesWithFieldsAndRelations() throws Exception {
        mvc.perform(get("/api/metadata/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].entity", hasItems("transaction", "instrument", "party")))
                .andExpect(jsonPath("$[?(@.entity=='transaction')].fields[?(@.name=='txnDate')].physicalName",
                        hasItem("TXN_DATE")))
                .andExpect(jsonPath("$[?(@.entity=='instrument')].relations[?(@.alias=='issuer')].targetEntity",
                        hasItem("party")))
                .andExpect(jsonPath("$[?(@.entity=='instrument')].relations[?(@.alias=='issuer')].joinType",
                        hasItem("LEFT")));
    }

    @Test
    void listsComparatorsMatchingTheMatrix() throws Exception {
        mvc.perform(get("/api/metadata/comparators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.string", hasItems("equals", "like", "in")))
                .andExpect(jsonPath("$.string", not(hasItem("greaterThan"))))
                .andExpect(jsonPath("$.number", hasItem("between")))
                .andExpect(jsonPath("$.timestamp", not(hasItem("between"))));
    }

    @Test
    void ambiguousEntityCombinationIsA400NotA500() throws Exception {
        // party referenced directly alongside transaction: reachable via both counterparty and issuer
        String body = """
                {
                  "select": [
                    { "entity": "transaction", "field": "status" },
                    { "entity": "party", "field": "partyName" }
                  ]
                }
                """;

        mvc.perform(post("/api/query/build").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].message", containsString("reaches all referenced entities")));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/query/build").contentType(MediaType.APPLICATION_JSON).content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(content().string(not(containsString("at [Source"))));
    }

    @Test
    void rejectsFilterNodeThatIsNeitherGroupNorCondition() throws Exception {
        String body = """
                {
                  "select": [ { "entity": "transaction", "field": "status" } ],
                  "filters": { "foo": "bar" }
                }
                """;

        mvc.perform(post("/api/query/build").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].message", containsString("must be either a group")));
    }

    @Test
    void wrongHttpMethodIsA405NotA500() throws Exception {
        mvc.perform(get("/api/query/build"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedMediaTypeIsA415NotA500() throws Exception {
        mvc.perform(post("/api/query/build").contentType(MediaType.TEXT_PLAIN).content(SPEC_EXAMPLE_BODY))
                .andExpect(status().isUnsupportedMediaType());
    }
}
