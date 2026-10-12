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
     * Lista las organizaciones activas ordenadas por nombre.
     *
     * @return organizaciones activas en su representación de salida
     */
    @Transactional(readOnly = true)
    public List<OrganizacionResponse> listarActivas() {
        return organizacionRepository.listarActivas().stream()
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
