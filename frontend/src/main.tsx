import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './index.css';
import App from './App';

// Sin reintentos automáticos (H11): un fallo se explica con un mensaje y, donde la pantalla lo
// ofrece, un botón "Reintentar" explícito. Tres reintentos silenciosos con backoff exponencial
// convertirían un error de negocio (403, 409) en una espera de varios segundos sin explicación.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>
);