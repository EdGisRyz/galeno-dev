// Utilidades de test para fabricar tokens con la forma del contrato (ADR-010).
// No son tests: `vitest.config.ts` solo recoge `src/test/**/*.test.{ts,tsx}`.

const JWT_HEADER = 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9';
const JWT_SIGNATURE = 'firma-simulada-rs256';

/**
 * Codifica texto como base64url. Se codifica primero en UTF-8 porque `btoa` rechaza cualquier
 * carácter fuera de Latin-1 y las claims pueden llevar acentos.
 */
export function toBase64Url(texto: string): string {
  const bytes = new TextEncoder().encode(texto);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);

  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Token con tres segmentos cuyo payload decodifica el store. */
export function makeToken(payload: Record<string, unknown>): string {
  return `${JWT_HEADER}.${toBase64Url(JSON.stringify(payload))}.${JWT_SIGNATURE}`;
}

/** Claims mínimos obligatorios según el contrato del emisor. */
export const CLAIMS = {
  sub: 'usr-0001',
  email: 'medico@clinica.mx',
  role: 'MEDICO',
  tenantId: 'org-001',
  medicoId: 'med-0001',
  exp: 1793000000,
};

/** Usuario que la UI espera para los claims de arriba. */
export const USUARIO = {
  id: 'usr-0001',
  email: 'medico@clinica.mx',
  role: 'MEDICO' as const,
  tenantId: 'org-001',
  medicoId: 'med-0001',
};
