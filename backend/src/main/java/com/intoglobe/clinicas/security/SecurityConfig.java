package com.intoglobe.clinicas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
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
 * <h2>Matriz de autorización por rol (R-SEG-05, D4)</h2>
 *
 * <p>La matriz congelada en §5.3 se aplica aquí, celda por celda, para que quede auditable
 * en un solo archivo en lugar de repartida en anotaciones por los controladores. Un rol sin
 * permiso recibe {@code 403} con el {@code ErrorResponse} habitual, porque la denegación de
 * la cadena la escribe {@link RestAccessDeniedHandler}.</p>
 *
 * <table>
 *   <caption>Matriz aplicada a las quince operaciones de la API</caption>
 *   <tr><th>Operación</th><th>ADMIN</th><th>MEDICO</th><th>PACIENTE</th></tr>
 *   <tr><td>{@code POST /organizaciones}</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones}</td><td>solo la suya</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET|PUT|DELETE /organizaciones/{id}}</td><td>propio tenant</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/consultorios}</td><td>propio tenant</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/consultorios}</td><td>propio tenant</td><td>propio tenant</td><td>403</td></tr>
 *   <tr><td>{@code GET /consultorios/{id}}</td><td>propio tenant</td><td>propio tenant</td><td>403</td></tr>
 *   <tr><td>{@code PUT|DELETE /consultorios/{id}}</td><td>propio tenant</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/medicos}</td><td>propio tenant</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/medicos}</td><td>propio tenant</td><td>propio tenant</td><td>403</td></tr>
 *   <tr><td>{@code GET /medicos/{id}}</td><td>propio tenant</td><td>propio tenant</td><td>403</td></tr>
 *   <tr><td>{@code PUT|DELETE /medicos/{id}}</td><td>propio tenant</td><td>403</td><td>403</td></tr>
 * </table>
 *
 * <p>«Propio tenant» tiene dos mitades y las aplican dos piezas distintas: aquí se exige el
 * rol, y {@link TenantAccessGuard} exige que el {@code organizacionId} del path coincida con
 * el {@code tenantId} del token, <strong>sin excepción para {@code ADMIN}</strong> (D5
 * corregido el 2026-10-29). Los recursos pedidos por id se filtran por organización en la
 * capa de datos y responden {@code 404} cuando son de otro tenant, para no revelar que
 * existen.</p>
 *
 * <h2>El alta de organizaciones está bloqueada, y es deliberado</h2>
 *
 * <p>{@code POST /organizaciones} responde {@code 403} a <strong>los tres roles</strong>
 * mediante {@code denyAll()} (decisión de producto de Eduardo, 2026-10-29). No es un
 * descuido ni una celda pendiente: con el aislamiento por tenant de esta misma matriz, la
 * organización recién creada quedaría <em>inalcanzable</em> para quien la creó —el {@code 201}
 * devolvía un {@code Location} que respondía {@code 403} a la lectura, al alta de hijos y a la
 * baja, porque el {@code tenantId} del token nunca es la organización nueva—, dejando una fila
 * activa huérfana. El alta vuelve con el flujo de onboarding dedicado (Sprint 2).</p>
 *
 * <p><strong>No lo «arregles» volviendo a conceder el alta a {@code ADMIN}.</strong> El código
 * del controlador y el del service se conservan intactos justamente para ese flujo futuro: lo
 * único que cambia es esta regla de autorización. Cualquier intento de reabrir la celda debe
 * venir acompañado del onboarding que haga alcanzable la organización creada, y de un test que
 * lo demuestre.</p>
 *
 * <p>{@code PACIENTE} recibe {@code 403} en las quince operaciones porque todavía no existe
 * ningún endpoint de paciente: no es una decisión de negocio, es ausencia de contrato. Su
 * matriz se define cuando exista {@code /pacientes}.</p>
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

    /** Ruta base de organizaciones, dueña de consultorios y médicos en las rutas anidadas. */
    private static final String RUTA_ORGANIZACIONES = "/api/v1/organizaciones";

    /** Ruta base de los consultorios pedidos por id. */
    private static final String RUTA_CONSULTORIOS = "/api/v1/consultorios";

    /** Ruta base de los médicos pedidos por id. */
    private static final String RUTA_MEDICOS = "/api/v1/medicos";

    /** Rol que administra los datos de su propia organización. */
    private static final String ROL_ADMIN = "ADMIN";

    /** Rol que solo lee los datos de su propia organización. */
    private static final String ROL_MEDICO = "MEDICO";

    /**
     * Define la única cadena de filtros de la aplicación: documentación pública, la matriz
     * de autorización por rol, todo lo demás autenticado, CORS con credenciales y errores de
     * seguridad dentro del contrato {@code ErrorResponse}.
     *
     * <p>El orden de las reglas sigue la matriz de §5.3: primero las rutas anidadas —son las
     * más específicas— y después las de ítem. Las reglas de {@code ADMIN} cubren tanto lo
     * exclusivo del rol como lo compartido con {@code MEDICO}; la lectura compartida se
     * declara antes para que {@code MEDICO} no caiga en la regla de {@code ADMIN}. El resto
     * de las rutas sigue exigiendo token aunque no estén en la matriz: una ruta inexistente
     * con token válido responde {@code 404} del framework, no {@code 403}.</p>
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
                        // Lecturas compartidas por ADMIN y MEDICO de su propio tenant.
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/consultorios")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/medicos")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        .requestMatchers(HttpMethod.GET, RUTA_CONSULTORIOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        .requestMatchers(HttpMethod.GET, RUTA_MEDICOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        // Altas y mutaciones anidadas: solo ADMIN de su propio tenant.
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES + "/*/consultorios")
                        .hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES + "/*/medicos")
                        .hasRole(ROL_ADMIN)
                        // Mutaciones por id: solo ADMIN de su propio tenant.
                        .requestMatchers(HttpMethod.PUT, RUTA_CONSULTORIOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_CONSULTORIOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.PUT, RUTA_MEDICOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_MEDICOS + "/*").hasRole(ROL_ADMIN)
                        // Organizaciones: el alta está bloqueada para los tres roles
                        // (decisión de producto del 2026-10-29: la organización creada
                        // quedaría inalcanzable por el aislamiento por tenant). denyAll() y
                        // no la ausencia de regla: sin esta línea, anyRequest() dejaría pasar
                        // a cualquier rol autenticado. El resto, solo ADMIN.
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES).denyAll()
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES).hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.PUT, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        .anyRequest().authenticated())
                .exceptionHandling(excepciones -> excepciones
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Expone la guardia de tenant que usan los controladores para validar el
     * {@code organizacionId} del path contra el claim (R-SEG-04, R-SEG-06).
     *
     * <p>Se declara como bean de esta configuración y no como {@code @Component} para que
     * las rebanadas {@code @WebMvcTest} que importan {@link SecurityConfig} tengan la misma
     * guardia real que producción, sin depender del escaneo de componentes.</p>
     *
     * @return la guardia de tenant sin estado
     */
    @Bean
    public TenantAccessGuard tenantAccessGuard() {
        return new TenantAccessGuard();
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
