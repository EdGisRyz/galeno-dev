import { describe, expect, it } from 'vitest';
import storeSource from '@/store/auth.store.ts?raw';
import layoutSource from '@/layouts/MainLayout.tsx?raw';
import routeSource from '@/components/navigation/ProtectedRoute.tsx?raw';
import appSource from '@/App.tsx?raw';

/**
 * T7 · plan sección 2, guardarraíl 4: prohibidos los valores por defecto inventados.
 *
 * El guardarraíl habla de lo que el código de producción *puede mostrar*, así que se lee la
 * fuente que se envía al navegador: `tenantId || 'org-001'` y `tenantSlug || 'vitalix'` no pueden
 * reaparecer en el store ni en el layout. Los datos de `src/lib/mock/mockData.ts` quedan fuera a
 * propósito: son fixtures del mock (una clínica de ejemplo), no un default que suplante un claim
 * ausente.
 *
 * "Vitalix" pasó a ser el nombre público de la plataforma (landing en `/`), así que el patrón de
 * ese literal se acotó al *fallback* real (`|| 'vitalix'`) en vez de prohibir la palabra entera:
 * un blanket ban rompería cualquier mención legítima de la marca en estos mismos archivos
 * (por ejemplo, el import de `VitalixHomePage` en `App.tsx`).
 */

const FUENTES_DE_PRODUCCION: [string, string][] = [
  ['src/store/auth.store.ts', storeSource],
  ['src/layouts/MainLayout.tsx', layoutSource],
  ['src/components/navigation/ProtectedRoute.tsx', routeSource],
  ['src/App.tsx', appSource],
];

const LITERALES_INVENTADOS: [string, RegExp][] = [
  ["'org-001'", /org-001/],
  ["'sanrafael'", /sanrafael/i],
  ["|| 'vitalix' (fallback de tenant)", /\|\|\s*['"]vitalix['"]/i],
];

describe('código de producción · sin tenants inventados', () => {
  for (const [ruta, fuente] of FUENTES_DE_PRODUCCION) {
    for (const [literal, patron] of LITERALES_INVENTADOS) {
      it(`${ruta} no menciona ${literal}`, () => {
        expect(fuente).not.toMatch(patron);
      });
    }
  }
});
