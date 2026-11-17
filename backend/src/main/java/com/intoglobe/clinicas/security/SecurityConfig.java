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
 * <p>La matriz congelada en sección 5.3 se aplica aquí, celda por celda, para que quede auditable
 * en un solo archivo en lugar de repartida en anotaciones por los controladores. Un rol sin
 * permiso recibe {@code 403} con el {@code ErrorResponse} habitual, porque la denegación de
 * la cadena la escribe {@link RestAccessDeniedHandler}.</p>
 *
 * <table>
 *   <caption>Matriz aplicada a las treinta y cinco operaciones de la API</caption>
 *   <tr><th>Operación</th><th>ADMIN</th><th>MEDICO</th><th>PACIENTE</th><th>RECEPCIONISTA</th></tr>
 *   <tr><td>{@code POST /organizaciones}</td><td>403</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones}</td><td>solo la suya</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET|PUT|DELETE /organizaciones/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/consultorios}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/consultorios}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code GET /consultorios/{id}}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code PUT|DELETE /consultorios/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/medicos}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/medicos}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code GET /medicos/{id}}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code PUT|DELETE /medicos/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /medicos/{id}/horarios}</td><td>propio tenant</td><td>su propia agenda</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /medicos/{id}/horarios}</td><td>propio tenant</td><td>su propia agenda</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code GET|PUT|DELETE /horarios/{id}}</td><td>propio tenant</td><td>su propia agenda</td><td>403</td><td>{@code GET} sí; {@code PUT|DELETE} 403</td></tr>
 *   <tr><td>{@code GET /medicos/{id}/consultorios}</td><td>propio tenant</td><td>solo los propios</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code POST /medicos/{id}/consultorios}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code DELETE /medicos/{id}/consultorios/{consultorioId}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/pacientes}</td><td>propio tenant</td><td>403</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/pacientes}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code GET /pacientes/{id}}</td><td>propio tenant</td><td>propio tenant</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code PUT|DELETE /pacientes/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code POST /pacientes/{id}/activar}</td><td>propio tenant</td><td>403</td><td>403</td><td>propio tenant</td></tr>
 *   <tr><td>{@code POST /organizaciones/{orgId}/usuarios}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /organizaciones/{orgId}/usuarios}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code GET /usuarios/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code PUT /usuarios/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code DELETE /usuarios/{id}}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 *   <tr><td>{@code POST /usuarios/{id}/activar}</td><td>propio tenant</td><td>403</td><td>403</td><td>403</td></tr>
 * </table>
 *
 * <p>«Propio tenant» tiene dos mitades y las aplican dos piezas distintas: aquí se exige el
 * rol, y {@link TenantAccessGuard} exige que el {@code organizacionId} del path coincida con
 * el {@code tenantId} del token, <strong>sin excepción para {@code ADMIN}</strong> (D5
 * corregido el 2026-10-29). Los recursos pedidos por id se filtran por organización en la
 * capa de datos y responden {@code 404} cuando son de otro tenant, para no revelar que
 * existen.</p>
 *
 * <h2>Horarios: la matriz concede el rol, el service acota el tenant y la agenda</h2>
 *
 * <p>Las cinco rutas de bloques de disponibilidad aparecen en la tabla como «su propia
 * agenda» para {@code MEDICO}: esa columna no la aplica esta cadena, la aplica
 * {@code HorarioMedicoService} (ver abajo). {@code RECEPCIONISTA} solo tiene las dos lecturas:
 * el horario del médico es suyo y de la administración, y recepción lo consulta para responder
 * dónde y cuándo atiende y derivar, pero no lo modifica.
 * La autorización de estas rutas tiene <strong>dos capas</strong> y esta cadena solo aplica la
 * primera: el rol abre la ruta, y {@code HorarioMedicoService} comprueba contra el token que
 * el médico —o el médico dueño del bloque— pertenezca al {@code tenantId} del claim
 * ({@code 404} si no, para no revelar que existe) y que un token {@code MEDICO} solo opere
 * sobre su propia agenda ({@code 403} cuando el bloque es de otro médico de la misma
 * organización). Ninguna de esas dos comprobaciones se puede expresar aquí: dependen del
 * estado de la base, no del rol.</p>
 *
 * <h2>Consultorios del médico: la matriz concede el rol, el service acota el tenant y la propiedad</h2>
 *
 * <p>Las tres rutas de la asignación de consultorios aparecen en la tabla como «solo los
 * propios» en la lectura y como {@code 403} para {@code MEDICO} en las dos escrituras, y
 * {@code RECEPCIONISTA} tiene la lectura para responder dónde atiende. Como en
 * horarios, esta cadena solo aplica la mitad del rol: {@code MedicoConsultorioService} comprueba
 * contra el {@code tenantId} del claim que el médico del path pertenezca a la organización del
 * token ({@code 404} si no, para no revelar que existe) y que un token {@code MEDICO} consulte
 * los consultorios del médico de su claim {@code medicoId} ({@code 403} si el path apunta a
 * otro). Ninguna de las dos se puede expresar aquí: dependen del estado de la base.</p>
 *
 * <p><strong>Estas rutas no tienen {@code organizacionId} en el path</strong>, porque la colección
 * cuelga del médico ({@code /medicos/{medicoId}/consultorios}). No hay, por lo tanto, ninguna
 * celda de «path de otro tenant responde {@code 403}» que aplicar: la organización sale siempre
 * del claim y el cruce de tenant se resuelve con {@code 404}, igual que en el módulo de
 * horarios. El {@code 409} de las dos reglas de conflicto —el consultorio principal del médico y
 * un consultorio con bloques de horario suyos— tampoco vive aquí: es estado de la base.</p>
 *
 * <h2>Pacientes: la matriz concede el rol, el service acota la organización y la pertenencia</h2>
 *
 * <p>Las seis rutas de pacientes aparecen en la tabla como «propio tenant» para
 * {@code ADMIN} y {@code RECEPCIONISTA} —recepción administra las fichas de paciente del
 * mostrador— y, en las dos lecturas, para {@code MEDICO}: esa columna tampoco la aplica
 * esta cadena. Aquí se concede el rol —{@code ADMIN} y {@code RECEPCIONISTA} escriben,
 * {@code MEDICO} solo lee— y
 * {@code PacienteService} comprueba contra el {@code tenantId} del claim que la
 * organización del path sea la del token y que el paciente pertenezca a esa organización,
 * respondiendo {@code 404} cuando no, para no revelar que existe en otra clínica.</p>
 *
 * <p>En las dos rutas anidadas la discrepancia entre el {@code organizacionId} del path y el
 * tenant del claim la resuelve el service con {@code 404} —la misma respuesta que una
 * organización inexistente— y no con el {@code 403} que {@link TenantAccessGuard} aplica a
 * los módulos anteriores. Es una decisión del contrato de {@code PacienteService} (sus
 * comprobaciones se ordenan así y el módulo la declara en su nota), no una excepción de
 * esta cadena: la respuesta difiere de la de los módulos anteriores y se deja dicha acá para
 * que la matriz no se lea como «todo path ajeno responde {@code 403}». Lo que se conserva es
 * la propiedad que importa: ninguna de las dos respuestas confirma que la organización ajena
 * exista.</p>
 *
 * <h2>Usuarios: la matriz concede el rol, el service acota la organización y protege las cuentas</h2>
 *
 * <p>Las seis rutas de cuentas aparecen en la tabla como «propio tenant» solo para {@code ADMIN},
 * que es el único rol que administra cuentas (R9 de la card 06); {@code MEDICO},
 * {@code PACIENTE} y {@code RECEPCIONISTA} no tienen ninguna celda de cuentas. Como en los
 * módulos anteriores, esta cadena solo
 * concede el rol: {@code UsuarioService} comprueba contra el {@code tenantId} del claim que la
 * organización del path sea la del token ({@code 403} sin consultar la base), responde
 * {@code 404} por una cuenta o un médico de otra organización para no revelar que existen, y
 * aplica las dos reglas que ningún rol puede ejercer sobre su propia cuenta: nadie se da de baja
 * ni se cambia el rol a sí mismo ({@code 403}, R11) y la última cuenta {@code ADMIN} activa no se
 * puede dar de baja ni perder el rol ({@code 409}, R8). Ninguna de esas tres comprobaciones se
 * puede expresar aquí: dependen del estado de la base y del actor, no del rol.</p>
 *
 * <h2>El alta de organizaciones está bloqueada, y es deliberado</h2>
 *
 * <p>{@code POST /organizaciones} responde {@code 403} a <strong>los cuatro roles</strong>
 * mediante {@code denyAll()} (decisión de producto, 2026-10-29). No es un
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
 * <p>{@code PACIENTE} recibe {@code 403} en las treinta y cinco operaciones. Desde este módulo
 * existe contrato de pacientes, así que su {@code 403} ya no es ausencia de endpoint:
 * <strong>hoy no se emiten tokens {@code PACIENTE}</strong> —el rol existe en el schema y se
 * conserva para una implementación futura, pero no hay cuentas de paciente— y por eso no
 * tiene ninguna celda concedida, ni las lecturas de pacientes que serían las primeras
 * candidatas. Cuando se emitan, necesitarán sus propias celdas acá, incluida la de sus
 * propios datos.</p>
 *
 * <p>El rol de personal {@code RECEPCIONISTA} está en el contrato de claims y tiene sus celdas
 * explícitas en esta matriz (ver la tabla): consulta consultorios, médicos, sus consultorios
 * asignados, la agenda de horarios y los pacientes de su organización, y registra, actualiza, da
 * de baja y reactiva pacientes. Lo que no tiene es escritura sobre la agenda ni sobre
 * consultorios, médicos y sus asignaciones, ni cuentas del personal, ni la organización: todas
 * esas celdas le responden {@code 403} con el contrato de error, porque el rol está en el
 * contrato y el rechazo es de matriz, no de token.</p>
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

    /** Ruta base de los bloques de disponibilidad pedidos por id. */
    private static final String RUTA_HORARIOS = "/api/v1/horarios";

    /** Ruta base de los pacientes pedidos por id y de su reactivación. */
    private static final String RUTA_PACIENTES = "/api/v1/pacientes";

    /** Ruta base de las cuentas de usuario pedidas por id y de su reactivación. */
    private static final String RUTA_USUARIOS = "/api/v1/usuarios";

    /** Endpoint de error del contenedor, servido por {@code RestErrorController}. */
    private static final String RUTA_ERROR = "/error";

    /** Rol que administra los datos de su propia organización. */
    private static final String ROL_ADMIN = "ADMIN";

    /** Rol que solo lee los datos de su propia organización. */
    private static final String ROL_MEDICO = "MEDICO";

    /**
     * Rol de personal del mostrador: consulta el catálogo, la agenda y los pacientes, y administra
     * esas mismas fichas de paciente de su organización. No toca cuentas ni la configuración de la
     * organización, y no escribe sobre la agenda ni el catálogo (S3).
     */
    private static final String ROL_RECEPCIONISTA = "RECEPCIONISTA";

    /**
     * Define la única cadena de filtros de la aplicación: documentación y error públicos, la
     * matriz de autorización por rol, deny-by-default para el resto, CORS con credenciales y
     * errores de seguridad dentro del contrato {@code ErrorResponse}.
     *
     * <p>El orden de las reglas sigue la matriz de sección 5.3: primero las rutas anidadas —son las
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
                        // Lecturas compartidas por ADMIN, MEDICO y RECEPCIONISTA de su propio
                        // tenant. Recepción las necesita para responder dónde y cuándo atiende
                        // cada médico y para atender el mostrador.
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/consultorios")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/medicos")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.GET, RUTA_CONSULTORIOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.GET, RUTA_MEDICOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
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
                        // Organizaciones: el alta está bloqueada para los cuatro roles
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
                        // Horarios: ADMIN y MEDICO escriben, y RECEPCIONISTA solo lee. La lectura
                        // es suya porque necesita responder dónde y cuándo atiende cada médico y
                        // derivar; la escritura de la agenda no entra en ninguna historia de
                        // recepción (decisión de producto, 2026-11-16). Esta regla solo concede el
                        // rol; el acotamiento por organización (404) y la propiedad de la agenda
                        // (403 si un MEDICO toca la de otro médico) los aplica HorarioMedicoService
                        // contra el tenantId y el medicoId del token. Se declaran los cinco verbos
                        // por separado porque cada uno es una operación de la matriz.
                        .requestMatchers(HttpMethod.GET, RUTA_MEDICOS + "/*/horarios")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.POST, RUTA_MEDICOS + "/*/horarios")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        .requestMatchers(HttpMethod.GET, RUTA_HORARIOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.PUT, RUTA_HORARIOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        .requestMatchers(HttpMethod.DELETE, RUTA_HORARIOS + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO)
                        // Consultorios asignados al médico: el ADMIN los asigna y los retira y
                        // comparte la lectura con MEDICO —que solo ve los de su propio médico— y
                        // con RECEPCIONISTA, que los consulta para responder dónde atiende.
                        // Esta regla solo concede el rol; el acotamiento por organización (404) y
                        // la propiedad (403 si un MEDICO pide los de otro médico) los aplica
                        // MedicoConsultorioService contra el tenantId y el medicoId del token. Se
                        // declaran los tres verbos por separado porque cada uno es una operación
                        // de la matriz.
                        .requestMatchers(HttpMethod.GET, RUTA_MEDICOS + "/*/consultorios")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.POST, RUTA_MEDICOS + "/*/consultorios")
                        .hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_MEDICOS + "/*/consultorios/*")
                        .hasRole(ROL_ADMIN)
                        // Pacientes: ADMIN y RECEPCIONISTA escriben, y MEDICO solo lee. Esta regla
                        // solo concede el rol; que la organización del path sea la del token y que
                        // el paciente pertenezca a esa organización (404 si no) lo aplica
                        // PacienteService contra el tenantId del claim. Se declaran las seis
                        // operaciones por separado porque cada una es una celda de la matriz;
                        // la reactivación se nombra aparte porque POST /pacientes/* no la
                        // alcanza (son dos segmentos después de la base).
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/pacientes")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.GET, RUTA_PACIENTES + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_MEDICO, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES + "/*/pacientes")
                        .hasAnyRole(ROL_ADMIN, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.PUT, RUTA_PACIENTES + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.DELETE, RUTA_PACIENTES + "/*")
                        .hasAnyRole(ROL_ADMIN, ROL_RECEPCIONISTA)
                        .requestMatchers(HttpMethod.POST, RUTA_PACIENTES + "/*/activar")
                        .hasAnyRole(ROL_ADMIN, ROL_RECEPCIONISTA)
                        // Usuarios: la administración de cuentas es exclusiva de ADMIN (R9 de la
                        // card 06). Esta regla solo concede el rol; el acotamiento por
                        // organización (403 si el path no es el del claim, 404 si la cuenta o el
                        // médico son de otra organización), la protección de la última
                        // administradora (409) y el rechazo de la operación sobre la propia
                        // cuenta (403) los aplica UsuarioService contra el tenantId y el actor
                        // del claim. Se declaran las seis operaciones por separado porque cada
                        // una es una celda de la matriz; la reactivación se nombra aparte porque
                        // POST /usuarios/* no la alcanza (son dos segmentos después de la base).
                        .requestMatchers(HttpMethod.GET, RUTA_ORGANIZACIONES + "/*/usuarios")
                        .hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.POST, RUTA_ORGANIZACIONES + "/*/usuarios")
                        .hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.GET, RUTA_USUARIOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.PUT, RUTA_USUARIOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.DELETE, RUTA_USUARIOS + "/*").hasRole(ROL_ADMIN)
                        .requestMatchers(HttpMethod.POST, RUTA_USUARIOS + "/*/activar").hasRole(ROL_ADMIN)
                        // Fallback: deny-by-default (R-SEG-09). Toda ruta con controlador que no
                        // esté declarada arriba queda cerrada para los cuatro roles del contrato —
                        // ADMIN, MEDICO, PACIENTE y RECEPCIONISTA—; una ruta
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
     * @param resolutor  traductor de slug a UUID; se recibe como proveedor y no como
     *                   dependencia directa para que las rebanadas {@code @WebMvcTest} que
     *                   importan esta configuración —y que no levantan JPA— sigan arrancando:
     *                   allí el proveedor no tiene resolutor y el filtro rechaza cerrado
     *                   cualquier claim con forma de slug
     * @return el filtro listo para insertarse en la cadena
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(Environment entorno, JsonMapper jsonMapper,
            ObjectProvider<ResolutorDeTenant> resolutor) {
        return new JwtAuthenticationFilter(
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_CLAVE),
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_ISSUER),
                AuthPublicKey.valorConfigurado(entorno, AuthPublicKey.PROPIEDAD_AUDIENCE),
                jsonMapper,
                resolutor.getIfAvailable());
    }
}
