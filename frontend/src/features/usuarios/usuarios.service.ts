// Servicio HTTP del dominio Usuario (cuentas del personal de la organización).
//
// Habla con el backend (`api`, `VITE_API_URL`) y adjunta el Bearer de la sesión por el
// interceptor de `lib/axios`. La colección de cuentas cuelga de la organización
// (`/organizaciones/{id}/usuarios`) y el ítem es plano (`/usuarios/{id}`), igual que el contrato
// del backend (ver `UsuarioController`). El listado de médicos es el mismo que alimenta el
// selector «Médico asociado» del formulario.

import { api } from '@/lib/axios';
import type { Usuario, RolUsuario } from './usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';

/** Cuerpo que acepta tanto el alta como la edición de una cuenta (mismo DTO en el backend). */
export interface UsuarioPayload {
  email: string;
  rol: RolUsuario;
  medicoId: string | null;
  nombre: string;
  telefono: string | null;
}

export const usuariosService = {
  /** `GET /organizaciones/{id}/usuarios`. */
  listarPorOrganizacion: async (rutaColeccion: string): Promise<Usuario[]> => {
    const { data } = await api.get<Usuario[]>(rutaColeccion);
    return data;
  },

  /** `GET /organizaciones/{id}/medicos`: para el selector «Médico asociado» del formulario. */
  listarMedicosDeOrganizacion: async (rutaMedicos: string): Promise<Medico[]> => {
    const { data } = await api.get<Medico[]>(rutaMedicos);
    return data;
  },

  /** `POST /organizaciones/{id}/usuarios`. */
  crear: async (rutaColeccion: string, payload: UsuarioPayload): Promise<Usuario> => {
    const { data } = await api.post<Usuario>(rutaColeccion, payload);
    return data;
  },

  /** `PUT /usuarios/{id}`. */
  actualizar: async (id: string, payload: UsuarioPayload): Promise<Usuario> => {
    const { data } = await api.put<Usuario>(`/usuarios/${id}`, payload);
    return data;
  },

  /** `DELETE /usuarios/{id}`: baja lógica, responde 204 sin cuerpo. El backend rechaza con 403
   *  una baja sobre la propia cuenta y con 409 la de la última cuenta ADMIN activa. */
  desactivar: async (id: string): Promise<void> => {
    await api.delete(`/usuarios/${id}`);
  },

  /** `POST /usuarios/{id}/activar`: reactivación, responde 204 sin cuerpo. */
  activar: async (id: string): Promise<void> => {
    await api.post(`/usuarios/${id}/activar`);
  },
};
