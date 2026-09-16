// Hooks TanStack Query del dominio Auth
// Completar esto cuando este la V1.sql

import { useMutation } from '@tanstack/react-query';
import { authService } from './auth.service';
import type { LoginRequest } from './auth.types';
import { useAuthStore } from '../../store/auth.store';

export const useLogin = () => {
  const login = useAuthStore((state) => state.login);

  return useMutation({
    mutationFn: (payload: LoginRequest) => authService.login(payload),
    onSuccess: (data) => {
      login(data.accessToken);
    },
  });
};

// Agregar más hooks cuando se necesiten (useLogout, useRefresh, etc.)