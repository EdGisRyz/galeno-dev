package com.intoglobe.clinicas.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.intoglobe.clinicas.model.PlanTipo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de una organización.
 *
 * <p>Expone el estado completo del registro, incluyendo los campos inmutables
 * asignados por el sistema ({@code id} y {@code createdAt}) y la bandera de baja
 * lógica {@code activo}.</p>
 *
 * @param id             identificador único asignado por el sistema
 * @param nombre         nombre de la organización
 * @param plan           plan contratado
 * @param subscriptionId identificador de suscripción o {@code null} si no tiene
 * @param trialHasta     fin del periodo de prueba o {@code null} si no aplica
 * @param activo         {@code true} si está activa, {@code false} si fue dada de baja
 * @param createdAt      fecha y hora de creación asignada por el sistema
 */
public record OrganizacionResponse(

        @Schema(description = "Identificador único de la organización.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID id,

        @Schema(description = "Nombre de la organización tal como se muestra en la plataforma.",
                example = "Clínica San Rafael")
        String nombre,

        @Schema(description = "Plan contratado por la organización.",
                example = "CLINICA")
        PlanTipo plan,

        @Schema(description = "Identificador de la suscripción; null si la organización no tiene una.",
                example = "sub_1PxR7kClinica")
        String subscriptionId,

        @Schema(description = "Fecha y hora con desplazamiento UTC hasta la que aplica el periodo de prueba; null si no aplica.",
                example = "2026-12-31T23:59:59-05:00")
        OffsetDateTime trialHasta,

        @Schema(description = "Indica si la organización está activa (true) o dada de baja lógica (false).",
                example = "true")
        boolean activo,

        @Schema(description = "Fecha y hora de creación de la organización, asignada por el sistema.",
                example = "2026-01-15T09:30:00-05:00")
        OffsetDateTime createdAt) {
}
