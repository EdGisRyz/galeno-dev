package com.intoglobe.clinicas.dto.request;

import java.time.OffsetDateTime;

import com.intoglobe.clinicas.model.PlanTipo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar una organización.
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad. Este mismo record
 * se reutiliza para {@code POST} y {@code PUT}: en ambos casos representa el
 * reemplazo de los campos editables.</p>
 *
 * @param nombre         nombre de la organización (obligatorio, 1..120 caracteres)
 * @param plan           plan contratado (obligatorio)
 * @param subscriptionId identificador de suscripción (opcional, máx. 100 caracteres)
 * @param trialHasta     fin del periodo de prueba (opcional, sin validación)
 */
public record OrganizacionRequest(

        @Schema(description = "Nombre de la organización tal como se mostrará en la plataforma.",
                example = "Clínica San Rafael")
        @NotBlank
        @Size(min = 1, max = 120)
        String nombre,

        @Schema(description = "Plan contratado por la organización.",
                example = "CLINICA")
        @NotNull
        PlanTipo plan,

        @Schema(description = "Identificador de la suscripción en la pasarela de pagos (opcional, máximo 100 caracteres).",
                example = "sub_1PxR7kClinica")
        @Size(max = 100)
        String subscriptionId,

        @Schema(description = "Fecha y hora con desplazamiento UTC hasta la que aplica el periodo de prueba (opcional).",
                example = "2026-12-31T23:59:59-05:00")
        OffsetDateTime trialHasta) {
}
