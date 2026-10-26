package com.intoglobe.clinicas.mapper;

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;

/**
 * Mapeo manual entre la entidad {@link Medico} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado, sin dependencias externas y sin librerías de
 * mapeo: agrupa la traducción explícita para que el service no conozca los detalles
 * del modelo.</p>
 */
public final class MedicoMapper {

    private MedicoMapper() {
    }

    /**
     * Construye una entidad nueva a partir de la organización, el consultorio y el
     * request.
     *
     * <p>La organización no viaja en el cuerpo —llega por el path— y el consultorio
     * sí; ambos llegan aquí ya resueltos por el service, porque sin ellos la clave
     * foránea fallaría y el cliente recibiría un 500 en lugar de un 404. Delega en el
     * constructor de negocio de {@link Medico}, que fija las dos asociaciones de una
     * vez y deja al médico activo.</p>
     *
     * @param organizacion organización dueña, ya resuelta por el service
     * @param consultorio  consultorio donde atiende, ya resuelto por el service
     * @param request      datos de entrada validados
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Medico aEntidad(Organizacion organizacion, Consultorio consultorio, MedicoRequest request) {
        return new Medico(organizacion, consultorio, request.nombre(), request.especialidad(),
                request.cedula(), request.telefono(), request.tarifaBase());
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * <p>De las asociaciones {@code organizacion} y {@code consultorio} se lee
     * únicamente su identificador: Hibernate lo resuelve desde el proxy sin consultar
     * la base, mientras que cualquier otro getter ({@code getNombre()},
     * {@code toString()}, etc.) dispararía la carga perezosa y podría fallar fuera de
     * la transacción. Por eso la respuesta es plana.</p>
     *
     * @param medico entidad de origen
     * @return DTO de respuesta con todos los campos del registro
     */
    public static MedicoResponse aResponse(Medico medico) {
        return new MedicoResponse(
                medico.getId(),
                medico.getOrganizacion().getId(),
                medico.getConsultorio().getId(),
                medico.getNombre(),
                medico.getEspecialidad(),
                medico.getCedula(),
                medico.getTelefono(),
                medico.getTarifaBase(),
                medico.isActivo());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica nombre,
     * especialidad, cédula, teléfono y tarifa. Nunca toca {@code id}, {@code activo},
     * {@code organizacion} ni {@code consultorio}: un valor {@code null} en los
     * opcionales borra el valor anterior, un médico inactivo no se reactiva y el
     * consultorio no se mueve (ese caso lo rechaza el service con 400 antes de
     * llegar aquí).</p>
     *
     * @param medico  entidad a modificar
     * @param request datos de entrada validados
     */
    public static void actualizarEntidad(Medico medico, MedicoRequest request) {
        medico.actualizarDatos(request.nombre(), request.especialidad(), request.cedula(),
                request.telefono(), request.tarifaBase());
    }
}
