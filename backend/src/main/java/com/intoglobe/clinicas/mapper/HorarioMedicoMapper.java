package com.intoglobe.clinicas.mapper;

import com.intoglobe.clinicas.dto.request.HorarioMedicoRequest;
import com.intoglobe.clinicas.dto.response.HorarioMedicoResponse;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;

/**
 * Mapeo manual entre la entidad {@link HorarioMedico} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado, sin dependencias externas y sin librerías de mapeo:
 * agrupa la traducción explícita para que el service no conozca los detalles del
 * modelo.</p>
 */
public final class HorarioMedicoMapper {

    private HorarioMedicoMapper() {
    }

    /**
     * Construye una entidad nueva a partir del médico, el consultorio y el request.
     *
     * <p>El médico no viaja en el cuerpo —llega por el path— y llega aquí ya resuelto por
     * el service, porque sin él la clave foránea fallaría y el cliente recibiría un 500
     * en lugar de un 404. El consultorio también llega resuelto: el service ya aplicó la
     * regla del consultorio principal cuando el cuerpo no lo trae y ya comprobó que esté
     * asignado al médico. Delega en el constructor de negocio de {@link HorarioMedico}, que
     * fija las asociaciones y asigna los cuatro campos editables.</p>
     *
     * <p>Este mapeo no valida reglas de negocio: el rango horario, la duración permitida y
     * el solapamiento los comprueba el service <em>antes</em> de llamar aquí, y su orden
     * forma parte del contrato.</p>
     *
     * @param medico      médico dueño de la agenda, ya resuelto por el service
     * @param consultorio consultorio donde se dicta el bloque, ya resuelto y asignado
     * @param request     datos de entrada validados
     * @return entidad transitoria con las asociaciones y los campos editables asignados
     */
    public static HorarioMedico aEntidad(Medico medico, Consultorio consultorio,
            HorarioMedicoRequest request) {
        return new HorarioMedico(medico, consultorio, request.diaSemana(), request.horaInicio(),
                request.horaFin(), request.duracionConsulta());
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * <p>De la asociación {@code medico} se lee únicamente su identificador: Hibernate lo
     * resuelve desde el proxy sin consultar la base, mientras que cualquier otro getter
     * ({@code getNombre()}, {@code toString()}, etc.) dispararía la carga perezosa y
     * podría fallar fuera de la transacción. Del consultorio se leen el identificador y el
     * nombre, y la lectura ocurre dentro de la transacción del service: es el único dato del
     * consultorio que la respuesta necesita y evita una petición extra al catálogo. La tabla
     * {@code horario_medico} no tiene {@code createdAt} ni {@code activo}, así que no se
     * exponen.</p>
     *
     * @param horario entidad de origen
     * @return DTO de respuesta con todos los campos del registro
     */
    public static HorarioMedicoResponse aResponse(HorarioMedico horario) {
        return new HorarioMedicoResponse(
                horario.getId(),
                horario.getMedico().getId(),
                horario.getConsultorio().getId(),
                horario.getConsultorio().getNombre(),
                horario.getDiaSemana(),
                horario.getHoraInicio(),
                horario.getHoraFin(),
                horario.getDuracionConsulta());
    }

    /**
     * Aplica los campos editables del request sobre una entidad existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica día, hora de
     * inicio, hora de fin y duración. Nunca toca {@code id} ni {@code medico}: mover un
     * horario a otro médico dejaría la agenda del primero sin ese bloque sin avisar, y ese
     * cambio de dueño no forma parte del contrato del {@code PUT}. El consultorio sí puede
     * cambiar, pero solo al que el service resolvió y comprobó: el bloque se mueve dentro de
     * la misma agenda y del mismo médico.</p>
     *
     * @param horario     entidad a modificar
     * @param consultorio consultorio resultante: el pedido, o el actual si el cuerpo no lo trae
     * @param request     datos de entrada validados
     */
    public static void actualizarEntidad(HorarioMedico horario, Consultorio consultorio,
            HorarioMedicoRequest request) {
        horario.actualizarDatos(request.diaSemana(), request.horaInicio(), request.horaFin(),
                request.duracionConsulta());
        horario.cambiarConsultorio(consultorio);
    }
}
