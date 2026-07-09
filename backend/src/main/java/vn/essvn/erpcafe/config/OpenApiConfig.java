package vn.essvn.erpcafe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Basic OpenAPI / Swagger metadata. UI available at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI erpCafeOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("ERP Cafe API")
                .description("Multi-branch cafe ERP backend")
                .version("v1"));
    }
}
