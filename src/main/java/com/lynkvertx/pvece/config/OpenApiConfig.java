package com.lynkvertx.pvece.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) Configuration
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("PVECE API")
                .description("Photovoltaic Energy Storage Calculation Engine API Documentation")
                .version("0.1.0")
                .contact(new Contact()
                    .name("PVECE Team")
                    .email("support@pvecv.com"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://pvecv.com/license")));
    }
}
