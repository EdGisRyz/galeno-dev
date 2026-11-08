// Esquema Zod del registro público de clínicas (onboarding).
//
// Sin contrato real todavía: `POST /api/v1/organizaciones` del backend está bloqueado a
// propósito (requiere un token ya autenticado y aun así responde 403 hasta que exista el flujo
// de onboarding de Sprint 2 — ver `OrganizacionController`). Este formulario solo habla con el
// mock (`/onboarding/registro-clinica`) mientras ese contrato no exista.

import { z } from 'zod';

// Los límites viajan como texto (lo que entrega un <input>) y se validan con una expresión
// numérica propia en vez de `z.coerce.number()`: el coerce cambia el tipo de entrada a salida
// del esquema, y ese desajuste rompe la inferencia genérica de `zodResolver` con react-hook-form.
// Convertir a número queda para el envío (`Number(values.maxMedicos)`), ya validado.
const numeroEntreUnoY = (max: number) =>
  z
    .string()
    .min(1, 'Requerido')
    .refine((valor) => {
      const n = Number(valor);
      return Number.isInteger(n) && n >= 1 && n <= max;
    }, `Debe ser un número entero entre 1 y ${max}`);

export const registroClinicaSchema = z
  .object({
    nombreClinica: z.string().min(2, 'Ingresa el nombre de la clínica').max(120),
    // Sin "ENTERPRISE": ese plan implica multi-sede, fuera del alcance actual del anteproyecto
    // (sección 2.5.2). Sale de un flujo de ventas asistido, no de este formulario público.
    plan: z.enum(['SOLO', 'CLINICA']),
    maxMedicos: numeroEntreUnoY(100),
    maxConsultorios: numeroEntreUnoY(50),
    nombreAdmin: z.string().min(2, 'Ingresa tu nombre completo').max(120),
    correoAdmin: z.string().email('Correo inválido'),
    password: z.string().min(6, 'Mínimo 6 caracteres'),
    confirmarPassword: z.string(),
  })
  .refine((datos) => datos.password === datos.confirmarPassword, {
    message: 'Las contraseñas no coinciden',
    path: ['confirmarPassword'],
  });

export type RegistroClinicaValues = z.infer<typeof registroClinicaSchema>;
