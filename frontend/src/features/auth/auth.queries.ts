// Hooks TanStack Query del dominio Auth.
// La sesión la abre el store (token en memoria + usuario decodificado del token), así que el hook
// no vuelve a llamar al servicio: solo delega en `login`.

import { useMutation } from '@tanstack/react-query';
import type { LoginRequest } from './auth.types';
import { useAuthStore } from '../../store/auth.store';

export const useLogin = () => {
  const login = useAuthStore((state) => state.login);

  return useMutation({
    mutationFn: ({ email, password }: LoginRequest) => login(email, password),
  });
};

// Agregar más hooks cuando se necesiten (useLogout, useRefresh, etc.)
