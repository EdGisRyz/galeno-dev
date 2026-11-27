import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { mockAdapterListo } from '@/lib/axios';

// Sin `globals: true`, Testing Library no puede registrar su limpieza automática.
afterEach(() => {
  cleanup();
});

// El mock se registra con un import dinámico (ver axios.ts, H6) para que el bundle de producción
// no lo incluya. Vitest espera este top-level await antes de correr cualquier test del archivo,
// así que ningún test depende de una carrera entre el registro del interceptor y su primera
// petición: cuando empieza el primer `it`, el mock ya está listo (o, fuera de dev, la promesa ya
// se resolvió sin registrar nada).
await mockAdapterListo;
