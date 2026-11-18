// Reglas de formato de la ficha del paciente (S1 a S7 del pedido de validación).
//
// Las expresiones y los mensajes son los compartidos de `@/lib/patronesDeFormato` (espejo Java en
// `backend/.../validation/PatronesDeFormato.java`); aquí solo se decide qué campo usa qué regla.
//
// El **domicilio no tiene regla de caracteres** (S4): una dirección lleva número de casa
// —`Av. Juárez 210`— y aplicarle el alfabeto del nombre rechazaría la mitad de las reales. Solo se
// limita su longitud, que es lo que declara la columna.

import {
  MENSAJE_NOMBRE_OBLIGATORIO,
  errorDeTelefono,
  errorDeTextoSinDigitos,
} from '@/lib/patronesDeFormato';

export const LONGITUD_MAXIMA_NOMBRE = 160;
export const LONGITUD_MAXIMA_OCUPACION = 120;
export const LONGITUD_MAXIMA_DOMICILIO = 240;
export const LONGITUD_MAXIMA_NOMBRE_RESPONSABLE = 160;
export const LONGITUD_MAXIMA_PARENTESCO = 40;

/**
 * Sugerencias del parentesco (S6). Son un `datalist`, no un `select`: quien escribe puede ignorarlas
 * y poner «amigo», «vecino» o «institución».
 *
 * Van en singular y sin la forma «hermano/a» porque la barra no pertenece al conjunto de caracteres
 * que la regla acepta: ofrecer un valor que la propia pantalla rechazaría sería peor que ofrecer
 * las dos formas.
 */
export const SUGERENCIAS_DE_PARENTESCO = [
  'Madre',
  'Padre',
  'Tutor',
  'Hermano',
  'Hermana',
  'Abuelo',
  'Abuela',
  'Tío',
  'Tía',
  'Cónyuge',
  'Hijo',
  'Hija',
];

/** Los campos de la ficha con regla propia, tal como los entrega el formulario (texto). */
export interface CamposDePaciente {
  nombre: string;
  telefono: string;
  ocupacion: string;
  domicilio: string;
  nombreResponsable: string;
  telefonoResponsable: string;
  parentesco: string;
}

export type CampoDePaciente = keyof CamposDePaciente;

/** Mensaje por campo; la ausencia de clave significa que el campo cumple las reglas. */
export type ErroresDePaciente = Partial<Record<CampoDePaciente, string>>;

/**
 * Regla de dos campos (S5): si se informa un parentesco, el nombre del responsable es obligatorio.
 *
 * Cada campo por separado es válido —`parentesco = "amigo"` es un valor real y el responsable es
 * opcional— y aun así la ficha no describe nada: dice qué es esa persona del paciente sin decir
 * quién es. El mensaje se muestra junto al campo que falta, que es el que hay que completar.
 */
export const MENSAJE_RESPONSABLE_OBLIGATORIO =
  'Si registras un parentesco, el nombre del responsable es obligatorio.';

/**
 * Aplica las reglas de formato y devuelve el mensaje de cada campo que no las cumple.
 *
 * Los opcionales vacíos no producen error: un campo en blanco es «no registrado», igual que en el
 * contrato del backend. El parentesco no se compara con ninguna lista: las sugerencias de la
 * pantalla son una ayuda, no un catálogo cerrado («amigo», «vecino» son casos reales).
 *
 * La única regla que cruza dos campos es la del parentesco con su responsable (S5): los dos en
 * blanco son el caso normal y no producen error; el parentesco informado sin responsable sí.
 */
export function erroresDePaciente(campos: CamposDePaciente): ErroresDePaciente {
  const errores: ErroresDePaciente = {};

  if (campos.nombre.trim() === '') {
    errores.nombre = MENSAJE_NOMBRE_OBLIGATORIO;
  } else {
    const error = errorDeTextoSinDigitos('El nombre', campos.nombre, LONGITUD_MAXIMA_NOMBRE);
    if (error) errores.nombre = error;
  }

  const errorTelefono = errorDeTelefono('El teléfono', campos.telefono);
  if (errorTelefono) errores.telefono = errorTelefono;

  const errorOcupacion = errorDeTextoSinDigitos(
    'La ocupación',
    campos.ocupacion,
    LONGITUD_MAXIMA_OCUPACION,
  );
  if (errorOcupacion) errores.ocupacion = errorOcupacion;

  // El domicilio solo se limita en longitud: su contenido es libre a propósito (S4).
  if (campos.domicilio.length > LONGITUD_MAXIMA_DOMICILIO) {
    errores.domicilio = `El domicilio no puede superar los ${LONGITUD_MAXIMA_DOMICILIO} caracteres.`;
  }

  const errorNombreResponsable = errorDeTextoSinDigitos(
    'El nombre del responsable',
    campos.nombreResponsable,
    LONGITUD_MAXIMA_NOMBRE_RESPONSABLE,
  );
  if (errorNombreResponsable) errores.nombreResponsable = errorNombreResponsable;

  // S5: el parentesco sin responsable no se envía. Va después de la regla de formato porque el
  // campo vacío no produce error propio: lo que falta no es un formato, es el dato completo.
  if (campos.parentesco.trim() !== '' && campos.nombreResponsable.trim() === '') {
    errores.nombreResponsable = MENSAJE_RESPONSABLE_OBLIGATORIO;
  }

  const errorTelefonoResponsable = errorDeTelefono(
    'El teléfono del responsable',
    campos.telefonoResponsable,
  );
  if (errorTelefonoResponsable) errores.telefonoResponsable = errorTelefonoResponsable;

  const errorParentesco = errorDeTextoSinDigitos(
    'El parentesco',
    campos.parentesco,
    LONGITUD_MAXIMA_PARENTESCO,
  );
  if (errorParentesco) errores.parentesco = errorParentesco;

  return errores;
}
