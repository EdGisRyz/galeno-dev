import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/axios';

// Los mocks solo se activan con el servidor de desarrollo y la variable en 'true'.
beforeEach(() => {
  vi.stubEnv('DEV', true);
  vi.stubEnv('VITE_USE_MOCKS', 'true');
});

afterEach(() => {
  vi.unstubAllEnvs();
});

/**
 * Las reglas del adaptador simulado se escriben sobre `ruta` —la URL **sin** query string— y no
 * sobre `url`. La diferencia se nota solo cuando la petición lleva parámetros: un patrón anclado
 * con `$` no casa con `...?algo=1` y la ruta cae en el 404 genérico, que en la pantalla se ve como
 * «no existe» cuando en realidad el recurso está.
 *
 * Hoy las pantallas no envían parámetros en estas rutas, así que la prueba no describe un fallo
 * visible: fija la convención para que el próximo que agregue un filtro no descubra el problema
 * desde la interfaz.
 */

const MEDICO_ID = 'med-001';
const RUTA_COLECCION = `/medicos/${MEDICO_ID}/consultorios`;

describe('mockAdapter · reglas sobre la ruta sin query string', () => {
  it('encuentra la colección de consultorios del médico aunque la petición lleve parámetros', async () => {
    const { status, data } = await api.get(`${RUTA_COLECCION}?incluir=1`);

    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);
  });

  it('sin parámetros devuelve lo mismo, para que la prueba anterior no dependa de otra regla', async () => {
    const { status, data } = await api.get(RUTA_COLECCION);

    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);
  });
});
