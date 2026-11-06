package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.dto.response.PacienteResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.PacienteMapper;
import com.intoglobe.clinicas.model.Paciente;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.PacienteRepository;

/**
 * Lógica de negocio de pacientes.
 *
 * <p>Orquesta los dos repositorios y el {@link PacienteMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y
 * escritura. Un recurso inexistente se traduce a
 * {@link ResourceNotFoundException}, que el handler convierte en 404.</p>
 *
 * <p>La organización que llega por el path se resuelve siempre contra
 * {@link OrganizacionRepository}. Sin esa comprobación, crear o listar
 * bajo una organización inexistente haría fallar la clave foránea —o devolvería un
 * {@code 200 []} que ocultaría el error del cliente—; en {@code crear} la resolución no
 * es una consulta extra, porque aporta la entidad que el mapper necesita.</p>
 *
 * <p><strong>Aislamiento por tenant (R-SEG-04):</strong> todas las operaciones reciben
 * el {@code tenantId} del token —nunca un dato del path sin validar— y un paciente de
 * otra organización responde {@code 404}, igual que un identificador inexistente: un
 * {@code 403} confirmaría que el recurso existe en otra clínica. Las operaciones por id
 * obtienen la fila con {@code findById} y comparan
 * {@code paciente.getOrganizacion().getId()} en memoria, el mismo mecanismo que usa
 * {@code HorarioMedicoService}. <strong>No se agregan consultas a
 * {@link PacienteRepository}</strong> para lograrlo: el acotamiento por organización
 * vive en este servicio y el repositorio conserva un único método propio, el listado.
 * En {@code crear} y {@code listarPorOrganizacion} el acotamiento se comprueba
 * comparando la organización del path con el tenant del token.</p>
 *
 * <p>La baja es lógica mediante {@link Paciente#desactivar()}. El borrado físico está
 * descartado porque {@code cita.paciente_id} y {@code adjunto.paciente_id} son
 * {@code NO ACTION} en V1 —la base lo bloquearía— y, si no lo hiciera, se perdería el
 * historial clínico.</p>
 */
@Service
public class PacienteService {

    private final PacienteRepository pacienteRepository;
    private final OrganizacionRepository organizacionRepository;

    public PacienteService(PacienteRepository pacienteRepository,
            OrganizacionRepository organizacionRepository) {
        this.pacienteRepository = pacienteRepository;
        this.organizacionRepository = organizacionRepository;
    }

    /**
     * Crea un paciente dentro de la organización indicada.
     *
     * <p>El orden de las comprobaciones es contractual: primero se exige que la
     * organización del path sea la del token —un id de otra clínica responde 404 sin
     * consultar nada— y después que la organización exista, para responder 404 en vez
     * de dejar que la clave foránea produzca un 500.</p>
     *
     * @param organizacionId organización dueña: el tenant del token, ya validado contra el
     *                       path por el controlador
     * @param tenantId       organización del token de la petición
     * @param request        datos de entrada validados
     * @return representación del paciente persistido
     * @throws ResourceNotFoundException si la organización del path no es la del token, o
     *                                   si no existe una organización con ese id
     */
    @Transactional
    public PacienteResponse crear(UUID organizacionId, UUID tenantId, PacienteRequest request) {
        exigirOrganizacionDelTenant(organizacionId, tenantId);
        var organizacion = organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", organizacionId));
        var guardado = pacienteRepository.save(PacienteMapper.aEntidad(organizacion, request));
        return PacienteMapper.aResponse(guardado);
    }

    /**
     * Busca un paciente por su identificador dentro del tenant indicado.
     *
     * @param id       identificador del paciente
     * @param tenantId organización del token de la petición
     * @return representación del paciente encontrado
     * @throws ResourceNotFoundException si no existe un paciente con ese id <em>en ese
     *                                   tenant</em>; un paciente de otra organización cae
     *                                   aquí a propósito, para no revelar que existe
     */
    @Transactional(readOnly = true)
    public PacienteResponse buscarPorId(UUID id, UUID tenantId) {
        return PacienteMapper.aResponse(buscarDelTenant(id, tenantId));
    }

    /**
     * Lista los pacientes activos de una organización, ordenados por nombre.
     *
     * <p>Antes de listar se comprueba que la organización del path sea la del token y que
     * exista. Un id de otra organización responde 404 sin llegar a consultar el listado, y
     * un id inexistente responde 404 en vez de una lista vacía que ocultaría el error del
     * cliente. El orden lo fija la consulta del repositorio y es parte del contrato.</p>
     *
     * @param organizacionId organización dueña de los pacientes
     * @param tenantId       organización del token de la petición
     * @return pacientes activos de esa organización en su representación de salida
     * @throws ResourceNotFoundException si la organización del path no es la del token, o
     *                                   si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<PacienteResponse> listarPorOrganizacion(UUID organizacionId, UUID tenantId) {
        exigirOrganizacionDelTenant(organizacionId, tenantId);
        if (organizacionRepository.findById(organizacionId).isEmpty()) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        return pacienteRepository.listarActivosPorOrganizacion(organizacionId).stream()
                .map(PacienteMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los seis campos editables de un paciente existente.
     *
     * <p>La organización nunca cambia: el request no la contiene y el mapper no toca la
     * asociación, porque mover a un paciente de organización dejaría su historial clínico
     * en otra clínica. {@code id}, {@code activo} y {@code createdAt} tampoco se tocan:
     * actualizar un paciente inactivo no lo reactiva y la fecha de alta no se reescribe.</p>
     *
     * @param id       identificador del paciente a actualizar
     * @param tenantId organización del token de la petición
     * @param request  datos de entrada validados
     * @return representación del paciente actualizado
     * @throws ResourceNotFoundException si no existe un paciente con ese id en ese tenant
     */
    @Transactional
    public PacienteResponse actualizar(UUID id, UUID tenantId, PacienteRequest request) {
        var paciente = buscarDelTenant(id, tenantId);
        PacienteMapper.actualizarEntidad(paciente, request);
        return PacienteMapper.aResponse(pacienteRepository.save(paciente));
    }

    /**
     * Aplica la baja lógica de un paciente. Es idempotente: desactivar un paciente ya
     * inactivo no produce error. Solo cambia {@code activo}.
     *
     * <p>El borrado físico está descartado: las claves foráneas de {@code cita} y
     * {@code adjunto} lo bloquean y, aunque no lo hicieran, se perdería el historial
     * clínico.</p>
     *
     * @param id       identificador del paciente a desactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un paciente con ese id en ese tenant
     */
    @Transactional
    public void desactivar(UUID id, UUID tenantId) {
        var paciente = buscarDelTenant(id, tenantId);
        paciente.desactivar();
        pacienteRepository.save(paciente);
    }

    /**
     * Reactiva un paciente dado de baja lógica. Es idempotente: activar uno que ya está
     * activo no produce error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p><strong>Por qué existe:</strong> un paciente que vuelve a la clínica después de
     * meses debe seguir siendo el mismo registro. Sin esta operación, la única forma de
     * recibirlo de nuevo sería crearlo otra vez, y eso partiría su historial clínico entre
     * dos identificadores: las citas y los adjuntos anteriores quedarían colgados del
     * viejo.</p>
     *
     * <p><strong>Deuda declarada:</strong> {@code medico} y {@code consultorio} solo
     * tienen {@code desactivar} y todavía no ofrecen la operación simétrica de
     * reactivación; este módulo no la agrega para ellos.</p>
     *
     * @param id       identificador del paciente a reactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un paciente con ese id en ese tenant
     */
    @Transactional
    public void activar(UUID id, UUID tenantId) {
        var paciente = buscarDelTenant(id, tenantId);
        paciente.activar();
        pacienteRepository.save(paciente);
    }

    /**
     * Exige que la organización indicada sea la del token.
     *
     * <p>Un id de otra organización y un UUID al azar producen el mismo
     * {@link ResourceNotFoundException}: el mensaje no confirma que la organización
     * exista. Se comprueba antes de consultar el repositorio, así que una organización
     * ajena no se consulta siquiera.</p>
     *
     * @param organizacionId organización recibida por el path
     * @param tenantId       organización del token de la petición
     * @throws ResourceNotFoundException si {@code organizacionId} no es el del token
     */
    private static void exigirOrganizacionDelTenant(UUID organizacionId, UUID tenantId) {
        if (!Objects.equals(organizacionId, tenantId)) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
    }

    /**
     * Recupera el paciente indicado solo si pertenece a la organización del token.
     *
     * <p>Un id inexistente y un id de otra organización producen el mismo
     * {@link ResourceNotFoundException}. Se resuelve con {@code findById} —no se agregan
     * consultas a {@link PacienteRepository}— y la comparación se hace en memoria, igual
     * que {@code HorarioMedicoService} acota sus recursos por tenant: una sola lectura por
     * petición y el repositorio conserva únicamente su listado propio.</p>
     *
     * @param id       identificador del paciente solicitado
     * @param tenantId organización del token de la petición
     * @return el paciente, si existe y pertenece a la organización del token
     * @throws ResourceNotFoundException si no existe o es de otra organización
     */
    private Paciente buscarDelTenant(UUID id, UUID tenantId) {
        return pacienteRepository.findById(id)
                .filter(paciente -> Objects.equals(paciente.getOrganizacion().getId(), tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Paciente", id));
    }
}
