package com.intoglobe.clinicas.mapper;

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Organizacion;

/**
 * Mapeo manual entre la entidad {@link Consultorio} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado, sin dependencias externas y sin librerías de
 * mapeo: agrupa la traducción explícita para que el service no conozca los
 * detalles del modelo.</p>
 */
public final class ConsultorioMapper {

    private ConsultorioMapper() {
    }

    /**
     * Construye una entidad nueva a partir de la organización y el request.
     *
     * <p>La organización se recibe como parámetro porque no viaja en el cuerpo; el
     * constructor de negocio de {@link Consultorio} la fija de una vez, y el
     * consultorio nace activo.</p>
     *
     * @param organizacion organización dueña, ya resuelta por el service
     * @param request      datos de entrada validados
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Consultorio aEntidad(Organizacion organizacion, ConsultorioRequest request) {
        return new Consultorio(organizacion, request.nombre(), request.direccion(), request.telefono());
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * <p>De la asociación {@code organizacion} se lee únicamente su identificador:
     * Hibernate lo resuelve desde el proxy sin consultar la base, mientras que
     * cualquier otro getter dispararía la carga perezosa y podría fallar fuera de
     * la transacción. Por eso la respuesta es plana.</p>
     *
     * @param consultorio entidad de origen
     * @return DTO de respuesta con todos los campos del registro
     */
    public static ConsultorioResponse aResponse(Consultorio consultorio) {
        return new ConsultorioResponse(
                consultorio.getId(),
                consultorio.getOrganizacion().getId(),
                consultorio.getNombre(),
                consultorio.getDireccion(),
                consultorio.getTelefono(),
                consultorio.isActivo(),
                consultorio.getCreatedAt());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica nombre,
     * direccion y telefono. Nunca toca {@code id}, {@code createdAt}, {@code activo}
     * ni la organización: un valor {@code null} en los opcionales borra el valor
     * anterior, y un consultorio inactivo no se reactiva.</p>
     *
     * @param consultorio entidad a modificar
     * @param request     datos de entrada validados
     */
    public static void actualizarEntidad(Consultorio consultorio, ConsultorioRequest request) {
        consultorio.actualizarDatos(request.nombre(), request.direccion(), request.telefono());
    }
}
