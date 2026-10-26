package com.intoglobe.clinicas.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar un médico.
 *
 * <p>Deliberadamente no incluye la organización. La pertenencia de un médico es
 * inmutable y estructural —es el límite de tenant—: llega por el path del
 * {@code POST} ({@code /api/v1/organizaciones/{organizacionId}/medicos}) y el
 * {@code PUT} no la recibe en absoluto. Como el cuerpo no puede expresar un cambio
 * de organización, no existe caso de discrepancia que rechazar y un único DTO sirve
 * para ambas operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p>El consultorio, en cambio, sí viaja en el cuerpo: es un atributo del médico,
 * no su dueño. Tiene que estar aquí porque en el alta es un dato obligatorio, y en
 * la actualización su presencia permite rechazar con 400 un cambio de consultorio
 * en vez de responder 200 sin aplicarlo (regla R13).</p>
 *
 * <p>{@code tarifaBase} sigue siendo opcional: {@code @Digits} y {@code @DecimalMin}
 * ignoran {@code null} por diseño, así que la ausencia de valor se acepta y solo se
 * valida el número cuando el cliente lo envía. {@code @Digits(integer = 8,
 * fraction = 2)} espeja la precisión declarada de la columna
 * {@code NUMERIC(10,2)} y convierte un desborde en un 400 en lugar de un error de
 * base de datos; {@code @DecimalMin("0.0")} es una regla de aplicación declarada,
 * no una restricción de V1.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad.</p>
 *
 * @param consultorioId consultorio donde atiende el médico (obligatorio)
 * @param nombre        nombre del médico (obligatorio, 1..120 caracteres)
 * @param especialidad  especialidad del médico (opcional, máx. 80 caracteres)
 * @param cedula        cédula profesional (opcional, máx. 30 caracteres)
 * @param telefono      teléfono de contacto (opcional, máx. 20 caracteres)
 * @param tarifaBase    tarifa base de la consulta (opcional, hasta 8 enteros y 2
 *                      decimales, nunca negativa)
 */
public record MedicoRequest(

        @Schema(description = "Identificador del consultorio donde atiende el médico; debe existir, si no se responde 404.",
                example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
        @NotNull
        UUID consultorioId,

        @Schema(description = "Nombre completo del médico (obligatorio, 1 a 120 caracteres).",
                example = "Dra. Ana Torres")
        @NotBlank
        @Size(min = 1, max = 120)
        String nombre,

        @Schema(description = "Especialidad médica del doctor; null si no se registró, máximo 80 caracteres.",
                example = "Cardiología")
        @Size(max = 80)
        String especialidad,

        @Schema(description = "Cédula profesional del médico; null si no se registró, máximo 30 caracteres.",
                example = "12345678")
        @Size(max = 30)
        String cedula,

        @Schema(description = "Teléfono de contacto del médico; null si no se registró, máximo 20 caracteres.",
                example = "+52 55 5555 9876")
        @Size(max = 20)
        String telefono,

        @Schema(description = "Tarifa base de la consulta; null si no se registró. Hasta 8 enteros y 2 decimales, nunca negativa.",
                example = "500.00")
        @Digits(integer = 8, fraction = 2)
        @DecimalMin(value = "0.0", inclusive = true)
        BigDecimal tarifaBase) {
}
