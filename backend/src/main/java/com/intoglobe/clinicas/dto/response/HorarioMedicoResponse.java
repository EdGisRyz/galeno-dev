package com.intoglobe.clinicas.dto.response;

import java.time.LocalTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de un bloque de disponibilidad.
 *
 * <p>Es plano: expone el médico y el consultorio únicamente como identificadores —y el
 * consultorio, además, con su nombre— nunca como entidades anidadas. Así la serialización no
 * depende de una asociación perezosa —que podría fallar fuera de la transacción— y no se
 * filtra el estado completo del médico, del consultorio ni de su organización.</p>
 *
 * <p>El nombre del consultorio viaja aquí para que la pantalla del horario no tenga que pedir
 * el consultorio aparte solo para mostrar dónde atiende el médico. Es un dato de catálogo, no
 * del paciente ni del médico.</p>
 *
 * <p>No incluye {@code createdAt} ni {@code activo}: la tabla {@code horario_medico} de V1
 * no declara esas columnas e inventarlas en el DTO prometería un dato que el registro no
 * tiene. Un horario se borra físicamente, no se desactiva.</p>
 *
 * @param id               identificador único asignado por el sistema
 * @param medicoId         identificador del médico dueño de la agenda
 * @param consultorioId    identificador del consultorio donde se dicta el bloque; nunca
 *                         {@code null}, porque la V7 lo dejó obligatorio en la tabla
 * @param consultorioNombre nombre del consultorio, para mostrarlo sin una petición extra
 * @param diaSemana        día de la semana, {@code 0=Domingo .. 6=Sábado}
 * @param horaInicio       hora local de inicio del bloque
 * @param horaFin          hora local de fin del bloque
 * @param duracionConsulta duración de cada cita en minutos
 */
public record HorarioMedicoResponse(

        @Schema(description = "Identificador único del bloque de disponibilidad.",
                example = "c1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
        UUID id,

        @Schema(description = "Identificador del médico dueño de la agenda.",
                example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
        UUID medicoId,

        @Schema(description = "Identificador del consultorio donde se dicta el bloque.",
                example = "e1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
        UUID consultorioId,

        @Schema(description = "Nombre del consultorio donde se dicta el bloque.",
                example = "Consultorio Norte")
        String consultorioNombre,

        @Schema(description = "Día de la semana con la convención de JavaScript Date.getDay(): 0=Domingo, 1=Lunes, 2=Martes, 3=Miércoles, 4=Jueves, 5=Viernes, 6=Sábado.",
                example = "1")
        short diaSemana,

        @Schema(description = "Hora local de inicio del bloque.",
                example = "09:00:00")
        LocalTime horaInicio,

        @Schema(description = "Hora local de fin del bloque.",
                example = "13:00:00")
        LocalTime horaFin,

        @Schema(description = "Duración de cada cita en minutos.",
                example = "30")
        short duracionConsulta) {
}
