package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
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
 * <p><strong>Aislamiento por tenant (R-SEG-04, R-SEG-06, D5):</strong> el listado recibe el
 * {@code tenantId} del token y devuelve solo esa organización. Las operaciones por id reciben
 * <em>dos</em> identificadores: el del path y el tenant del claim. Antes de consultar el
 * repositorio se comparan, y una discrepancia responde {@link AccessDeniedException}, que el
 * handler traduce a {@code 403} con el contrato de error. La comparación es pura —sin repositorio,
 * sin base de datos— así que negar el path ajeno no revela si esa organización existe. Aplica a
 * los tres roles que operan sobre organizaciones, {@code ADMIN} incluido: no existe un rol de
 * plataforma que administre varias. El identificador que llega a {@link #buscarOFallar(UUID)} es
 * el del path, pero solo después de haber demostrado que coincide con el claim.</p>
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
     * Busca una organización por su identificador dentro del tenant indicado.
     *
     * @param id       identificador de la organización tomado del path
     * @param tenantId organización del token de la petición
     * @return representación de la organización encontrada
     * @throws AccessDeniedException si el id del path no es el del token; el handler lo
     *                               traduce a {@code 403} sin consultar el repositorio
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public OrganizacionResponse buscarPorId(UUID id, UUID tenantId) {
        exigirOrganizacionDelTenant(id, tenantId);
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
     * @param id       identificador de la organización tomado del path
     * @param tenantId organización del token de la petición
     * @param request  datos de entrada validados
     * @return representación de la organización actualizada
     * @throws AccessDeniedException si el id del path no es el del token; el handler lo
     *                               traduce a {@code 403} sin consultar el repositorio
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional
    public OrganizacionResponse actualizar(UUID id, UUID tenantId, OrganizacionRequest request) {
        exigirOrganizacionDelTenant(id, tenantId);
        var organizacion = buscarOFallar(id);
        OrganizacionMapper.actualizarEntidad(organizacion, request);
        var guardada = organizacionRepository.save(organizacion);
        return OrganizacionMapper.aResponse(guardada);
    }

    /**
     * Aplica la baja lógica de una organización. Es idempotente: desactivar una
     * organización ya inactiva no produce error.
     *
     * @param id       identificador de la organización tomado del path
     * @param tenantId organización del token de la petición
     * @throws AccessDeniedException si el id del path no es el del token; el handler lo
     *                               traduce a {@code 403} sin consultar el repositorio
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional
    public void desactivar(UUID id, UUID tenantId) {
        exigirOrganizacionDelTenant(id, tenantId);
        var organizacion = buscarOFallar(id);
        organizacion.desactivar();
        organizacionRepository.save(organizacion);
    }

    /**
     * Exige que el identificador del path sea el del tenant del token antes de consultar nada.
     *
     * <p>Es la misma regla de {@code PacienteService.exigirOrganizacionDelTenant} y de
     * {@code TenantAccessGuard}, pero vive aquí porque esta capa es la que garantiza el aislamiento
     * de las operaciones por id: quien llame al service —desde el controlador o desde otro
     * servicio— no puede saltarse la comprobación. El path es una afirmación del cliente y la
     * comparación es pura, así que un {@code 403} no confirma si la organización ajena existe.</p>
     *
     * @param organizacionId organización recibida por el path
     * @param tenantId       organización del token de la petición
     * @throws AccessDeniedException si no coinciden; el manejador global lo traduce a
     *                               {@code 403} con {@code ErrorResponse}
     */
    private static void exigirOrganizacionDelTenant(UUID organizacionId, UUID tenantId) {
        if (!Objects.equals(organizacionId, tenantId)) {
            throw new AccessDeniedException(
                    "El organizacionId de la ruta no corresponde al tenant del token");
        }
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
