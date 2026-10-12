package com.intoglobe.clinicas.dto.response;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo único de error para todas las respuestas 4xx/5xx de la API.
 *
 * <p>Es el contrato de error de referencia para los módulos 1-10: los handlers
 * traducen sus excepciones a esta forma, sin exponer stacktraces ni detalles
 * internos del parser.</p>
 *
 * @param marcaTemporal fecha y hora en que se generó el error
 * @param codigo        código de estado HTTP
 * @param error         nombre corto del estado HTTP
 * @param mensaje       mensaje descriptivo y seguro para el cliente
 * @param ruta          ruta de la petición que originó el error
 */
public record ErrorResponse(

        @Schema(description = "Fecha y hora en que se generó el error.",
                example = "2026-01-15T09:30:00-05:00")
        OffsetDateTime marcaTemporal,

        @Schema(description = "Código de estado HTTP del error.",
                example = "404")
        int codigo,

        @Schema(description = "Nombre corto del estado HTTP.",
                example = "Not Found")
        String error,

        @Schema(description = "Mensaje descriptivo del error, sin detalles internos.",
                example = "No se encontró el recurso Organizacion con id: 3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        String mensaje,

        @Schema(description = "Ruta de la petición que originó el error.",
                example = "/api/v1/organizaciones/3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        String ruta) {
}
