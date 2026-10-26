package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.exception.CambioDeConsultorioNoPermitidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.MedicoMapper;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

/**
 * Lógica de negocio de médicos.
 *
 * <p>Orquesta los tres repositorios y el {@link MedicoMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y
 * escritura. Un recurso inexistente se traduce a
 * {@link ResourceNotFoundException}, que el handler convierte en 404.</p>
 *
 * <p>La organización que llega por el path y el consultorio que llega en el cuerpo se
 * resuelven siempre contra sus repositorios. Sin esas comprobaciones, crear un médico
 * bajo una organización o un consultorio inexistente haría fallar la clave foránea y
 * el cliente recibiría un 500 en lugar de un 404; en {@code crear} la resolución no
 * es una consulta extra, porque aporta las entidades que el mapper necesita. En el
 * listado anidado la existencia del padre es parte del contrato: un id inexistente
 * responde 404 y no un {@code 200 []} que ocultaría el error del cliente.</p>
 *
 * <p>Este módulo <b>no</b> valida que el consultorio pertenezca a la organización del
 * médico: el schema no lo garantiza y la decisión registrada es no inventar esa regla
 * aquí. También está deliberadamente ausente el listado de médicos por consultorio,
 * porque V1 no indexa {@code medico.consultorio_id}.</p>
 */
@Service
public class MedicoService {

    private final MedicoRepository medicoRepository;
    private final OrganizacionRepository organizacionRepository;
    private final ConsultorioRepository consultorioRepository;

    public MedicoService(MedicoRepository medicoRepository,
            OrganizacionRepository organizacionRepository,
            ConsultorioRepository consultorioRepository) {
        this.medicoRepository = medicoRepository;
        this.organizacionRepository = organizacionRepository;
        this.consultorioRepository = consultorioRepository;
    }

    /**
     * Crea un médico dentro de la organización indicada y su consultorio.
     *
     * <p>Ambas entidades se resuelven antes de mapear: si alguna no existe se responde
     * 404 en vez de dejar que la clave foránea produzca un 500.</p>
     *
     * @param organizacionId organización dueña, tomada del path de la petición
     * @param request        datos de entrada validados
     * @return representación del médico persistido
     * @throws ResourceNotFoundException si no existe la organización o el consultorio
     */
    @Transactional
    public MedicoResponse crear(UUID organizacionId, MedicoRequest request) {
        var organizacion = organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", organizacionId));
        var consultorio = consultorioRepository.findById(request.consultorioId())
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", request.consultorioId()));
        var medico = MedicoMapper.aEntidad(organizacion, consultorio, request);
        var guardado = medicoRepository.save(medico);
        return MedicoMapper.aResponse(guardado);
    }

    /**
     * Busca un médico por su identificador.
     *
     * @param id identificador del médico
     * @return representación del médico encontrado
     * @throws ResourceNotFoundException si no existe un médico con ese id
     */
    @Transactional(readOnly = true)
    public MedicoResponse buscarPorId(UUID id) {
        return MedicoMapper.aResponse(buscarOFallar(id));
    }

    /**
     * Lista los médicos activos de una organización, ordenados por nombre.
     *
     * <p>Antes de listar comprueba que la organización exista: si el id del padre está
     * mal escrito, el cliente debe recibir 404 en vez de una lista vacía.</p>
     *
     * @param organizacionId organización dueña de los médicos
     * @return médicos activos de esa organización en su representación de salida
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<MedicoResponse> listarPorOrganizacion(UUID organizacionId) {
        if (!organizacionRepository.existsById(organizacionId)) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        return medicoRepository.listarActivosPorOrganizacion(organizacionId).stream()
                .map(MedicoMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los cinco campos editables de un médico existente.
     *
     * <p>La organización nunca cambia: el request no la contiene. El consultorio
     * tampoco: si el cuerpo trae uno distinto del actual se rechaza con
     * {@link CambioDeConsultorioNoPermitidoException} —que el handler traduce a 400—
     * porque responder 200 sin aplicar lo pedido mentiría. Con el mismo valor la
     * actualización procede, y el mapper no toca las asociaciones.</p>
     *
     * @param id      identificador del médico a actualizar
     * @param request datos de entrada validados
     * @return representación del médico actualizado
     * @throws ResourceNotFoundException              si no existe un médico con ese id
     * @throws CambioDeConsultorioNoPermitidoException si el consultorio del cuerpo no es
     *                                                el consultorio actual del médico
     */
    @Transactional
    public MedicoResponse actualizar(UUID id, MedicoRequest request) {
        var medico = buscarOFallar(id);
        var consultorioActualId = medico.getConsultorio().getId();
        if (!request.consultorioId().equals(consultorioActualId)) {
            throw new CambioDeConsultorioNoPermitidoException(consultorioActualId, request.consultorioId());
        }
        MedicoMapper.actualizarEntidad(medico, request);
        var guardado = medicoRepository.save(medico);
        return MedicoMapper.aResponse(guardado);
    }

    /**
     * Aplica la baja lógica de un médico. Es idempotente: desactivar un médico ya
     * inactivo no produce error.
     *
     * <p>El borrado físico está descartado: la clave foránea de {@code cita} lo
     * bloquea y la de {@code horario_medico} borraría los horarios en silencio.</p>
     *
     * @param id identificador del médico a desactivar
     * @throws ResourceNotFoundException si no existe un médico con ese id
     */
    @Transactional
    public void desactivar(UUID id) {
        var medico = buscarOFallar(id);
        medico.desactivar();
        medicoRepository.save(medico);
    }

    /**
     * Recupera la entidad o lanza la excepción de negocio correspondiente.
     *
     * @param id identificador buscado
     * @return la entidad encontrada
     * @throws ResourceNotFoundException si no existe un médico con ese id
     */
    private Medico buscarOFallar(UUID id) {
        return medicoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Medico", id));
    }
}
