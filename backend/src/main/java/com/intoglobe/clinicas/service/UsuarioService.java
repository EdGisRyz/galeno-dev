package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.exception.CorreoDuplicadoEnOrganizacionException;
import com.intoglobe.clinicas.exception.MedicoRequeridoException;
import com.intoglobe.clinicas.exception.OperacionSobreLaPropiaCuentaException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.UltimaAdministradoraException;
import com.intoglobe.clinicas.mapper.UsuarioMapper;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;
import com.intoglobe.clinicas.security.AuthenticatedUser;

/**
 * Lógica de negocio de las cuentas de usuario.
 *
 * <p>Orquesta los tres repositorios y el {@link UsuarioMapper}. Las lecturas son transacciones de
 * solo lectura; las escrituras usan una transacción de lectura y escritura.</p>
 *
 * <p><strong>Aislamiento por tenant (R1 y R10).</strong> La organización sale siempre del claim de
 * la sesión, nunca del cuerpo ni de la URL. Las dos respuestas son distintas a propósito:</p>
 * <ul>
 *   <li>Una <em>organización del path</em> que no es la del claim responde
 *       {@link AccessDeniedException}, es decir {@code 403}, <strong>sin consultar la base</strong>:
 *       el path es una afirmación del cliente y desmentirla no confirma la existencia de nada.</li>
 *   <li>Una <em>cuenta pedida por id</em> que pertenece a otra organización responde
 *       {@link ResourceNotFoundException}, es decir {@code 404} igual que un identificador
 *       inexistente, porque un {@code 403} confirmaría que esa cuenta existe en otra clínica.</li>
 * </ul>
 *
 * <p><strong>Orden de las comprobaciones al crear y actualizar.</strong> Primero la organización
 * del claim, después la existencia de la organización, luego la coherencia entre rol y médico
 * (R4) y por último la disponibilidad del correo (R2). El orden importa porque cada regla tiene su
 * propio código y el cliente debe recibir el que corresponde a lo que envió: una petición con un
 * médico inexistente <em>y</em> un correo repetido es primero un 404, que es el error más
 * específico.</p>
 *
 * <p><strong>El correo se normaliza a minúsculas y sin espacios en los extremos</strong> en dos
 * puntos que usan la misma definición, {@link Usuario#normalizarEmail(String)}: la entidad al
 * guardar y este servicio al buscar si el correo está ocupado. La unicidad es por organización —el
 * mismo correo puede tener cuentas independientes en dos clínicas— y la restricción de la base sigue
 * siendo la última palabra frente a dos peticiones simultáneas.</p>
 *
 * <p><strong>La identidad de autenticación la aporta este servicio</strong> (R6): en local se
 * genera un UUID v4 al crear la cuenta, porque el emisor de desarrollo consulta esta misma tabla
 * para emitir el token. Contra la plataforma real el valor será el {@code sub} del token, que
 * entrará por el mismo parámetro. La cuenta nunca cambia de identidad después.</p>
 *
 * <p><strong>Nadie opera sobre su propia cuenta (R11).</strong> Las dos operaciones destructivas
 * —la baja y el cambio de rol— reciben el {@link AuthenticatedUser} de la sesión, no solo el
 * tenant: además de acotar la organización, el actor permite rechazar que alguien se dé de baja a sí
 * mismo o se quite el rol, que es el clic que deja a una administradora fuera del sistema. Esa
 * comprobación va <strong>antes</strong> que la de la última administradora (R8): si alguien intenta
 * las dos cosas a la vez, el mensaje útil es el de R11, porque el de R8 sería solo la consecuencia.
 * Las demás operaciones conservan la firma con {@code tenantId}, porque no pueden perjudicar al
 * actor.</p>
 */
@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final OrganizacionRepository organizacionRepository;
    private final MedicoRepository medicoRepository;

    public UsuarioService(UsuarioRepository usuarioRepository, OrganizacionRepository organizacionRepository,
            MedicoRepository medicoRepository) {
        this.usuarioRepository = usuarioRepository;
        this.organizacionRepository = organizacionRepository;
        this.medicoRepository = medicoRepository;
    }

    /**
     * Crea una cuenta dentro de la organización indicada.
     *
     * <p>La identidad de autenticación se genera aquí (R6) y no se recibe del cliente. El médico
     * se resuelve y se valida contra la organización antes de mapear (R4), y el correo se busca ya
     * normalizado antes de guardar (R2). El nombre y el teléfono vienen en el request: el nombre es
     * obligatorio —lo impone el DTO— y el teléfono opcional, y los dos se persisten en las columnas
     * que la V10 ya creó. No hay una variante de este método que cree la cuenta sin nombre.</p>
     *
     * @param organizacionId organización dueña: debe ser el tenant del token
     * @param tenantId       organización del token de la petición
     * @param request        datos de entrada validados, incluidos el nombre y el teléfono
     * @return representación de la cuenta persistida, sin {@code authUserId}
     * @throws AccessDeniedException      si la organización del path no es la del token; el handler
     *                                    lo traduce a 403 sin consultar la base
     * @throws ResourceNotFoundException  si la organización no existe, o si el médico indicado no
     *                                    existe o es de otra organización (404 en ambos casos)
     * @throws MedicoRequeridoException   si el rol es {@code MEDICO} y no se indicó médico (400)
     * @throws CorreoDuplicadoEnOrganizacionException si el correo ya está ocupado en la
     *                                    organización (409)
     */
    @Transactional
    public UsuarioResponse crear(UUID organizacionId, UUID tenantId, UsuarioRequest request) {
        exigirOrganizacionDelTenant(organizacionId, tenantId);
        var organizacion = organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", organizacionId));
        var medico = resolverMedico(organizacionId, request);
        exigirCorreoDisponible(organizacionId, request.email(), null);
        var authUserId = UUID.randomUUID();
        var guardado = usuarioRepository.save(
                UsuarioMapper.aEntidad(organizacion, medico, request, authUserId));
        return UsuarioMapper.aResponse(guardado);
    }

    /**
     * Crea la cuenta administradora de una organización recién creada (S5).
     *
     * <p>Es la <strong>misma</strong> creación de cuentas que {@link #crear(UUID, UUID, UsuarioRequest)}:
     * el rol {@code ADMIN}, la identidad de autenticación nueva, el médico nulo y el correo
     * comprobado dentro de la organización salen de este mismo camino. No existe una segunda forma de
     * crear un administrador, y por eso el registro público no arma una entidad por su cuenta.</p>
     *
     * <p>Se diferencia de {@link #crear(UUID, UUID, UsuarioRequest)} en dos cosas, y solo en dos: no
     * recibe el {@code tenantId} del token —la organización acaba de nacer y todavía no hay sesión que
     * la reclame— y el teléfono de la persona no se pide, porque el formulario público no tiene ese
     * campo. El <strong>nombre sí se guarda</strong>, como en el alta administrativa, y viaja dentro
     * del mismo {@code UsuarioRequest} para que las dos rutas escriban la fila por el mismo mapeo.</p>
     *
     * <p>La organización tiene que estar ya persistida: el correo se comprueba contra su
     * identificador y la fila del usuario apunta a ella. Lo garantiza quien llama, que crea la
     * organización en la misma transacción.</p>
     *
     * @param organizacion organización recién creada y persistida, dueña de la cuenta
     * @param nombre       nombre de la persona que administrará la organización
     * @param email        correo de la cuenta; se normaliza al guardar
     * @return representación de la cuenta persistida, sin {@code authUserId}
     * @throws CorreoDuplicadoEnOrganizacionException si el correo ya está ocupado en la organización
     *                                                (no ocurre en un alta recién creada, y se
     *                                                conserva para que la regla sea la misma)
     */
    @Transactional
    public UsuarioResponse crearAdministrador(Organizacion organizacion, String nombre, String email) {
        exigirCorreoDisponible(organizacion.getId(), email, null);
        var authUserId = UUID.randomUUID();
        var guardado = usuarioRepository.save(UsuarioMapper.aEntidad(organizacion, null,
                new UsuarioRequest(email, RolUsuario.ADMIN, null, nombre, null), authUserId));
        return UsuarioMapper.aResponse(guardado);
    }

    /**
     * Busca una cuenta por su identificador dentro del tenant indicado.
     *
     * @param id       identificador de la cuenta
     * @param tenantId organización del token de la petición
     * @return representación de la cuenta encontrada
     * @throws ResourceNotFoundException si no existe una cuenta con ese id <em>en ese tenant</em>;
     *                                   una cuenta de otra organización cae aquí a propósito, para
     *                                   no revelar que existe (R10)
     */
    @Transactional(readOnly = true)
    public UsuarioResponse buscarPorId(UUID id, UUID tenantId) {
        return UsuarioMapper.aResponse(buscarDelTenant(id, tenantId));
    }

    /**
     * Lista las cuentas de una organización, activas e inactivas, ordenadas por correo.
     *
     * <p>Antes de listar se comprueba que la organización del path sea la del token y que exista:
     * un id de otra organización responde 403 sin llegar a consultar el listado, y un id
     * inexistente responde 404 en vez de una lista vacía que ocultaría el error del cliente. Las
     * cuentas dadas de baja se incluyen porque la baja es lógica y la pantalla necesita ofrecer la
     * reactivación: ocultarlas dejaría la única salida en un alta nueva, que la unicidad del correo
     * rechaza.</p>
     *
     * @param organizacionId organización dueña de las cuentas
     * @param tenantId       organización del token de la petición
     * @return cuentas de esa organización ordenadas por correo ascendente
     * @throws AccessDeniedException     si la organización del path no es la del token
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<UsuarioResponse> listarPorOrganizacion(UUID organizacionId, UUID tenantId) {
        exigirOrganizacionDelTenant(organizacionId, tenantId);
        if (organizacionRepository.findById(organizacionId).isEmpty()) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        return usuarioRepository.listarPorOrganizacion(organizacionId).stream()
                .map(UsuarioMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los campos editables de una cuenta existente: correo, rol, médico, nombre y teléfono.
     *
     * <p>La organización, la identidad de autenticación, {@code activo} y {@code createdAt} nunca
     * cambian: actualizar una cuenta dada de baja no la reactiva y la fecha de alta no se reescribe.
     * Cambiar de {@code MEDICO} a otro rol libera el médico —la agenda queda sin dueño, pero la
     * fila del médico y sus bloques de horario no se tocan—, y la sesión abierta conserva el rol
     * anterior hasta que la persona vuelva a iniciar sesión, que es el comportamiento normal de un
     * token ya emitido.</p>
     *
     * <p>El nombre es obligatorio en la edición, así que guardar una cuenta anterior a la V10 obliga a
     * completárselo: es la consecuencia asumida de que la lista muestre personas y no correos. El
     * teléfono nulo lo borra, porque el {@code PUT} es un reemplazo.</p>
     *
     * @param actor    usuario autenticado de la sesión: aporta el tenant y permite rechazar la
     *                 operación sobre la propia cuenta (R11)
     * @param id       identificador de la cuenta a actualizar
     * @param request  datos de entrada validados, incluidos el nombre y el teléfono
     * @return representación de la cuenta actualizada
     * @throws OperacionSobreLaPropiaCuentaException si el actor se cambia el rol a sí mismo (403)
     * @throws ResourceNotFoundException  si la cuenta no existe en ese tenant, o si el médico
     *                                    indicado no existe o es de otra organización
     * @throws MedicoRequeridoException   si el rol resultante es {@code MEDICO} y no se indicó
     *                                    médico (400)
     * @throws CorreoDuplicadoEnOrganizacionException si otra cuenta de la organización ya usa ese
     *                                    correo (409)
     * @throws UltimaAdministradoraException si retirarle el rol {@code ADMIN} dejaría a la
     *                                    organización sin administración (409)
     */
    @Transactional
    public UsuarioResponse actualizar(AuthenticatedUser actor, UUID id, UsuarioRequest request) {
        var usuario = buscarDelTenant(id, actor.tenantId());
        exigirQueNoSeCambieElRolASiMisma(actor, usuario, request.rol());
        var organizacionId = usuario.getOrganizacion().getId();
        var medico = resolverMedico(organizacionId, request);
        exigirCorreoDisponible(organizacionId, request.email(), usuario);
        exigirAdministracionRestanteAlCambiarElRol(usuario, request.rol());
        UsuarioMapper.actualizarEntidad(usuario, medico, request);
        return UsuarioMapper.aResponse(usuarioRepository.save(usuario));
    }

    /**
     * Aplica la baja lógica de una cuenta. Es idempotente: desactivar una cuenta ya inactiva no
     * produce error. Solo cambia {@code activo}.
     *
     * <p>La fila se conserva: la cuenta es la que da sentido a los adjuntos que subió y al historial
     * de la organización, y su correo sigue ocupado por la restricción de unicidad, así que volver a
     * crearla para la misma persona obligaría a inventarle otro correo.</p>
     *
     * @param actor    usuario autenticado de la sesión: aporta el tenant y permite rechazar la baja
     *                 de la propia cuenta (R11)
     * @param id       identificador de la cuenta a dar de baja
     * @throws OperacionSobreLaPropiaCuentaException si el actor se da de baja a sí mismo (403)
     * @throws ResourceNotFoundException     si la cuenta no existe en ese tenant
     * @throws UltimaAdministradoraException si es la última cuenta {@code ADMIN} activa de la
     *                                       organización (409): sin ella nadie podría administrar
     *                                       cuentas
     */
    @Transactional
    public void desactivar(AuthenticatedUser actor, UUID id) {
        var usuario = buscarDelTenant(id, actor.tenantId());
        exigirQueNoSeDeDeBajaASiMisma(actor, usuario);
        exigirAdministracionRestanteAlDarDeBaja(usuario);
        usuario.desactivar();
        usuarioRepository.save(usuario);
    }

    /**
     * Reactiva una cuenta dada de baja lógica. Es idempotente: activar una cuenta que ya está
     * activa no produce error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p>No comprueba la regla de la última administradora porque activar nunca reduce la
     * administración de la organización. Tampoco reactiva al médico de la cuenta: si su médico está
     * inactivo el acceso se cierra en el borde, de modo que reactivar al médico devuelve el acceso
     * sin que nadie tenga que acordarse de reactivar la cuenta.</p>
     *
     * @param id       identificador de la cuenta a reactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si la cuenta no existe en ese tenant
     */
    @Transactional
    public void activar(UUID id, UUID tenantId) {
        var usuario = buscarDelTenant(id, tenantId);
        usuario.activar();
        usuarioRepository.save(usuario);
    }

    /**
     * Exige que la organización del path sea la del token.
     *
     * <p>La comparación es pura —sin repositorio, sin base de datos—, así que la denegación no
     * revela si la organización ajena existe: es la misma regla que aplica {@code PacienteService}
     * y la que fija D5.</p>
     *
     * @param organizacionId organización recibida por el path
     * @param tenantId       organización del token de la petición
     * @throws AccessDeniedException si no coinciden; el manejador global lo traduce a 403
     */
    private static void exigirOrganizacionDelTenant(UUID organizacionId, UUID tenantId) {
        if (!Objects.equals(organizacionId, tenantId)) {
            throw new AccessDeniedException(
                    "El organizacionId de la ruta no corresponde al tenant del token");
        }
    }

    /**
     * Recupera la cuenta indicada solo si pertenece a la organización del token.
     *
     * <p>Un id inexistente y un id de otra organización producen el mismo
     * {@link ResourceNotFoundException}, es decir el mismo 404, para no confirmar que la cuenta
     * existe en otra clínica. Se resuelve con {@code findById} y la comparación en memoria, igual
     * que el resto de los módulos.</p>
     *
     * @param id       identificador de la cuenta solicitada
     * @param tenantId organización del token de la petición
     * @return la cuenta, si existe y pertenece a la organización del token
     * @throws ResourceNotFoundException si no existe o es de otra organización
     */
    private Usuario buscarDelTenant(UUID id, UUID tenantId) {
        return usuarioRepository.findById(id)
                .filter(usuario -> Objects.equals(usuario.getOrganizacion().getId(), tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
    }

    /**
     * Resuelve el médico asociado a la cuenta y comprueba la coherencia con el rol (R4 y R5).
     *
     * <p>Sin médico indicado, los roles que no atienden son válidos y el {@code MEDICO} responde
     * 400: una cuenta de médico sin su fila de médico no tiene agenda ni aparece en el directorio, y
     * el CHECK de la base lo impediría de todos modos, pero como error de integridad. Con médico
     * indicado, la cuenta solo se asocia si el médico existe y es de la misma organización; en
     * cualquier otro caso responde 404, nunca 403, porque un 403 confirmaría que ese médico existe en
     * otra clínica.</p>
     *
     * @param organizacionId organización de la cuenta
     * @param request        datos de entrada ya validados
     * @return el médico a asociar, o {@code null} si la cuenta no lleva médico
     * @throws MedicoRequeridoException  si el rol es {@code MEDICO} y no se indicó médico
     * @throws ResourceNotFoundException si el médico indicado no existe o es de otra organización
     */
    private Medico resolverMedico(UUID organizacionId, UsuarioRequest request) {
        var medicoId = request.medicoId();
        if (medicoId == null) {
            if (request.rol() == RolUsuario.MEDICO) {
                throw new MedicoRequeridoException();
            }
            return null;
        }
        return medicoRepository.findById(medicoId)
                .filter(medico -> Objects.equals(medico.getOrganizacion().getId(), organizacionId))
                .orElseThrow(() -> new ResourceNotFoundException("Medico", medicoId));
    }

    /**
     * Exige que el correo esté libre dentro de la organización (R2).
     *
     * <p>El correo se busca normalizado —recortado y en minúsculas—, con la misma definición que usa
     * la entidad al guardar, de modo que {@code Juan@x.com}, {@code juan@x.com} y
     * {@code "  juan@x.com  "} compiten por el mismo hueco. La
     * consulta está acotada a la organización: el mismo correo en otra clínica es otra cuenta y no un
     * duplicado. La comprobación previa da un 409 entendible; la restricción de unicidad de la base
     * sigue siendo la última palabra si dos peticiones llegan a la vez.</p>
     *
     * @param organizacionId organización en cuyo espacio de correos se comprueba
     * @param email          correo tal como llegó en el cuerpo
     * @param cuentaActual   cuenta que se está actualizando —su propio correo no es un duplicado— o
     *                       {@code null} al crear
     * @throws CorreoDuplicadoEnOrganizacionException si el correo ya lo usa otra cuenta de la
     *                                                organización
     */
    private void exigirCorreoDisponible(UUID organizacionId, String email, Usuario cuentaActual) {
        var normalizado = Usuario.normalizarEmail(email);
        var ocupado = usuarioRepository.buscarPorOrganizacionYEmail(organizacionId, normalizado)
                .filter(otra -> cuentaActual == null || !Objects.equals(otra.getId(), cuentaActual.getId()))
                .isPresent();
        if (ocupado) {
            throw new CorreoDuplicadoEnOrganizacionException(normalizado);
        }
    }

    /**
     * Exige que el actor no sea el dueño de la cuenta que intenta dar de baja (R11).
     *
     * <p>Va antes que {@link #exigirAdministracionRestanteAlDarDeBaja(Usuario)} a propósito: si
     * alguien intenta darse de baja a sí mismo y además es la última administradora, el mensaje que le
     * sirve es el de R11 —lo que tiene que hacer es pedirle a otra cuenta que lo haga—, no el de R8,
     * que sería una consecuencia de su propio intento.</p>
     *
     * @param actor   usuario autenticado de la sesión
     * @param usuario cuenta que se intenta dar de baja
     * @throws OperacionSobreLaPropiaCuentaException si el actor es el dueño de la cuenta
     */
    private static void exigirQueNoSeDeDeBajaASiMisma(AuthenticatedUser actor, Usuario usuario) {
        if (esLaPropiaCuenta(actor, usuario)) {
            throw OperacionSobreLaPropiaCuentaException.alDarDeBaja(usuario.getId());
        }
    }

    /**
     * Exige que el actor no se esté cambiando el rol a sí mismo (R11).
     *
     * <p>Solo aplica cuando el rol resultante difiere del actual: editar el propio correo, o reenviar
     * el mismo rol, no deja a nadie fuera del sistema y la regla no lo prohíbe. También aquí la
     * comprobación precede a R8, por la misma razón que en la baja.</p>
     *
     * @param actor     usuario autenticado de la sesión
     * @param usuario   cuenta que se intenta actualizar
     * @param nuevoRol  rol que quedaría tras la operación
     * @throws OperacionSobreLaPropiaCuentaException si el actor es el dueño de la cuenta y el rol
     *                                               cambia
     */
    private static void exigirQueNoSeCambieElRolASiMisma(AuthenticatedUser actor, Usuario usuario,
            RolUsuario nuevoRol) {
        if (nuevoRol != usuario.getRol() && esLaPropiaCuenta(actor, usuario)) {
            throw OperacionSobreLaPropiaCuentaException.alCambiarElRol(usuario.getId());
        }
    }

    /**
     * Indica si la cuenta operada es la del propio actor.
     *
     * <p><strong>Se compara contra {@code authUserId}, no contra {@code id}.</strong> El
     * {@link AuthenticatedUser#id()} del principal es el claim {@code sub} del token, es decir el
     * {@code auth_user_id} de la cuenta —el sujeto del servicio de identidad—, y no la clave primaria
     * de la fila: son dos columnas distintas y siempre distintas. Comparar contra {@code usuario.id}
     * haría que esta regla no disparara nunca y dejaría a una administradora poder darse de baja a sí
     * misma. No «simplificar» esta comparación: el identificador del token no es el de la fila.</p>
     *
     * @param actor   usuario autenticado de la sesión
     * @param usuario cuenta operada
     * @return {@code true} si la identidad de autenticación del actor es la de la cuenta
     */
    private static boolean esLaPropiaCuenta(AuthenticatedUser actor, Usuario usuario) {
        return actor != null && Objects.equals(usuario.getAuthUserId(), actor.id());
    }

    /**
     * Exige que la baja no deje a la organización sin administración (R8).
     *
     * @param usuario cuenta que se intenta dar de baja
     * @throws UltimaAdministradoraException si es la última cuenta {@code ADMIN} activa
     */
    private void exigirAdministracionRestanteAlDarDeBaja(Usuario usuario) {
        if (esUltimaAdministradoraActiva(usuario)) {
            throw UltimaAdministradoraException.alDarDeBaja(usuario.getId());
        }
    }

    /**
     * Exige que el cambio de rol no deje a la organización sin administración (R8).
     *
     * @param usuario  cuenta a la que se le cambia el rol
     * @param nuevoRol rol que quedaría tras la operación
     * @throws UltimaAdministradoraException si la cuenta es la última {@code ADMIN} activa y el rol
     *                                       resultante no es {@code ADMIN}
     */
    private void exigirAdministracionRestanteAlCambiarElRol(Usuario usuario, RolUsuario nuevoRol) {
        if (nuevoRol != RolUsuario.ADMIN && esUltimaAdministradoraActiva(usuario)) {
            throw UltimaAdministradoraException.alCambiarElRol(usuario.getId());
        }
    }

    /**
     * Indica si la cuenta es la única administradora activa de su organización.
     *
     * <p>Una cuenta inactiva no administra nada, así que su baja —o el cambio de su rol— no reduce la
     * administración y no se consulta el conteo siquiera. Cuando la cuenta sí administra, el conteo
     * se hace por organización, nunca global: cada clínica tiene su propia administración y las
     * administradoras de otra organización no cubren a esta.</p>
     *
     * @param usuario cuenta en evaluación
     * @return {@code true} si está activa, tiene rol {@code ADMIN} y es la única en esa situación
     *         dentro de su organización
     */
    private boolean esUltimaAdministradoraActiva(Usuario usuario) {
        if (!usuario.isActivo() || usuario.getRol() != RolUsuario.ADMIN) {
            return false;
        }
        return usuarioRepository.contarActivosPorOrganizacionYRol(
                usuario.getOrganizacion().getId(), RolUsuario.ADMIN) == 1;
    }
}
