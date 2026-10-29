package com.intoglobe.clinicas.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Aviso de arranque cuando la documentación de la API queda habilitada fuera del perfil
 * {@code dev} (R-SEG-09; decisión de Eduardo del 2026-10-29).
 *
 * <p>La línea base versionada mantiene springdoc apagado
 * ({@code springdoc.api-docs.enabled=false} y {@code springdoc.swagger-ui.enabled=false} en
 * {@code application.yml}) y solo {@code application-dev.properties} lo enciende, que es la
 * forma prevista de trabajar. Nada impide encenderlo fuera de {@code dev} —por línea de
 * comandos, por variable de entorno o por un perfil propio—, pero si eso ocurre la
 * documentación y su interfaz quedan alcanzables <strong>sin token</strong>: contarlas como
 * rutas públicas es correcto cuando son una herramienta de desarrollo, y es un problema
 * cuando no lo son.</p>
 *
 * <p><strong>Se avisa, no se falla.</strong> Un despliegue con la bandera encendida tiene que
 * seguir arrancando: romperlo convertiría una decisión de operación en una caída. Lo que no
 * puede pasar es que la bandera quede encendida en silencio, así que el arranque deja un
 * {@code WARN} con la propiedad culpable, la consecuencia y la forma de hacerlo bien. Es un
 * solo registro aunque las dos propiedades estén en {@code true}: el aviso describe ambas en
 * la misma línea y repetirlo no aportaría información.</p>
 *
 * <p>Se implementa igual que {@link com.intoglobe.clinicas.security.AuthKeyStartupConfig}:
 * un {@code @Configuration} que trabaja en su constructor, de modo que la comprobación corre
 * en el arranque del contexto y no depende de que alguien llame a nada. Aquí no hay
 * {@link IllegalArgumentException} ni {@link IllegalStateException}: el constructor termina
 * siempre y el contexto sigue su curso.</p>
 */
@Configuration
public class ApiDocsStartupConfig {

    /** Perfil de desarrollo: el único en el que la documentación se enciende por configuración. */
    public static final String PERFIL_DEV = "dev";

    /** Propiedad que habilita el documento OpenAPI. */
    public static final String PROPIEDAD_API_DOCS = "springdoc.api-docs.enabled";

    /** Propiedad que habilita la interfaz de Swagger UI. */
    public static final String PROPIEDAD_SWAGGER_UI = "springdoc.swagger-ui.enabled";

    /**
     * Texto del aviso. Es una constante pública a propósito: las pruebas de arranque afirman
     * su contenido y un cambio accidental de redacción debe romperlas. Los dos marcadores
     * {@code {}} reciben la lista de propiedades encendidas.
     */
    public static final String MENSAJE_FUERA_DE_DEV =
            "Documentación de la API habilitada fuera del perfil '" + PERFIL_DEV + "': {} en true, "
            + "así que /v3/api-docs y /swagger-ui quedan accesibles sin autenticación. "
            + "Si es intencional, arranque con --spring.profiles.active=" + PERFIL_DEV
            + " (o SPRING_PROFILES_ACTIVE=" + PERFIL_DEV + "); si no, vuelva a poner {} en false.";

    private static final Logger REGISTRO = LoggerFactory.getLogger(ApiDocsStartupConfig.class);

    /**
     * Comprueba la configuración de springdoc en el arranque y avisa si quedó habilitada
     * fuera de desarrollo.
     *
     * @param entorno entorno de la aplicación, del que se leen las dos propiedades de
     *                springdoc y los perfiles activos
     */
    public ApiDocsStartupConfig(Environment entorno) {
        List<String> habilitadas = propiedadesHabilitadas(entorno);
        if (habilitadas.isEmpty() || Arrays.asList(entorno.getActiveProfiles()).contains(PERFIL_DEV)) {
            return;
        }
        String propiedades = String.join(" y ", habilitadas);
        // Un solo registro: la línea nombra todas las propiedades encendidas.
        REGISTRO.warn(MENSAJE_FUERA_DE_DEV, propiedades, propiedades);
    }

    /**
     * Devuelve las propiedades de springdoc que están en {@code true}.
     *
     * <p>Solo se considera encendida una propiedad cuyo valor, ya recortado, sea
     * {@code true} sin distinguir mayúsculas: el valor por defecto de springdoc es
     * {@code true}, pero en esta aplicación la línea base lo deja explícitamente en off, así
     * que una propiedad ausente no es un caso que haya que adivinar.</p>
     *
     * @param entorno entorno de la aplicación
     * @return nombres de las propiedades habilitadas, en orden de declaración
     */
    private static List<String> propiedadesHabilitadas(Environment entorno) {
        List<String> habilitadas = new ArrayList<>(2);
        for (String propiedad : List.of(PROPIEDAD_API_DOCS, PROPIEDAD_SWAGGER_UI)) {
            if (Boolean.parseBoolean(entorno.getProperty(propiedad, "false").trim())) {
                habilitadas.add(propiedad);
            }
        }
        return habilitadas;
    }
}
