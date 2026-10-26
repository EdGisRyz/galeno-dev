package com.intoglobe.clinicas.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de un médico.
 *
 * <p>Es plano: expone la organización y el consultorio únicamente como sus
 * identificadores, nunca como entidades anidadas. Así la serialización no depende de
 * una asociación perezosa (que podría fallar fuera de la transacción) y no se filtra
 * el estado completo de la organización del tenant ni del consultorio.</p>
 *
 * <p>No incluye {@code createdAt}: la tabla {@code medico} de V1 no declara esa
 * columna, e inventarla en el DTO prometería un dato que el registro no tiene. Cuando
 * el V2 agrupe la migración, agregar el campo será aditivo y no romperá el contrato.
 * Incluye en cambio la bandera de baja lógica {@code activo}, que sí existe en V1.</p>
 *
 * @param id             identificador único asignado por el sistema
 * @param organizacionId identificador de la organización dueña del médico
 * @param consultorioId  identificador del consultorio donde atiende
 * @param nombre         nombre completo del médico
 * @param especialidad   especialidad médica o {@code null} si no se registró
 * @param cedula         cédula profesional o {@code null} si no se registró
 * @param telefono       teléfono de contacto o {@code null} si no se registró
 * @param tarifaBase     tarifa base de la consulta o {@code null} si no se registró
 * @param activo         {@code true} si está activo, {@code false} si fue dado de baja
 */
public record MedicoResponse(

        @Schema(description = "Identificador único del médico.",
                example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
        UUID id,

        @Schema(description = "Identificador de la organización dueña del médico.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID organizacionId,

        @Schema(description = "Identificador del consultorio donde atiende el médico.",
                example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
        UUID consultorioId,

        @Schema(description = "Nombre completo del médico.",
                example = "Dra. Ana Torres")
        String nombre,

        @Schema(description = "Especialidad médica del doctor; null si no se registró.",
                example = "Cardiología")
        String especialidad,

        @Schema(description = "Cédula profesional del médico; null si no se registró.",
                example = "12345678")
        String cedula,

        @Schema(description = "Teléfono de contacto del médico; null si no se registró.",
                example = "+52 55 5555 9876")
        String telefono,

        @Schema(description = "Tarifa base de la consulta; null si no se registró.",
                example = "500.00")
        BigDecimal tarifaBase,

        @Schema(description = "Indica si el médico está activo (true) o dado de baja lógica (false).",
                example = "true")
        boolean activo) {
}
