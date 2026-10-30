#!/usr/bin/env node
/**
 * dev-auth — emisor local de tokens RS256 para desarrollo.
 *
 * <p><strong>Qué es y qué no es.</strong> Es una herramienta de desarrollo, no el AuthService
 * del producto. El backend (R8.2) solo valida tokens: no firma, no emite y no toca
 * contraseñas. Este proceso existe únicamente para que haya tokens reales que validar
 * mientras no exista el AuthService de InToGlobe. El nombre `dev-auth` es deliberado: dice
 * en voz alta que no es producción, y su disparador de borrado está en el README.</p>
 *
 * <p><strong>Contrato que implementa</strong> (ADR-008 a ADR-012, §5.1, §5.2 y §5.6):</p>
 * <ul>
 *   <li>Firma RS256 con la clave privada PEM indicada por {@code AUTH_PRIVATE_KEY_PATH}. El
 *       arranque <em>falla</em> si la variable no está, si el archivo no se puede leer o si
 *       el contenido no es una clave RSA.</li>
 *   <li>Claims del access token: {@code sub}, {@code email}, {@code role},
 *       {@code tenantId}, {@code medicoId} (solo cuando el rol lo tiene), {@code iat},
 *       {@code exp}, {@code iss}, {@code aud}. {@code sub}, {@code role},
 *       {@code tenantId} y {@code exp} son obligatorios para el backend.</li>
 *   <li>Vigencia: 30 minutos para el access token y 30 minutos máximos para el refresco,
 *       deslizante mientras hay actividad. No hay revocación.</li>
 *   <li>{@code iss} y {@code aud} salen de {@code AUTH_ISSUER} y {@code AUTH_AUDIENCE}.</li>
 * </ul>
 *
 * <p><strong>Cero dependencias.</strong> Solo {@code node:crypto}, {@code node:http},
 * {@code node:fs} y utilidades de ruta de Node. No hay {@code package.json} en
 * {@code tools/}.</p>
 *
 * <p><strong>Nunca se registra</strong> la clave privada, la contraseña ni ningún token.</p>
 */

import { createHash, createPrivateKey, createPublicKey, randomUUID, sign, timingSafeEqual, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** Nombre del servicio; el prefijo `dev-` es deliberado (ver README). */
export const NOMBRE_SERVICIO = 'dev-auth';

/** TTL del access token: 30 minutos, alineado con la ventana de inactividad (ADR-012). */
export const TTL_ACCESO_SEGUNDOS = 1800;

/** TTL máximo del refresco: 30 minutos, deslizante en cada refresco (ADR-012). */
export const TTL_REFRESCO_SEGUNDOS = 1800;

/** Origen explícito del frontend de desarrollo; nunca comodín (ADR-011). */
export const ORIGEN_PERMITIDO = 'http://localhost:5173';

/** Nombre de la cookie de refresco. */
export const NOMBRE_COOKIE_REFRESCO = 'refresh_token';

/** Path acotado de la cookie: solo viaja a los endpoints de sesión. */
export const RUTA_COOKIE_REFRESCO = '/auth';

const ALGORITMO = 'RS256';
const TIPO_REFRESCO = 'refresh';
const EMISOR_POR_DEFECTO = 'galeno-dev';
const AUDIENCIA_POR_DEFECTO = 'galeno-api';
const PUERTO_POR_DEFECTO = 4001;
const LIMITE_CUERPO_BYTES = 10 * 1024;
const ROLES_PERMITIDOS = new Set(['ADMIN', 'MEDICO', 'PACIENTE']);
const PATRON_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MOTIVOS = new Map([
  [400, 'Bad Request'],
  [401, 'Unauthorized'],
  [403, 'Forbidden'],
  [404, 'Not Found'],
  [405, 'Method Not Allowed'],
]);

const MENSAJE_CREDENCIALES_INVALIDAS = 'Credenciales inválidas';
const MENSAJE_REFRESCO_INVALIDO = 'La sesión no es válida o expiró; vuelva a iniciar sesión';
const MENSAJE_ORIGEN_NO_PERMITIDO = 'El origen de la petición no está permitido para esta operación';

const RUTA_USUARIOS = path.join(path.dirname(fileURLToPath(import.meta.url)), 'usuarios.json');

// ===============================
// Configuración y arranque fail-fast
// ===============================

/**
 * Lee y valida la configuración desde el entorno.
 *
 * @param {NodeJS.ProcessEnv} entorno variables de entorno (inyectables en pruebas)
 * @returns {{rutaClavePrivada: string, contrasena: string, emisor: string, audiencia: string, puerto: number, rutaUsuarios: string}}
 */
export function leerConfiguracion(entorno = process.env) {
  return {
    rutaClavePrivada: textoObligatorio(
      entorno.AUTH_PRIVATE_KEY_PATH,
      'AUTH_PRIVATE_KEY_PATH',
      'ruta al PEM de la clave privada RS256',
    ),
    contrasena: textoObligatorio(
      entorno.DEV_AUTH_PASSWORD,
      'DEV_AUTH_PASSWORD',
      'contraseña de los usuarios de la fixture de desarrollo',
    ),
    emisor: textoOpcional(entorno.AUTH_ISSUER) ?? EMISOR_POR_DEFECTO,
    audiencia: textoOpcional(entorno.AUTH_AUDIENCE) ?? AUDIENCIA_POR_DEFECTO,
    puerto: leerPuerto(entorno.DEV_AUTH_PORT),
    rutaUsuarios: RUTA_USUARIOS,
  };
}

function textoObligatorio(valor, nombreVariable, descripcion) {
  const texto = textoOpcional(valor);
  if (texto === null) {
    throw new Error(`Falta la variable de entorno ${nombreVariable}: ${descripcion}. dev-auth no arranca sin ella.`);
  }
  return texto;
}

function textoOpcional(valor) {
  return typeof valor === 'string' && valor.trim() !== '' ? valor.trim() : null;
}

function leerPuerto(valor) {
  if (valor === undefined || valor === '') {
    return PUERTO_POR_DEFECTO;
  }
  const puerto = Number(valor);
  if (!Number.isInteger(puerto) || puerto < 0 || puerto > 65535) {
    throw new Error(`DEV_AUTH_PORT no es un puerto válido: "${valor}". Use un entero entre 0 y 65535.`);
  }
  return puerto;
}

/**
 * Lee la clave privada del PEM y comprueba que sea RSA. Falla cerrado: sin clave RSA no se
 * firma nada.
 *
 * @param {string} ruta ruta del PEM
 * @returns {import('node:crypto').KeyObject} clave privada RSA en memoria
 */
function cargarClavePrivada(ruta) {
  let pem;
  try {
    pem = readFileSync(ruta, 'utf8');
  } catch (causa) {
    throw new Error(`No se pudo leer la clave privada en ${ruta}: ${causa.message}`);
  }

  let clave;
  try {
    clave = createPrivateKey(pem);
  } catch (causa) {
    throw new Error(`No se pudo interpretar la clave privada en ${ruta}: ${causa.message}`);
  }
  if (clave.asymmetricKeyType !== 'rsa') {
    throw new Error(
      `La clave privada en ${ruta} no es una clave RSA (tipo detectado: ${clave.asymmetricKeyType}). RS256 exige una clave RSA.`,
    );
  }
  return clave;
}

/**
 * Huella SHA-256 del DER SPKI de la clave pública derivada de la privada, en hexadecimal.
 * Permite comparar con la clave que el backend tiene en `AUTH_PUBLIC_KEY`: si no coincide, el
 * backend rechazará todos los tokens y el síntoma es indistinguible de «el token está mal».
 *
 * @param {import('node:crypto').KeyObject|string} clavePrivada clave privada o su PEM
 * @returns {string} 64 caracteres hexadecimales en minúsculas
 */
export function huellaEnHex(clavePrivada) {
  const clave = typeof clavePrivada === 'string' ? createPrivateKey(clavePrivada) : clavePrivada;
  const der = createPublicKey(clave).export({ type: 'spki', format: 'der' });
  return createHash('sha256').update(der).digest('hex');
}

/**
 * Carga la fixture de usuarios (el directorio de identidad en desarrollo).
 *
 * @param {string} ruta ruta del JSON
 * @returns {Array<{sub: string, email: string, role: string, tenantId: string, medicoId?: string}>}
 */
function cargarUsuarios(ruta) {
  let fixture;
  try {
    fixture = JSON.parse(readFileSync(ruta, 'utf8'));
  } catch (causa) {
    throw new Error(`No se pudo leer la fixture de usuarios en ${ruta}: ${causa.message}`);
  }

  const usuarios = fixture?.usuarios;
  if (!Array.isArray(usuarios) || usuarios.length === 0) {
    throw new Error(`La fixture ${ruta} no declara una lista "usuarios" con al menos un usuario.`);
  }

  const correos = new Set();
  return usuarios.map((usuario, indice) => {
    const referencia = `usuarios[${indice}] de ${ruta}`;
    const valido = (valor, campo) => {
      if (typeof valor !== 'string' || !PATRON_UUID.test(valor)) {
        throw new Error(`${referencia}: el campo "${campo}" debe ser un UUID bien formado.`);
      }
      return valor;
    };

    const correo = typeof usuario.email === 'string' ? usuario.email.trim().toLowerCase() : '';
    if (correo === '' || !correo.includes('@')) {
      throw new Error(`${referencia}: el campo "email" debe ser un correo.`);
    }
    if (correos.has(correo)) {
      throw new Error(`${referencia}: el correo ${correo} está repetido.`);
    }
    correos.add(correo);

    if (!ROLES_PERMITIDOS.has(usuario.role)) {
      throw new Error(`${referencia}: el campo "role" debe ser ADMIN, MEDICO o PACIENTE.`);
    }

    const limpio = {
      sub: valido(usuario.sub, 'sub'),
      email: correo,
      role: usuario.role,
      tenantId: valido(usuario.tenantId, 'tenantId'),
    };
    if (usuario.role === 'MEDICO') {
      limpio.medicoId = valido(usuario.medicoId, 'medicoId');
    } else if (usuario.medicoId !== undefined && usuario.medicoId !== null) {
      throw new Error(`${referencia}: "medicoId" solo aplica al rol MEDICO.`);
    }
    return limpio;
  });
}

// ===============================
// Emisión y verificación de tokens
// ===============================

function codificarBase64Url(pieza) {
  return Buffer.from(JSON.stringify(pieza), 'utf8').toString('base64url');
}

/** Segundos desde la época, que es la unidad de `iat`/`exp` en un JWT. */
function ahoraEnSegundos() {
  return Math.floor(Date.now() / 1000);
}

function firmarToken(carga, emisor) {
  // Invariante explícita: ningún camino de este emisor produce un token sin `exp`. El
  // backend rechaza un token sin vencimiento porque no caducaría nunca (ADR-012).
  if (!Number.isFinite(carga.exp)) {
    throw new Error('Invariante rota: el token de dev-auth siempre debe llevar exp numérico.');
  }
  const cabecera = { alg: ALGORITMO, typ: 'JWT' };
  const contenido = `${codificarBase64Url(cabecera)}.${codificarBase64Url(carga)}`;
  const firma = sign('sha256', Buffer.from(contenido, 'utf8'), emisor.clavePrivada).toString('base64url');
  return `${contenido}.${firma}`;
}

function crearTokenDeAcceso(usuario, emisor, ahora) {
  const carga = {
    sub: usuario.sub,
    email: usuario.email,
    role: usuario.role,
    tenantId: usuario.tenantId,
  };
  if (usuario.medicoId) {
    carga.medicoId = usuario.medicoId;
  }
  Object.assign(carga, {
    iat: ahora,
    exp: ahora + TTL_ACCESO_SEGUNDOS,
    iss: emisor.emisor,
    aud: emisor.audiencia,
    jti: randomUUID(),
  });
  return firmarToken(carga, emisor);
}

function crearTokenDeRefresco(usuario, emisor, ahora) {
  return firmarToken(
    {
      sub: usuario.sub,
      tipo: TIPO_REFRESCO,
      iat: ahora,
      exp: ahora + TTL_REFRESCO_SEGUNDOS,
      iss: emisor.emisor,
      aud: emisor.audiencia,
      jti: randomUUID(),
    },
    emisor,
  );
}

/**
 * Verifica firma, algoritmo, tipo, emisor, audiencia y vencimiento de un token de refresco.
 * Cualquier desviación lanza; el llamador responde `401` sin distinguir la causa.
 */
function verificarTokenDeRefresco(token, emisor, ahora) {
  const partes = String(token).split('.');
  if (partes.length !== 3) {
    throw new Error('El token de refresco no tiene tres partes.');
  }
  const [cabeceraCodificada, cargaCodificada, firmaCodificada] = partes;

  const cabecera = JSON.parse(Buffer.from(cabeceraCodificada, 'base64url').toString('utf8'));
  if (cabecera.alg !== ALGORITMO) {
    throw new Error(`Algoritmo no permitido en el refresco: ${cabecera.alg}`);
  }

  const firmaValida = verify(
    'sha256',
    Buffer.from(`${cabeceraCodificada}.${cargaCodificada}`, 'utf8'),
    emisor.clavePublica,
    Buffer.from(firmaCodificada, 'base64url'),
  );
  if (!firmaValida) {
    throw new Error('La firma del token de refresco no verifica.');
  }

  const carga = JSON.parse(Buffer.from(cargaCodificada, 'base64url').toString('utf8'));
  if (carga.tipo !== TIPO_REFRESCO) {
    throw new Error('El token presentado no es un token de refresco.');
  }
  if (carga.iss !== emisor.emisor || carga.aud !== emisor.audiencia) {
    throw new Error('El token de refresco no es de este emisor o de esta audiencia.');
  }
  if (!Number.isFinite(carga.exp) || carga.exp <= ahora) {
    throw new Error('El token de refresco está vencido.');
  }
  return carga;
}

// ===============================
// Cookies y contrato de error
// ===============================

function cabeceraDeCookieDeRefresco(token) {
  return [
    `${NOMBRE_COOKIE_REFRESCO}=${token}`,
    `Path=${RUTA_COOKIE_REFRESCO}`,
    'HttpOnly',
    'SameSite=Strict',
    `Max-Age=${TTL_REFRESCO_SEGUNDOS}`,
  ].join('; ');
}

function cabeceraDeCookieBorrada() {
  // `Max-Age=0` es la instrucción; `Expires` en el pasado cubre clientes viejos que lo
  // ignoran. No se usa `Secure` porque el emisor de desarrollo escucha en http://localhost;
  // el emisor real deberá agregarlo.
  return [
    `${NOMBRE_COOKIE_REFRESCO}=`,
    `Path=${RUTA_COOKIE_REFRESCO}`,
    'HttpOnly',
    'SameSite=Strict',
    'Max-Age=0',
    'Expires=Thu, 01 Jan 1970 00:00:00 GMT',
  ].join('; ');
}

function leerCookieDeRefresco(cabeceraCookie) {
  if (!cabeceraCookie) {
    return null;
  }
  for (const trozo of cabeceraCookie.split(';')) {
    const indice = trozo.indexOf('=');
    if (indice === -1) {
      continue;
    }
    if (trozo.slice(0, indice).trim() !== NOMBRE_COOKIE_REFRESCO) {
      continue;
    }
    const valor = trozo.slice(indice + 1).trim();
    return valor === '' ? null : valor;
  }
  return null;
}

function responderJson(respuesta, estado, cuerpo, cabecerasAdicionales = {}) {
  const contenido = Buffer.from(JSON.stringify(cuerpo), 'utf8');
  respuesta.writeHead(estado, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': contenido.length,
    'Cache-Control': 'no-store',
    ...cabecerasAdicionales,
  });
  respuesta.end(contenido);
}

function responderError(peticion, respuesta, estado, mensaje, cabecerasAdicionales = {}) {
  responderJson(
    respuesta,
    estado,
    {
      marcaTemporal: new Date().toISOString(),
      codigo: estado,
      error: MOTIVOS.get(estado) ?? 'Error',
      mensaje,
      ruta: rutaDe(peticion),
    },
    cabecerasAdicionales,
  );
}

function rutaDe(peticion) {
  return new URL(peticion.url ?? '/', 'http://localhost').pathname;
}

function cabecerasCors(origen) {
  if (origen !== ORIGEN_PERMITIDO) {
    return {};
  }
  return {
    'Access-Control-Allow-Origin': ORIGEN_PERMITIDO,
    'Access-Control-Allow-Credentials': 'true',
    Vary: 'Origin',
  };
}

/**
 * El refresco y el logout se autentican por cookie, así que entran en el radio de CSRF
 * (ADR-011): se exige que el `Origin`, cuando viene, sea el del frontend de desarrollo.
 * SameSite=Strict es la primera barrera; esto es la segunda.
 */
function origenPermitido(peticion) {
  const origen = peticion.headers.origin;
  return origen === undefined || origen === ORIGEN_PERMITIDO;
}

function contrasenaCoincide(intento, esperada) {
  // Se comparan resúmenes de longitud fija para no filtrar la longitud ni el prefijo de la
  // contraseña por tiempo de respuesta.
  const resumenIntento = createHash('sha256').update(intento, 'utf8').digest();
  const resumenEsperado = createHash('sha256').update(esperada, 'utf8').digest();
  return timingSafeEqual(resumenIntento, resumenEsperado);
}

function proyeccionDeUsuario(usuario) {
  const proyeccion = {
    sub: usuario.sub,
    email: usuario.email,
    role: usuario.role,
    tenantId: usuario.tenantId,
  };
  if (usuario.medicoId) {
    proyeccion.medicoId = usuario.medicoId;
  }
  return proyeccion;
}

async function leerCuerpoJson(peticion) {
  const trozos = [];
  let tamano = 0;
  for await (const trozo of peticion) {
    tamano += trozo.length;
    if (tamano > LIMITE_CUERPO_BYTES) {
      throw new Error('El cuerpo de la petición es demasiado grande.');
    }
    trozos.push(trozo);
  }
  const texto = Buffer.concat(trozos).toString('utf8').trim();
  if (texto === '') {
    return {};
  }
  return JSON.parse(texto);
}

// ===============================
// Endpoints
// ===============================

async function manejarLogin(peticion, respuesta, emisor, cabeceras) {
  let cuerpo;
  try {
    cuerpo = await leerCuerpoJson(peticion);
  } catch {
    responderError(peticion, respuesta, 400, 'El cuerpo de la petición debe ser JSON válido.', cabeceras);
    return;
  }

  const correo = typeof cuerpo.email === 'string' ? cuerpo.email.trim().toLowerCase() : '';
  const contrasena = typeof cuerpo.password === 'string' ? cuerpo.password : '';
  if (correo === '' || contrasena === '') {
    responderError(peticion, respuesta, 400, 'La petición requiere "email" y "password".', cabeceras);
    return;
  }

  const usuario = emisor.usuariosPorCorreo.get(correo) ?? null;

  // La comparación de contraseña se hace siempre, exista o no el correo: el tiempo de
  // respuesta y el cuerpo de error son idénticos, así que no se puede enumerar usuarios.
  const contrasenaValida = contrasenaCoincide(contrasena, emisor.contrasena);
  if (usuario === null || !contrasenaValida) {
    responderError(peticion, respuesta, 401, MENSAJE_CREDENCIALES_INVALIDAS, cabeceras);
    return;
  }

  const ahora = ahoraEnSegundos();
  responderJson(
    respuesta,
    200,
    {
      accessToken: crearTokenDeAcceso(usuario, emisor, ahora),
      user: proyeccionDeUsuario(usuario),
    },
    {
      ...cabeceras,
      'Set-Cookie': cabeceraDeCookieDeRefresco(crearTokenDeRefresco(usuario, emisor, ahora)),
    },
  );
}

async function manejarRefresco(peticion, respuesta, emisor, cabeceras) {
  if (!origenPermitido(peticion)) {
    responderError(peticion, respuesta, 403, MENSAJE_ORIGEN_NO_PERMITIDO, cabeceras);
    return;
  }

  const token = leerCookieDeRefresco(peticion.headers.cookie);
  if (token === null) {
    responderError(peticion, respuesta, 401, MENSAJE_REFRESCO_INVALIDO, cabeceras);
    return;
  }

  const ahora = ahoraEnSegundos();
  let usuario;
  try {
    const carga = verificarTokenDeRefresco(token, emisor, ahora);
    // El rol y el tenant se releen de la fixture: es la autoridad del directorio de
    // identidad (D2.1), así que un cambio de clínica se refleja en el siguiente refresco.
    usuario = emisor.usuariosPorId.get(carga.sub) ?? null;
  } catch {
    responderError(peticion, respuesta, 401, MENSAJE_REFRESCO_INVALIDO, cabeceras);
    return;
  }
  if (!usuario) {
    responderError(peticion, respuesta, 401, MENSAJE_REFRESCO_INVALIDO, cabeceras);
    return;
  }

  responderJson(
    respuesta,
    200,
    { accessToken: crearTokenDeAcceso(usuario, emisor, ahora) },
    {
      ...cabeceras,
      'Set-Cookie': cabeceraDeCookieDeRefresco(crearTokenDeRefresco(usuario, emisor, ahora)),
    },
  );
}

function manejarLogout(peticion, respuesta, cabeceras) {
  if (!origenPermitido(peticion)) {
    responderError(peticion, respuesta, 403, MENSAJE_ORIGEN_NO_PERMITIDO, cabeceras);
    return;
  }
  // Best-effort y sin estado: se borra la cookie del cliente y el access token ya emitido
  // sigue vigente hasta su `exp` (no hay revocación, ADR-012).
  respuesta.writeHead(204, {
    ...cabeceras,
    'Set-Cookie': cabeceraDeCookieBorrada(),
    'Cache-Control': 'no-store',
  });
  respuesta.end();
}

function manejarHealth(respuesta, emisor, cabeceras) {
  responderJson(
    respuesta,
    200,
    {
      estado: 'ok',
      servicio: NOMBRE_SERVICIO,
      algoritmo: ALGORITMO,
      accessTtlSegundos: TTL_ACCESO_SEGUNDOS,
      refreshTtlSegundos: TTL_REFRESCO_SEGUNDOS,
      emisor: emisor.emisor,
      audiencia: emisor.audiencia,
    },
    cabeceras,
  );
}

const RUTAS = new Map([
  ['/auth/login', { POST: manejarLogin }],
  ['/auth/refresh', { POST: manejarRefresco }],
  ['/auth/logout', { POST: (peticion, respuesta, emisor, cabeceras) => manejarLogout(peticion, respuesta, cabeceras) }],
  ['/health', { GET: (peticion, respuesta, emisor, cabeceras) => manejarHealth(respuesta, emisor, cabeceras) }],
]);

/**
 * Construye el emisor en memoria: carga la clave y la fixture. Falla aquí, antes de escuchar.
 *
 * @param {ReturnType<typeof leerConfiguracion>} config
 */
function crearEmisor(config) {
  const clavePrivada = cargarClavePrivada(config.rutaClavePrivada);
  const usuarios = cargarUsuarios(config.rutaUsuarios);
  return {
    clavePrivada,
    clavePublica: createPublicKey(clavePrivada),
    huella: huellaEnHex(clavePrivada),
    emisor: config.emisor,
    audiencia: config.audiencia,
    contrasena: config.contrasena,
    usuarios,
    usuariosPorCorreo: new Map(usuarios.map((usuario) => [usuario.email, usuario])),
    usuariosPorId: new Map(usuarios.map((usuario) => [usuario.sub, usuario])),
  };
}

/** Construye el servidor HTTP sin escucharlo. */
function crearServidorHttp(emisor) {
  return createServer(async (peticion, respuesta) => {
    try {
      const cabeceras = cabecerasCors(peticion.headers.origin);

      if (peticion.method === 'OPTIONS') {
        // Preflight: sin el origen permitido no se anuncia nada.
        if (peticion.headers.origin !== ORIGEN_PERMITIDO) {
          responderError(peticion, respuesta, 403, MENSAJE_ORIGEN_NO_PERMITIDO, cabeceras);
          return;
        }
        respuesta.writeHead(204, {
          ...cabeceras,
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Accept',
          'Access-Control-Max-Age': '600',
        });
        respuesta.end();
        return;
      }

      const ruta = RUTAS.get(rutaDe(peticion));
      if (ruta === undefined) {
        responderError(peticion, respuesta, 404, 'Ruta no encontrada.', cabeceras);
        return;
      }

      const manejador = ruta[peticion.method ?? 'GET'];
      if (manejador === undefined) {
        responderError(peticion, respuesta, 405, 'Método no permitido para esta ruta.', {
          ...cabeceras,
          Allow: Object.keys(ruta).join(', '),
        });
        return;
      }

      await manejador(peticion, respuesta, emisor, cabeceras);
    } catch {
      // Cualquier fallo inesperado se responde sin detalles internos. No se registra la
      // petición: ni credenciales ni tokens terminan en la salida del proceso.
      if (!respuesta.headersSent) {
        responderError(peticion, respuesta, 400, 'La petición no se pudo procesar.', {});
      } else {
        respuesta.end();
      }
    }
  });
}

/**
 * Arranca el emisor. Falla cerrado si la configuración o la clave no sirven.
 *
 * @param {ReturnType<typeof leerConfiguracion>} config
 * @returns {Promise<{servidor: import('node:http').Server, puerto: number, url: string, huella: string, config: object, cerrar: () => Promise<void>}>}
 */
export async function iniciarServidor(config) {
  const emisor = crearEmisor(config);
  const servidor = crearServidorHttp(emisor);

  await new Promise((resolver, rechazar) => {
    const alFallar = (error) => rechazar(error);
    servidor.once('error', alFallar);
    servidor.listen(config.puerto, () => {
      servidor.off('error', alFallar);
      resolver();
    });
  });

  const direccion = servidor.address();
  const puerto = typeof direccion === 'object' && direccion !== null ? direccion.port : config.puerto;

  return {
    servidor,
    puerto,
    url: `http://localhost:${puerto}`,
    huella: emisor.huella,
    roles: [...new Set(emisor.usuarios.map((usuario) => usuario.role))].sort(),
    cantidadUsuarios: emisor.usuarios.length,
    config,
    cerrar: () =>
      new Promise((resolver, rechazar) => {
        servidor.closeAllConnections?.();
        servidor.close((error) => (error ? rechazar(error) : resolver()));
      }),
  };
}

/** Huella en formato legible por humanos: pares hexadecimales en mayúsculas separados por `:`. */
export function huellaFormateada(huellaHex) {
  return (huellaHex.toUpperCase().match(/.{2}/g) ?? []).join(':');
}

function imprimirArranque(emisor) {
  process.stdout.write(
    [
      '',
      `${NOMBRE_SERVICIO} · emisor de tokens RS256 para DESARROLLO LOCAL. No es el AuthService del producto.`,
      `Escuchando en ${emisor.url} (el backend espera este servicio en AUTH_SERVICE_URL).`,
      `iss=${emisor.config.emisor} · aud=${emisor.config.audiencia} · access=${TTL_ACCESO_SEGUNDOS}s · refresh=${TTL_REFRESCO_SEGUNDOS}s`,
      `Usuarios de la fixture: ${emisor.cantidadUsuarios} (roles: ${emisor.roles.join(', ')}). La contraseña sale de DEV_AUTH_PASSWORD.`,
      `Huella SHA-256 de la clave pública (DER SPKI): ${huellaFormateada(emisor.huella)}`,
      'Compare esta huella con la clave de AUTH_PUBLIC_KEY del backend: si no coincide, el backend rechazará todos los tokens.',
      '',
    ].join('\n'),
  );
}

// ===============================
// Ejecución directa (`node tools/dev-auth/server.mjs`)
// ===============================

const esEjecucionDirecta =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (esEjecucionDirecta) {
  let emisor;
  try {
    emisor = await iniciarServidor(leerConfiguracion(process.env));
  } catch (error) {
    process.stderr.write(`${NOMBRE_SERVICIO} no arrancó: ${error.message}\n`);
    process.exitCode = 1;
  }

  if (emisor) {
    imprimirArranque(emisor);
    const apagar = async () => {
      await emisor.cerrar();
      process.exit(0);
    };
    process.on('SIGINT', apagar);
    process.on('SIGTERM', apagar);
  }
}
