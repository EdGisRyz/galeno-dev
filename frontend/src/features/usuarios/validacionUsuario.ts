// Reglas de los datos de la persona en el alta y la edición de una cuenta (S1, S5, S7).
//
// Las expresiones y los mensajes son los compartidos de `@/lib/patronesDeFormato` (espejo Java en
// `backend/.../validation/PatronesDeFormato.java`); aquí solo se decide qué campo usa qué regla. El
// correo de acceso no se valida aquí: su regla es `errorDeCorreoDeCuenta` y la pantalla la aplica
// junto a estas, con el mismo mensaje junto al campo.
//
// El nombre usa la regla de las **personas** —sin dígitos—: en el nombre de alguien que trabaja en la
// clínica un número no es parte del nombre. El teléfono usa la regla compartida de teléfono y es
// **opcional**: en blanco significa «no registrado», igual que en el contrato del backend.

import {
  MENSAJE_NOMBRE_OBLIGATORIO,
  errorDeTelefono,
  errorDeTextoSinDigitos,
} from '@/lib/patronesDeFormato';

/** El mismo máximo que la columna `usuario.nombre` y que el nombre de una organización. */
export const LONGITUD_MAXIMA_NOMBRE = 120;

/** Los campos de la persona tal como los entrega el formulario (texto). */
export interface CamposDeCuenta {
  nombre: string;
  telefono: string;
}

export type CampoDeCuenta = keyof CamposDeCuenta;

/** Mensaje por campo; la ausencia de clave significa que el campo cumple las reglas. */
export type ErroresDeCuenta = Partial<Record<CampoDeCuenta, string>>;

/**
 * Aplica las reglas de la persona y devuelve el mensaje de cada campo que no las cumple.
 *
 * El nombre es **obligatorio** (S1, S5) y el teléfono opcional. El nombre en blanco tiene su propio
 * mensaje —el campo vacío no es un problema de formato— y va antes que la regla de caracteres, para
 * que la respuesta no lleve dos motivos del mismo campo en un orden que no se controla. El teléfono
 * en blanco no produce error: es «no registrado».
 */
export function erroresDeCuenta(campos: CamposDeCuenta): ErroresDeCuenta {
  const errores: ErroresDeCuenta = {};

  if (campos.nombre.trim() === '') {
    errores.nombre = MENSAJE_NOMBRE_OBLIGATORIO;
  } else {
    const errorNombre = errorDeTextoSinDigitos(
      'El nombre',
      campos.nombre,
      LONGITUD_MAXIMA_NOMBRE,
    );
    if (errorNombre) errores.nombre = errorNombre;
  }

  const errorTelefono = errorDeTelefono('El teléfono', campos.telefono);
  if (errorTelefono) errores.telefono = errorTelefono;

  return errores;
}

/**
 * Indica si la cuenta tiene nombre de la persona registrado.
 *
 * Falso en las cuentas anteriores a la V10, que no tienen nombre y no lo reciben inventado: la lista
 * las muestra por correo y el formulario de edición las obliga a completarlo al guardar.
 */
export function tieneNombreDePersona(cuenta: { nombre?: string | null }): boolean {
  return (cuenta.nombre ?? '').trim() !== '';
}

/**
 * Texto que la lista muestra como dato principal de la cuenta (S4).
 *
 * Es el nombre de la persona cuando lo tiene y, si no, el correo: las cuentas anteriores a la V10 no
 * tienen nombre y **no se inventa** a partir de la dirección —`pablo@correo.mx` no se convierte en
 * «Pablo»—. El correo se muestra además como dato secundario cuando hay nombre, porque sigue siendo
 * el identificador con el que la persona inicia sesión.
 */
export function nombrePrincipalDeLaCuenta(cuenta: {
  nombre?: string | null;
  email: string;
}): string {
  const nombre = (cuenta.nombre ?? '').trim();
  return nombre === '' ? cuenta.email : nombre;
}
