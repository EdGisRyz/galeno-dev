import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/axios';

/**
 * Regresión del bug de duplicados del módulo de Pacientes.
 *
 * Los GET de `/pacientes` devolvían el array mutable del store del mock **por referencia**.
 * El POST hacía `unshift` sobre ese mismo array, así que el estado de React —que ya tenía la
 * referencia vieja— mostraba el registro recién creado dos veces con un solo clic: una por el
 * `unshift` del mock y otra por el prepend optimista del componente.
 *
 * El contrato que se fija aquí: la respuesta de un GET es **una copia**. Si alguien vuelve a
 * devolver el array interno, la primera prueba falla.
 */

const pacienteNuevo = { nombre: 'Prueba', apellido_paterno: 'Regresión' };

interface PacienteMinimo {
  id: string;
}

beforeEach(() => {
  vi.stubEnv('DEV', true);
  vi.stubEnv('VITE_USE_MOCKS', 'true');
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('mockAdapter · GET de pacientes entrega una copia (regresión del duplicado)', () => {
  it('no muta el array que ya le entregó al llamador cuando después se crea un paciente', async () => {
    const primera = await api.get<PacienteMinimo[]>('/pacientes');
    const totalInicial = primera.data.length;
    expect(totalInicial).toBeGreaterThan(0);

    await api.post('/pacientes', pacienteNuevo);

    // La referencia entregada antes del POST no puede cambiar.
    expect(primera.data).toHaveLength(totalInicial);

    const segunda = await api.get<PacienteMinimo[]>('/pacientes');
    expect(segunda.data).toHaveLength(totalInicial + 1);
  });

  it('no repite el registro creado al combinar lo que el componente ya tenía con el nuevo', async () => {
    const antes = (await api.get<PacienteMinimo[]>('/pacientes')).data;
    const creado = (await api.post<PacienteMinimo>('/pacientes', pacienteNuevo)).data;

    // El prepend optimista del componente: lo que ya tenía, más el registro nuevo.
    const enPantalla = [creado, ...antes];
    const ids = enPantalla.map((paciente) => paciente.id);

    expect(new Set(ids).size).toBe(ids.length);

    const despues = (await api.get<PacienteMinimo[]>('/pacientes')).data;
    expect(despues.filter((paciente) => paciente.id === creado.id)).toHaveLength(1);
  });
});
