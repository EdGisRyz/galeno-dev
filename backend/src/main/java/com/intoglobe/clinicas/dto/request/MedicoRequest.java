package com.intoglobe.clinicas.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import com.intoglobe.clinicas.validation.PatronesDeFormato;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de entrada para crear y actualizar un médico.
 *
 * <p>Deliberadamente no incluye la organización. La pertenencia de un médico es
 * inmutable y estructural —es el límite de tenant—: llega por el path del
 * {@code POST} ({@code /api/v1/organizaciones/{organizacionId}/medicos}) y el
 * {@code PUT} no la recibe en absoluto. Como el cuerpo no puede expresar un cambio
 * de organización, no existe caso de discrepancia que rechazar y un único DTO sirve
 * para ambas operaciones, de modo que la validación no diverge entre crear y
 * actualizar.</p>
 *
 * <p>El consultorio, en cambio, sí viaja en el cuerpo: es un atributo del médico,
 * no su dueño. Tiene que estar aquí porque en el alta es un dato obligatorio, y en
 * la actualización su presencia permite rechazar con 400 un cambio de consultorio
 * en vez de responder 200 sin aplicarlo (regla R13).</p>
 *
 * <p>{@code tarifaBase} sigue siendo opcional: {@code @Digits} y {@code @DecimalMin}
 * ignoran {@code null} por diseño, así que la ausencia de valor se acepta y solo se
 * valida el número cuando el cliente lo envía. {@code @Digits(integer = 8,
 * fraction = 2)} espeja la precisión declarada de la columna
 * {@code NUMERIC(10,2)} y convierte un desborde en un 400 en lugar de un error de
 * base de datos; {@code @DecimalMin("0.0")} es una regla de aplicación declarada,
 * no una restricción de V1.</p>
 *
 * <p>Las validaciones Jakarta viven aquí, nunca en la entidad.</p>
 *
 * <p><strong>Reglas de formato (S1 a S4).</strong> El módulo solo validaba longitudes y por eso
 * la base aceptó un médico llamado {@code 52454255}, con cédula {@code ccwcwcc} y teléfono
 * {@code vvvvvuvu}. Estas reglas son de <em>borde</em>: corren en el controller, antes de que el
 * cuerpo llegue al service, así que un valor con formato inválido nunca alcanza el repositorio.</p>
 *
 * <ul>
 *   <li><strong>Nombre (S1):</strong> letras —incluidas las acentuadas y la eñe—, espacios,
 *       punto, guion y apóstrofo. Ningún dígito. La obligatoriedad y el máximo de 120 los siguen
 *       imponiendo {@code @NotBlank} y {@code @Size}.</li>
 *   <li><strong>Especialidad (S2):</strong> el mismo conjunto de caracteres, máximo 80.</li>
 *   <li><strong>Cédula (S3):</strong> solo dígitos, entre 5 y 10.</li>
 *   <li><strong>Teléfono (S4):</strong> dígitos con un {@code +} inicial opcional y espacios o
 *       guiones como separadores, entre 7 y 20 caracteres en total.</li>
 * </ul>
 *
 * <p>Los tres campos opcionales aceptan {@code null} —{@code @Pattern} se salta {@code null} por
 * diseño— y también la cadena vacía, que es lo que un formulario envía cuando el campo se deja en
 * blanco (la pantalla manda {@code null}, pero el contrato no obliga a ello). Por eso sus
 * expresiones incluyen la alternativa vacía. El nombre no la necesita: una cadena vacía ya la
 * rechazan {@code @NotBlank} y {@code @Size}.</p>
 *
 * <p>Los patrones aceptan a propósito los datos que ya están en la base —{@code Dra. Elena Ruiz},
 * {@code Medicina general}, cédula {@code 1234567}, teléfono {@code 9511111111}—: son las formas
 * reales del directorio y ninguna regla nueva puede impedir volver a guardarlas. Un patrón que
 * rechazara un acento o un punto rompería la edición de un médico existente.</p>
 *
 * <p>Las expresiones <strong>no se escriben aquí</strong>: son las de
 * {@link PatronesDeFormato}, compartidas con paciente y consultorio. Una copia por módulo
 * aceptaría en un formulario lo que otra rechaza.</p>
 *
 * @param consultorioId consultorio donde atiende el médico (obligatorio)
 * @param nombre        nombre del médico (obligatorio, 1..120 caracteres, sin dígitos)
 * @param especialidad  especialidad del médico (opcional, máx. 80 caracteres, sin dígitos)
 * @param cedula        cédula profesional (opcional, solo dígitos, 5..10)
 * @param telefono      teléfono de contacto (opcional, 7..20 caracteres, dígitos, {@code +}
 *                      inicial opcional, espacios y guiones)
 * @param tarifaBase    tarifa base de la consulta (opcional, hasta 8 enteros y 2
 *                      decimales, nunca negativa)
 */
public record MedicoRequest(

        @Schema(description = "Identificador del consultorio donde atiende el médico; debe existir, si no se responde 404.",
                example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
        @NotNull
        UUID consultorioId,

        @Schema(description = "Nombre completo del médico (obligatorio, 1 a 120 caracteres: letras, "
                + "espacios, punto, guion y apóstrofo; sin dígitos).",
                example = "Dra. Elena Ruiz")
        @NotBlank
        @Size(min = 1, max = 120)
        // Letras, espacios, punto, guion y apóstrofo, sin dígitos. El blanco lo rechaza @NotBlank.
        @Pattern(regexp = PatronesDeFormato.TEXTO_SIN_DIGITOS)
        String nombre,

        @Schema(description = "Especialidad médica; null si no se registró, máximo 80 caracteres "
                + "(letras, espacios, punto, guion y apóstrofo; sin dígitos).",
                example = "Medicina general")
        @Size(max = 80)
        // Mismo conjunto de caracteres que el nombre, con la alternativa vacía del opcional.
        @Pattern(regexp = PatronesDeFormato.TEXTO_SIN_DIGITOS_OPCIONAL)
        String especialidad,

        @Schema(description = "Cédula profesional del médico; null o vacía si no se registró. Solo "
                + "dígitos, entre 5 y 10.",
                example = "1234567")
        // Solo dígitos y entre 5 y 10: el máximo de la columna (30) es irrelevante porque este
        // patrón ya es más estricto, y declarar los dos produciría dos mensajes para el mismo dato.
        @Pattern(regexp = PatronesDeFormato.CEDULA_PROFESIONAL)
        String cedula,

        @Schema(description = "Teléfono de contacto del médico; null o vacío si no se registró. De 7 "
                + "a 20 caracteres: dígitos, un + inicial opcional, espacios y guiones.",
                example = "+52 55 5555 9876")
        // De 7 a 20 caracteres contando el +: el largo lo acota el patrón compartido.
        @Pattern(regexp = PatronesDeFormato.TELEFONO)
        String telefono,

        @Schema(description = "Tarifa base de la consulta; null si no se registró. Hasta 8 enteros y 2 decimales, nunca negativa.",
                example = "500.00")
        @Digits(integer = 8, fraction = 2)
        @DecimalMin(value = "0.0", inclusive = true)
        BigDecimal tarifaBase) {
}
