// Servicio HTTP del dominio Organización.
//
// Habla con el backend (`api`, `VITE_API_URL`) y adjunta el Bearer de la sesión por el
// interceptor de `lib/axios`. La lectura por id es la celda que ADMIN, MEDICO y RECEPCIONISTA
// comparten para leer el nombre de su propia organización (S3): un id de otro tenant responde 403.

import { api } from '@/lib/axios';
import type { Organizacion } from './organizaciones.types';

export const organizacionService = {
  /** `GET /organizaciones/{id}` → la organización del propio tenant, con su nombre. */
  obtenerPorId: async (id: string): Promise<Organizacion> => {
    const { data } = await api.get<Organizacion>(`/organizaciones/${encodeURIComponent(id)}`);
    return data;
  },
};
