package com.intoglobe.clinicas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * CONFIGURACIÓN TEMPORAL — reemplazar por RS256 ANTES de prod (Sprint 1)
 *
 * <p>CONFIGURACIÓN TEMPORAL — reemplazar por validación RS256 del AuthService
 * InToGlobe ANTES de cualquier despliegue a producción (Sprint 1 del roadmap).
 * Prohibido llevar esta configuración a prod sin JWT activo.</p>
 *
 * <p>Configuración base de seguridad (fase 0, endpoints abiertos). Existe
 * únicamente para que Eduardo verifique Swagger a mano en local: NO es una
 * decisión de seguridad de producción y no aporta aislamiento entre tenants. El
 * Sprint 1 elimina esta cadena abierta y la reemplaza por la validación RS256 del
 * AuthService InToGlobe (ver R8.2); a partir de entonces el backend SOLO valida
 * tokens emitidos por dicho AuthService y nunca genera tokens ni hashea
 * contraseñas. Prohibido agregar reglas aquí sin actualizar la tarjeta.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Define la única cadena de filtros de esta fase: todos los endpoints quedan
     * abiertos, sin CSRF y sin sesión, tal como dicta R8.1bis.
     *
     * <p>Las tres reglas de autorización siguen intactas: esta tarea (T2) solo agrega la
     * validación de la firma RS256. Cerrar las reglas es de T3.</p>
     *
     * @param http configuración de seguridad web de Spring Security
     * @param jwtAuthenticationFilter filtro que valida el token Bearer RS256
     * @return la cadena de filtros con las tres reglas mínimas autorizadas
     * @throws Exception si Spring Security no puede construir la cadena
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .authorizeHttpRequests(autorizacion -> autorizacion.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
