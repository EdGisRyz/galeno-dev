// Hooks TanStack Query del dominio Paciente.
//
// Las mutaciones escriben la respuesta del backend directamente en la caché del directorio, sin
// una relectura extra: las pruebas de baja y reactivación fijan que ninguna de las dos vuelve a
// pedir el listado. `retry: false`: un fallo se explica con un mensaje y un botón "Reintentar"
// explícito, nunca con reintentos automáticos silenciosos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { resumenDeErrorHttp } from '@/lib/apiError';
import type { Paciente } from './pacientes.types';
import { pacientesService, type PacientePayload } from './pacientes.service';

const directorioKey = (rutaColeccion: string | null) =>
  ['pacientes', 'directorio', rutaColeccion] as const;

/** Directorio de pacientes de la organización (incluye las bajas). */
export function usePacientesDirectorio(rutaColeccion: string | null) {
  return useQuery({
    queryKey: directorioKey(rutaColeccion),
    queryFn: async (): Promise<Paciente[]> => {
      try {
        return await pacientesService.listarPorOrganizacion(rutaColeccion as string);
      } catch (err) {
        console.error('Error al cargar pacientes:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: rutaColeccion !== null,
    retry: false,
  });
}

/** Alta: inserta la respuesta al frente del directorio en caché. */
export function useCrearPaciente(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (payload: PacientePayload) => pacientesService.crear(rutaColeccion as string, payload),
    onSuccess: (creado) => {
      queryClient.setQueryData<Paciente[]>(queryKey, (previo) => (previo ? [creado, ...previo] : previo));
    },
  });
}

/** Edición de la ficha: reemplaza la fila en caché con la respuesta del backend. */
export function useActualizarPaciente(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: PacientePayload }) =>
      pacientesService.actualizar(id, payload),
    onSuccess: (actualizado) => {
      queryClient.setQueryData<Paciente[]>(queryKey, (previo) =>
        previo?.map((p) => (p.id === actualizado.id ? actualizado : p)),
      );
    },
  });
}

/** Reactivación (S5): `POST .../activar` responde 204, así que la fila se marca activa a mano. */
export function useActivarPaciente(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (id: string) => pacientesService.activar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<Paciente[]>(queryKey, (previo) =>
        previo?.map((p) => (p.id === id ? { ...p, activo: true } : p)),
      );
    },
  });
}

/** Baja lógica: `DELETE /pacientes/{id}` responde 204, así que la fila se marca inactiva a mano. */
export function useDesactivarPaciente(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (id: string) => pacientesService.desactivar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<Paciente[]>(queryKey, (previo) =>
        previo?.map((p) => (p.id === id ? { ...p, activo: false } : p)),
      );
    },
  });
}
