package com.intoglobe.clinicas.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.intoglobe.clinicas.model.RolUsuario;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representación de salida de una cuenta de usuario.
 *
 * <p>Es plana: la organización y el médico se exponen únicamente como identificadores, nunca
 * como entidades anidadas. Así la serialización no depende de asociaciones perezosas —que
 * podrían fallar fuera de la transacción— y no se filtra el estado de la organización ni los
 * datos personales del médico.</p>
 *
 * <p><strong>No expone {@code authUserId}.</strong> Es un identificador del servicio de
 * identidad, no un dato de negocio de la organización, y no hace falta para operar la cuenta;
 * publicarlo solo ofrecería un dato con el que intentar suplantar a la persona en otro sistema.
 * Para depurar existe la consulta SQL documentada en el módulo.</p>
 *
 * <p>El correo se devuelve tal como está almacenado, es decir normalizado a minúsculas, para
 * que la respuesta no sugiera que conviven dos formas del mismo correo.</p>
 *
 * <p><strong>El nombre y el teléfono viajan en la respuesta (S3)</strong> para que la lista del
 * personal muestre a la persona sin una segunda consulta. Los dos son <em>nulables</em> y el contrato
 * lo dice: las cuentas anteriores a la V10 no tienen ninguno de los dos, y el teléfono es opcional en
 * el alta. Un {@code nombre} nulo no se rellena con nada —la pantalla muestra el correo como dato
 * principal— porque un nombre inventado a partir de la dirección es peor que el dato ausente.</p>
 *
 * @param id             identificador único asignado por el sistema
 * @param organizacionId identificador de la organización dueña de la cuenta
 * @param medicoId       identificador del médico asociado, o {@code null} si la cuenta no atiende
 * @param nombre         nombre de la persona, o {@code null} en las cuentas anteriores a la V10
 * @param telefono       teléfono de contacto, o {@code null} si no se registró
 * @param email          correo de la cuenta, normalizado a minúsculas
 * @param rol            rol de la cuenta
 * @param activo         {@code true} si la cuenta está activa, {@code false} si fue dada de baja
 * @param createdAt      fecha y hora de alta asignada por el sistema
 */
public record UsuarioResponse(

        @Schema(description = "Identificador único de la cuenta.",
                example = "b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13")
        UUID id,

        @Schema(description = "Identificador de la organización dueña de la cuenta.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID organizacionId,

        @Schema(description = "Identificador del médico asociado; null si la cuenta no atiende.",
                example = "5b2e9c1a-7d34-4f80-9a2b-6c1d9e0f3a41")
        UUID medicoId,

        @Schema(description = "Nombre de la persona; null en las cuentas anteriores a la V10. La "
                + "pantalla muestra el correo cuando no hay nombre, sin inventarlo.",
                example = "Administración")
        String nombre,

        @Schema(description = "Teléfono de contacto de la persona; null si no se registró (es opcional).",
                example = "+52 55 5555 9876")
        String telefono,

        @Schema(description = "Correo de la cuenta, normalizado a minúsculas.",
                example = "laura.mendez@example.com")
        String email,

        @Schema(description = "Rol de la cuenta.",
                example = "MEDICO",
                allowableValues = {"ADMIN", "MEDICO", "PACIENTE", "RECEPCIONISTA"})
        RolUsuario rol,

        @Schema(description = "Indica si la cuenta está activa (true) o dada de baja lógica (false).",
                example = "true")
        boolean activo,

        @Schema(description = "Fecha y hora de alta del registro, en la zona del servidor.",
                example = "2025-01-15T10:30:00-06:00")
        OffsetDateTime createdAt) {
}
