// Servicio HTTP del dominio Auth.
// Habla con el **emisor de tokens** (`authApi`, `VITE_AUTH_URL`), no con el backend: el backend
// solo valida tokens (ADR-008). Ninguna de estas llamadas adjunta el Bearer — la sesión del
// emisor se transporta en la cookie de refresco `httpOnly` (ADR-011).

import { authApi } from '../../lib/axios';
import type { LoginRequest, LoginResponse, RefreshResponse } from './auth.types';

export const authService = {
  /** `POST /auth/login` → `{ accessToken, user }` y setea la cookie de refresco. */
  login: async (payload: LoginRequest): Promise<LoginResponse> => {
    const { data } = await authApi.post<LoginResponse>('/auth/login', payload);
    return data;
  },

  /** `POST /auth/refresh` → `{ accessToken }`; rota la cookie (deslizante). */
  refresh: async (): Promise<RefreshResponse> => {
    const { data } = await authApi.post<RefreshResponse>('/auth/refresh');
    return data;
  },

  /** `POST /auth/logout` → `204`; borra la cookie. No revoca el access token (ADR-012). */
  logout: async (): Promise<void> => {
    await authApi.post('/auth/logout');
  },
};
