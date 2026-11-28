// Hooks TanStack Query del dominio Usuario.
//
// `useUsuariosDirectorio` combina las cuentas y los médicos de la organización en una sola
// lectura (igual que el `loadData` que reemplaza): el listado de médicos alimenta el selector
// «Médico asociado» del formulario. Las mutaciones escriben la respuesta del backend directamente
// en la caché del directorio, sin una relectura extra. `retry: false`: un fallo se explica con un
// mensaje y un botón "Reintentar" explícito, nunca con reintentos automáticos silenciosos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { resumenDeErrorHttp } from '@/lib/apiError';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Usuario } from './usuarios.types';
import { usuariosService, type UsuarioPayload } from './usuarios.service';

export interface DirectorioUsuarios {
  usuarios: Usuario[];
  medicos: Medico[];
}

const directorioKey = (rutaColeccion: string | null, rutaMedicos: string | null) =>
  ['usuarios', 'directorio', rutaColeccion, rutaMedicos] as const;

/** Directorio de cuentas y médicos de la organización. */
export function useUsuariosDirectorio(rutaColeccion: string | null, rutaMedicos: string | null) {
  return useQuery({
    queryKey: directorioKey(rutaColeccion, rutaMedicos),
    queryFn: async (): Promise<DirectorioUsuarios> => {
      try {
        const [usuarios, medicos] = await Promise.all([
          usuariosService.listarPorOrganizacion(rutaColeccion as string),
          rutaMedicos ? usuariosService.listarMedicosDeOrganizacion(rutaMedicos) : Promise.resolve([]),
        ]);
        return { usuarios, medicos };
      } catch (err) {
        console.error('Error al cargar cuentas:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: rutaColeccion !== null,
    retry: false,
  });
}

/** Alta de cuenta: inserta la respuesta en caché, ordenada por correo (mismo orden que antes). */
export function useCrearUsuario(rutaColeccion: string | null, rutaMedicos: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaMedicos);

  return useMutation({
    mutationFn: (payload: UsuarioPayload) => usuariosService.crear(rutaColeccion as string, payload),
    onSuccess: (creado) => {
      queryClient.setQueryData<DirectorioUsuarios>(queryKey, (previo) =>
        previo
          ? {
              ...previo,
              usuarios: [...previo.usuarios, creado].sort((a, b) => a.email.localeCompare(b.email)),
            }
          : previo,
      );
    },
  });
}

/** Edición de cuenta: reemplaza la fila en caché con la respuesta del backend. */
export function useActualizarUsuario(rutaColeccion: string | null, rutaMedicos: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaMedicos);

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UsuarioPayload }) =>
      usuariosService.actualizar(id, payload),
    onSuccess: (actualizado) => {
      queryClient.setQueryData<DirectorioUsuarios>(queryKey, (previo) =>
        previo
          ? {
              ...previo,
              usuarios: previo.usuarios.map((u) => (u.id === actualizado.id ? actualizado : u)),
            }
          : previo,
      );
    },
  });
}

/** Reactivación: `POST .../activar` responde 204, así que la fila se marca activa a mano. */
export function useActivarUsuario(rutaColeccion: string | null, rutaMedicos: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaMedicos);

  return useMutation({
    mutationFn: (id: string) => usuariosService.activar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<DirectorioUsuarios>(queryKey, (previo) =>
        previo
          ? { ...previo, usuarios: previo.usuarios.map((u) => (u.id === id ? { ...u, activo: true } : u)) }
          : previo,
      );
    },
  });
}

/** Baja lógica: `DELETE /usuarios/{id}` responde 204, así que la fila se marca inactiva a mano.
 *  El backend rechaza con 403 (propia cuenta) o 409 (última cuenta ADMIN activa); la fila
 *  permanece intacta cuando la mutación rechaza, porque `setQueryData` solo corre en `onSuccess`. */
export function useDesactivarUsuario(rutaColeccion: string | null, rutaMedicos: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaMedicos);

  return useMutation({
    mutationFn: (id: string) => usuariosService.desactivar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<DirectorioUsuarios>(queryKey, (previo) =>
        previo
          ? { ...previo, usuarios: previo.usuarios.map((u) => (u.id === id ? { ...u, activo: false } : u)) }
          : previo,
      );
    },
  });
}
