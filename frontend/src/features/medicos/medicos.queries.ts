// Hooks TanStack Query del dominio Médico.
//
// `useMedicosDirectorio` combina médicos y consultorios de la organización en una sola lectura
// (igual que el `loadData` que reemplaza): las dos piden las bajas incluidas (S8, S12). Las
// mutaciones de médico actualizan la caché del directorio directamente con la respuesta del
// backend, sin una relectura extra: `medicos.reactivar.test.tsx` fija que reactivar no vuelve a
// pedir el listado. Las de consultorios asignados sí invalidan su propia lectura: el `POST`/`DELETE`
// responde 204 sin cuerpo y la forma canónica —ordenada por el backend— solo la da el `GET`.
//
// `retry: false` en todas las lecturas: un fallo se explica con un mensaje y un botón "Reintentar"
// explícito (o se reintenta solo al reabrir el panel), nunca con reintentos automáticos silenciosos.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { resumenDeErrorHttp } from '@/lib/apiError';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import type { HorarioMedico, Medico } from './medicos.types';
import { medicosService, type HorarioPayload, type MedicoPayload } from './medicos.service';

export interface DirectorioMedicos {
  medicos: Medico[];
  consultorios: Consultorio[];
}

const directorioKey = (rutaColeccion: string | null, rutaConsultorios: string | null) =>
  ['medicos', 'directorio', rutaColeccion, rutaConsultorios] as const;

const horariosKey = (medicoId: string | null) => ['medicos', 'horarios', medicoId] as const;

const asignadosKey = (medicoId: string | null) =>
  ['medicos', 'consultorios-asignados', medicoId] as const;

/** Directorio de médicos y consultorios de la organización. */
export function useMedicosDirectorio(rutaColeccion: string | null, rutaConsultorios: string | null) {
  return useQuery({
    queryKey: directorioKey(rutaColeccion, rutaConsultorios),
    queryFn: async (): Promise<DirectorioMedicos> => {
      try {
        const [medicos, consultorios] = await Promise.all([
          medicosService.listarPorOrganizacion(rutaColeccion as string),
          rutaConsultorios
            ? medicosService.listarConsultoriosDeOrganizacion(rutaConsultorios)
            : Promise.resolve([]),
        ]);
        return { medicos, consultorios };
      } catch (err) {
        console.error('Error al cargar médicos:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: rutaColeccion !== null,
    retry: false,
  });
}

/** Alta de médico: inserta la respuesta al frente del directorio en caché. */
export function useCrearMedico(rutaColeccion: string | null, rutaConsultorios: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaConsultorios);

  return useMutation({
    mutationFn: (payload: MedicoPayload) =>
      medicosService.crear(rutaColeccion as string, payload),
    onSuccess: (creado) => {
      queryClient.setQueryData<DirectorioMedicos>(queryKey, (previo) =>
        previo ? { ...previo, medicos: [creado, ...previo.medicos] } : previo,
      );
    },
  });
}

/** Edición de médico: reemplaza la fila en caché con la respuesta del backend. */
export function useActualizarMedico(rutaColeccion: string | null, rutaConsultorios: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaConsultorios);

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: MedicoPayload }) =>
      medicosService.actualizar(id, payload),
    onSuccess: (actualizado) => {
      queryClient.setQueryData<DirectorioMedicos>(queryKey, (previo) =>
        previo
          ? {
              ...previo,
              medicos: previo.medicos.map((m) => (m.id === actualizado.id ? actualizado : m)),
            }
          : previo,
      );
    },
  });
}

/** Reactivación (S4, S7): `POST .../activar` responde 204, así que la fila se marca activa a mano. */
export function useActivarMedico(rutaColeccion: string | null, rutaConsultorios: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaConsultorios);

  return useMutation({
    mutationFn: (id: string) => medicosService.activar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<DirectorioMedicos>(queryKey, (previo) =>
        previo
          ? { ...previo, medicos: previo.medicos.map((m) => (m.id === id ? { ...m, activo: true } : m)) }
          : previo,
      );
    },
  });
}

/** Baja lógica: `DELETE /medicos/{id}` responde 204, así que la fila se marca inactiva a mano. */
export function useDesactivarMedico(rutaColeccion: string | null, rutaConsultorios: string | null) {
  const queryClient = useQueryClient();
  const queryKey = directorioKey(rutaColeccion, rutaConsultorios);

  return useMutation({
    mutationFn: (id: string) => medicosService.desactivar(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<DirectorioMedicos>(queryKey, (previo) =>
        previo
          ? { ...previo, medicos: previo.medicos.map((m) => (m.id === id ? { ...m, activo: false } : m)) }
          : previo,
      );
    },
  });
}

/** Bloques de disponibilidad del médico del panel (bajo demanda: solo con el panel abierto). */
export function useHorarios(medicoId: string | null) {
  return useQuery({
    queryKey: horariosKey(medicoId),
    queryFn: async () => {
      try {
        return await medicosService.listarHorarios(medicoId as string);
      } catch (err) {
        console.error('Error al cargar horarios:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: medicoId !== null,
    retry: false,
  });
}

/** Alta de bloque: inserta y reordena por día/hora, igual que el listado del backend. */
export function useCrearHorario(medicoId: string | null) {
  const queryClient = useQueryClient();
  const queryKey = horariosKey(medicoId);

  return useMutation({
    mutationFn: (payload: HorarioPayload) => medicosService.crearHorario(medicoId as string, payload),
    onSuccess: (creado) => {
      queryClient.setQueryData<HorarioMedico[]>(queryKey, (previo) =>
        [...(previo ?? []), creado].sort(
          (a, b) => a.diaSemana - b.diaSemana || a.horaInicio.localeCompare(b.horaInicio),
        ),
      );
    },
  });
}

/** Edición de bloque. */
export function useActualizarHorario(medicoId: string | null) {
  const queryClient = useQueryClient();
  const queryKey = horariosKey(medicoId);

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: HorarioPayload }) =>
      medicosService.actualizarHorario(id, payload),
    onSuccess: (actualizado) => {
      queryClient.setQueryData<HorarioMedico[]>(queryKey, (previo) =>
        previo?.map((h) => (h.id === actualizado.id ? actualizado : h)),
      );
    },
  });
}

/** Borrado físico de bloque: `horario_medico` no tiene baja lógica. */
export function useEliminarHorario(medicoId: string | null) {
  const queryClient = useQueryClient();
  const queryKey = horariosKey(medicoId);

  return useMutation({
    mutationFn: (id: string) => medicosService.eliminarHorario(id),
    onSuccess: (_sinCuerpo, id) => {
      queryClient.setQueryData<HorarioMedico[]>(queryKey, (previo) => previo?.filter((h) => h.id !== id));
    },
  });
}

/** Consultorios asignados al médico del panel (bajo demanda, solo donde la lectura está permitida). */
export function useConsultoriosAsignados(medicoId: string | null, habilitado: boolean) {
  return useQuery({
    queryKey: asignadosKey(medicoId),
    queryFn: async () => {
      try {
        return await medicosService.listarConsultoriosAsignados(medicoId as string);
      } catch (err) {
        console.error('Error al cargar consultorios asignados:', resumenDeErrorHttp(err));
        throw err;
      }
    },
    enabled: medicoId !== null && habilitado,
    retry: false,
  });
}

/** Asigna un consultorio. El `POST` responde 204 sin cuerpo: se relee el listado del backend. */
export function useAsignarConsultorio(medicoId: string | null) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (consultorioId: string) =>
      medicosService.asignarConsultorio(medicoId as string, consultorioId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: asignadosKey(medicoId) });
    },
  });
}

/** Quita un consultorio. El `DELETE` responde 204 sin cuerpo: se relee el listado del backend. */
export function useQuitarConsultorio(medicoId: string | null) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (consultorioId: string) =>
      medicosService.quitarConsultorio(medicoId as string, consultorioId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: asignadosKey(medicoId) });
    },
  });
}
