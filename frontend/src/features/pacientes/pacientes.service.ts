// Servicio HTTP del dominio Paciente.
//
// Habla con el backend (`api`, `VITE_API_URL`) y adjunta el Bearer de la sesión por el
// interceptor de `lib/axios`. La colección cuelga de la organización
// (`/organizaciones/{id}/pacientes`) y el ítem es plano (`/pacientes/{id}`), igual que el
// contrato del backend (ver `PacienteController`).

import { api } from '@/lib/axios';
import type { Paciente } from './pacientes.types';

/** Cuerpo que acepta tanto el alta como la edición de la ficha (mismo DTO en el backend). Los
 *  seis campos de la ficha de identificación (NOM-004) van planos, al mismo nivel que el resto. */
export interface PacientePayload {
  nombre: string;
  telefono: string | null;
  fechaNacimiento: string | null;
  sexo: 'M' | 'F' | 'O' | null;
  email: string | null;
  notas: string | null;
  curp: string | null;
  ocupacion: string | null;
  domicilio: string | null;
  nombreResponsable: string | null;
  telefonoResponsable: string | null;
  parentesco: string | null;
}

export const pacientesService = {
  /** `GET /organizaciones/{id}/pacientes?incluirInactivos=true`: incluye las bajas, porque es
   *  lógica y sin verlas el usuario solo podría volver a dar de alta al paciente. */
  listarPorOrganizacion: async (rutaColeccion: string): Promise<Paciente[]> => {
    const { data } = await api.get<Paciente[]>(`${rutaColeccion}?incluirInactivos=true`);
    return data;
  },

  /** `POST /organizaciones/{id}/pacientes`. */
  crear: async (rutaColeccion: string, payload: PacientePayload): Promise<Paciente> => {
    const { data } = await api.post<Paciente>(rutaColeccion, payload);
    return data;
  },

  /** `PUT /pacientes/{id}`. La organización dueña no se envía ni se puede cambiar. */
  actualizar: async (id: string, payload: PacientePayload): Promise<Paciente> => {
    const { data } = await api.put<Paciente>(`/pacientes/${id}`, payload);
    return data;
  },

  /** `DELETE /pacientes/{id}`: baja lógica, responde 204 sin cuerpo. */
  desactivar: async (id: string): Promise<void> => {
    await api.delete(`/pacientes/${id}`);
  },

  /** `POST /pacientes/{id}/activar`: reactivación, responde 204 sin cuerpo. */
  activar: async (id: string): Promise<void> => {
    await api.post(`/pacientes/${id}/activar`);
  },
};
