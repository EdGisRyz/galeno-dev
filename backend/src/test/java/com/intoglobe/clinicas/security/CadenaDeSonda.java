package com.intoglobe.clinicas.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Cadena de filtros mínima para las rutas de sonda de las pruebas.
 *
 * <p>Hasta T5 las sondas de prueba vivían bajo {@code /api/v1/sonda/**} y llegaban al
 * controlador porque el fallback de la cadena era {@code anyRequest().authenticated()}.
 * Desde T5 el fallback es <strong>deny-by-default</strong>: una ruta con controlador y sin
 * regla explícita responde {@code 403} (R-SEG-09), así que las sondas dejarían de ejercer lo
 * que quieren ejercer. Esta cadena declara esa ruta explícitamente, igual que la matriz
 * declara las quince operaciones, y reutiliza el filtro JWT y los manejadores de error
 * reales: las pruebas siguen corriendo sobre la cadena de producción cableada a mano, no
 * sobre una cadena simulada ni sobre un post-procesador de seguridad.</p>
 *
 * <p>Solo se usa en pruebas. La cadena de producción vive en {@link SecurityConfig} y no
 * conoce ninguna ruta de sonda.</p>
 */
final class CadenaDeSonda {

    private CadenaDeSonda() {
    }

    /**
     * Construye la cadena de la sonda: exige token, valida el Bearer con el filtro real y
     * responde {@code 401} con el contrato de error también real.
     *
     * @param http           configuración de seguridad web del contexto de prueba
     * @param patron         patrón de rutas que atiende esta cadena (por ejemplo
     *                       {@code /api/v1/sonda/**})
     * @param filtroJwt      filtro de validación RS256 de producción
     * @param entryPoint     entry point del {@code 401}
     * @param accesoDenegado manejador del {@code 403}
     * @return la cadena lista para registrarse como bean
     * @throws Exception si Spring Security no puede construir la cadena
     */
    static SecurityFilterChain crear(HttpSecurity http, String patron, JwtAuthenticationFilter filtroJwt,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler accesoDenegado) throws Exception {
        http
                .securityMatcher(patron)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion.anyRequest().authenticated())
                .exceptionHandling(excepciones -> excepciones
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accesoDenegado))
                .addFilterBefore(filtroJwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
