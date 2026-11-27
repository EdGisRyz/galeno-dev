import type { ReactElement } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Envuelve un render con un `QueryClient` propio y aislado por prueba (H11).
 *
 * Sin reintentos: un `mockRejectedValueOnce` describe un único fallo, y con el reintento por
 * defecto de TanStack Query (3, con backoff) la petición se repetiría contra la siguiente
 * implementación del mock en vez de resolver en el estado de error que la prueba espera.
 */
export function renderConQueryClient(ui: ReactElement, options?: RenderOptions) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>, options);
}
