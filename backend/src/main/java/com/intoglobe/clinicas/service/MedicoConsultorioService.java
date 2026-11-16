package com.intoglobe.clinicas.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ConsultorioNoRetirableException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.ConsultorioMapper;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.HorarioMedicoRepository;
import com.intoglobe.clinicas.repository.MedicoConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.security.AuthenticatedUser;

/**
 * Lógica de negocio de la asignación de consultorios a un médico.
 *
 * <p>Es la operación del punto de la lista que faltaba: el administrador decide en qué
 * consultorios puede atender un médico y, a partir de ahí, cada bloque de horario elige uno de
 * los asignados. Sin esta operación la tabla {@code medico_consultorio} de la migración V4 no
 * tenía escrituras y la regla de la V6 —un bloque solo puede apuntar a un consultorio asignado—
 * no se podía cumplir desde la API.</p>
 *
 * <p>Orquesta los cuatro repositorios y el {@link ConsultorioMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y escritura. Un
 * recurso inexistente se traduce a {@link ResourceNotFoundException}, que el handler convierte en
 * 404, y las dos reglas de conflicto a {@link ConsultorioNoRetirableException}, que el handler
 * convierte en 409.</p>
 *
 * <h2>Orden de las comprobaciones, que es contractual</h2>
 *
 * <p>Una misma petición puede incumplir varias reglas y el orden decide qué error ve el cliente:
 * </p>
 * <ol>
 *   <li><strong>Médico del tenant (404).</strong> El médico del path tiene que existir en la
 *       organización del token. Un médico de otra clínica responde el mismo 404 que un
 *       identificador inexistente: un 403 confirmaría que existe.</li>
 *   <li><strong>Consultorio del tenant (404).</strong> Igual, y con la misma razón: el
 *       {@code consultorioId} es una entrada del cliente, así que se resuelve contra
 *       {@link ConsultorioRepository#buscarPorIdYOrganizacion} y nunca con un {@code findById}
 *       sin acotar.</li>
 *   <li><strong>Propiedad (403), solo en la lectura.</strong> Un token {@code MEDICO} solo puede
 *       consultar los consultorios del médico de su claim {@code medicoId}; cualquier otro médico
 *       —aunque sea de su organización— recibe {@link AccessDeniedException}. Es la misma regla de
 *       propiedad que ya protege la agenda de horarios, aplicada a esta ruta. Va <em>después</em>
 *       del acotamiento por organización: un médico de otra clínica responde 404, no 403.</li>
 *   <li><strong>Consultorio principal (409).</strong> El consultorio de {@code medico.consultorio_id}
 *       no se retira: sigue siendo la referencia por defecto del médico y la regla que impide
 *       cambiarlo sigue vigente. Se comprueba antes que la existencia de la asignación porque lo
 *       prohibido es la operación, no el estado de la fila.</li>
 *   <li><strong>Bloques de horario (409).</strong> Un consultorio que todavía tiene bloques del
 *       médico no se puede retirar: la clave {@code horario_medico_consultorio_asignado_fkey} de la
 *       V6 lo impediría con un error de integridad, así que se detecta <em>antes</em> y se responde
 *       un conflicto que además dice qué bloques lo impiden.</li>
 *   <li><strong>Persistir o borrar.</strong></li>
 * </ol>
 *
 * <h2>Idempotencia</h2>
 *
 * <p>Las dos escrituras son idempotentes y responden {@code 204} sin cuerpo: asignar un
 * consultorio que ya estaba asignado no duplica la fila, y retirar uno que ya no estaba tampoco
 * falla. La comprobación previa vive dentro de la transacción y no sustituye a la clave primaria
 * de la tabla: si dos altas simultáneas pasan la comprobación a la vez, la segunda choca contra
 * {@code medico_consultorio_pkey} y ese error <strong>no se silencia</strong>, porque un
 * {@code 204} mentiroso sería peor que un fallo visible.</p>
 *
 * <h2>El rol no se decide aquí</h2>
 *
 * <p>Qué rol alcanza cada ruta lo aplica la cadena de filtros, en {@code SecurityConfig}: la
 * lectura admite {@code ADMIN} y {@code MEDICO}, y las dos escrituras solo {@code ADMIN}. Este
 * servicio recibe el {@link AuthenticatedUser} de la petición —el principal que dejó el filtro
 * JWT, nunca un dato del path— y aplica las comprobaciones que el rol solo no puede expresar,
 * porque dependen del estado de la base.</p>
 *
 * <h2>No hay 403 por path en estas rutas</h2>
 *
 * <p>La colección cuelga del médico y no de la organización
 * ({@code /api/v1/medicos/{medicoId}/consultorios}), así que no hay ningún
 * {@code organizacionId} en la ruta que comparar con el claim: la organización sale siempre del
 * token y el cruce de tenant se resuelve con 404. Es la misma forma que el módulo de horarios, y
 * por eso este servicio no usa {@code TenantAccessGuard#exigirTenantDeLaRuta}.</p>
 */
@Service
public class MedicoConsultorioService {

    /** Rol que solo puede consultar los consultorios de su propio médico. */
    private static final String ROL_MEDICO = "MEDICO";

    /**
     * Nombres de los días con la convención de {@code Date.getDay()} que usa la columna
     * {@code horario_medico.dia_semana}: {@code 0=Domingo .. 6=Sábado}. Se usan solo para que el
     * mensaje del 409 diga cuáles son los bloques que impiden retirar el consultorio.
     */
    private static final String[] NOMBRES_DE_LOS_DIAS = {
            "domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"
    };

    private final MedicoRepository medicoRepository;
    private final ConsultorioRepository consultorioRepository;
    private final MedicoConsultorioRepository medicoConsultorioRepository;
    private final HorarioMedicoRepository horarioMedicoRepository;

    public MedicoConsultorioService(MedicoRepository medicoRepository,
            ConsultorioRepository consultorioRepository,
            MedicoConsultorioRepository medicoConsultorioRepository,
            HorarioMedicoRepository horarioMedicoRepository) {
        this.medicoRepository = medicoRepository;
        this.consultorioRepository = consultorioRepository;
        this.medicoConsultorioRepository = medicoConsultorioRepository;
        this.horarioMedicoRepository = horarioMedicoRepository;
    }

    /**
     * Lista los consultorios asignados a un médico, ordenados por nombre del consultorio.
     *
     * <p>Antes de listar comprueba que el médico exista en la organización del token: si el id del
     * padre está mal escrito o es de otra clínica, el cliente debe recibir 404 en vez de una lista
     * vacía que ocultaría el error. El orden lo fija la consulta y forma parte del contrato: la
     * pantalla de asignación lo pinta tal como llega.</p>
     *
     * @param actor    usuario autenticado de la petición: tenant y rol del token
     * @param medicoId médico cuyos consultorios se listan
     * @return consultorios asignados a ese médico en su representación de salida
     * @throws ResourceNotFoundException si no existe un médico con ese id en la organización del
     *                                   token
     * @throws AccessDeniedException     si el actor es {@code MEDICO} y no es el médico del path
     */
    @Transactional(readOnly = true)
    public List<ConsultorioResponse> listar(AuthenticatedUser actor, UUID medicoId) {
        medicoDelTenant(actor, medicoId);
        exigirPropiedadDeLosConsultorios(actor, medicoId);
        return medicoConsultorioRepository
                .listarConsultoriosPorMedico(medicoId, actor.tenantId()).stream()
                .map(ConsultorioMapper::aResponse)
                .toList();
    }

    /**
     * Asigna un consultorio a un médico. Es idempotente: si el par ya estaba asignado responde
     * igual que la primera vez y no escribe nada.
     *
     * <p>El médico y el consultorio se resuelven antes de escribir, de modo que un identificador
     * de otra organización produzca 404 —y no un error de integridad de la clave foránea
     * compuesta—. La organización de la fila es la del médico, nunca un dato del cliente: es la
     * mitad de las dos claves foráneas que garantizan que el vínculo no cruce clínicas.</p>
     *
     * @param actor         usuario autenticado de la petición: tenant y rol del token
     * @param medicoId      médico al que se le asigna el consultorio
     * @param consultorioId consultorio que se asigna
     * @throws ResourceNotFoundException si el médico o el consultorio no existen en la organización
     *                                   del token
     */
    @Transactional
    public void asignar(AuthenticatedUser actor, UUID medicoId, UUID consultorioId) {
        var medico = medicoDelTenant(actor, medicoId);
        var consultorio = consultorioDelTenant(actor, consultorioId);
        if (medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, actor.tenantId())
                .isPresent()) {
            // Ya estaba: la operación es idempotente y no vuelve a escribir la fila.
            return;
        }
        medicoConsultorioRepository.save(new MedicoConsultorio(medico, consultorio, medico.getOrganizacion()));
    }

    /**
     * Retira un consultorio de un médico. Es idempotente: si el par no estaba asignado responde
     * igual que si se hubiera borrado.
     *
     * <p>El borrado es físico, como la tabla: {@code medico_consultorio} no tiene columna
     * {@code activo}. Antes de borrar se aplican las dos reglas de conflicto, en el orden descrito
     * en el Javadoc de la clase, para que el cliente reciba un 409 con motivo en lugar de un error
     * de integridad de la V6.</p>
     *
     * @param actor         usuario autenticado de la petición: tenant y rol del token
     * @param medicoId      médico al que se le retira el consultorio
     * @param consultorioId consultorio que se retira
     * @throws ResourceNotFoundException        si el médico o el consultorio no existen en la
     *                                          organización del token
     * @throws ConsultorioNoRetirableException si el consultorio es el principal del médico o si
     *                                          todavía tiene bloques de horario suyos
     */
    @Transactional
    public void quitar(AuthenticatedUser actor, UUID medicoId, UUID consultorioId) {
        var medico = medicoDelTenant(actor, medicoId);
        consultorioDelTenant(actor, consultorioId);
        exigirQueNoSeaElPrincipal(medico, consultorioId);
        var asignacion = medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId,
                actor.tenantId());
        if (asignacion.isEmpty()) {
            // No estaba asignado: la operación es idempotente y no hay nada que borrar.
            return;
        }
        exigirQueNoTengaBloques(medicoId, consultorioId);
        medicoConsultorioRepository.delete(asignacion.get());
    }

    /**
     * Recupera el médico indicado solo si pertenece a la organización del token.
     *
     * <p>Un id inexistente y un id de otra organización producen el mismo
     * {@link ResourceNotFoundException}: el mensaje no confirma que el recurso exista. Se resuelve
     * con {@code findById} y la comparación se hace en memoria, igual que en
     * {@code HorarioMedicoService}, porque el número de accesos es uno por petición y el service
     * necesita la entidad —con su consultorio principal y su organización— y no solo un booleano.</p>
     *
     * @param actor    usuario autenticado de la petición
     * @param medicoId identificador del médico solicitado
     * @return el médico, si existe y pertenece a la organización del token
     * @throws ResourceNotFoundException si no existe o es de otra organización
     */
    private Medico medicoDelTenant(AuthenticatedUser actor, UUID medicoId) {
        return medicoRepository.findById(medicoId)
                .filter(medico -> Objects.equals(medico.getOrganizacion().getId(), actor.tenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Medico", medicoId));
    }

    /**
     * Recupera el consultorio indicado solo si pertenece a la organización del token.
     *
     * <p>Un consultorio de otra organización y un UUID al azar producen el mismo 404, a propósito:
     * nunca un 403, que confirmaría que existe en otra clínica.</p>
     *
     * @param actor         usuario autenticado de la petición
     * @param consultorioId identificador del consultorio solicitado
     * @return el consultorio, si existe en la organización del token
     * @throws ResourceNotFoundException si no existe allí
     */
    private Consultorio consultorioDelTenant(AuthenticatedUser actor, UUID consultorioId) {
        return consultorioRepository.buscarPorIdYOrganizacion(consultorioId, actor.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", consultorioId));
    }

    /**
     * Exige que el actor, si es un médico, consulte los consultorios de su propio médico.
     *
     * <p>Un token {@code MEDICO} sin claim {@code medicoId} se rechaza igual que uno ajeno: ante la
     * duda se falla cerrado, nunca se concede acceso a los consultorios de otro.</p>
     *
     * @param actor    usuario autenticado de la petición
     * @param medicoId médico cuyos consultorios se quieren leer
     * @throws AccessDeniedException si el actor es {@code MEDICO} y no es el médico del path
     */
    private static void exigirPropiedadDeLosConsultorios(AuthenticatedUser actor, UUID medicoId) {
        if (ROL_MEDICO.equals(actor.role())
                && (actor.medicoId() == null || !actor.medicoId().equals(medicoId))) {
            throw new AccessDeniedException(
                    "Un médico solo puede consultar los consultorios de su propia agenda");
        }
    }

    /**
     * Rechaza la operación si el consultorio es el principal del médico.
     *
     * <p>El consultorio principal ({@code medico.consultorio_id}) sigue siendo la referencia por
     * defecto y la regla del módulo de médicos que impide cambiarlo sigue vigente; retirarlo de la
     * lista dejaría al médico sin el consultorio por defecto. La comprobación no consulta la
     * asignación: lo prohibido es la operación, no el estado de la fila.</p>
     *
     * @param medico        médico al que se le quiere retirar el consultorio
     * @param consultorioId consultorio que se quiere retirar
     * @throws ConsultorioNoRetirableException si el consultorio es el principal del médico
     */
    private static void exigirQueNoSeaElPrincipal(Medico medico, UUID consultorioId) {
        if (medico.getConsultorio().getId().equals(consultorioId)) {
            throw ConsultorioNoRetirableException.porSerElPrincipal(medico.getId(), consultorioId);
        }
    }

    /**
     * Rechaza la operación si el consultorio todavía tiene bloques de horario del médico.
     *
     * <p>La clave {@code horario_medico_consultorio_asignado_fkey} de la migración V6 impide el
     * borrado, pero su manifestación sería un error de integridad que llega al cliente como 500.
     * Por eso se consultan los bloques antes: la respuesta es un 409 que dice cuáles son, para que
     * el administrador sepa qué mover antes de reintentar.</p>
     *
     * @param medicoId      médico dueño de los bloques
     * @param consultorioId consultorio que se quiere retirar
     * @throws ConsultorioNoRetirableException si hay al menos un bloque en ese consultorio
     */
    private void exigirQueNoTengaBloques(UUID medicoId, UUID consultorioId) {
        var bloques = horarioMedicoRepository.listarBloquesEnConsultorio(medicoId, consultorioId);
        if (!bloques.isEmpty()) {
            throw ConsultorioNoRetirableException.porTenerBloquesDeHorario(medicoId, consultorioId,
                    bloques.stream().map(MedicoConsultorioService::describirBloque).toList());
        }
    }

    /**
     * Describe un bloque para el mensaje del 409, con el día y el rango horario.
     *
     * @param bloque bloque de horario a describir
     * @return descripción legible, por ejemplo {@code "día 1 (lunes) de 09:00 a 13:00"}
     */
    private static String describirBloque(HorarioMedico bloque) {
        var dia = bloque.getDiaSemana();
        var nombreDelDia = dia >= 0 && dia < NOMBRES_DE_LOS_DIAS.length
                ? NOMBRES_DE_LOS_DIAS[dia]
                : "día fuera de la convención";
        return "día " + dia + " (" + nombreDelDia + ") de " + hora(bloque.getHoraInicio())
                + " a " + hora(bloque.getHoraFin());
    }

    /**
     * Formatea una hora local como {@code HH:mm} para el mensaje del conflicto.
     *
     * @param hora hora del bloque
     * @return la hora con dos dígitos en horas y minutos
     */
    private static String hora(LocalTime hora) {
        return "%02d:%02d".formatted(hora.getHour(), hora.getMinute());
    }
}
