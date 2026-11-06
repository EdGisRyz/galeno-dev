package com.intoglobe.clinicas.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * Declara en OpenAPI el esquema de autenticación que la API ya exige en tiempo de ejecución.
 *
 * <p>Todas las operaciones de datos requieren un token Bearer RS256, pero hasta ahora ese
 * requisito vivía solo en la cadena de filtros. La consecuencia práctica era doble: Swagger UI
 * no mostraba el botón para autenticarse —springdoc solo lo dibuja cuando el documento declara
 * un esquema— y el contrato publicado omitía que el token es obligatorio, así que un cliente
 * que se guiara por la documentación descubría la exigencia con un {@code 401}.</p>
 *
 * <p>El esquema se llama {@code bearerAuth} y es de tipo HTTP con formato {@code JWT}: en el
 * diálogo de autenticación se pega <strong>solo el token</strong>, sin el prefijo
 * {@code Bearer}, que lo agrega la herramienta. El requisito se aplica a todas las operaciones
 * mediante un {@link OpenApiCustomizer}, para no tener que repetir la anotación en cada
 * controlador ni arriesgar que una operación nueva quede sin declararlo.</p>
 *
 * <p>Los dos endpoints públicos de documentación no llevan el requisito por sí mismos, pero se
 * declaran fuera del alcance de la cadena de filtros en {@code SecurityConfig}: este archivo no
 * cambia ninguna regla de autorización, solo la describe.</p>
 */
@Configuration
@SecurityScheme(
        name = OpenApiConfig.ESQUEMA_BEARER,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Token JWT RS256 emitido por el emisor de InToGlobe. Pegar solo el token, sin el prefijo Bearer.")
public class OpenApiConfig {

    /** Nombre del esquema de seguridad tal como aparece en el diálogo de autenticación. */
    static final String ESQUEMA_BEARER = "bearerAuth";

    /**
     * Aplica el requisito de seguridad a todas las operaciones publicadas.
     *
     * <p>Se hace por customizer y no operación por operación para que cualquier endpoint futuro
     * quede declarado sin intervención manual: el olvido en una sola anotación dejaría una
     * operación que la documentación muestra como pública y el servidor rechaza con {@code 401}.</p>
     *
     * @return customizer que agrega el requisito {@code bearerAuth} a cada operación
     */
    @Bean
    OpenApiCustomizer declararSeguridadEnTodasLasOperaciones() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(ruta -> ruta.readOperations().forEach(operacion ->
                    operacion.addSecurityItem(new SecurityRequirement().addList(ESQUEMA_BEARER))));
        };
    }
}
