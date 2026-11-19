package com.intoglobe.clinicas.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import com.intoglobe.clinicas.validation.PatronesDeFormato;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * <p><strong>Formato.</strong> El teléfono usa la regla compartida de
 * {@link PatronesDeFormato}, la misma de médico y paciente: entre 7 y 20 caracteres con dígitos, un
 * {@code +} inicial opcional, espacios, guiones y paréntesis. El <strong>nombre admite dígitos si
 * incluye al menos una letra</strong> —{@code Consultorio 2}, {@code Sala 3}— porque es un nombre de
 * local, no de persona; {@code 454545} sigue rechazándose. La <strong>dirección queda libre</strong>
 * y solo se le aplica el límite de la columna: una dirección mezcla números y letras
 * —{@code Av. Juárez 210}, {@code Calle 5 de Mayo 12-B}— y el alfabeto del nombre rechazaría la
 * mayoría de las reales.</p>
 *
 * <p>Distinguir una dirección real de una cadena de seis dígitos <strong>no</strong> es trabajo de
 * una expresión regular: {@code 454545} no es inválido por tener dígitos, es inválido porque no es
 * una dirección, y comprobarlo exigiría un servicio de direcciones. Queda como deuda declarada, no
 * como un patrón que finja resolverlo.</p>
 *
 * @param nombre    nombre del consultorio (obligatorio, 1..120 caracteres; admite dígitos si
 *                  incluye al menos una letra)
 * @param direccion dirección del consultorio (opcional y libre; la columna es {@code TEXT}
 *                  sin límite declarado, por eso no se impone un tamaño)
 * @param telefono  teléfono del consultorio (opcional, 7..20 caracteres con dígitos, {@code +}
 *                  inicial opcional, espacios, guiones y paréntesis)
 */
public record ConsultorioRequest(

        @Schema(description = "Nombre con el que se identifica el consultorio dentro de la organización. "
                + "Letras, números, espacios, punto, guion y apóstrofo; debe incluir al menos una letra.",
                example = "Consultorio Centro")
        @NotBlank
        @Size(min = 1, max = 120)
        // Admite dígitos si hay al menos una letra: `Consultorio 2` es un nombre real, `454545` no.
        // El blanco lo rechaza @NotBlank, que es quien tiene el mensaje propio.
        @Pattern(regexp = PatronesDeFormato.NOMBRE_DE_LOCAL)
        String nombre,

        @Schema(description = "Dirección física del consultorio; null o vacía si no se registró. "
                + "Campo libre: acepta números y letras. Sin límite de longitud declarado.",
                example = "Av. Insurgentes Sur 1234, Col. Del Valle, CDMX")
        // Libre a propósito: aquí NO se aplica el patrón de texto sin dígitos, por la misma razón
        // que el domicilio del paciente. Validar que una dirección sea una dirección exige un
        // servicio de direcciones, no una expresión regular; queda fuera de alcance.
        String direccion,

        @Schema(description = "Teléfono de contacto del consultorio; null o vacío si no se registró. "
                + "De 7 a 20 caracteres: dígitos, un + inicial opcional, espacios, guiones y paréntesis.",
                example = "+52 55 5555 1234")
        // El máximo de 20 vive dentro del patrón compartido: declarar además @Size produciría dos
        // mensajes para el mismo dato.
        @Pattern(regexp = PatronesDeFormato.TELEFONO)
        String telefono) {
}
