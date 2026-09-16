// Store de autenticación con Zustand.
// Guarda los tokens y los datos del usuario autenticado.
// Cualquier componente puede leer o modificar este estado con un solo hook.

import { create } from 'zustand';

interface AuthState {
  accessToken:     string | null;
  isAuthenticated: boolean;

  login:  (accessToken: string) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken:     localStorage.getItem('access_token'),
  isAuthenticated: !!localStorage.getItem('access_token'),

  login: (accessToken) => {
    localStorage.setItem('access_token',  accessToken);
    set({ accessToken, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('access_token');
    set({ accessToken: null, isAuthenticated: false });
  },
}));