package com.intoglobe.clinicas.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.HorarioMedicoRequest;
import com.intoglobe.clinicas.dto.response.HorarioMedicoResponse;
import com.intoglobe.clinicas.exception.DuracionConsultaInvalidaException;
import com.intoglobe.clinicas.exception.RangoHorarioInvalidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.SolapamientoDeHorarioException;
import com.intoglobe.clinicas.mapper.HorarioMedicoMapper;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.repository.HorarioMedicoRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.security.AuthenticatedUser;

/**
 * Lógica de negocio de los bloques de disponibilidad de un médico.
 *
 * <p>Orquesta los dos repositorios y el {@link HorarioMedicoMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y
 * escritura. Un recurso inexistente se traduce a {@link ResourceNotFoundException}, que el
 * handler convierte en 404.</p>
 *
 * <p>El schema de V1 <strong>no</strong> declara ninguna de las tres reglas de negocio de
 * este servicio: no hay CHECK que compare {@code hora_inicio} con {@code hora_fin}, el
 * CHECK de duración existe pero se manifiesta como error de integridad, y no hay
 * restricción de exclusión que impida solapamientos. Sin estas comprobaciones el cliente
 * recibiría un 500 o, peor, una agenda imposible. Por eso todas viven aquí, en este
 * orden, que es <strong>contractual</strong> porque decide qué error ve el cliente cuando
 * dos reglas fallan a la vez:</p>
 * <ol>
 *   <li>acotamiento por organización —un médico o un bloque de otro tenant responde 404
 *       antes que cualquier regla del cuerpo, para no revelar que existe—,</li>
 *   <li>propiedad de la agenda —un {@code MEDICO} que opera sobre la agenda de otro médico
 *       recibe 403—,</li>
 *   <li>rango horario: {@code horaInicio} estrictamente anterior a {@code horaFin},</li>
 *   <li>duración de consulta dentro de {@code {20, 30, 45, 60}},</li>
 *   <li>solapamiento con otro bloque del mismo médico y día,</li>
 *   <li>persistir.</li>
 * </ol>
 *
 * <p>El solapamiento se comprueba con
 * {@link HorarioMedicoRepository#contarSolapamientos} sobre intervalos semiabiertos, así
 * que dos bloques contiguos (09:00-10:00 y 10:00-11:00) siguen siendo válidos.</p>
 *
 * <h2>Tenant y propiedad: dos comprobaciones distintas, en este orden</h2>
 *
 * <p><strong>El rol no se decide aquí.</strong> Qué rol puede llegar a esta capa lo aplica
 * la cadena de filtros, en {@code SecurityConfig}: las cinco rutas admiten {@code ADMIN} y
 * {@code MEDICO}, y {@code PACIENTE} queda cerrado por el fallback deny-by-default. Este
 * servicio recibe el {@link AuthenticatedUser} de la petición —el principal que dejó el
 * filtro JWT, nunca un dato del path— y aplica las dos comprobaciones que el rol solo no
 * puede expresar:</p>
 * <ol>
 *   <li><strong>Organización (404).</strong> El médico involucrado debe pertenecer al
 *       {@code tenantId} del token y, en las operaciones por id de bloque, el médico dueño
 *       del horario también. Un recurso de otra organización responde el mismo 404 que un
 *       identificador inexistente: es el cierre del IDOR que este módulo tenía abierto
 *       —antes se buscaba por id sin mirar la organización— y no confirma que el recurso
 *       exista (R-SEG-04, R-SEG-06).</li>
 *   <li><strong>Propiedad de la agenda (403).</strong> Un token {@code MEDICO} solo puede
 *       operar sobre la agenda del médico cuyo id lleva su claim {@code medicoId}; cualquier
 *       otro médico —aunque sea de su propia organización— recibe
 *       {@link AccessDeniedException}, que el handler traduce al 403 del contrato. Un token
 *       {@code MEDICO} sin claim {@code medicoId} se trata igual que uno ajeno: falla
 *       cerrado. {@code ADMIN} administra la agenda de cualquier médico de su organización y
 *       no tiene esta restricción.</li>
 * </ol>
 *
 * <p>El orden importa y es contractual: un médico de otro tenant responde <strong>404</strong>
 * y no 403, porque el 403 confirmaría que su agenda existe.</p>
 *
 * <p>El borrado es <strong>físico</strong>: {@code horario_medico} no tiene columna
 * {@code activo} y ninguna otra tabla la referencia, así que no hay baja lógica que
 * aplicar y un horario eliminado no deja rastro. V1 tampoco declara índice sobre
 * {@code medico_id}, por lo que el listado por médico recorre una tabla pequeña; el
 * índice es un pendiente declarado del V2 y este servicio no puede resolverlo.</p>
 */
@Service
public class HorarioMedicoService {

    /**
     * Valor de {@code idExcluido} que se envía al crear. El {@code id} de la tabla nunca
     * es nulo —lo rellena {@code gen_random_uuid()}—, así que este UUID, que no puede
     * colisionar con ningún identificador generado, no excluye ninguna fila: un valor nulo
     * excluiría todas y la consulta devolvería siempre {@code 0}, ocultando cualquier
     * solapamiento.
     */
    private static final UUID NINGUN_ID = new UUID(0L, 0L);

    /**
     * Duraciones de cita admitidas en minutos. Espeja el CHECK
     * {@code duracion_consulta IN (20, 30, 45, 60)} de V1; aquí se convierte en un 400 en
     * lugar de un error de integridad.
     */
    private static final Set<Short> DURACIONES_PERMITIDAS =
            Set.of((short) 20, (short) 30, (short) 45, (short) 60);

    /** Rol que solo puede operar sobre su propia agenda. */
    private static final String ROL_MEDICO = "MEDICO";

    private final HorarioMedicoRepository horarioMedicoRepository;
    private final MedicoRepository medicoRepository;

    public HorarioMedicoService(HorarioMedicoRepository horarioMedicoRepository,
            MedicoRepository medicoRepository) {
        this.horarioMedicoRepository = horarioMedicoRepository;
        this.medicoRepository = medicoRepository;
    }

    /**
     * Crea un bloque de disponibilidad para el médico indicado.
     *
     * <p>El médico se resuelve antes de mirar el cuerpo: si no existe, si es de otra
     * organización o si un {@code MEDICO} intenta escribir en la agenda de otro, se rechaza
     * sin persistir nada, en vez de dejar que la clave foránea produzca un 500. Después se
     * validan rango, duración y solapamiento, en ese orden.</p>
     *
     * @param actor    usuario autenticado de la petición: tenant y rol del token
     * @param medicoId médico dueño de la agenda, tomado del path
     * @param request  datos de entrada validados
     * @return representación del bloque persistido
     * @throws ResourceNotFoundException        si no existe un médico con ese id en la
     *                                          organización del token
     * @throws AccessDeniedException            si el actor es {@code MEDICO} y la agenda no
     *                                          es la suya
     * @throws RangoHorarioInvalidoException    si {@code horaInicio} no es estrictamente
     *                                          anterior a {@code horaFin}
     * @throws DuracionConsultaInvalidaException si la duración no es una de
     *                                          {@code {20, 30, 45, 60}}
     * @throws SolapamientoDeHorarioException   si el bloque se solapa con otro del mismo
     *                                          médico y día
     */
    @Transactional
    public HorarioMedicoResponse crear(AuthenticatedUser actor, UUID medicoId,
            HorarioMedicoRequest request) {
        var medico = medicoDelTenant(actor, medicoId);
        exigirPropiedadDeLaAgenda(actor, medicoId);
        validarRango(request.horaInicio(), request.horaFin());
        validarDuracion(request.duracionConsulta());
        comprobarSolapamiento(actor, medicoId, request.diaSemana(), request.horaInicio(),
                request.horaFin(), NINGUN_ID);
        var guardado = horarioMedicoRepository.save(HorarioMedicoMapper.aEntidad(medico, request));
        return HorarioMedicoMapper.aResponse(guardado);
    }

    /**
     * Busca un bloque de disponibilidad por su identificador.
     *
     * <p>Un bloque de otra organización responde 404 con el mismo mensaje que un id
     * inexistente; un bloque de otro médico de la misma organización responde 403 cuando el
     * actor es {@code MEDICO}.</p>
     *
     * @param actor usuario autenticado de la petición: tenant y rol del token
     * @param id    identificador del bloque
     * @return representación del bloque encontrado
     * @throws ResourceNotFoundException si no existe un bloque con ese id en la organización
     *                                   del token
     * @throws AccessDeniedException     si el actor es {@code MEDICO} y el bloque no es de
     *                                   su agenda
     */
    @Transactional(readOnly = true)
    public HorarioMedicoResponse buscarPorId(AuthenticatedUser actor, UUID id) {
        var horario = buscarEnElTenant(actor, id);
        exigirPropiedadDeLaAgenda(actor, horario.getMedico().getId());
        return HorarioMedicoMapper.aResponse(horario);
    }

    /**
     * Lista los bloques de disponibilidad de un médico, ordenados por día y hora de
     * inicio.
     *
     * <p>Antes de listar comprueba que el médico exista en la organización del token: si el
     * id del padre está mal escrito o es de otra clínica, el cliente debe recibir 404 en vez
     * de una lista vacía que ocultaría el error. El orden lo fija la consulta y es parte del
     * contrato: alimenta la rejilla semanal.</p>
     *
     * @param actor    usuario autenticado de la petición: tenant y rol del token
     * @param medicoId médico dueño de la agenda
     * @return bloques de ese médico en su representación de salida
     * @throws ResourceNotFoundException si no existe un médico con ese id en la organización
     *                                   del token
     * @throws AccessDeniedException     si el actor es {@code MEDICO} y la agenda no es la
     *                                   suya
     */
    @Transactional(readOnly = true)
    public List<HorarioMedicoResponse> listarPorMedico(AuthenticatedUser actor, UUID medicoId) {
        medicoDelTenant(actor, medicoId);
        exigirPropiedadDeLaAgenda(actor, medicoId);
        return horarioMedicoRepository.listarPorMedico(medicoId, actor.tenantId()).stream()
                .map(HorarioMedicoMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los cuatro campos editables de un bloque existente.
     *
     * <p>El médico nunca cambia: el request no lo contiene y el mapper no toca la
     * asociación. Las validaciones repiten el orden de {@code crear} —rango, duración,
     * solapamiento— y esta vez el solapamiento se consulta excluyendo el identificador del
     * propio bloque, para que no cuente como solapado consigo mismo.</p>
     *
     * @param actor   usuario autenticado de la petición: tenant y rol del token
     * @param id      identificador del bloque a actualizar
     * @param request datos de entrada validados
     * @return representación del bloque actualizado
     * @throws ResourceNotFoundException        si no existe un bloque con ese id en la
     *                                          organización del token
     * @throws AccessDeniedException            si el actor es {@code MEDICO} y el bloque no
     *                                          es de su agenda
     * @throws RangoHorarioInvalidoException    si el rango enviado es inválido
     * @throws DuracionConsultaInvalidaException si la duración no es una de
     *                                          {@code {20, 30, 45, 60}}
     * @throws SolapamientoDeHorarioException   si el bloque se solapa con otro del mismo
     *                                          médico y día
     */
    @Transactional
    public HorarioMedicoResponse actualizar(AuthenticatedUser actor, UUID id,
            HorarioMedicoRequest request) {
        var horario = buscarEnElTenant(actor, id);
        var medicoId = horario.getMedico().getId();
        exigirPropiedadDeLaAgenda(actor, medicoId);
        validarRango(request.horaInicio(), request.horaFin());
        validarDuracion(request.duracionConsulta());
        comprobarSolapamiento(actor, medicoId, request.diaSemana(), request.horaInicio(),
                request.horaFin(), id);
        HorarioMedicoMapper.actualizarEntidad(horario, request);
        return HorarioMedicoMapper.aResponse(horarioMedicoRepository.save(horario));
    }

    /**
     * Elimina un bloque de disponibilidad.
     *
     * <p>Es un borrado físico, no una baja lógica: la tabla no tiene columna {@code activo}
     * y ninguna otra tabla la referencia, así que no queda estado que conservar.</p>
     *
     * @param actor usuario autenticado de la petición: tenant y rol del token
     * @param id    identificador del bloque a eliminar
     * @throws ResourceNotFoundException si no existe un bloque con ese id en la organización
     *                                   del token
     * @throws AccessDeniedException     si el actor es {@code MEDICO} y el bloque no es de
     *                                   su agenda
     */
    @Transactional
    public void eliminar(AuthenticatedUser actor, UUID id) {
        var horario = buscarEnElTenant(actor, id);
        exigirPropiedadDeLaAgenda(actor, horario.getMedico().getId());
        horarioMedicoRepository.delete(horario);
    }

    /**
     * Recupera el médico indicado solo si pertenece a la organización del token.
     *
     * <p>Un id inexistente y un id de otra organización producen el mismo
     * {@link ResourceNotFoundException}: el mensaje no confirma que el recurso exista. Se
     * resuelve con {@code findById} —no se agregan consultas nuevas a
     * {@code MedicoRepository}— y la comparación se hace en memoria porque el número de
     * accesos es uno por petición.</p>
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
     * Recupera el bloque indicado solo si su médico pertenece a la organización del token.
     *
     * @param actor usuario autenticado de la petición
     * @param id    identificador del bloque solicitado
     * @return el bloque, si existe y su médico es de la organización del token
     * @throws ResourceNotFoundException si no existe o su médico es de otra organización
     */
    private HorarioMedico buscarEnElTenant(AuthenticatedUser actor, UUID id) {
        return horarioMedicoRepository.findById(id)
                .filter(horario -> Objects.equals(
                        horario.getMedico().getOrganizacion().getId(), actor.tenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Horario", id));
    }

    /**
     * Exige que el actor, si es un médico, opere sobre su propia agenda.
     *
     * <p>Un token {@code MEDICO} sin claim {@code medicoId} se rechaza igual que uno ajeno:
     * ante la duda se falla cerrado, nunca se concede acceso a la agenda de otro.</p>
     *
     * @param actor    usuario autenticado de la petición
     * @param medicoId médico dueño de la agenda que la operación tocaría
     * @throws AccessDeniedException si el actor es {@code MEDICO} y la agenda no es la suya
     */
    private static void exigirPropiedadDeLaAgenda(AuthenticatedUser actor, UUID medicoId) {
        if (ROL_MEDICO.equals(actor.role())
                && (actor.medicoId() == null || !actor.medicoId().equals(medicoId))) {
            throw new AccessDeniedException(
                    "Un médico solo puede operar sobre su propia agenda");
        }
    }

    /**
     * Exige que {@code horaInicio} sea estrictamente anterior a {@code horaFin}.
     *
     * @param horaInicio hora de inicio del bloque
     * @param horaFin    hora de fin del bloque
     * @throws RangoHorarioInvalidoException si {@code horaInicio} no es estrictamente
     *                                       anterior a {@code horaFin}
     */
    private static void validarRango(LocalTime horaInicio, LocalTime horaFin) {
        if (!horaInicio.isBefore(horaFin)) {
            throw new RangoHorarioInvalidoException(horaInicio, horaFin);
        }
    }

    /**
     * Exige que la duración pertenezca al conjunto cerrado admitido.
     *
     * @param duracionConsulta duración en minutos a validar
     * @throws DuracionConsultaInvalidaException si no es una de {@code {20, 30, 45, 60}}
     */
    private static void validarDuracion(short duracionConsulta) {
        if (!DURACIONES_PERMITIDAS.contains(duracionConsulta)) {
            throw new DuracionConsultaInvalidaException(duracionConsulta);
        }
    }

    /**
     * Rechaza el bloque si ya existe otro del mismo médico y día que se solape con él.
     *
     * <p>La consulta exige además la organización del token, así que el conteo nunca mira
     * la agenda de otra clínica.</p>
     *
     * @param actor      usuario autenticado de la petición, del que sale la organización
     * @param medicoId   médico dueño de la agenda
     * @param diaSemana  día de la semana del bloque
     * @param horaInicio hora de inicio del bloque
     * @param horaFin    hora de fin del bloque
     * @param idExcluido identificador del bloque que se está editando, o {@link #NINGUN_ID}
     *                   al crear
     * @throws SolapamientoDeHorarioException si hay al menos un bloque que se solapa
     */
    private void comprobarSolapamiento(AuthenticatedUser actor, UUID medicoId, short diaSemana,
            LocalTime horaInicio, LocalTime horaFin, UUID idExcluido) {
        if (horarioMedicoRepository.contarSolapamientos(medicoId, actor.tenantId(), diaSemana,
                horaInicio, horaFin, idExcluido) > 0) {
            throw new SolapamientoDeHorarioException(diaSemana, horaInicio, horaFin);
        }
    }
}
