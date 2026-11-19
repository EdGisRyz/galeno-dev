package com.intoglobe.clinicas.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta del alta pública de una clínica (S2).
 *
 * <p>Es exactamente lo que el formulario de la landing ya esperaba: el identificador de la
 * organización recién creada y su nombre. <strong>No lleva ningún dato de autenticación</strong> —ni
 * correo de la cuenta, ni rol, ni {@code auth_user_id}, ni contraseña—: la pantalla ya sabe con qué
 * correo se registró la persona y se lo recuerda junto a la contraseña del entorno, así que
 * devolverlo aquí solo ampliaría la superficie de un endpoint público.</p>
 *
 * @param organizacionId identificador de la organización creada
 * @param nombreClinica  nombre de la organización, tal como se guardó
 */
public record RegistroResponse(

        @Schema(description = "Identificador único de la organización creada.",
                example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
        UUID organizacionId,

        @Schema(description = "Nombre de la organización tal como se guardó.",
                example = "Clínica San Rafael")
        String nombreClinica) {
}
