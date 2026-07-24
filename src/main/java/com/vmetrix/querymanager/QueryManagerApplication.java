package com.vmetrix.querymanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the VMetrix Query Manager Proof of Concept.
 *
 * <p>The application boots an embedded H2 database (Oracle compatibility mode), applies the
 * schema and seed scripts, loads the metadata catalog into memory and exposes the query-building
 * REST API. Run with {@code mvn spring-boot:run}.
 */
@SpringBootApplication
public class QueryManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryManagerApplication.class, args);
    }
}
