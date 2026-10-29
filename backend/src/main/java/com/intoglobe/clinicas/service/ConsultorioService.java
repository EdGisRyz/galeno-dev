package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.ConsultorioMapper;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
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
 * <p><strong>Aislamiento por tenant (R-SEG-04, D5.6):</strong> las tres operaciones por id
 * reciben el {@code tenantId} del token y consultan con
 * {@link ConsultorioRepository#buscarPorIdYOrganizacion}. Un consultorio de otra
 * organización responde {@code 404}, igual que un identificador inexistente, para no
 * revelar que existe. El tenant lo aporta el llamador desde el claim del token —nunca desde
 * el path sin validar— porque el controlador ya lo obtuvo de {@code TenantAccessGuard}.</p>
 */
@Service
public class ConsultorioService {

    private final ConsultorioRepository consultorioRepository;
    private final OrganizacionRepository organizacionRepository;

    public ConsultorioService(ConsultorioRepository consultorioRepository,
            OrganizacionRepository organizacionRepository) {
        this.consultorioRepository = consultorioRepository;
        this.organizacionRepository = organizacionRepository;
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
        if (!organizacionRepository.existsById(organizacionId)) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        return consultorioRepository.listarActivosPorOrganizacion(organizacionId).stream()
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
     * @param id       identificador del consultorio a desactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un consultorio con ese id en ese tenant
     */
    @Transactional
    public void desactivar(UUID id, UUID tenantId) {
        var consultorio = buscarDelTenant(id, tenantId);
        consultorio.desactivar();
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
