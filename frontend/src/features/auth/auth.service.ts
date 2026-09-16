// Servicio HTTP del dominio Auth
// completar esto cuando este la V1.sql

import { api } from '../../lib/axios';
import type { LoginRequest, LoginResponse } from './auth.types';

export const authService = {
  login: async (payload: LoginRequest): Promise<LoginResponse> => {
    const { data } = await api.post<LoginResponse>('/auth/login', payload);
    return data;
  },

  // Agregar más métodos cuando se necesiten (logout, refresh, etc.)
};