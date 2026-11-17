import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/axios';

/**
 * S5 · el mock de desarrollo también rechaza la CURP duplicada con 409.
 *
 * Sin esta regla la prueba del 409 en la pantalla sería una mentira: los mocks persistían
 * cualquier CURP repetida, así que el desarrollo local decía que el duplicado entraba cuando el
 * backend real lo rechaza. El mock reproduce las tres reglas del backend para la CURP: se guarda
 * canónica (recortada y en mayúsculas), es única **dentro de la organización** y un registro sin
 * CURP no colisiona con otro que tampoco la tenga, porque los nulos no chocan en la restricción
 * única.
 *
 * El store del mock vive por archivo de prueba (Vitest aísla el registro de módulos), así que
 * cada prueba usa una CURP propia para no depender del orden.
 */

/** Organización dueña de la colección de pacientes en la fixture del mock. */
const ORGANIZACION_ID = 'org-001';

/** Colección del contrato anidado. */
const RUTA_COLECCION = (organizacionId: string) =>
  `/organizaciones/${organizacionId}/pacientes`;

/** Ítem plano de la edición. */
const RUTA_ITEM = (id: string) => `/pacientes/${id}`;

interface PacienteDeMock {
  id: string;
  organizacionId: string;
  curp?: string | null;
  parentesco?: string | null;
  nombreResponsable?: string | null;
}

beforeEach(() => {
  vi.stubEnv('DEV', true);
  vi.stubEnv('VITE_USE_MOCKS', 'true');
});

afterEach(() => {
  vi.unstubAllEnvs();
});

/** Da de alta un paciente en la organización indicada y devuelve lo que respondió el mock. */
async function alta(organizacionId: string, cuerpo: Record<string, unknown>) {
  const respuesta = await api.post<PacienteDeMock>(RUTA_COLECCION(organizacionId), cuerpo);
  return respuesta.data;
}

/** Cantidad de pacientes que el mock tiene para la organización. */
async function totalDePacientes(organizacionId: string): Promise<number> {
  const { data } = await api.get<PacienteDeMock[]>(RUTA_COLECCION(organizacionId));
  return data.length;
}

describe('mockAdapter · la CURP repetida responde 409 como el backend', () => {
  it('guarda la CURP canónica y rechaza el duplicado escrito en otra forma', async () => {
    const creado = await alta(ORGANIZACION_ID, {
      nombre: 'Paciente de prueba',
      curp: '  curp930101mdfrrn01  ',
      parentesco: 'Hermana',
      nombreResponsable: 'Responsable de prueba',
    });

    // S3: se recorta y se pasa a mayúsculas antes de guardar.
    expect(creado.curp).toBe('CURP930101MDFRRN01');
    // S6: la ficha viaja plana; el objeto anidado no existe en el contrato.
    expect(creado.parentesco).toBe('Hermana');
    expect(creado).not.toHaveProperty('ficha');

    const antes = await totalDePacientes(ORGANIZACION_ID);

    await expect(
      alta(ORGANIZACION_ID, { nombre: 'Otro paciente de prueba', curp: 'CURP930101MDFRRN01' }),
    ).rejects.toMatchObject({
      response: {
        status: 409,
        data: { mensaje: expect.stringContaining('CURP930101MDFRRN01') },
      },
    });

    // El rechazo no deja el registro creado a medias.
    expect(await totalDePacientes(ORGANIZACION_ID)).toBe(antes);
  });

  it('la misma CURP en otra organización no colisiona', async () => {
    const creado = await alta('org-002', {
      nombre: 'Paciente de prueba',
      curp: 'CURP940101hdfrrn02',
    });
    expect(creado.organizacionId).toBe('org-002');

    // La unicidad es por organización, nunca global.
    const mismoEnOtraClinica = await alta('org-003', {
      nombre: 'Paciente de prueba',
      curp: 'CURP940101HDFRRN02',
    });
    expect(mismoEnOtraClinica.organizacionId).toBe('org-003');
  });

  it('dos pacientes sin CURP se registran sin chocar', async () => {
    const primero = await alta(ORGANIZACION_ID, { nombre: 'Paciente de prueba' });
    const segundo = await alta(ORGANIZACION_ID, { nombre: 'Otro paciente de prueba' });

    expect(primero.curp ?? null).toBeNull();
    expect(segundo.curp ?? null).toBeNull();
    expect(primero.id).not.toBe(segundo.id);
  });

  it('en la edición rechaza la CURP de otro paciente y acepta la propia', async () => {
    const primero = await alta(ORGANIZACION_ID, {
      nombre: 'Paciente de prueba',
      curp: 'CURP950101mdfrrn03',
    });
    const segundo = await alta(ORGANIZACION_ID, {
      nombre: 'Otro paciente de prueba',
      curp: 'CURP960101hdfrrn04',
    });

    await expect(
      api.put(RUTA_ITEM(segundo.id), { nombre: 'Otro paciente de prueba', curp: 'CURP950101MDFRRN03' }),
    ).rejects.toMatchObject({
      response: {
        status: 409,
        data: { mensaje: expect.stringContaining('CURP950101MDFRRN03') },
      },
    });

    // Conservar la CURP que el paciente ya tiene no es un duplicado consigo mismo.
    const actualizado = await api.put<PacienteDeMock>(RUTA_ITEM(primero.id), {
      nombre: 'Paciente de prueba',
      curp: 'CURP950101MDFRRN03',
      parentesco: 'Madre',
    });
    expect(actualizado.status).toBe(200);
    expect(actualizado.data.curp).toBe('CURP950101MDFRRN03');
    expect(actualizado.data.parentesco).toBe('Madre');
  });
});
