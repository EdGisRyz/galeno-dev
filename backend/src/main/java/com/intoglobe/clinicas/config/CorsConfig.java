package com.intoglobe.clinicas.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS explícito para el frontend (R-SEG-13).
 *
 * <p>El cliente guarda el access token en memoria y el refresh en una cookie
 * {@code httpOnly} (D3), así que las peticiones viajan con credenciales. Con
 * {@code allowCredentials=true} el comodín de origen es ilegal: el navegador lo rechaza y,
 * si algún día lo aceptara, cualquier sitio podría leer respuestas autenticadas. Por eso el
 * origen comodín no se acepta como valor de configuración: <strong>aborta el arranque</strong>
 * con un mensaje explícito. Es un error de seguridad, no una errata tolerada.</p>
 *
 * <p>Los orígenes se toman de {@code app.cors.allowed-origins}, una lista separada por
 * comas, con el origen del servidor de desarrollo de Vite como valor por defecto. Nunca se
 * derivan del encabezado {@code Origin} de la petición: eso equivaldría a aceptar cualquier
 * origen.</p>
 */
@Configuration
public class CorsConfig {

    /** Propiedad con la lista de orígenes permitidos, separados por comas. */
    public static final String PROPIEDAD_ORIGENES = "app.cors.allowed-origins";

    /** Origen por defecto: el servidor de desarrollo del frontend (Vite). */
    public static final String ORIGENES_POR_DEFECTO = "http://localhost:5173";

    /** Mensaje de arranque fallido cuando la configuración usa un comodín de origen. */
    public static final String MENSAJE_ORIGEN_COMODIN =
            "Configuración CORS inválida: " + PROPIEDAD_ORIGENES + " no admite comodines de origen "
            + "(por ejemplo '*'). Con allowCredentials activo, un origen comodín permitiría a cualquier "
            + "sitio leer respuestas autenticadas; configure los orígenes exactos del frontend separados por comas.";

    /** Mensaje de arranque fallido cuando no queda ningún origen configurado. */
    public static final String MENSAJE_SIN_ORIGENES =
            "Configuración CORS inválida: " + PROPIEDAD_ORIGENES + " no puede quedar vacío; "
            + "configure al menos un origen exacto del frontend.";

    private static final List<String> METODOS_PERMITIDOS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    private static final List<String> CABECERAS_PERMITIDAS = List.of("Authorization", "Content-Type");

    /** Una hora: el navegador puede cachear el preflight y no repetirlo en cada petición. */
    private static final Duration ANTIGUEDAD_MAXIMA = Duration.ofHours(1);

    /**
     * Fuente de configuración CORS que consume la cadena de filtros de Spring Security.
     *
     * @param entorno entorno del que se lee {@value #PROPIEDAD_ORIGENES}
     * @return fuente con la configuración de CORS para todas las rutas
     * @throws IllegalStateException si la configuración tiene un comodín o queda vacía
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment entorno) {
        List<String> origenes = leerOrigenes(entorno.getProperty(PROPIEDAD_ORIGENES, ORIGENES_POR_DEFECTO));

        CorsConfiguration configuracion = new CorsConfiguration();
        configuracion.setAllowedOrigins(origenes);
        configuracion.setAllowedMethods(METODOS_PERMITIDOS);
        configuracion.setAllowedHeaders(CABECERAS_PERMITIDAS);
        configuracion.setAllowCredentials(true);
        configuracion.setMaxAge(ANTIGUEDAD_MAXIMA);

        var fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/**", configuracion);
        return fuente;
    }

    /**
     * Interpreta la lista configurada y rechaza los valores incompatibles con credenciales.
     *
     * @param valor lista de orígenes separados por comas
     * @return orígenes exactos, ya recortados
     * @throws IllegalStateException si la lista queda vacía o contiene un comodín
     */
    static List<String> leerOrigenes(String valor) {
        List<String> origenes = valor == null
                ? List.of()
                : Arrays.stream(valor.split(",")).map(String::trim).filter(origen -> !origen.isEmpty()).toList();

        if (origenes.isEmpty()) {
            throw new IllegalStateException(MENSAJE_SIN_ORIGENES);
        }
        for (String origen : origenes) {
            if (origen.contains("*")) {
                throw new IllegalStateException(MENSAJE_ORIGEN_COMODIN);
            }
        }
        return origenes;
    }
}
