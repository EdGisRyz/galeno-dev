package com.intoglobe.clinicas.dto.request;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar un paciente.
 *
 * <p>Deliberadamente no incluye la organización. La pertenencia de un paciente es
 * inmutable y estructural —es el límite de tenant—: llega por el path del
 * {@code POST} ({@code /api/v1/organizaciones/{organizacionId}/pacientes}) y el
 * {@code PUT} no la recibe en absoluto. Como el cuerpo no puede expresar un cambio
 * de organización, no existe caso de discrepancia que rechazar y un único DTO sirve
 * para ambas operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p>Tampoco incluye {@code activo} ni {@code createdAt}: la baja y la reactivación
 * son operaciones propias ({@code PATCH .../desactivar} y {@code PATCH .../activar})
 * y la fecha de alta es un dato que asigna el sistema. La columna {@code created_at}
 * es {@code updatable = false} en la entidad, así que un cliente no puede
 * sobrescribirla ni enviándola en el cuerpo de la actualización.</p>
 *
 * <p><strong>{@code email} no lleva {@code @Email} a propósito.</strong> La columna
 * {@code paciente.email} de V1 es {@code VARCHAR(180)} sin CHECK ni formato
 * declarado: agregar aquí una validación de formato sería inventar una regla que la
 * base no tiene y rechazar datos que hoy sí se almacenan. Es una decisión declarada,
 * no un olvido.</p>
 *
 * <p><strong>{@code notas} no declara longitud</strong> porque la columna es
 * {@code TEXT} en V1 y no fija límite. Poner un máximo aquí sería inventar una
 * restricción ausente en el schema.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad.</p>
 *
 * @param nombre          nombre del paciente (obligatorio, 1..160 caracteres)
 * @param telefono        teléfono de contacto (opcional, máx. 20 caracteres)
 * @param fechaNacimiento fecha de nacimiento (opcional, {@code DATE} sin hora ni zona)
 * @param sexo            sexo registrado (opcional, uno de {@code M}, {@code F}, {@code O})
 * @param email           correo electrónico (opcional, máx. 180 caracteres, sin
 *                        validación de formato)
 * @param notas           notas libres (opcional, columna {@code TEXT})
 */
public record PacienteRequest(

        @Schema(description = "Nombre completo del paciente (obligatorio, 1 a 160 caracteres).",
                example = "María López Ramírez")
        @NotBlank
        @Size(min = 1, max = 160)
        String nombre,

        @Schema(description = "Teléfono de contacto del paciente; null si no se registró, máximo 20 caracteres.",
                example = "+52 55 5555 4321")
        @Size(max = 20)
        String telefono,

        @Schema(description = "Fecha de nacimiento del paciente; null si no se registró.",
                example = "1985-03-14")
        LocalDate fechaNacimiento,

        @Schema(description = "Sexo registrado: M, F u O; null si no se registró.",
                example = "F",
                allowableValues = {"M", "F", "O"})
        @Size(max = 1)
        @Pattern(regexp = "^[MFO]$")
        String sexo,

        @Schema(description = "Correo electrónico del paciente; null si no se registró, máximo 180 caracteres.",
                example = "maria.lopez@example.com")
        @Size(max = 180)
        String email,

        @Schema(description = "Notas libres sobre el paciente; null si no se registraron. La columna es TEXT, sin límite.",
                example = "Alergia a la penicilina")
        String notas) {
}
