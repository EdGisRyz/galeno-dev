package com.intoglobe.clinicas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.intoglobe.clinicas.config.CorsConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cadena de filtros cerrada de la API (R-SEG-03).
 *
 * <p>Toda petición exige un token RS256 válido emitido por el AuthService de InToGlobe.
 * El backend solo valida la firma: nunca emite tokens ni guarda contraseñas (R8.2, D2). El
 * {@code tenantId} y el {@code role} salen de los claims y viven en el
 * {@code SecurityContext}; el path nunca es fuente de verdad (R-SEG-06).</p>
 *
 * <h2>Rutas públicas</h2>
 *
 * <p>Las únicas rutas abiertas son las de documentación de la API
 * ({@code /v3/api-docs/**}, {@code /swagger-ui/**} y {@code /swagger-ui.html}). Esas rutas
 * solo existen cuando springdoc está habilitado, y hoy lo está por defecto; dejarlas
 * públicas es aceptable mientras el plan sea deshabilitar springdoc en producción, que es
 * el trabajo de T5 (endurecimiento de configuración). Si T5 las apaga en producción, estas
 * reglas quedan inertes: no abren nada que no exista.</p>
 *
 * <h2>No hay rutas {@code /api/v1/auth/**} que permitir</h2>
 *
 * <p>Es deliberado y no una omisión. Este backend <strong>no emite tokens</strong>: el
 * emisor es un servicio externo (D1) y el cliente habla con él directamente. Agregar aquí
 * un {@code permitAll()} para {@code /api/v1/auth/**} abriría un camino que no existe hoy y
 * que además permitiría que un controlador futuro de autenticación quedara sin protección
 * por descuido.</p>
 *
 * <h2>Estado de la sesión</h2>
 *
 * <p>CSRF sigue deshabilitado y la sesión es {@code STATELESS}: el token viaja en la
 * cabecera {@code Authorization}, no en una cookie de sesión del backend, así que no hay
 * cookie que falsificar contra esta API. El refresh token del emisor es su propia cookie
 * {@code httpOnly} y su protección CSRF ({@code SameSite=Strict} y validación de origen)
 * pertenece al emisor, no a este backend (ver R-SEG-13 en el documento de la feature).</p>
 */
@Configuration
@EnableWebSecurity
@Import(CorsConfig.class)
public class SecurityConfig {

    /**
     * Rutas de documentación abiertas. Ver el Javadoc de la clase: existen solo si
     * springdoc está habilitado y desactivarlo en producción es de T5.
     */
    private static final String[] RUTAS_PUBLICAS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * Define la única cadena de filtros de la aplicación: documentación pública, todo lo
     * demás autenticado, CORS con credenciales y errores de seguridad dentro del contrato
     * {@code ErrorResponse}.
     *
     * @param http                  configuración de seguridad web de Spring Security
     * @param jwtAuthenticationFilter filtro que valida el token Bearer RS256
     * @param entryPoint            respuesta {@code 401} con el contrato de error
     * @param accessDeniedHandler   respuesta {@code 403} con el contrato de error
     * @return la cadena de filtros cerrada
     * @throws Exception si Spring Security no puede construir la cadena
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint entryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers(RUTAS_PUBLICAS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(excepciones -> excepciones
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Crea el entry point del {@code 401}.
     *
     * @param jsonMapper serializador con el que se escribe el {@code ErrorResponse}
     * @return entry point con el contrato de error de la API
     */
    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return new RestAuthenticationEntryPoint(jsonMapper);
    }

    /**
     * Crea el manejador del {@code 403} de la cadena de filtros.
     *
     * @param jsonMapper serializador con el que se escribe el {@code ErrorResponse}
     * @return manejador con el contrato de error de la API
     */
    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(JsonMapper jsonMapper) {
        return new RestAccessDeniedHandler(jsonMapper);
    }

    /**
     * Crea el filtro de validación RS256 a partir de la configuración de la aplicación.
     *
     * <p>Las tres propiedades se leen del entorno y una propiedad ausente o con un
     * placeholder sin resolver se trata como no configurada: eso ya no puede ocurrir en un
     * arranque normal, porque {@link AuthKeyStartupConfig} impide arrancar sin clave en
     * cualquier perfil, {@code test} incluido.</p>
     *
     * @param entorno    entorno del que se leen {@code auth.public.key}, {@code auth.issuer} y
     *                   {@code auth.audience}
     * @param jsonMapper serializador con el que se escribe el {@code ErrorResponse} del 401
     * @return el filtro listo para insertarse en la cadena
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(Environment entorno, JsonMapper jsonMapper) {
        return new JwtAuthenticationFilter(
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_CLAVE),
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_ISSUER),
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_AUDIENCE),
                jsonMapper);
    }
}
