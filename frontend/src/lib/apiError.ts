// Resumen seguro de un error HTTP para registrarlo en consola.
//
// Un `AxiosError` completo lleva `config.headers.Authorization`: registrarlo con
// `console.error(err)` publica el token de acceso en la consola del navegador —y en cualquier
// captura de errores que recoja la consola—, que es exactamente lo que ADR-011 evita al mantenerlo
// en memoria. Aquí solo se extraen método, ruta y estado, que es lo que hace falta para
// diagnosticar. Nunca se devuelve el error, ni el `config`, ni las cabeceras.

export interface ResumenErrorHttp {
  metodo: string;
  ruta: string;
  estado: number | null;
}

/** Proyección mínima y sin secretos de un error de red o de HTTP. */
export function resumenDeErrorHttp(error: unknown): ResumenErrorHttp {
  const posible = error as
    | { config?: { method?: string; url?: string }; response?: { status?: number } }
    | null
    | undefined;

  // Se descarta la cadena de consulta: puede llevar datos clínicos o de búsqueda que tampoco
  // deben acabar en un log.
  const ruta = posible?.config?.url?.split('?')[0];

  return {
    metodo: (posible?.config?.method ?? 'desconocido').toUpperCase(),
    ruta: ruta && ruta.length > 0 ? ruta : 'desconocida',
    estado: posible?.response?.status ?? null,
  };
}
