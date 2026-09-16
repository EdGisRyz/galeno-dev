// Tipos TypeScript del dominio Auth
// completar esto cuando este la V1.sql

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  // Agregar más campos cuando se defina los datos con el backend
}