package com.vmetrix.querymanager.infrastructure.rest.jackson;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.vmetrix.querymanager.domain.query.FilterNode;
import com.vmetrix.querymanager.domain.query.SortDirection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the request-side deserializers as a Jackson {@link Module}. Spring Boot applies any
 * {@code Module} bean to the auto-configured {@code ObjectMapper}, so binding the API payload straight
 * onto the (annotation-free) domain records needs nothing more than this bean.
 */
@Configuration
public class QueryJacksonConfig {

    @Bean
    public Module queryManagerModule() {
        SimpleModule module = new SimpleModule("query-manager");
        module.addDeserializer(FilterNode.class, new FilterNodeDeserializer());
        module.addDeserializer(SortDirection.class, new SortDirectionDeserializer());
        return module;
    }
}
