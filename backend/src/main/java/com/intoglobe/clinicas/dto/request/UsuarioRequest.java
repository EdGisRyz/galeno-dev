package com.intoglobe.clinicas.dto.request;

import java.util.UUID;

import com.intoglobe.clinicas.model.RolUsuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar una cuenta de usuario.
 *
 * <p>Deliberadamente no incluye la organización: la pertenencia de una cuenta es el límite de
 * tenant, llega por el path del {@code POST} y el {@code PUT} no la recibe. Como el cuerpo no
 * puede expresar un cambio de organización, no existe discrepancia que rechazar y un único DTO
 * sirve para las dos operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p><strong>No incluye {@code authUserId}.</strong> Es el sujeto del servicio de identidad y
 * lo aporta el servicio: en local se genera un UUID v4 al crear y contra la plataforma real es
 * el {@code sub} del token. Aceptarlo del cliente permitiría a una organización reclamar la
 * identidad de otra persona.</p>
 *
 * <p>Tampoco incluye {@code activo} ni {@code createdAt}: la baja y la reactivación son
 * operaciones propias —{@code DELETE /usuarios/{id}} y {@code POST /usuarios/{id}/activar},
 * ambas idempotentes y sin cuerpo— y la fecha de alta la asigna el sistema.</p>
 *
 * <p><strong>El rol es un {@link RolUsuario}</strong>, espejo del CHECK de la base: un rol
 * fuera del conjunto cerrado no se puede deserializar y la petición responde 400 en el borde,
 * en lugar de llegar al servicio y morir en el CHECK como un 500. {@code @NotNull} cubre el
 * caso del rol ausente.</p>
 *
 * <p><strong>{@code medicoId} no lleva {@code @NotNull} a propósito:</strong> solo es
 * obligatorio cuando el rol es {@code MEDICO}, y una anotación de campo no puede expresar una
 * obligación condicionada al valor de otro campo. Esa comprobación vive en el servicio, que
 * responde 400 con un mensaje propio; la pertenencia del médico a la organización la impone
 * además la clave foránea compuesta de la base.</p>
 *
 * <p><strong>{@code email} no lleva {@code @Email} a propósito.</strong> La columna
 * {@code usuario.email} es {@code VARCHAR(180)} sin CHECK ni formato declarado, igual que
 * {@code paciente.email}: agregar aquí una validación de formato sería inventar una regla que
 * la base no tiene y rechazar datos que hoy se almacenan. Es una decisión declarada, no un
 * olvido.</p>
 *
 * @param email    correo de la cuenta (obligatorio, 1..180 caracteres); se guarda normalizado
 *                 a minúsculas
 * @param rol      rol de la cuenta (obligatorio, uno de {@link RolUsuario})
 * @param medicoId médico asociado (opcional; obligatorio cuando el rol es {@code MEDICO})
 */
public record UsuarioRequest(

        @Schema(description = "Correo de la cuenta (obligatorio, 1 a 180 caracteres). Se guarda "
                + "normalizado a minúsculas y es único dentro de la organización.",
                example = "laura.mendez@example.com")
        @NotBlank
        @Size(min = 1, max = 180)
        String email,

        @Schema(description = "Rol de la cuenta. Solo el conjunto cerrado del esquema.",
                example = "MEDICO",
                allowableValues = {"ADMIN", "MEDICO", "PACIENTE", "RECEPCIONISTA"})
        @NotNull
        RolUsuario rol,

        @Schema(description = "Médico asociado a la cuenta; obligatorio si el rol es MEDICO, "
                + "opcional para los demás roles (por ejemplo, una cuenta ADMIN que además atiende).",
                example = "5b2e9c1a-7d34-4f80-9a2b-6c1d9e0f3a41")
        UUID medicoId) {
}
