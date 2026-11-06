package com.intoglobe.clinicas.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de un paciente.
 *
 * <p>Es plano: expone la organización únicamente como su identificador, nunca como
 * entidad anidada. Así la serialización no depende de una asociación perezosa (que
 * podría fallar fuera de la transacción) y no se filtra el estado completo de la
 * organización del tenant.</p>
 *
 * <p>{@code createdAt} es {@link OffsetDateTime} porque la columna
 * {@code paciente.created_at} es {@code TIMESTAMP WITH TIME ZONE} en V1: con
 * {@link java.time.LocalDateTime} se perdería el desplazamiento horario y el instante
 * dejaría de ser el mismo. El campo es de solo lectura —el cliente nunca lo envía— y
 * {@code activo} refleja la baja lógica, que en este módulo se revierte con
 * {@code activar}.</p>
 *
 * @param id              identificador único asignado por el sistema
 * @param organizacionId  identificador de la organización dueña del paciente
 * @param nombre          nombre completo del paciente
 * @param telefono        teléfono de contacto o {@code null} si no se registró
 * @param fechaNacimiento fecha de nacimiento o {@code null} si no se registró
 * @param sexo            sexo registrado ({@code M}, {@code F}, {@code O}) o {@code null}
 * @param email           correo electrónico o {@code null} si no se registró
 * @param notas           notas libres o {@code null} si no se registraron
 * @param activo          {@code true} si está activo, {@code false} si fue dado de baja
 * @param createdAt       fecha y hora de alta asignada por el sistema
 */
public record PacienteResponse(

        @Schema(description = "Identificador único del paciente.",
                example = "0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b")
        UUID id,

        @Schema(description = "Identificador de la organización dueña del paciente.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID organizacionId,

        @Schema(description = "Nombre completo del paciente.",
                example = "María López Ramírez")
        String nombre,

        @Schema(description = "Teléfono de contacto; null si no se registró.",
                example = "+52 55 5555 4321")
        String telefono,

        @Schema(description = "Fecha de nacimiento; null si no se registró.",
                example = "1985-03-14")
        LocalDate fechaNacimiento,

        @Schema(description = "Sexo registrado (M, F u O); null si no se registró.",
                example = "F")
        String sexo,

        @Schema(description = "Correo electrónico; null si no se registró.",
                example = "maria.lopez@example.com")
        String email,

        @Schema(description = "Notas libres; null si no se registraron.",
                example = "Alergia a la penicilina")
        String notas,

        @Schema(description = "Indica si el paciente está activo (true) o dado de baja lógica (false).",
                example = "true")
        boolean activo,

        @Schema(description = "Fecha y hora de alta del registro, en la zona del servidor.",
                example = "2025-01-15T10:30:00-06:00")
        OffsetDateTime createdAt) {
}
