package ro.myfinance.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document — the contract source for the frontend's generated typed client.
 * Served at {@code /v3/api-docs}; Swagger UI at {@code /swagger-ui.html}.
 */
@Configuration
@ConditionalOnWebApplication
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI myFinanceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MyFinance API")
                        .version("v1")
                        .description("Multi-tenant accounting portal API. All endpoints are tenant-scoped via RLS."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Supabase-issued access token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
