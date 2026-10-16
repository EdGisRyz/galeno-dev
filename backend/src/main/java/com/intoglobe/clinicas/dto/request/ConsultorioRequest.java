package com.intoglobe.clinicas.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar un consultorio.
 *
 * <p>Deliberadamente no incluye la organización. La pertenencia de un consultorio
 * es inmutable y estructural: llega por el path del {@code POST}
 * ({@code /api/v1/organizaciones/{organizacionId}/consultorios}) y el {@code PUT}
 * no la recibe en absoluto. Como el cuerpo no puede expresar un cambio de
 * organización, no existe caso de discrepancia que rechazar y un único DTO sirve
 * para ambas operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad.</p>
 *
 * @param nombre    nombre del consultorio (obligatorio, 1..120 caracteres)
 * @param direccion dirección del consultorio (opcional; la columna es {@code TEXT}
 *                  sin límite declarado, por eso no se impone un tamaño)
 * @param telefono  teléfono del consultorio (opcional, máx. 20 caracteres)
 */
public record ConsultorioRequest(

        @Schema(description = "Nombre con el que se identifica el consultorio dentro de la organización.",
                example = "Consultorio Centro")
        @NotBlank
        @Size(min = 1, max = 120)
        String nombre,

        @Schema(description = "Dirección física del consultorio; null si no se registró. Sin límite de longitud declarado.",
                example = "Av. Insurgentes Sur 1234, Col. Del Valle, CDMX")
        String direccion,

        @Schema(description = "Teléfono de contacto del consultorio; null si no se registró, máximo 20 caracteres.",
                example = "+52 55 5555 1234")
        @Size(max = 20)
        String telefono) {
}
