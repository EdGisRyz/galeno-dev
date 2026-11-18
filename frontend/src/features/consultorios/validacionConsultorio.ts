// Reglas de formato de los datos del consultorio (S1 y S2 del pedido de validación).
//
// Las expresiones y los mensajes son los compartidos de `@/lib/patronesDeFormato` (espejo Java en
// `backend/.../validation/PatronesDeFormato.java`); aquí solo se decide qué campo usa qué regla.
//
// El **nombre admite dígitos si incluye al menos una letra** (`Consultorio 2`, `Sala 3` son nombres
// reales), a diferencia del nombre de un médico o de un paciente, donde un número no es parte del
// nombre.
//
// La **dirección no tiene regla de caracteres** (S3): `Calle 5 de Mayo 12-B` es una dirección y
// `454545` no lo es, pero distinguirlas exige un servicio de direcciones y no una expresión
// regular. Queda fuera de alcance: la dirección solo se limita en longitud.

import {
  MENSAJE_NOMBRE_OBLIGATORIO,
  errorDeNombreDeLocal,
  errorDeTelefono,
} from '@/lib/patronesDeFormato';

export const LONGITUD_MAXIMA_NOMBRE = 120;

/** Los campos del consultorio con regla propia, tal como los entrega el formulario (texto). */
export interface CamposDeConsultorio {
  nombre: string;
  telefono: string;
}

export type CampoDeConsultorio = keyof CamposDeConsultorio;

/** Mensaje por campo; la ausencia de clave significa que el campo cumple las reglas. */
export type ErroresDeConsultorio = Partial<Record<CampoDeConsultorio, string>>;

/**
 * Aplica las reglas de formato y devuelve el mensaje de cada campo que no las cumple.
 *
 * El teléfono vacío no produce error: es opcional en el contrato. La dirección no se valida por
 * caracteres, así que aquí no aparece.
 */
export function erroresDeConsultorio(campos: CamposDeConsultorio): ErroresDeConsultorio {
  const errores: ErroresDeConsultorio = {};

  if (campos.nombre.trim() === '') {
    errores.nombre = MENSAJE_NOMBRE_OBLIGATORIO;
  } else {
    // El nombre de un local admite números si hay al menos una letra: `Consultorio 2` es válido.
    const error = errorDeNombreDeLocal('El nombre', campos.nombre, LONGITUD_MAXIMA_NOMBRE);
    if (error) errores.nombre = error;
  }

  const errorTelefono = errorDeTelefono('El teléfono', campos.telefono);
  if (errorTelefono) errores.telefono = errorTelefono;

  return errores;
}
