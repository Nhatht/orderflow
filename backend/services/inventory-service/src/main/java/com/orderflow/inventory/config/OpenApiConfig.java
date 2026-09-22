package com.orderflow.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("OrderFlow — Inventory Service")
                .version("0.0.1")
                .description("Stock reservation with Redis distributed locking"));
    }
}
