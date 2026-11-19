package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ConsultorioPrincipalConMedicoActivoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.ConsultorioMapper;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

/**
 * Lógica de negocio de consultorios.
 *
 * <p>Orquesta los dos repositorios y el {@link ConsultorioMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y
 * escritura. Un recurso inexistente se traduce a
 * {@link ResourceNotFoundException}, que el handler convierte en 404.</p>
 *
 * <p>La organización que llega por el path se resuelve siempre contra
 * {@link OrganizacionRepository}. Sin esa comprobación, crear un consultorio bajo
 * una organización inexistente haría fallar la clave foránea y el cliente recibiría
 * un 500 en lugar de un 404; en {@code crear} la resolución no es una consulta
 * extra, porque aporta la entidad que el mapper necesita. En el listado anidado la
 * existencia del padre es parte del contrato: un id inexistente responde 404 y no
 * un {@code 200 []} que ocultaría el error del cliente.</p>
 *
 * <p><strong>Aislamiento por tenant (R-SEG-04, D5.6):</strong> las cuatro operaciones por id
 * —buscar, actualizar, dar de baja y reactivar— reciben el {@code tenantId} del token y consultan
 * con {@link ConsultorioRepository#buscarPorIdYOrganizacion}. Un consultorio de otra
 * organización responde {@code 404}, igual que un identificador inexistente, para no
 * revelar que existe. El tenant lo aporta el llamador desde el claim del token —nunca desde
 * el path sin validar— porque el controlador ya lo obtuvo de {@code TenantAccessGuard}.</p>
 */
@Service
public class ConsultorioService {

    private final ConsultorioRepository consultorioRepository;
    private final OrganizacionRepository organizacionRepository;
    private final MedicoRepository medicoRepository;

    public ConsultorioService(ConsultorioRepository consultorioRepository,
            OrganizacionRepository organizacionRepository,
            MedicoRepository medicoRepository) {
        this.consultorioRepository = consultorioRepository;
        this.organizacionRepository = organizacionRepository;
        this.medicoRepository = medicoRepository;
    }

    /**
     * Crea un consultorio dentro de la organización indicada.
     *
     * @param organizacionId organización dueña: el tenant del token, ya validado contra el
     *                       path por el controlador
     * @param request        datos de entrada validados
     * @return representación del consultorio persistido
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional
    public ConsultorioResponse crear(UUID organizacionId, ConsultorioRequest request) {
        var organizacion = organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", organizacionId));
        var consultorio = ConsultorioMapper.aEntidad(organizacion, request);
        var guardado = consultorioRepository.save(consultorio);
        return ConsultorioMapper.aResponse(guardado);
    }

    /**
     * Busca un consultorio por su identificador dentro del tenant indicado.
     *
     * @param id       identificador del consultorio
     * @param tenantId organización del token de la petición
     * @return representación del consultorio encontrado
     * @throws ResourceNotFoundException si no existe un consultorio con ese id <em>en ese
     *                                   tenant</em>; un consultorio de otra organización cae
     *                                   aquí a propósito, para no revelar que existe
     */
    @Transactional(readOnly = true)
    public ConsultorioResponse buscarPorId(UUID id, UUID tenantId) {
        return ConsultorioMapper.aResponse(buscarDelTenant(id, tenantId));
    }

    /**
     * Lista los consultorios activos de una organización, ordenados por nombre.
     *
     * <p>Antes de listar comprueba que la organización exista: si el id del padre
     * está mal escrito, el cliente debe recibir 404 en vez de una lista vacía.</p>
     *
     * @param organizacionId organización dueña de los consultorios
     * @return consultorios activos de esa organización en su representación de salida
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<ConsultorioResponse> listarPorOrganizacion(UUID organizacionId) {
        return listarPorOrganizacion(organizacionId, false);
    }

    /**
     * Lista los consultorios de una organización.
     *
     * <p>Por omisión devuelve solo los activos, que es el contrato declarado del listado. Con
     * {@code incluirInactivos} devuelve también los dados de baja, para que la pantalla pueda
     * ofrecer la reactivación en la misma fila (S8): el consultorio dado de baja por una
     * refacción no puede desaparecer de la tabla cuando hay que volver a activarlo.</p>
     *
     * <p>Las comprobaciones de acceso no dependen del parámetro: la organización del path ya la
     * contrastó el controlador contra el claim y aquí se comprueba que exista.</p>
     *
     * @param organizacionId organización dueña de los consultorios
     * @param incluirInactivos si se incluyen también los consultorios dados de baja
     * @return consultorios de esa organización en su representación de salida
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<ConsultorioResponse> listarPorOrganizacion(UUID organizacionId,
            boolean incluirInactivos) {
        if (!organizacionRepository.existsById(organizacionId)) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        var consultorios = incluirInactivos
                ? consultorioRepository.listarPorOrganizacion(organizacionId)
                : consultorioRepository.listarActivosPorOrganizacion(organizacionId);
        return consultorios.stream()
                .map(ConsultorioMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los tres campos editables de un consultorio existente.
     *
     * <p>La organización nunca cambia: el request no la contiene y el mapper no la
     * toca, porque mover un consultorio dejaría huérfanos a sus médicos y citas.</p>
     *
     * @param id       identificador del consultorio a actualizar
     * @param tenantId organización del token de la petición
     * @param request  datos de entrada validados
     * @return representación del consultorio actualizado
     * @throws ResourceNotFoundException si no existe un consultorio con ese id en ese tenant
     */
    @Transactional
    public ConsultorioResponse actualizar(UUID id, UUID tenantId, ConsultorioRequest request) {
        var consultorio = buscarDelTenant(id, tenantId);
        ConsultorioMapper.actualizarEntidad(consultorio, request);
        var guardado = consultorioRepository.save(consultorio);
        return ConsultorioMapper.aResponse(guardado);
    }

    /**
     * Aplica la baja lógica de un consultorio. Es idempotente: desactivar un
     * consultorio ya inactivo no produce error.
     *
     * <p><strong>No se da de baja el consultorio principal de un médico activo (S6).</strong>
     * Ese consultorio es la referencia por defecto de la agenda del médico, y su baja dejaría a
     * un médico «activo» con la agenda apuntando a una sala cerrada. Se responde
     * {@link ConsultorioPrincipalConMedicoActivoException} —{@code 409}— con el camino para
     * desbloquear la operación: dar de baja primero al médico. Si el único médico que lo tiene
     * como principal está inactivo, la baja se permite; es la mitad de la invariante que deja
     * sitio a la reactivación del médico (S7).</p>
     *
     * @param id       identificador del consultorio a desactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un consultorio con ese id en ese tenant
     * @throws ConsultorioPrincipalConMedicoActivoException si el consultorio es el principal de
     *                                   un médico activo de la organización; el handler lo traduce
     *                                   a 409
     */
    @Transactional
    public void desactivar(UUID id, UUID tenantId) {
        var consultorio = buscarDelTenant(id, tenantId);
        if (medicoRepository.contarActivosPorConsultorioPrincipal(id, tenantId) > 0) {
            throw ConsultorioPrincipalConMedicoActivoException.porSerElPrincipalDeUnMedicoActivo();
        }
        consultorio.desactivar();
        consultorioRepository.save(consultorio);
    }

    /**
     * Reactiva un consultorio dado de baja lógica. Es idempotente: activar uno que ya está
     * activo no produce error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p><strong>Por qué existe:</strong> el caso típico es el consultorio que se dio de baja
     * por una refacción. Sin esta operación, la única forma de volver a usarlo era crear otro,
     * y los médicos y las citas que lo referencian quedaban colgados del viejo. La baja es
     * lógica precisamente para poder deshacerla.</p>
     *
     * @param id       identificador del consultorio a reactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un consultorio con ese id en ese tenant
     */
    @Transactional
    public void activar(UUID id, UUID tenantId) {
        var consultorio = buscarDelTenant(id, tenantId);
        consultorio.activar();
        consultorioRepository.save(consultorio);
    }

    /**
     * Recupera el consultorio del tenant indicado o lanza la excepción de negocio.
     *
     * @param id       identificador buscado
     * @param tenantId organización del token de la petición
     * @return la entidad encontrada en ese tenant
     * @throws ResourceNotFoundException si no existe en ese tenant
     */
    private Consultorio buscarDelTenant(UUID id, UUID tenantId) {
        return consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", id));
    }
}
