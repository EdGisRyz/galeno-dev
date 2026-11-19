// Esquema Zod del registro público de clínicas (onboarding).
//
// El contrato es el del backend real desde esta tarea: `POST /api/v1/onboarding/registro-clinica`
// crea la organización y su primer administrador en una sola transacción, y
// `GET /api/v1/onboarding/planes` publica las etiquetas y los límites de cada plan.
//
// Los campos que este esquema **no** declara, y por qué:
//   - `maxMedicos` y `maxConsultorios`: los define el plan y la pantalla los muestra en solo lectura.
//     El cliente no elige sus propios límites y no los envía.
//   - `password` y `confirmarPassword`: se piden y se validan entre sí en el cliente, pero no viajan
//     al servidor. La base no guarda contraseñas y el emisor de tokens usa una por entorno
//     (`DEV_AUTH_PASSWORD`); después de registrar, la pantalla dice con qué entrar.

import { z } from 'zod';
import {
  MENSAJE_CORREO_DE_CUENTA,
  errorDeCorreoDeCuenta,
  errorDeNombreDeLocal,
  errorDeTextoSinDigitos,
  mensajeDeNombreDeLocal,
  mensajeDeTextoSinDigitos,
} from '@/lib/patronesDeFormato';

/** Longitud de `organizacion.nombre` y de `usuario.nombre`. */
const LONGITUD_MAXIMA_NOMBRE = 120;

/** Los planes del conjunto cerrado del esquema; las etiquetas llegan del catálogo público. */
export const PLANES = ['SOLO', 'CLINICA', 'ENTERPRISE'] as const;

/** Un plan tal como lo publica `GET /onboarding/planes`. */
export interface PlanDisponible {
  plan: (typeof PLANES)[number];
  etiqueta: string;
  /** `null` significa «sin tope», no un límite enorme. */
  maxMedicos: number | null;
  maxConsultorios: number | null;
}

const nombreDeLocal = z
  .string()
  .min(1, 'Ingresa el nombre de la clínica.')
  .refine((valor) => errorDeNombreDeLocal('El nombre de la clínica', valor, LONGITUD_MAXIMA_NOMBRE) === undefined, {
    message: mensajeDeNombreDeLocal('El nombre de la clínica'),
  });

const nombreDePersona = z
  .string()
  .min(1, 'Ingresa el nombre de la persona que administrará la clínica.')
  .refine(
    (valor) => errorDeTextoSinDigitos('El nombre del administrador', valor, LONGITUD_MAXIMA_NOMBRE) === undefined,
    { message: mensajeDeTextoSinDigitos('El nombre del administrador') },
  );

const correoDeCuenta = z
  .string()
  .min(1, 'Ingresa el correo de la cuenta administradora.')
  .refine((valor) => errorDeCorreoDeCuenta(valor) === undefined, { message: MENSAJE_CORREO_DE_CUENTA });

export const registroClinicaSchema = z
  .object({
    nombreClinica: nombreDeLocal,
    plan: z.enum(PLANES),
    nombreAdmin: nombreDePersona,
    correoAdmin: correoDeCuenta,
    // Solo para la pantalla: no forman parte del cuerpo que se envía.
    password: z.string().min(6, 'Mínimo 6 caracteres.'),
    confirmarPassword: z.string(),
  })
  .refine((datos) => datos.password === datos.confirmarPassword, {
    message: 'Las contraseñas no coinciden.',
    path: ['confirmarPassword'],
  });

export type RegistroClinicaValues = z.infer<typeof registroClinicaSchema>;

/**
 * Cuerpo que viaja al backend: solo los cuatro campos del contrato.
 *
 * Es una función y no un `omit` del esquema a propósito: así el cuerpo enviado se puede leer de un
 * vistazo y una prueba puede afirmar que las contraseñas no están.
 *
 * @param valores datos ya validados del formulario
 * @returns cuerpo del `POST /onboarding/registro-clinica`
 */
export function cuerpoDeRegistro(valores: RegistroClinicaValues) {
  return {
    nombreClinica: valores.nombreClinica.trim(),
    plan: valores.plan,
    nombreAdmin: valores.nombreAdmin.trim(),
    correoAdmin: valores.correoAdmin.trim(),
  };
}
