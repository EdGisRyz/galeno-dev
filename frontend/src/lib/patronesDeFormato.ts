// Patrones y mensajes de formato compartidos por los formularios de médico, paciente y consultorio.
//
// **Espejo del backend:** `backend/src/main/java/com/intoglobe/clinicas/validation/PatronesDeFormato.java`.
// Son dos definiciones —una por lenguaje— y no una copia por módulo: `validacionMedico`,
// `validacionPaciente` y `validacionConsultorio` reutilizan estas. Los dos archivos tienen que
// cambiar juntos; una regla que solo se ajuste en un lado deja al formulario aceptando lo que la
// API rechaza (o al revés), y el desajuste no rompe ninguna compilación.

/** Texto sin dígitos: letras (con acentos y eñe), espacios, punto, guion y apóstrofo. */
export const PATRON_TEXTO_SIN_DIGITOS = /^[\p{L}\p{M} .,'-]*$/u;

/**
 * Nombre de un local —hoy solo el consultorio—: admite dígitos, pero exige al menos una letra.
 *
 * `Consultorio 2` y `Sala 3` son nombres reales y el patrón de las personas los rechazaba; `454545`,
 * `123` y `---` no son nombres. Se aplica **solo** al nombre del consultorio: en el nombre de un
 * médico o de un paciente un número no es parte del nombre. La alternativa de solo espacios deja el
 * blanco a `@NotBlank`, que tiene su mensaje propio.
 */
export const PATRON_NOMBRE_DE_CONSULTORIO = /^(?:| *|(?=.*\p{L})[\p{L}\p{M} .,'\-0-9]*)$/u;

/**
 * Teléfono: dígitos con un `+` inicial opcional, espacios, guiones y paréntesis; de 7 a 20 caracteres
 * en total. La primera anticipación acota el largo para que el máximo incluya el `+`; la segunda
 * exige al menos un dígito, para que siete paréntesis o guiones no cuenten como teléfono. La cadena
 * vacía se acepta: el teléfono es opcional en los tres formularios. Los separadores son literales,
 * no `\s`, porque la clase de espacios de JavaScript incluye espacios Unicode que la de Java no y el
 * espejo tiene que aceptar exactamente lo mismo.
 */
export const PATRON_TELEFONO = /^(?:|(?=.{7,20}$)(?=.*[0-9])\+?[0-9(][0-9() -]*)$/;

/** Cédula profesional: solo dígitos, entre 5 y 10. Vacía se acepta (es opcional). */
export const PATRON_CEDULA_PROFESIONAL = /^(?:|\d{5,10})$/;

/** Etiqueta del campo vacío que es obligatorio: el mismo texto en los tres módulos. */
export const MENSAJE_NOMBRE_OBLIGATORIO = 'El nombre es obligatorio.';

/** La cédula no tiene equivalente en los otros formularios, pero vive aquí con las demás reglas. */
export const MENSAJE_CEDULA_PROFESIONAL =
  'La cédula profesional debe tener entre 5 y 10 dígitos, sin letras ni símbolos.';

/** Mensaje del campo de texto libre de dígitos, con la etiqueta del campo al frente. */
export function mensajeDeTextoSinDigitos(etiqueta: string): string {
  return `${etiqueta} solo admite letras, espacios, punto, guion y apóstrofo; no admite dígitos.`;
}

/** Mensaje del nombre de un local, que admite números si hay letras. */
export function mensajeDeNombreDeLocal(etiqueta: string): string {
  return `${etiqueta} solo admite letras, números, espacios, punto, guion y apóstrofo, y debe incluir al menos una letra.`;
}

/** Mensaje del teléfono, con la etiqueta del campo al frente. */
export function mensajeDeTelefono(etiqueta: string): string {
  return `${etiqueta} debe tener entre 7 y 20 caracteres: dígitos, un + inicial opcional, espacios y guiones.`;
}

/** Mensaje del límite de longitud, con la etiqueta del campo y el máximo. */
export function mensajeDeLongitudMaxima(etiqueta: string, maximo: number): string {
  return `${etiqueta} no puede superar los ${maximo} caracteres.`;
}

/**
 * Error de un campo de texto sin dígitos, o `undefined` si cumple. La cadena vacía no es un error:
 * los campos opcionales en blanco son «no registrado», igual que en el contrato del backend.
 */
export function errorDeTextoSinDigitos(
  etiqueta: string,
  valor: string,
  maximo?: number,
): string | undefined {
  if (maximo !== undefined && valor.length > maximo) {
    return mensajeDeLongitudMaxima(etiqueta, maximo);
  }
  return PATRON_TEXTO_SIN_DIGITOS.test(valor) ? undefined : mensajeDeTextoSinDigitos(etiqueta);
}

/** Error de un teléfono, o `undefined` si cumple. La cadena vacía se acepta (es opcional). */
export function errorDeTelefono(etiqueta: string, valor: string): string | undefined {
  return PATRON_TELEFONO.test(valor) ? undefined : mensajeDeTelefono(etiqueta);
}

/**
 * Error del nombre de un local (consultorio), o `undefined` si cumple: admite números siempre que
 * haya al menos una letra. La cadena vacía y el blanco se dejan pasar aquí; el campo es obligatorio
 * y quien lo rechaza es la comprobación de obligatoriedad de la pantalla.
 */
export function errorDeNombreDeLocal(
  etiqueta: string,
  valor: string,
  maximo?: number,
): string | undefined {
  if (maximo !== undefined && valor.length > maximo) {
    return mensajeDeLongitudMaxima(etiqueta, maximo);
  }
  return PATRON_NOMBRE_DE_CONSULTORIO.test(valor) ? undefined : mensajeDeNombreDeLocal(etiqueta);
}

/** Error de una cédula profesional, o `undefined` si cumple. La cadena vacía se acepta. */
export function errorDeCedulaProfesional(valor: string): string | undefined {
  return PATRON_CEDULA_PROFESIONAL.test(valor) ? undefined : MENSAJE_CEDULA_PROFESIONAL;
}
