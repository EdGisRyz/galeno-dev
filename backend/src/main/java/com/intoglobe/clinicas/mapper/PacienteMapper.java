package com.intoglobe.clinicas.mapper;

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.dto.response.PacienteResponse;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Paciente;

/**
 * Mapeo manual entre la entidad {@link Paciente} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado, sin dependencias externas y sin librerías de
 * mapeo: agrupa la traducción explícita para que el service no conozca los detalles
 * del modelo.</p>
 */
public final class PacienteMapper {

    private PacienteMapper() {
    }

    /**
     * Construye una entidad nueva a partir de la organización y el request.
     *
     * <p>La organización no viaja en el cuerpo —llega por el path— y llega aquí ya
     * resuelta por el service, porque sin ella la clave foránea fallaría y el cliente
     * recibiría un 500 en lugar de un 404. Delega en el constructor de negocio de
     * {@link Paciente}, que fija la asociación y deja al paciente activo; ni
     * {@code activo} ni {@code createdAt} son parámetros, porque el cliente no los
     * fija.</p>
     *
     * @param organizacion organización dueña, ya resuelta por el service
     * @param request      datos de entrada validados
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Paciente aEntidad(Organizacion organizacion, PacienteRequest request) {
        return new Paciente(organizacion, request.nombre(), request.telefono(),
                request.fechaNacimiento(), request.sexo(), request.email(), request.notas());
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * <p>De la asociación {@code organizacion} se lee únicamente su identificador:
     * Hibernate lo resuelve desde el proxy sin consultar la base, mientras que
     * cualquier otro getter ({@code getNombre()}, {@code toString()}, etc.) dispararía
     * la carga perezosa y podría fallar fuera de la transacción. Por eso la respuesta
     * es plana.</p>
     *
     * @param paciente entidad de origen
     * @return DTO de respuesta con los diez campos del registro
     */
    public static PacienteResponse aResponse(Paciente paciente) {
        return new PacienteResponse(
                paciente.getId(),
                paciente.getOrganizacion().getId(),
                paciente.getNombre(),
                paciente.getTelefono(),
                paciente.getFechaNacimiento(),
                paciente.getSexo(),
                paciente.getEmail(),
                paciente.getNotas(),
                paciente.isActivo(),
                paciente.getCreatedAt());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica nombre,
     * teléfono, fecha de nacimiento, sexo, correo y notas. Nunca toca {@code id},
     * {@code activo}, {@code createdAt} ni {@code organizacion}: un valor {@code null}
     * en los opcionales borra el valor anterior, un paciente inactivo no se reactiva
     * y la fecha de alta no se reescribe.</p>
     *
     * @param paciente entidad a modificar
     * @param request  datos de entrada validados
     */
    public static void actualizarEntidad(Paciente paciente, PacienteRequest request) {
        paciente.actualizarDatos(request.nombre(), request.telefono(), request.fechaNacimiento(),
                request.sexo(), request.email(), request.notas());
    }
}
