import { mergeConfig, defineConfig } from 'vitest/config';

import viteConfig from './vite.config.ts';

// Configuración de test separada de la de build a propósito: `vite.config.ts` no debe
// saber que existe Vitest, para que el build de producción no dependa de una dependencia
// de desarrollo. Aquí se hereda la configuración de Vite (plugins y alias `@`) y se
// agrega solo lo que necesita el runner.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // `pool: 'threads'` en lugar del valor por defecto `forks`.
      //
      // Motivo medido, no preferencia: en este checkout sobre `/mnt/c` (drvfs) un `statSync` cuesta
      // ~1,3 ms (frente a microsegundos en un sistema de archivos nativo), así que importar jsdom
      // desde `node_modules` tarda ~19 s por proceso. Con `forks`, Vitest arranca un proceso Node
      // por cada archivo de test, y con la concurrencia por defecto (tantos workers como CPUs, 20
      // aquí) varios procesos no alcanzaban a responder en el plazo de arranque de 60 s: el run
      // terminaba con `[vitest-pool]: Failed to start forks worker … Timeout waiting for worker to
      // respond` y código de salida 1 **sin un solo test fallando**. Bajar los workers con
      // `forks` lo estabilizaba pero encarecía la suite (214 s con 2 workers, 111 s con 8).
      //
      // Los hilos de `threads` arrancan en milisegundos y viven en el mismo proceso, así que el
      // mismo trabajo de importación entra en el presupuesto de arranque: la suite completa pasa a
      // ~65 s con la concurrencia por defecto, sin flags ni variables de entorno, y `pnpm test`
      // vuelve a ser el comando único de verificación.
      //
      // `isolate` se queda en su valor por defecto (`true`) a propósito: con `isolate: false` el
      // registro de módulos se comparte entre archivos del mismo worker, y los archivos que hacen
      // `vi.mock('@/features/auth/auth.service')` contaminarían a los que necesitan el servicio real
      // (axios, AgendaPage, mockAdapter). El aislamiento por archivo es lo que hace fiables a los
      // mocks actuales.
      pool: 'threads',
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/test/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  }),
);
