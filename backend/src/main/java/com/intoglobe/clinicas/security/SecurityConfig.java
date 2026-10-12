package com.intoglobe.clinicas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
     * @param http configuración de seguridad web de Spring Security
     * @return la cadena de filtros con las tres reglas mínimas autorizadas
     * @throws Exception si Spring Security no puede construir la cadena
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(autorizacion -> autorizacion.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
