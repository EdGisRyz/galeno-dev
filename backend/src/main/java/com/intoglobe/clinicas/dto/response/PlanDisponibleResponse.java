package com.intoglobe.clinicas.dto.response;

import com.intoglobe.clinicas.model.PlanTipo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un plan del catálogo público del onboarding, con sus límites de producto (S2 ter).
 *
 * <p>Los valores salen de {@link PlanTipo}, que es el único lugar donde están declarados: el
 * formulario los pinta en solo lectura al elegir el plan y no los envía al registrarse. Un límite
 * {@code null} significa «sin tope» —es el caso de {@code ENTERPRISE}— y se publica así a propósito,
 * para que un número enorme no se confunda con un límite real.</p>
 *
 * @param plan            plan del conjunto cerrado del esquema
 * @param etiqueta        nombre del plan para la interfaz
 * @param maxMedicos      médicos permitidos, o {@code null} si no hay tope
 * @param maxConsultorios consultorios permitidos, o {@code null} si no hay tope
 */
public record PlanDisponibleResponse(

        @Schema(description = "Plan del conjunto cerrado del esquema.",
                example = "CLINICA",
                allowableValues = {"SOLO", "CLINICA", "ENTERPRISE"})
        PlanTipo plan,

        @Schema(description = "Nombre del plan para mostrarlo en la interfaz.",
                example = "Clínica (varios profesionales)")
        String etiqueta,

        @Schema(description = "Médicos que permite el plan; null si no hay tope.",
                example = "10")
        Integer maxMedicos,

        @Schema(description = "Consultorios que permite el plan; null si no hay tope.",
                example = "6")
        Integer maxConsultorios) {
}
