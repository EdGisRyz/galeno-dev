// Utilidades compartidas por las pruebas de dev-auth.
// Sin dependencias: solo módulos nativos de Node (node:test, node:crypto, node:fs).

import { createHash, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { iniciarServidor, leerConfiguracion } from '../server.mjs';
import { normalizarCorreo } from '../usuarios.mjs';

/** Contraseña de prueba: nunca está en el repositorio, la inyecta esta suite. */
export const CONTRASENA = 'contrasena-sintetica-de-prueba';

export const CORREO_ADMIN = 'admin@dev-auth.local';
export const CORREO_MEDICO = 'medico@dev-auth.local';
export const CORREO_PACIENTE = 'paciente@dev-auth.local';

export const ORGANIZACION_A = '11111111-1111-4111-8111-111111111111';
export const ORGANIZACION_B = '22222222-2222-4222-8222-222222222222';
export const AUTH_USER_ADMIN = 'a1b2c3d4-0000-4000-8000-000000000001';
export const AUTH_USER_MEDICO = 'a1b2c3d4-0000-4000-8000-000000000002';
export const AUTH_USER_PACIENTE = 'a1b2c3d4-0000-4000-8000-000000000003';
export const MEDICO_ACTIVO = 'b2c3d4e5-0000-4000-8000-000000000002';

/**
 * Filas de la tabla `usuario` que usa la suite: la misma forma que devuelve la consulta del
 * emisor (fila de `usuario` más el `activo` de su médico, si tiene uno). Reproducen
 * `usuarios.json`, que quedó como semilla documentada y ya no se lee al arrancar.
 *
 * Son datos de prueba: ninguna prueba toca PostgreSQL.
 */
export const FILAS_SEMILLA = [
  {
    auth_user_id: AUTH_USER_ADMIN,
    email: CORREO_ADMIN,
    rol: 'ADMIN',
    organizacion_id: ORGANIZACION_A,
    medico_id: null,
    activo: true,
    medico_activo: null,
  },
  {
    auth_user_id: AUTH_USER_MEDICO,
    email: CORREO_MEDICO,
    rol: 'MEDICO',
    organizacion_id: ORGANIZACION_A,
    medico_id: MEDICO_ACTIVO,
    activo: true,
    medico_activo: true,
  },
  {
    auth_user_id: AUTH_USER_PACIENTE,
    email: CORREO_PACIENTE,
    rol: 'PACIENTE',
    organizacion_id: ORGANIZACION_B,
    medico_id: null,
    activo: true,
    medico_activo: null,
  },
];

/**
 * Copia profunda de las filas de la suite, para que una prueba pueda alterarlas (dar de baja
 * una cuenta, agregar el mismo correo en otra organización) sin contaminar a las demás.
 */
export function filasPropias(sobrescrituras = []) {
  const filas = JSON.parse(JSON.stringify(FILAS_SEMILLA));
  for (const [indice, cambio] of sobrescrituras) {
    filas[indice] = { ...filas[indice], ...cambio };
  }
  return filas;
}

/**
 * Repositorio de identidad en memoria: cumple el mismo contrato que el de PostgreSQL para que
 * las pruebas ejerciten el servidor real sin base de datos. Filtra por correo normalizado,
 * igual que el `WHERE lower(u.email) = $1` de la consulta real.
 *
 * @param {Array<object>} filas filas de `usuario` (mutables: el repositorio las lee en vivo)
 */
export function crearRepositorioEnMemoria(filas) {
  const consultas = [];
  return {
    consultas,
    filas,
    async verificarConexion() {
      consultas.push('verificarConexion');
    },
    async buscarPorCorreo(correo) {
      consultas.push(['correo', correo]);
      const normalizado = normalizarCorreo(correo);
      return filas.filter((fila) => normalizarCorreo(fila.email) === normalizado);
    },
    async buscarPorAuthUserId(authUserId) {
      consultas.push(['auth_user_id', authUserId]);
      return filas.filter((fila) => fila.auth_user_id === authUserId);
    },
    async cerrar() {
      consultas.push('cerrar');
    },
  };
}

/**
 * Repositorio que falla en toda consulta: prueba que un error de la base responde con un
 * mensaje claro y sin detalles del driver.
 */
export function crearRepositorioCaido() {
  return {
    async verificarConexion() {
      throw new Error('sin base');
    },
    async buscarPorCorreo() {
      throw new Error('connect ECONNREFUSED 127.0.0.1:5432');
    },
    async buscarPorAuthUserId() {
      throw new Error('connect ECONNREFUSED 127.0.0.1:5432');
    },
    async cerrar() {},
  };
}

export const ORIGEN_FRONTEND = 'http://localhost:5173';

/**
 * Par RSA generado en memoria al cargar el módulo: ninguna clave privada vive en el
 * repositorio y cada corrida de la suite usa un par distinto.
 */
const CLAVE_PRIVADA_PEM = generateKeyPairSync('rsa', { modulusLength: 2048 })
  .privateKey.export({ type: 'pkcs8', format: 'pem' });

/** PEM de la clave privada de la suite, el mismo que el servidor recibe por archivo. */
export const CLAVE_PRIVADA = CLAVE_PRIVADA_PEM;

/**
 * Segundo par RSA, generado en memoria y **ajeno** al emisor: sirve para probar que una
 * firma criptográficamente correcta pero de otra clave se rechaza.
 */
export const CLAVE_PRIVADA_AJENA = generateKeyPairSync('rsa', { modulusLength: 2048 })
  .privateKey.export({ type: 'pkcs8', format: 'pem' });

/**
 * Entorno mínimo válido para arrancar el emisor. La conexión a PostgreSQL solo se valida al
 * armar la configuración: la suite inyecta un repositorio en memoria, así que nunca se conecta.
 */
export function entornoBase(sobrescrituras = {}) {
  return {
    AUTH_PRIVATE_KEY_PATH: '',
    DEV_AUTH_PASSWORD: CONTRASENA,
    AUTH_ISSUER: 'galeno-dev',
    AUTH_AUDIENCE: 'galeno-api',
    DEV_AUTH_PORT: '0',
    PGDATABASE: 'galeno_clinica_dev',
    ...sobrescrituras,
  };
}

/**
 * Arranca un emisor real en un puerto efímero, con clave privada en un directorio
 * temporal fuera del árbol de trabajo y apagado garantizado al terminar la prueba. La
 * identidad se inyecta: un repositorio en memoria con las filas indicadas.
 *
 * @param {import('node:test').TestContext} t contexto de la prueba, para el teardown
 * @param {object} [opciones]
 * @param {Array<object>} [opciones.filas] filas de `usuario` visibles para el emisor
 * @param {object} [opciones.repositorio] repositorio de identidad ya construido
 * @param {Record<string, string>} [opciones.entorno] variables de entorno a cambiar
 */
export async function levantarEmisor(t, opciones = {}) {
  const directorio = mkdtempSync(path.join(tmpdir(), 'dev-auth-'));
  const rutaClave = path.join(directorio, 'privada.pem');
  writeFileSync(rutaClave, CLAVE_PRIVADA_PEM);

  const entorno = entornoBase({ AUTH_PRIVATE_KEY_PATH: rutaClave, ...opciones.entorno });
  const config = leerConfiguracion(entorno);
  const repositorio = opciones.repositorio ?? crearRepositorioEnMemoria(opciones.filas ?? FILAS_SEMILLA);
  const emisor = await iniciarServidor(config, { repositorioUsuarios: repositorio });

  t.after(async () => {
    await emisor.cerrar();
    rmSync(directorio, { recursive: true, force: true });
  });

  return { ...emisor, config, entorno, repositorio };
}

/** Envía una petición HTTP al emisor. `cuerpo` se serializa como JSON si viene definido. */
export async function enviar(emisor, ruta, opciones = {}) {
  const { cuerpo, cabeceras = {}, metodo = 'POST', sinCuerpo = false } = opciones;
  const peticion = {
    method: metodo,
    headers: { ...cabeceras },
  };
  if (!sinCuerpo && cuerpo !== undefined) {
    peticion.headers['Content-Type'] = 'application/json';
    peticion.body = JSON.stringify(cuerpo);
  }
  return fetch(`${emisor.url}${ruta}`, peticion);
}

/** Inicia sesión con las credenciales sembradas y devuelve la respuesta cruda. */
export function iniciarSesion(emisor, correo, contrasena = CONTRASENA) {
  return enviar(emisor, '/auth/login', { cuerpo: { email: correo, password: contrasena } });
}

/** Separa un JWT en sus tres partes decodificadas, sin verificar la firma. */
export function decodificarJwt(token) {
  const partes = String(token).split('.');
  if (partes.length !== 3) {
    throw new Error(`No es un JWT de tres partes: ${token}`);
  }
  const [cabecera, carga, firma] = partes;
  return {
    cabecera: JSON.parse(Buffer.from(cabecera, 'base64url').toString('utf8')),
    carga: JSON.parse(Buffer.from(carga, 'base64url').toString('utf8')),
    firma,
    contenidoFirmado: `${cabecera}.${carga}`,
  };
}

/** Verifica la firma RS256 de un token contra la clave pública derivada de la privada. */
export function firmaEsValida(token) {
  const { firma, contenidoFirmado } = decodificarJwt(token);
  const clavePublica = createPublicKey(CLAVE_PRIVADA_PEM);
  return verify(
    'sha256',
    Buffer.from(contenidoFirmado, 'utf8'),
    clavePublica,
    Buffer.from(firma, 'base64url'),
  );
}

/** Devuelve todas las cabeceras Set-Cookie de una respuesta. */
export function cookiesDe(respuesta) {
  return respuesta.headers.getSetCookie();
}

/** Extrae el valor del token de refresco de las cabeceras Set-Cookie. */
export function tokenDeRefrescoDe(respuesta) {
  const cookie = cookiesDe(respuesta).find((valor) => valor.startsWith('refresh_token='));
  if (!cookie) {
    return null;
  }
  return cookie.slice('refresh_token='.length).split(';')[0];
}

/** Cabecera Cookie con el token de refresco, para simular al navegador. */
export function cabeceraDeCookie(token) {
  return { Cookie: `refresh_token=${token}` };
}

/**
 * Forja un token de refresco con control total del contenido y de la firma, para probar los
 * caminos de rechazo que el emisor nunca produce por sí mismo (vencido, `alg=none`, clave
 * ajena). No forma parte de la API del servidor: es andamiaje de prueba.
 *
 * @param {object} opciones
 * @param {string} opciones.sub `sub` del usuario, normalmente tomado de un login real
 * @param {number} opciones.exp vencimiento en segundos de época
 * @param {number} [opciones.iat] emisión en segundos de época (default: `exp - 1800`)
 * @param {string} [opciones.tipo] claim de tipo (default: `refresh`)
 * @param {string} [opciones.emisor] claim `iss` (default: `galeno-dev`)
 * @param {string} [opciones.audiencia] claim `aud` (default: `galeno-api`)
 * @param {string} [opciones.clavePrivada] PEM con el que se firma (default: la del emisor)
 * @param {string} [opciones.algoritmo] algoritmo declarado en la cabecera (default: `RS256`)
 * @param {boolean} [opciones.firmar] si es `false` se deja la firma vacía (default: `true`)
 * @returns {string} JWT de tres partes
 */
export function tokenDeRefrescoForjado(opciones) {
  const {
    sub,
    exp,
    iat = exp - 1800,
    tipo = 'refresh',
    emisor = 'galeno-dev',
    audiencia = 'galeno-api',
    clavePrivada = CLAVE_PRIVADA,
    algoritmo = 'RS256',
    firmar = true,
  } = opciones;

  const cabecera = Buffer.from(
    JSON.stringify({ alg: algoritmo, typ: 'JWT' }),
    'utf8',
  ).toString('base64url');
  const carga = Buffer.from(
    JSON.stringify({ sub, tipo, iat, exp, iss: emisor, aud: audiencia }),
    'utf8',
  ).toString('base64url');
  const contenido = `${cabecera}.${carga}`;

  if (!firmar) {
    return `${contenido}.`;
  }
  const firma = sign('sha256', Buffer.from(contenido, 'utf8'), clavePrivada).toString('base64url');
  return `${contenido}.${firma}`;
}

/** Huella SHA-256 en hexadecimal del DER SPKI de la clave pública derivada. */
export function huellaEsperada() {
  const der = createPublicKey(CLAVE_PRIVADA_PEM).export({ type: 'spki', format: 'der' });
  return createHash('sha256').update(der).digest('hex');
}

/** Cuerpo JSON de una respuesta, sin lanzar si no es JSON. */
export async function cuerpoJson(respuesta) {
  const texto = await respuesta.text();
  try {
    return JSON.parse(texto);
  } catch {
    throw new Error(`La respuesta no es JSON (${respuesta.status}): ${texto}`);
  }
}
