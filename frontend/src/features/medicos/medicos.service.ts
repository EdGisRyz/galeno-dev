// Servicio HTTP del dominio Médico.
//
// Habla con el backend (`api`, `VITE_API_URL`) y adjunta el Bearer de la sesión por el
// interceptor de `lib/axios`. Las rutas de colección cuelgan de la organización
// (`/organizaciones/{id}/medicos`) y las de ítem son planas (`/medicos/{id}`), igual que el
// contrato del backend (ver `MedicoController`). Los horarios y los consultorios asignados
// cuelgan del médico (`/medicos/{id}/horarios`, `/medicos/{id}/consultorios`).

import { api } from '@/lib/axios';
import type { Medico, HorarioMedico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';

/** Cuerpo que acepta tanto el alta como la edición de un médico (mismo DTO en el backend). */
export interface MedicoPayload {
  consultorioId: string;
  nombre: string;
  especialidad: string | null;
  cedula: string | null;
  telefono: string | null;
  tarifaBase: number | null;
}

/** Cuerpo de un bloque de disponibilidad. `consultorioId` es opcional: sin él, el alta usa el
 *  principal del médico y la edición conserva el consultorio actual del bloque. */
export interface HorarioPayload {
  diaSemana: number;
  horaInicio: string;
  horaFin: string;
  duracionConsulta: number;
  consultorioId?: string;
}

export const medicosService = {
  /** `GET /organizaciones/{id}/medicos?incluirInactivos=true`: incluye las bajas (S8). */
  listarPorOrganizacion: async (rutaColeccion: string): Promise<Medico[]> => {
    const { data } = await api.get<Medico[]>(`${rutaColeccion}?incluirInactivos=true`);
    return data;
  },

  /** `GET /organizaciones/{id}/consultorios?incluirInactivos=true`: con las bajas (S12), para
   *  nombrar el consultorio de un médico aunque esté dado de baja. */
  listarConsultoriosDeOrganizacion: async (rutaConsultorios: string): Promise<Consultorio[]> => {
    const { data } = await api.get<Consultorio[]>(`${rutaConsultorios}?incluirInactivos=true`);
    return data;
  },

  /** `POST /organizaciones/{id}/medicos`. */
  crear: async (rutaColeccion: string, payload: MedicoPayload): Promise<Medico> => {
    const { data } = await api.post<Medico>(rutaColeccion, payload);
    return data;
  },

  /** `PUT /medicos/{id}`. El consultorio no se puede cambiar por esta vía (R13). */
  actualizar: async (id: string, payload: MedicoPayload): Promise<Medico> => {
    const { data } = await api.put<Medico>(`/medicos/${id}`, payload);
    return data;
  },

  /** `DELETE /medicos/{id}`: baja lógica, responde 204 sin cuerpo. */
  desactivar: async (id: string): Promise<void> => {
    await api.delete(`/medicos/${id}`);
  },

  /** `POST /medicos/{id}/activar`: reactivación, responde 204 sin cuerpo (S4, S7). */
  activar: async (id: string): Promise<void> => {
    await api.post(`/medicos/${id}/activar`);
  },

  /** `GET /medicos/{id}/horarios`. */
  listarHorarios: async (medicoId: string): Promise<HorarioMedico[]> => {
    const { data } = await api.get<HorarioMedico[]>(`/medicos/${medicoId}/horarios`);
    return data;
  },

  /** `POST /medicos/{id}/horarios`. */
  crearHorario: async (medicoId: string, payload: HorarioPayload): Promise<HorarioMedico> => {
    const { data } = await api.post<HorarioMedico>(`/medicos/${medicoId}/horarios`, payload);
    return data;
  },

  /** `PUT /horarios/{id}`. */
  actualizarHorario: async (id: string, payload: HorarioPayload): Promise<HorarioMedico> => {
    const { data } = await api.put<HorarioMedico>(`/horarios/${id}`, payload);
    return data;
  },

  /** `DELETE /horarios/{id}`: borrado físico, `horario_medico` no tiene baja lógica. */
  eliminarHorario: async (id: string): Promise<void> => {
    await api.delete(`/horarios/${id}`);
  },

  /** `GET /medicos/{id}/consultorios`: conjunto de consultorios asignados al médico. */
  listarConsultoriosAsignados: async (medicoId: string): Promise<Consultorio[]> => {
    const { data } = await api.get<Consultorio[]>(`/medicos/${medicoId}/consultorios`);
    return data;
  },

  /** `POST /medicos/{id}/consultorios`: asigna un consultorio, responde 204 sin cuerpo. */
  asignarConsultorio: async (medicoId: string, consultorioId: string): Promise<void> => {
    await api.post(`/medicos/${medicoId}/consultorios`, { consultorioId });
  },

  /** `DELETE /medicos/{id}/consultorios/{consultorioId}`: responde 409 si es el principal o
   *  todavía tiene bloques (el mensaje del backend explica cuál de las dos reglas lo impide). */
  quitarConsultorio: async (medicoId: string, consultorioId: string): Promise<void> => {
    await api.delete(`/medicos/${medicoId}/consultorios/${consultorioId}`);
  },
};
