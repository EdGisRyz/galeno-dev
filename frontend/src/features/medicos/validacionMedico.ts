// Reglas de formato de los datos del médico (S1 a S4 del pedido de validación).
//
// Las expresiones y los mensajes no se escriben aquí: son los compartidos de
// `@/lib/patronesDeFormato`, que tienen su contraparte Java en
// `backend/.../validation/PatronesDeFormato.java`. Este módulo solo decide **qué campo** usa
// **qué regla** y con qué límite, que es lo propio de la ficha del médico.
//
// Los patrones aceptan deliberadamente los datos que ya están guardados —`Dra. Elena Ruiz`,
// `Medicina general`, cédula `1234567`, teléfono `9511111111`—: una regla nueva impide guardar
// datos mal formados, no corregir los que ya existen (S8).

import {
  MENSAJE_NOMBRE_OBLIGATORIO,
  errorDeCedulaProfesional,
  errorDeTelefono,
  errorDeTextoSinDigitos,
} from '@/lib/patronesDeFormato';

export const LONGITUD_MAXIMA_NOMBRE = 120;
export const LONGITUD_MAXIMA_ESPECIALIDAD = 80;

/** Los campos del médico con regla de formato, tal como los entrega el formulario (texto). */
export interface CamposDeMedico {
  nombre: string;
  especialidad: string;
  cedula: string;
  telefono: string;
}

export type CampoDeMedico = keyof CamposDeMedico;

/** Mensaje por campo; la ausencia de clave significa que el campo cumple las reglas. */
export type ErroresDeMedico = Partial<Record<CampoDeMedico, string>>;

/**
 * Aplica las reglas de formato y devuelve el mensaje de cada campo que no las cumple.
 *
 * Los opcionales vacíos no producen error: un campo en blanco es «no registrado», igual que en el
 * contrato del backend.
 */
export function erroresDeMedico(campos: CamposDeMedico): ErroresDeMedico {
  const errores: ErroresDeMedico = {};

  if (campos.nombre.trim() === '') {
    errores.nombre = MENSAJE_NOMBRE_OBLIGATORIO;
  } else {
    const error = errorDeTextoSinDigitos('El nombre', campos.nombre, LONGITUD_MAXIMA_NOMBRE);
    if (error) errores.nombre = error;
  }

  const errorEspecialidad = errorDeTextoSinDigitos(
    'La especialidad',
    campos.especialidad,
    LONGITUD_MAXIMA_ESPECIALIDAD,
  );
  if (errorEspecialidad) errores.especialidad = errorEspecialidad;

  const errorCedula = errorDeCedulaProfesional(campos.cedula);
  if (errorCedula) errores.cedula = errorCedula;

  const errorTelefono = errorDeTelefono('El teléfono', campos.telefono);
  if (errorTelefono) errores.telefono = errorTelefono;

  return errores;
}
