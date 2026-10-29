import { afterEach, describe, expect, it, vi } from 'vitest';
import { debenUsarseMocks } from '@/lib/mock/mockAdapter';

/**
 * Contrato fail-closed de los mocks (R-SEG-07, plan §2 guardarraíl 3):
 * los mocks solo existen con `import.meta.env.DEV && VITE_USE_MOCKS === 'true'`.
 * Una variable sin definir NO activa mocks: un despliegue mal configurado debe
 * fallar limpio contra la red real, no fabricar datos ni aceptar logins falsos.
 */

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('mockAdapter · debenUsarseMocks (fail-closed)', () => {
  it('activa los mocks cuando está en DEV y VITE_USE_MOCKS es "true"', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'true');

    expect(debenUsarseMocks()).toBe(true);
  });

  it('desactiva los mocks cuando VITE_USE_MOCKS es "false", aun en DEV', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'false');

    expect(debenUsarseMocks()).toBe(false);
  });

  it('desactiva los mocks cuando VITE_USE_MOCKS no está definida', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', undefined);

    expect(debenUsarseMocks()).toBe(false);
  });

  it('desactiva los mocks en producción aunque VITE_USE_MOCKS sea "true"', () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_USE_MOCKS', 'true');

    expect(debenUsarseMocks()).toBe(false);
  });
});
