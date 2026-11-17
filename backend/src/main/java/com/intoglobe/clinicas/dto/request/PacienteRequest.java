package com.intoglobe.clinicas.dto.request;

import java.time.LocalDate;

import com.intoglobe.clinicas.model.Paciente;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar un paciente.
 *
 * <p>Deliberadamente no incluye la organización. La pertenencia de un paciente es
 * inmutable y estructural —es el límite de tenant—: llega por el path del
 * {@code POST} ({@code /api/v1/organizaciones/{organizacionId}/pacientes}) y el
 * {@code PUT} no la recibe en absoluto. Como el cuerpo no puede expresar un cambio
 * de organización, no existe caso de discrepancia que rechazar y un único DTO sirve
 * para ambas operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p>Tampoco incluye {@code activo} ni {@code createdAt}: la baja y la reactivación
 * son operaciones propias —{@code DELETE /pacientes/{id}} para la baja lógica y
 * {@code POST /pacientes/{id}/activar} para la reactivación, ambas idempotentes y sin
 * cuerpo— y la fecha de alta es un dato que asigna el sistema. La columna {@code created_at}
 * es {@code updatable = false} en la entidad, así que un cliente no puede
 * sobrescribirla ni enviándola en el cuerpo de la actualización.</p>
 *
 * <p><strong>{@code email} no lleva {@code @Email} a propósito.</strong> La columna
 * {@code paciente.email} de V1 es {@code VARCHAR(180)} sin CHECK ni formato
 * declarado: agregar aquí una validación de formato sería inventar una regla que la
 * base no tiene y rechazar datos que hoy sí se almacenan. Es una decisión declarada,
 * no un olvido.</p>
 *
 * <p><strong>{@code notas} no declara longitud</strong> porque la columna es
 * {@code TEXT} en V1 y no fija límite. Poner un máximo aquí sería inventar una
 * restricción ausente en el schema.</p>
 *
 * <p><strong>La CURP es opcional y viaja plana.</strong> Un paciente puede registrarse sin ella
 * —urgencia, menor, extranjero— y eso es normal, así que no lleva {@code @NotBlank}. Cuando viene,
 * su estructura se valida aquí; cuando viene vacía se acepta y se guarda como {@code null}, para
 * que dos pacientes sin CURP no compitan por el mismo hueco en la restricción única de la base.</p>
 *
 * <p><strong>La CURP llega ya canónica a la validación.</strong> El constructor compacto la recorta
 * y la pasa a mayúsculas delegando en {@link Paciente#normalizarCurp(String)}, que es la única
 * definición de «la misma CURP» del módulo, y convierte el vacío en {@code null}. De ahí que el
 * {@code @Pattern} exija la forma canónica y a la vez acepte minúsculas: cuando el cuerpo trae
 * {@code sasc850101mdfrrn01}, lo que se valida es {@code SASC850101MDFRRN01}.</p>
 *
 * <p><strong>La validación de la CURP es estructural, no autoritativa.</strong> El
 * {@code @Pattern} comprueba 18 posiciones con la forma declarada —cuatro letras, seis dígitos,
 * sexo, cinco letras, homoclave y dígito de control—, que es lo que este borde puede afirmar sin
 * salir de la petición. Comprobar que la CURP existe en el registro nacional, o que su dígito de
 * control cierra, queda fuera de alcance: aquí solo se rechaza lo que no puede ser una CURP.</p>
 *
 * <p>El resto de los campos opcionales no se normaliza: llegan al mapper tal como el cliente los
 * envió, porque el contrato de actualización es un reemplazo y una cadena vacía significa «campo
 * presente y vacío», no «campo ausente». Solo la CURP se canoniza, porque de ella depende la
 * unicidad.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad.</p>
 *
 * @param nombre          nombre del paciente (obligatorio, 1..160 caracteres)
 * @param telefono        teléfono de contacto (opcional, máx. 20 caracteres)
 * @param fechaNacimiento fecha de nacimiento (opcional, {@code DATE} sin hora ni zona)
 * @param sexo            sexo registrado (opcional, uno de {@code M}, {@code F}, {@code O})
 * @param email           correo electrónico (opcional, máx. 180 caracteres, sin
 *                        validación de formato)
 * @param notas           notas libres (opcional, columna {@code TEXT})
 * @param curp            CURP del paciente (opcional, 18 posiciones; se guarda canónica)
 * @param ocupacion       ocupación del paciente (opcional, máx. 120 caracteres)
 * @param domicilio       domicilio del paciente (opcional, máx. 240 caracteres)
 * @param nombreResponsable nombre del responsable (opcional, máx. 160 caracteres)
 * @param telefonoResponsable teléfono del responsable (opcional, máx. 20 caracteres)
 * @param parentesco      parentesco del responsable con el paciente (opcional, máx. 40 caracteres)
 */
public record PacienteRequest(

        @Schema(description = "Nombre completo del paciente (obligatorio, 1 a 160 caracteres).",
                example = "María López Ramírez")
        @NotBlank
        @Size(min = 1, max = 160)
        String nombre,

        @Schema(description = "Teléfono de contacto del paciente; null si no se registró, máximo 20 caracteres.",
                example = "+52 55 5555 4321")
        @Size(max = 20)
        String telefono,

        @Schema(description = "Fecha de nacimiento del paciente; null si no se registró.",
                example = "1985-03-14")
        LocalDate fechaNacimiento,

        @Schema(description = "Sexo registrado: M, F u O; null si no se registró.",
                example = "F",
                allowableValues = {"M", "F", "O"})
        @Size(max = 1)
        @Pattern(regexp = "^[MFO]$")
        String sexo,

        @Schema(description = "Correo electrónico del paciente; null si no se registró, máximo 180 caracteres.",
                example = "maria.lopez@example.com")
        @Size(max = 180)
        String email,

        @Schema(description = "Notas libres sobre el paciente; null si no se registraron. La columna es TEXT, sin límite.",
                example = "Alergia a la penicilina")
        String notas,

        @Schema(description = "CURP del paciente; null o vacía si no se registró. Opcional, única "
                + "dentro de la organización y se guarda en mayúsculas.",
                example = "SASC850101MDFRRN01")
        @Size(max = 18)
        // Acepta vacío (la CURP es opcional) y acepta minúsculas (el constructor compacto ya
        // canonizó el valor); cuando hay valor exige las 18 posiciones de una CURP: cuatro
        // letras, seis dígitos de la fecha, sexo, cinco letras, homoclave alfanumérica y dígito
        // de control. Es estructural: la validación autoritativa es del registro nacional.
        @Pattern(regexp = "^$|^(?i)[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$")
        String curp,

        @Schema(description = "Ocupación del paciente; null si no se registró, máximo 120 caracteres.",
                example = "Comerciante")
        @Size(max = 120)
        String ocupacion,

        @Schema(description = "Domicilio del paciente; null si no se registró, máximo 240 caracteres.",
                example = "Av. Reforma 123, Col. Centro")
        @Size(max = 240)
        String domicilio,

        @Schema(description = "Nombre del responsable del paciente; null si no se registró, máximo 160 caracteres.",
                example = "Rosa Ramírez Silva")
        @Size(max = 160)
        String nombreResponsable,

        @Schema(description = "Teléfono del responsable; null si no se registró, máximo 20 caracteres.",
                example = "+52 55 5555 8765")
        @Size(max = 20)
        String telefonoResponsable,

        @Schema(description = "Parentesco del responsable con el paciente; null si no se registró, máximo 40 caracteres.",
                example = "Madre")
        @Size(max = 40)
        String parentesco) {

    /**
     * Canoniza la CURP en el borde de la API, antes de que Bean Validation la mire.
     *
     * <p>Se delega en {@link Paciente#normalizarCurp(String)} para que la forma canónica tenga una
     * sola definición: recorta, pasa a mayúsculas y convierte el vacío en {@code null}. Así
     * {@code "  sasc850101mdfrrn01 "} y {@code "SASC850101MDFRRN01"} son la misma CURP, y una CURP
     * en blanco es «no se registró» en lugar de una cadena vacía que competiría consigo misma en
     * la restricción única de la base.</p>
     *
     * <p>Es idempotente respecto del mapper y de la entidad, que vuelven a aplicarla: las tres
     * capas comparten la misma función, así que no pueden discrepar.</p>
     */
    public PacienteRequest {
        curp = Paciente.normalizarCurp(curp);
    }
}
