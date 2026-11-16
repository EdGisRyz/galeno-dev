package com.intoglobe.clinicas.dto.request;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de entrada para asignar un consultorio a un médico.
 *
 * <p>Es el único dato de la operación: el médico viaja en el path
 * ({@code POST /api/v1/medicos/{medicoId}/consultorios}) y la organización sale del claim del
 * token, nunca del cuerpo. Por eso el DTO tiene un solo campo y no hay caso de discrepancia
 * entre lo que dice el cliente y el dueño del recurso.</p>
 *
 * <p>La validación es solo de forma: {@code @NotNull} convierte un cuerpo sin
 * {@code consultorioId} en un {@code 400} en lugar de un {@code 404} por un identificador
 * nulo. Que el consultorio exista, y que sea de la organización del token, lo decide el
 * servicio contra la base: el schema no puede saberlo y un {@code 404} temprano confirmaría
 * que el identificador llegó al menos bien formado.</p>
 *
 * @param consultorioId consultorio que se asigna al médico (obligatorio)
 */
public record AsignacionConsultorioRequest(

        @Schema(description = "Identificador del consultorio a asignar al médico; debe existir en la "
                + "organización del token, si no se responde 404.",
                example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
        @NotNull
        UUID consultorioId) {
}
