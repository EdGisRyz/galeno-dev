package com.intoglobe.clinicas.security;

import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.servlet.HandlerMapping;

import com.intoglobe.clinicas.config.CorsConfig;

import jakarta.servlet.DispatcherType;
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
 * ({@code /v3/api-docs/**}, {@code /swagger-ui/**} y {@code /swagger-ui.html}) y el endpoint
 * de error {@code /error}, que responde con el contrato {@code ErrorResponse} del proyecto y
 * nunca con el cuerpo por defecto del framework.</p>
 *
 * <p><strong>La documentación se apaga por configuración, no aquí.</strong> Desde T5
 * springdoc está deshabilitado en la línea base versionada
 * ({@code springdoc.api-docs.enabled=false} y {@code springdoc.swagger-ui.enabled=false} en
 * {@code application.yml}) y solo el perfil {@code dev} lo vuelve a encender (R-SEG-09). Las
 * reglas {@code permitAll()} de documentación se conservan a propósito: mientras springdoc
 * esté apagado <em>no abren nada que no exista</em> —la ruta responde {@code 404}—, y así el
 * único interruptor de la documentación es el perfil, no esta cadena. Borrarlas no aportaría
 * seguridad y agregaría una segunda cosa que recordar el día que alguien encienda la
 * documentación en otro perfil.</p>
 *
 * <p>El {@code permitAll()} de {@code /error} no es una ruta de datos: el controlador de error
 * publica el estado real de un reenvío del contenedor y, si nadie reenvió nada, un {@code 404};
 * nunca incluye la excepción, el mensaje original ni la traza (R-SEG-10).</p>
 *
 * <h2>Fallback: deny-by-default</h2>
 *
 * <p>La cadena <strong>no</strong> termina en {@code anyRequest().authenticated()}. Termina
 * en {@link DenyByDefaultAuthorizationManager}, que cierra toda ruta con controlador y sin
 * regla explícita para <em>todos</em> los roles (R-SEG-09). Solo las rutas de la matriz y las
 * dos rutas públicas de arriba están declaradas; lo demás está cerrado por omisión, no
 * abierto por omisión. El manager también resuelve la otra mitad del contrato: una ruta
 * <em>inexistente</em> responde {@code 401} sin token y el {@code 404} del framework con
 * token, nunca {@code 403}.</p>
 *
 * <p>Ese {@code 404} depende de que el despacho de {@code ERROR} esté permitido: el
 * contenedor reenvía el {@code 404} a {@code /error} y ese reenvío vuelve a pasar por esta
 * cadena, con lo que un {@code denyAll} lo convertiría en {@code 403}. Por eso la primera
 * regla es {@code dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()}.</p>
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
     * Rutas de documentación abiertas. Desde T5 springdoc está apagado por defecto y solo el
     * perfil {@code dev} lo enciende, así que estas reglas son inertes en el resto de los
     * perfiles: la ruta responde {@code 404} y no hay nada que abrir. Ver el Javadoc de la
     * clase.
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

    /** Endpoint de error del contenedor, servido por {@code RestErrorController}. */
    private static final String RUTA_ERROR = "/error";

    /** Rol que administra los datos de su propia organización. */
    private static final String ROL_ADMIN = "ADMIN";

    /** Rol que solo lee los datos de su propia organización. */
    private static final String ROL_MEDICO = "MEDICO";

    /**
     * Define la única cadena de filtros de la aplicación: documentación y error públicos, la
     * matriz de autorización por rol, deny-by-default para el resto, CORS con credenciales y
     * errores de seguridad dentro del contrato {@code ErrorResponse}.
     *
     * <p>El orden de las reglas sigue la matriz de §5.3: primero las rutas anidadas —son las
     * más específicas— y después las de ítem. Las reglas de {@code ADMIN} cubren tanto lo
     * exclusivo del rol como lo compartido con {@code MEDICO}; la lectura compartida se
     * declara antes para que {@code MEDICO} no caiga en la regla de {@code ADMIN}. Lo que no
     * está declarado no queda «autenticado»: queda cerrado por
     * {@link DenyByDefaultAuthorizationManager}, que además deja pasar el {@code 404} de una
     * ruta inexistente en lugar de disfrazarlo de {@code 403}.</p>
     *
     * @param http                  configuración de seguridad web de Spring Security
     * @param jwtAuthenticationFilter filtro que valida el token Bearer RS256
     * @param entryPoint            respuesta {@code 401} con el contrato de error
     * @param accessDeniedHandler   respuesta {@code 403} con el contrato de error
     * @param mapeosDeHandler       mapeos de MVC con los que el fallback decide si la ruta
     *                              tiene controlador (se resuelven de forma perezosa)
     * @return la cadena de filtros cerrada
     * @throws Exception si Spring Security no puede construir la cadena
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint entryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<HandlerMapping> mapeosDeHandler) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        // El reenvío de error entra otra vez en la cadena: sin esta regla, un
                        // 404 del framework se convertiría en 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // /error se puede pedir directamente: responde el contrato
                        // ErrorResponse del proyecto (404 si no hay error que representar).
                        .requestMatchers(RUTA_ERROR).permitAll()
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
                        // quedaría inalcanzable por el aislamiento por tenant). Se declara con
                        // denyAll() y no se deja a la ausencia de regla: la celda queda
                        // auditada aquí, con nombre propio, y no depende de que el fallback
                        // siga negando todo. El resto, solo ADMIN.
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES).denyAll()
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES).hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.PUT, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_ORGANIZACIONES + "/*").hasRole(ROL_ADMIN)
                        // Fallback: deny-by-default (R-SEG-09). Toda ruta con controlador que no
                        // esté declarada arriba queda cerrada para los tres roles; una ruta
                        // inexistente conserva el 401/404 normal del framework.
                        .anyRequest().access(new DenyByDefaultAuthorizationManager(mapeosDeHandler)))
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
