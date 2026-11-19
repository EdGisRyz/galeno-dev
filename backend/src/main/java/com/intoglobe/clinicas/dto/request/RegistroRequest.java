package com.intoglobe.clinicas.dto.request;

import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.validation.PatronesDeFormato;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo del alta pública de una clínica desde la landing (S2, S3, T7).
 *
 * <p>Es el contrato que el formulario ya enviaba, con los campos que la base no puede guardar ya
 * resueltos: {@code maxMedicos} y {@code maxConsultorios} salieron del cuerpo —los define el plan y
 * se consultan en {@code GET /api/v1/onboarding/planes}— y {@code nombreAdmin} se conserva porque su
 * columna existe desde la V10.</p>
 *
 * <p><strong>No declara la contraseña.</strong> Ni {@code password} ni {@code confirmarPassword} son
 * parte de este contrato: la base no almacena contraseñas y el emisor de tokens usa una sola por
 * entorno ({@code DEV_AUTH_PASSWORD}). Si un cliente viejo las sigue enviando, Jackson las descarta;
 * aquí no hay ningún campo que las reciba y, por lo tanto, ningún log que las escriba.</p>
 *
 * <p><strong>El nombre de la clínica usa la regla de los locales</strong>
 * ({@link PatronesDeFormato#NOMBRE_DE_LOCAL}): admite dígitos pero exige al menos una letra, porque
 * {@code Consultorio 2} es un nombre real y {@code 454545} no. <strong>El nombre de la persona usa
 * la regla de las personas</strong> ({@link PatronesDeFormato#TEXTO_SIN_DIGITOS}): en el nombre de
 * una administradora un número no es parte del nombre. Son dos reglas distintas a propósito, y
 * aplicarlas al revés dejaría entrar nombres de personas con dígitos o nombres de clínicas
 * legítimos fuera.</p>
 *
 * @param nombreClinica nombre de la clínica (obligatorio, 1..120, con al menos una letra)
 * @param plan          plan elegido (obligatorio, uno de {@link PlanTipo})
 * @param nombreAdmin   nombre del administrador (obligatorio, 1..120, sin dígitos)
 * @param correoAdmin   correo de la cuenta administradora (obligatorio, 1..180, forma de correo); se
 *                      recorta antes de validar y se guarda en minúsculas
 */
public record RegistroRequest(

        @Schema(description = "Nombre de la clínica tal como se mostrará en la plataforma. Admite "
                + "números si incluye al menos una letra.",
                example = "Clínica San Rafael")
        @NotBlank
        @Size(min = 1, max = 120)
        @Pattern(regexp = PatronesDeFormato.NOMBRE_DE_LOCAL)
        String nombreClinica,

        @Schema(description = "Plan contratado. Se persiste TAL CUAL llega, validado contra el "
                + "conjunto cerrado del esquema: no se reemplaza por una constante. Sus límites se "
                + "consultan en GET /api/v1/onboarding/planes.",
                example = "CLINICA",
                allowableValues = {"SOLO", "CLINICA", "ENTERPRISE"})
        @NotNull
        PlanTipo plan,

        @Schema(description = "Nombre de la persona que administrará la clínica (sin dígitos).",
                example = "Administración")
        @NotBlank
        @Size(min = 1, max = 120)
        @Pattern(regexp = PatronesDeFormato.TEXTO_SIN_DIGITOS)
        String nombreAdmin,

        @Schema(description = "Correo de la cuenta administradora: es el identificador de inicio de "
                + "sesión. Se guarda normalizado a minúsculas y es único dentro de la organización, "
                + "aunque el mismo correo pueda administrar otra clínica.",
                example = "administracion@example.com")
        @NotBlank
        @Size(min = 1, max = 180)
        @Pattern(regexp = PatronesDeFormato.CORREO_CUENTA)
        String correoAdmin) {

    /**
     * Recorta los espacios de los extremos de los nombres y del correo antes de que Bean Validation
     * aplique los patrones.
     *
     * <p>Los registros se validan sobre el objeto ya construido, así que el constructor compacto es
     * el único punto que corre antes. Un nombre pegado desde un documento trae un espacio invisible
     * al final y ese espacio no forma parte del dato; el correo usa
     * {@link Usuario#recortarEmail(String)} —la misma función que el alta de cuentas— para que el
     * borde, el guardado y la búsqueda de duplicados compartan una sola definición de «el mismo
     * correo».</p>
     */
    public RegistroRequest {
        nombreClinica = nombreClinica == null ? null : nombreClinica.strip();
        nombreAdmin = nombreAdmin == null ? null : nombreAdmin.strip();
        correoAdmin = Usuario.recortarEmail(correoAdmin);
    }
}
