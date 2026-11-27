// Servicio HTTP del dominio Consultorio.
//
// Habla con el backend (`api`, `VITE_API_URL`) y adjunta el Bearer de la sesión por el
// interceptor de `lib/axios`. La ruta de colección cuelga de la organización
// (`/organizaciones/{id}/consultorios`) y las de ítem son planas (`/consultorios/{id}`), igual que
// el contrato del backend (ver `ConsultorioController`).

import { api } from '@/lib/axios';
import type { Consultorio } from './consultorios.types';

/** Cuerpo que acepta tanto el alta como la edición de un consultorio (mismo DTO en el backend). */
export interface ConsultorioPayload {
  nombre: string;
  direccion: string | null;
  telefono: string | null;
}

export const consultoriosService = {
  /** `GET /organizaciones/{id}/consultorios?incluirInactivos=true`: incluye las bajas (S8). */
  listarPorOrganizacion: async (rutaColeccion: string): Promise<Consultorio[]> => {
    const { data } = await api.get<Consultorio[]>(`${rutaColeccion}?incluirInactivos=true`);
    return data;
  },

  /** `POST /organizaciones/{id}/consultorios`. */
  crear: async (rutaColeccion: string, payload: ConsultorioPayload): Promise<Consultorio> => {
    const { data } = await api.post<Consultorio>(rutaColeccion, payload);
    return data;
  },

  /** `PUT /consultorios/{id}`. La organización dueña no se envía ni se puede cambiar. */
  actualizar: async (id: string, payload: ConsultorioPayload): Promise<Consultorio> => {
    const { data } = await api.put<Consultorio>(`/consultorios/${id}`, payload);
    return data;
  },

  /** `DELETE /consultorios/{id}`: baja lógica, responde 204 sin cuerpo (S6). */
  desactivar: async (id: string): Promise<void> => {
    await api.delete(`/consultorios/${id}`);
  },

  /** `POST /consultorios/{id}/activar`: reactivación, responde 204 sin cuerpo (S4). */
  activar: async (id: string): Promise<void> => {
    await api.post(`/consultorios/${id}/activar`);
  },
};
