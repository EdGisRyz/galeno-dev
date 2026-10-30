package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.OrganizacionMapper;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

/**
 * Lógica de negocio de organizaciones.
 *
 * <p>Orquesta el repositorio y el {@link OrganizacionMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura
 * y escritura. Cuando la organización no existe se lanza
 * {@link ResourceNotFoundException}, que el handler traduce a 404.</p>
 *
 * <p><strong>Aislamiento por tenant (R-SEG-04, R-SEG-06):</strong> el listado recibe el
 * {@code tenantId} del token y devuelve solo esa organización. Las operaciones por id no
 * necesitan un parámetro de tenant: en una organización el identificador del recurso
 * <em>es</em> el del tenant, y quien garantiza que coincida con el claim antes de llamar
 * aquí es {@code TenantAccessGuard}, que responde 403 ante cualquier discrepancia. Por eso
 * el {@code id} que reciben {@link #buscarPorId(UUID)}, {@link #actualizar(UUID, OrganizacionRequest)}
 * y {@link #desactivar(UUID)} es ya el del token, nunca el del path sin validar.</p>
 */
@Service
public class OrganizacionService {

    private final OrganizacionRepository organizacionRepository;

    public OrganizacionService(OrganizacionRepository organizacionRepository) {
        this.organizacionRepository = organizacionRepository;
    }

    /**
     * Crea una organización nueva a partir del request.
     *
     * @param request datos de entrada validados
     * @return representación de la organización persistida
     */
    @Transactional
    public OrganizacionResponse crear(OrganizacionRequest request) {
        var organizacion = OrganizacionMapper.aEntidad(request);
        var guardada = organizacionRepository.save(organizacion);
        return OrganizacionMapper.aResponse(guardada);
    }

    /**
     * Busca una organización por su identificador.
     *
     * @param id identificador de la organización
     * @return representación de la organización encontrada
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public OrganizacionResponse buscarPorId(UUID id) {
        return OrganizacionMapper.aResponse(buscarOFallar(id));
    }

    /**
     * Lista las organizaciones activas del tenant indicado.
     *
     * <p>Resuelve R8.3(a): el endpoint {@code GET /api/v1/organizaciones} deja de enumerar
     * la plataforma y devuelve <strong>solo la organización del token</strong>. El resultado
     * es una lista de cero o un elemento para no romper el contrato de respuesta (un arreglo)
     * ni obligar al cliente a distinguir dos formas. Cero elementos significa que la
     * organización del token no existe o está inactiva: el endpoint lista lo vigente, y una
     * sesión de una organización dada de baja no debe ver nada.</p>
     *
     * @param tenantId organización del token de la petición, nunca un valor del path
     * @return la organización activa del tenant en su representación de salida, o lista vacía
     */
    @Transactional(readOnly = true)
    public List<OrganizacionResponse> listarActivas(UUID tenantId) {
        return organizacionRepository.buscarActivaPorId(tenantId).stream()
                .map(OrganizacionMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los cuatro campos editables de una organización existente.
     *
     * @param id      identificador de la organización a actualizar
     * @param request datos de entrada validados
     * @return representación de la organización actualizada
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional
    public OrganizacionResponse actualizar(UUID id, OrganizacionRequest request) {
        var organizacion = buscarOFallar(id);
        OrganizacionMapper.actualizarEntidad(organizacion, request);
        var guardada = organizacionRepository.save(organizacion);
        return OrganizacionMapper.aResponse(guardada);
    }

    /**
     * Aplica la baja lógica de una organización. Es idempotente: desactivar una
     * organización ya inactiva no produce error.
     *
     * @param id identificador de la organización a desactivar
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional
    public void desactivar(UUID id) {
        var organizacion = buscarOFallar(id);
        organizacion.desactivar();
        organizacionRepository.save(organizacion);
    }

    /**
     * Recupera la entidad o lanza la excepción de negocio correspondiente.
     *
     * @param id identificador buscado
     * @return la entidad encontrada
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    private Organizacion buscarOFallar(UUID id) {
        return organizacionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", id));
    }
}
