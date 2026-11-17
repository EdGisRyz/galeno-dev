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
     * <p>La ficha administrativa se aplica después del constructor con
     * {@link Paciente#registrarFicha(String, String, String, String, String, String)}: el
     * constructor conserva su firma y la ficha es un dato que puede faltar. La CURP se normaliza
     * aquí también —es idempotente— para que la entidad reciba siempre la forma canónica aunque
     * la petición no haya pasado por el DTO.</p>
     *
     * @param organizacion organización dueña, ya resuelta por el service
     * @param request      datos de entrada validados
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Paciente aEntidad(Organizacion organizacion, PacienteRequest request) {
        var paciente = new Paciente(organizacion, request.nombre(), request.telefono(),
                request.fechaNacimiento(), request.sexo(), request.email(), request.notas());
        aplicarFicha(paciente, request);
        return paciente;
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
     * @return DTO de respuesta con los dieciséis campos del registro
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
                paciente.getCurp(),
                paciente.getOcupacion(),
                paciente.getDomicilio(),
                paciente.getNombreResponsable(),
                paciente.getTelefonoResponsable(),
                paciente.getParentesco(),
                paciente.isActivo(),
                paciente.getCreatedAt());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica nombre,
     * teléfono, fecha de nacimiento, sexo, correo y notas, y en
     * {@code registrarFicha} para los seis campos de la ficha. Nunca toca {@code id},
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
        aplicarFicha(paciente, request);
    }

    /**
     * Vuelca la ficha del request sobre el paciente, con la CURP ya canónica.
     *
     * <p>Es el único punto donde las dos operaciones —alta y actualización— traducen la ficha, para
     * que la lista de campos no se duplique y no pueda divergir entre ellas.</p>
     *
     * @param paciente entidad a modificar
     * @param request  datos de entrada validados
     */
    private static void aplicarFicha(Paciente paciente, PacienteRequest request) {
        paciente.registrarFicha(Paciente.normalizarCurp(request.curp()), request.ocupacion(),
                request.domicilio(), request.nombreResponsable(), request.telefonoResponsable(),
                request.parentesco());
    }
}
