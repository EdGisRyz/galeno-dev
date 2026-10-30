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
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/test/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  }),
);
