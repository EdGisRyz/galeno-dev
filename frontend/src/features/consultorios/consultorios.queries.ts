// Hooks TanStack Query del dominio Consultorio.
//
// Las mutaciones escriben la respuesta del backend directamente en la caché del directorio, sin
// una relectura extra: `ConsultoriosPage.reactivar.test.tsx` fija que reactivar no vuelve a pedir
// el listado. `retry: false`: un fallo se explica con un mensaje y un botón "Reintentar" explícito,
// nunca con reintentos automáticos silenciosos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { resumenDeErrorHttp } from '@/lib/apiError';
import type { Consultorio } from './consultorios.types';
import { consultoriosService, type ConsultorioPayload } from './consultorios.service';

const directorioKey = (rutaColeccion: string | null) =>
  ['consultorios', 'directorio', rutaColeccion] as const;

/** Directorio de consultorios de la organización. */
export function useConsultoriosDirectorio(rutaColeccion: string | null) {
  return useQuery({
    queryKey: directorioKey(rutaColeccion),
    queryFn: async (): Promise<Consultorio[]> => {
      try {
        return await consultoriosService.listarPorOrganizacion(rutaColeccion as string);
      } catch (err) {
        console.error('Error al cargar consultorios:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: rutaColeccion !== null,
    retry: false,
  });
}

/** Alta: inserta la respuesta al frente del directorio en caché. */
export function useCrearConsultorio(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (payload: ConsultorioPayload) =>
      consultoriosService.crear(rutaColeccion as string, payload),
    onSuccess: (creado) => {
      queryClient.setQueryData<Consultorio[]>(queryKey, (previo) =>
        previo ? [creado, ...previo] : previo,
      );
    },
  });
}

/** Edición: reemplaza la fila en caché con la respuesta del backend. */
export function useActualizarConsultorio(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: ConsultorioPayload }) =>
      consultoriosService.actualizar(id, payload),
    onSuccess: (actualizado) => {
      queryClient.setQueryData<Consultorio[]>(queryKey, (previo) =>
        previo?.map((c) => (c.id === actualizado.id ? actualizado : c)),
      );
    },
  });
}

/** Reactivación (S4): `POST .../activar` responde 204, así que la fila se marca activa a mano. */
export function useActivarConsultorio(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (id: string) => consultoriosService.activar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<Consultorio[]>(queryKey, (previo) =>
        previo?.map((c) => (c.id === id ? { ...c, activo: true } : c)),
      );
    },
  });
}

/** Baja lógica (S6): `DELETE /consultorios/{id}` responde 204, la fila se marca inactiva a mano. */
export function useDesactivarConsultorio(rutaColeccion: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion);

  return useMutation({
    mutationFn: (id: string) => consultoriosService.desactivar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<Consultorio[]>(queryKey, (previo) =>
        previo?.map((c) => (c.id === id ? { ...c, activo: false } : c)),
      );
    },
  });
}
