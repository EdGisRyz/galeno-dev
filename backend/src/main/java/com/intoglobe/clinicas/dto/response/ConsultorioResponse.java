package com.intoglobe.clinicas.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de un consultorio.
 *
 * <p>Es plano: expone la organización únicamente como su identificador, nunca como
 * la entidad {@code Organizacion} anidada. Así la serialización no depende de una
 * asociación perezosa (que podría fallar fuera de la transacción) y no se filtra
 * el estado completo de la organización del tenant.</p>
 *
 * <p>Incluye los campos inmutables asignados por el sistema ({@code id},
 * {@code createdAt}) y la bandera de baja lógica {@code activo}.</p>
 *
 * @param id             identificador único asignado por el sistema
 * @param organizacionId identificador de la organización dueña del consultorio
 * @param nombre         nombre del consultorio
 * @param direccion      dirección del consultorio o {@code null} si no se registró
 * @param telefono       teléfono del consultorio o {@code null} si no se registró
 * @param activo         {@code true} si está activo, {@code false} si fue dado de baja
 * @param createdAt      fecha y hora de creación asignada por el sistema
 */
public record ConsultorioResponse(

        @Schema(description = "Identificador único del consultorio.",
                example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
        UUID id,

        @Schema(description = "Identificador de la organización dueña del consultorio.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID organizacionId,

        @Schema(description = "Nombre con el que se identifica el consultorio dentro de la organización.",
                example = "Consultorio Centro")
        String nombre,

        @Schema(description = "Dirección física del consultorio; null si no se registró.",
                example = "Av. Insurgentes Sur 1234, Col. Del Valle, CDMX")
        String direccion,

        @Schema(description = "Teléfono de contacto del consultorio; null si no se registró.",
                example = "+52 55 5555 1234")
        String telefono,

        @Schema(description = "Indica si el consultorio está activo (true) o dado de baja lógica (false).",
                example = "true")
        boolean activo,

        @Schema(description = "Fecha y hora de creación del consultorio, asignada por el sistema.",
                example = "2026-02-10T08:45:00-06:00")
        OffsetDateTime createdAt) {
}
