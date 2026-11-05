package com.intoglobe.clinicas.dto.request;

import java.time.LocalTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de entrada para crear y actualizar un bloque de disponibilidad.
 *
 * <p>Deliberadamente no incluye el médico. La pertenencia de un horario a un médico
 * es estructural —es el dueño de la agenda—: llega por el path del
 * {@code POST} ({@code /api/v1/medicos/{medicoId}/horarios}) y el {@code PUT} no la
 * recibe en absoluto, porque mover un bloque de un médico a otro dejaría la agenda del
 * primero sin ese bloque sin avisar. Como el cuerpo no puede expresar un cambio de
 * dueño, un único DTO sirve para ambas operaciones y la validación no diverge.</p>
 *
 * <p>Los cuatro campos son obligatorios. {@code diaSemana} sigue la convención de
 * JavaScript {@code Date.getDay()} que ya asume el frontend —{@code 0=Domingo, 1=Lunes,
 * 2=Martes, 3=Miércoles, 4=Jueves, 5=Viernes, 6=Sábado}— y su rango espeja el CHECK de
 * V1 ({@code dia_semana >= 0 AND dia_semana <= 6}); con {@code @Min(0)} y {@code @Max(6)}
 * un valor fuera de rango se convierte en un 400 y no en un error de base de datos.</p>
 *
 * <p><strong>Lo que este DTO no puede validar:</strong> que {@code horaInicio} sea
 * estrictamente anterior a {@code horaFin}, que el bloque no se solape con otro del
 * mismo médico y que {@code duracionConsulta} pertenezca al conjunto cerrado
 * {@code {20, 30, 45, 60}}. Las tres son reglas de negocio y viven en el service: las
 * dos primeras dependen de otros bloques o de comparar dos campos entre sí, y la tercera
 * no es expresable con Bean Validation sin introducir infraestructura de constraints
 * personalizadas, fuera del alcance de este módulo.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad. Y una advertencia sobre las
 * que no pueden fallar: {@code diaSemana} y {@code duracionConsulta} son primitivos, así que
 * su {@code @NotNull} declara la obligatoriedad pero <strong>nunca produce una violación</strong>
 * —Bean Validation no valida primitivos contra nulos—. La omisión no queda sin castigo, solo
 * cambia de capa: un campo primitivo ausente en el JSON se deserializa como {@code 0}, y el
 * service lo intercepta. En {@code diaSemana}, {@code 0} es domingo y es un valor legítimo; en
 * {@code duracionConsulta}, {@code 0} no pertenece al conjunto cerrado y responde 400.</p>
 *
 * @param diaSemana        día de la semana, {@code 0=Domingo .. 6=Sábado} (obligatorio).
 *                         Primitivo: su {@code @NotNull} declara la obligatoriedad pero no
 *                         puede fallar; omitirlo llega como {@code 0}, que es domingo.
 * @param horaInicio       hora local de inicio del bloque (obligatoria)
 * @param horaFin          hora local de fin del bloque (obligatoria)
 * @param duracionConsulta duración de cada cita en minutos, uno de
 *                         {@code {20, 30, 45, 60}}. El tipo es primitivo, así que
 *                         {@code @NotNull} declara la obligatoriedad del campo pero
 *                         nunca puede fallar: si el cliente omite el campo, se
 *                         deserializa como {@code 0} y el service lo rechaza con
 *                         {@code DuracionConsultaInvalidaException} → 400. La
 *                         intercepción ocurre en esa capa, no en esta anotación.
 */
public record HorarioMedicoRequest(

        @Schema(description = "Día de la semana con la convención de JavaScript Date.getDay(): 0=Domingo, 1=Lunes, 2=Martes, 3=Miércoles, 4=Jueves, 5=Viernes, 6=Sábado.",
                example = "1")
        @Min(0)
        @Max(6)
        short diaSemana,

        @Schema(description = "Hora local de inicio del bloque; debe ser estrictamente anterior a la hora de fin, si no se responde 400.",
                example = "09:00:00")
        @NotNull
        LocalTime horaInicio,

        @Schema(description = "Hora local de fin del bloque; debe ser estrictamente posterior a la hora de inicio, si no se responde 400.",
                example = "13:00:00")
        @NotNull
        LocalTime horaFin,

        @Schema(description = "Duración de cada cita en minutos; solo se aceptan 20, 30, 45 o 60, si no se responde 400.",
                example = "30")
        @NotNull
        short duracionConsulta) {
}
