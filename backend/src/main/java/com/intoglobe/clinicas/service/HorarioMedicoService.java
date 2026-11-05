package com.intoglobe.clinicas.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.intoglobe.clinicas.repository.HorarioMedicoRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;

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
 *   <li>resolver el médico dueño —un id inexistente responde 404 antes que cualquier regla
 *       del cuerpo—,</li>
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
     * <p>El médico se resuelve antes de mirar el cuerpo: si no existe se responde 404 y no
     * se persiste nada, en vez de dejar que la clave foránea produzca un 500. Después se
     * validan rango, duración y solapamiento, en ese orden.</p>
     *
     * @param medicoId médico dueño de la agenda, tomado del path
     * @param request  datos de entrada validados
     * @return representación del bloque persistido
     * @throws ResourceNotFoundException        si no existe un médico con ese id
     * @throws RangoHorarioInvalidoException    si {@code horaInicio} no es estrictamente
     *                                          anterior a {@code horaFin}
     * @throws DuracionConsultaInvalidaException si la duración no es una de
     *                                          {@code {20, 30, 45, 60}}
     * @throws SolapamientoDeHorarioException   si el bloque se solapa con otro del mismo
     *                                          médico y día
     */
    @Transactional
    public HorarioMedicoResponse crear(UUID medicoId, HorarioMedicoRequest request) {
        var medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new ResourceNotFoundException("Medico", medicoId));
        validarRango(request.horaInicio(), request.horaFin());
        validarDuracion(request.duracionConsulta());
        comprobarSolapamiento(medicoId, request.diaSemana(), request.horaInicio(), request.horaFin(),
                NINGUN_ID);
        var guardado = horarioMedicoRepository.save(HorarioMedicoMapper.aEntidad(medico, request));
        return HorarioMedicoMapper.aResponse(guardado);
    }

    /**
     * Busca un bloque de disponibilidad por su identificador.
     *
     * @param id identificador del bloque
     * @return representación del bloque encontrado
     * @throws ResourceNotFoundException si no existe un bloque con ese id
     */
    @Transactional(readOnly = true)
    public HorarioMedicoResponse buscarPorId(UUID id) {
        return HorarioMedicoMapper.aResponse(buscar(id));
    }

    /**
     * Lista los bloques de disponibilidad de un médico, ordenados por día y hora de
     * inicio.
     *
     * <p>Antes de listar comprueba que el médico exista: si el id del padre está mal
     * escrito, el cliente debe recibir 404 en vez de una lista vacía que ocultaría el
     * error. El orden lo fija la consulta y es parte del contrato: alimenta la rejilla
     * semanal.</p>
     *
     * @param medicoId médico dueño de la agenda
     * @return bloques de ese médico en su representación de salida
     * @throws ResourceNotFoundException si no existe un médico con ese id
     */
    @Transactional(readOnly = true)
    public List<HorarioMedicoResponse> listarPorMedico(UUID medicoId) {
        if (!medicoRepository.existsById(medicoId)) {
            throw new ResourceNotFoundException("Medico", medicoId);
        }
        return horarioMedicoRepository.listarPorMedico(medicoId).stream()
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
     * @param id      identificador del bloque a actualizar
     * @param request datos de entrada validados
     * @return representación del bloque actualizado
     * @throws ResourceNotFoundException        si no existe un bloque con ese id
     * @throws RangoHorarioInvalidoException    si el rango enviado es inválido
     * @throws DuracionConsultaInvalidaException si la duración no es una de
     *                                          {@code {20, 30, 45, 60}}
     * @throws SolapamientoDeHorarioException   si el bloque se solapa con otro del mismo
     *                                          médico y día
     */
    @Transactional
    public HorarioMedicoResponse actualizar(UUID id, HorarioMedicoRequest request) {
        var horario = buscar(id);
        validarRango(request.horaInicio(), request.horaFin());
        validarDuracion(request.duracionConsulta());
        comprobarSolapamiento(horario.getMedico().getId(), request.diaSemana(), request.horaInicio(),
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
     * @param id identificador del bloque a eliminar
     * @throws ResourceNotFoundException si no existe un bloque con ese id
     */
    @Transactional
    public void eliminar(UUID id) {
        horarioMedicoRepository.delete(buscar(id));
    }

    /**
     * Recupera el bloque indicado o lanza la excepción de negocio.
     *
     * @param id identificador buscado
     * @return la entidad encontrada
     * @throws ResourceNotFoundException si no existe un bloque con ese id
     */
    private HorarioMedico buscar(UUID id) {
        return horarioMedicoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Horario", id));
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
     * @param medicoId   médico dueño de la agenda
     * @param diaSemana  día de la semana del bloque
     * @param horaInicio hora de inicio del bloque
     * @param horaFin    hora de fin del bloque
     * @param idExcluido identificador del bloque que se está editando, o
     *                   {@link #NINGUN_ID} al crear
     * @throws SolapamientoDeHorarioException si hay al menos un bloque que se solapa
     */
    private void comprobarSolapamiento(UUID medicoId, short diaSemana, LocalTime horaInicio,
            LocalTime horaFin, UUID idExcluido) {
        if (horarioMedicoRepository.contarSolapamientos(medicoId, diaSemana, horaInicio, horaFin,
                idExcluido) > 0) {
            throw new SolapamientoDeHorarioException(diaSemana, horaInicio, horaFin);
        }
    }
}
