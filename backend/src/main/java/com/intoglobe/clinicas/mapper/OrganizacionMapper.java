package com.intoglobe.clinicas.mapper;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.model.Organizacion;

/**
 * Mapeo manual entre la entidad {@link Organizacion} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado ni dependencias externas: agrupa la traducción
 * explícita para que el service no conozca los detalles del modelo.</p>
 */
public final class OrganizacionMapper {

    private OrganizacionMapper() {
    }

    /**
     * Construye una entidad nueva a partir del request.
     *
     * <p>Usa el constructor de negocio de {@link Organizacion} y luego
     * {@code actualizarDatos} para asignar también el {@code trialHasta} opcional.
     * La organización resultante queda activa por defecto.</p>
     *
     * @param request datos de entrada validados
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Organizacion aEntidad(OrganizacionRequest request) {
        var organizacion = new Organizacion(request.nombre(), request.plan(), request.subscriptionId());
        organizacion.actualizarDatos(request.nombre(), request.plan(), request.subscriptionId(), request.trialHasta());
        return organizacion;
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * @param organizacion entidad de origen
     * @return DTO de respuesta con todos los campos del registro
     */
    public static OrganizacionResponse aResponse(Organizacion organizacion) {
        return new OrganizacionResponse(
                organizacion.getId(),
                organizacion.getNombre(),
                organizacion.getPlan(),
                organizacion.getSubscriptionId(),
                organizacion.getTrialHasta(),
                organizacion.isActivo(),
                organizacion.getCreatedAt());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica nombre,
     * plan, {@code subscriptionId} y {@code trialHasta}. Un valor {@code null} en
     * los opcionales borra el valor anterior y la organización inactiva nunca se
     * reactiva.</p>
     *
     * @param organizacion entidad a modificar
     * @param request      datos de entrada validados
     */
    public static void actualizarEntidad(Organizacion organizacion, OrganizacionRequest request) {
        organizacion.actualizarDatos(request.nombre(), request.plan(), request.subscriptionId(), request.trialHasta());
    }
}
