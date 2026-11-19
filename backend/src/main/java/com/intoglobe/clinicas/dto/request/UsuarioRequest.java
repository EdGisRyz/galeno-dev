package com.intoglobe.clinicas.dto.request;

import java.util.UUID;

import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.validation.PatronesDeFormato;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * <p><strong>{@code email} lleva una comprobación estructural de forma, y el correo del paciente
 * no.</strong> Aquí el correo es el <em>identificador de inicio de sesión</em>: una cuenta creada
 * con {@code juan@} nace sin que nadie pueda entrar con ella. Se exige la forma mínima —una parte
 * local, una {@code @}, un dominio y un punto con al menos dos letras después— con
 * {@link PatronesDeFormato#CORREO_CUENTA}, la misma regla que aplica el formulario de cuentas antes
 * de enviar. {@code paciente.email} es un dato de <em>contacto</em> opcional y sigue sin regla de
 * formato (decisión de producto, 2026-11-05): un contacto mal escrito no impide atender a la
 * persona, mientras que una credencial inutilizable deja la cuenta sin función. La decisión
 * original no cambia; lo que se acota es su alcance, porque este módulo heredó el párrafo del
 * módulo de paciente sin considerar que el dato había cambiado de función (decisión de producto,
 * 2026-11-18).</p>
 *
 * <p><strong>Se valida la forma, no la existencia.</strong> Que la dirección exista, o que su parte
 * local respete la longitud que impone una norma, corresponde al servicio de identidad, que es
 * quien autentica; este DTO solo rechaza lo que no puede ser un correo. Por eso no se usa
 * {@code @Email}: su definición cambia entre versiones de Hibernate Validator y no es la regla que
 * el módulo necesita. El patrón acepta mayúsculas a propósito, porque la normalización a
 * minúsculas ocurre <em>después</em> de validar, en {@code Usuario#normalizarEmail(String)}.</p>
 *
 * <p><strong>Los espacios de los extremos se recortan antes de validar.</strong> Un correo pegado
 * con un espacio invisible al final es el mismo correo, y el patrón rechaza los extremos en blanco:
 * sin recorte, quien pegaba un correo válido leía un mensaje sobre su forma y reescribía un dato
 * correcto. El constructor compacto aplica {@link Usuario#recortarEmail(String)} —la misma función
 * que la entidad usa al guardar y el servicio al buscar—, de modo que Bean Validation ve el valor
 * recortado y el cuerpo que llega al servicio y a la base ya no tiene esos espacios.</p>
 *
 * @param email    correo de la cuenta (obligatorio, 1..180 caracteres, con forma
 *                 {@code algo@dominio.mx}); se recortan los espacios de los extremos y se guarda
 *                 normalizado a minúsculas
 * @param rol      rol de la cuenta (obligatorio, uno de {@link RolUsuario})
 * @param medicoId médico asociado (opcional; obligatorio cuando el rol es {@code MEDICO})
 */
public record UsuarioRequest(

        @Schema(description = "Correo de la cuenta (obligatorio, 1 a 180 caracteres, con forma "
                + "algo@dominio.mx; los espacios de los extremos se recortan). Se guarda "
                + "normalizado a minúsculas y es único dentro de la organización. Es el "
                + "identificador de inicio de sesión: se valida su forma, no su existencia.",
                example = "laura.mendez@example.com")
        @NotBlank
        @Size(min = 1, max = 180)
        // Estructural, no la RFC completa. La existencia la comprueba el servicio de identidad.
        @Pattern(regexp = PatronesDeFormato.CORREO_CUENTA)
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

    /**
     * Recorta los espacios de los extremos del correo <strong>antes</strong> de que Bean Validation
     * aplique {@link PatronesDeFormato#CORREO_CUENTA}.
     *
     * <p>Los registros se validan sobre el objeto ya construido, así que un constructor compacto es
     * el único punto que corre antes del patrón sin duplicar la regla: {@code @Pattern} verá el
     * valor recortado y no rechazará un correo que solo traía un espacio invisible de más. La
     * función es la de la entidad —{@link Usuario#recortarEmail(String)}— para que el recorte del
     * borde, el del guardado y el de la búsqueda de duplicados sean el mismo.</p>
     */
    public UsuarioRequest {
        email = Usuario.recortarEmail(email);
    }
}
