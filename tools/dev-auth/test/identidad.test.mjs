// Pruebas de la resolución de identidad: la cuenta se busca en la tabla `usuario`, no en un
// archivo. Cada estado de la cuenta tiene su respuesta y la traducción fila → claims se prueba
// sin base de datos, porque es una función pura.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { evaluarCuenta, normalizarCorreo, traducirFilaAClaims } from '../usuarios.mjs';

import {
  AUTH_USER_ADMIN,
  AUTH_USER_MEDICO,
  CONTRASENA,
  CORREO_ADMIN,
  CORREO_MEDICO,
  MEDICO_ACTIVO,
  ORGANIZACION_A,
  ORGANIZACION_B,
  cabeceraDeCookie,
  cookiesDe,
  cuerpoJson,
  decodificarJwt,
  enviar,
  filasPropias,
  iniciarSesion,
  levantarEmisor,
  tokenDeRefrescoDe,
  tokenDeRefrescoForjado,
} from './ayuda.mjs';

const DIRECTORIO = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

// ===============================
// Funciones puras: no tocan la base
// ===============================

test('normalizarCorreo recorta y pasa a minúsculas; lo que no es texto queda vacío', () => {
  assert.equal(normalizarCorreo('  Admin@Dev-Auth.Local '), 'admin@dev-auth.local');
  assert.equal(normalizarCorreo('medico@dev-auth.local'), 'medico@dev-auth.local');
  assert.equal(normalizarCorreo(undefined), '');
  assert.equal(normalizarCorreo(null), '');
  assert.equal(normalizarCorreo(42), '');
  assert.equal(normalizarCorreo('   '), '');
});

test('traducirFilaAClaims mapea la fila de usuario a los claims del token', () => {
  const claims = traducirFilaAClaims({
    auth_user_id: AUTH_USER_MEDICO,
    email: '  Medico@Dev-Auth.Local ',
    rol: 'MEDICO',
    organizacion_id: ORGANIZACION_A,
    medico_id: MEDICO_ACTIVO,
    activo: true,
    medico_activo: true,
  });

  assert.deepEqual(claims, {
    sub: AUTH_USER_MEDICO,
    email: CORREO_MEDICO,
    role: 'MEDICO',
    tenantId: ORGANIZACION_A,
    medicoId: MEDICO_ACTIVO,
  });
});

test('traducirFilaAClaims omite medicoId fuera del rol MEDICO aunque la fila lo tenga', () => {
  const claims = traducirFilaAClaims({
    auth_user_id: AUTH_USER_ADMIN,
    email: CORREO_ADMIN,
    rol: 'ADMIN',
    organizacion_id: ORGANIZACION_A,
    medico_id: MEDICO_ACTIVO,
    activo: true,
    medico_activo: true,
  });

  assert.deepEqual(Object.keys(claims).sort(), ['email', 'role', 'sub', 'tenantId']);
  assert.equal(claims.role, 'ADMIN');
});

test('traducirFilaAClaims rechaza un rol que el contrato de claims no admite', () => {
  assert.throws(
    () =>
      traducirFilaAClaims({
        auth_user_id: AUTH_USER_ADMIN,
        email: CORREO_ADMIN,
        rol: 'RECEPCIONISTA',
        organizacion_id: ORGANIZACION_A,
        medico_id: null,
        activo: true,
        medico_activo: null,
      }),
    /rol/,
    'un rol fuera del contrato produciría un token que el backend rechaza',
  );
});

test('traducirFilaAClaims rechaza identificadores que no son UUID canónicos', () => {
  const fila = {
    auth_user_id: '1-1-1-1-1',
    email: CORREO_ADMIN,
    rol: 'ADMIN',
    organizacion_id: ORGANIZACION_A,
    medico_id: null,
    activo: true,
    medico_activo: null,
  };
  assert.throws(() => traducirFilaAClaims(fila), /auth_user_id/);
  assert.throws(() => traducirFilaAClaims({ ...fila, auth_user_id: AUTH_USER_ADMIN, organizacion_id: 'no-es-uuid' }), /organizacion_id/);
  assert.throws(
    () => traducirFilaAClaims({ ...fila, auth_user_id: AUTH_USER_ADMIN, rol: 'MEDICO', medico_id: null }),
    /medico_id/,
  );
});

test('evaluarCuenta decide el estado a partir de las filas devueltas', () => {
  const activa = filasPropias()[0];

  assert.deepEqual(evaluarCuenta([]), { resultado: 'inexistente' });
  assert.deepEqual(evaluarCuenta([{ ...activa }]), {
    resultado: 'ok',
    claims: {
      sub: AUTH_USER_ADMIN,
      email: CORREO_ADMIN,
      role: 'ADMIN',
      tenantId: ORGANIZACION_A,
    },
  });

  assert.deepEqual(evaluarCuenta([{ ...activa, activo: false }]), { resultado: 'inactiva' });

  const medico = filasPropias()[1];
  assert.deepEqual(evaluarCuenta([{ ...medico, medico_activo: false }]), { resultado: 'medico-inactivo' });

  const ambiguo = evaluarCuenta([
    { ...activa },
    { ...activa, auth_user_id: AUTH_USER_MEDICO, organizacion_id: ORGANIZACION_B },
  ]);
  assert.equal(ambiguo.resultado, 'ambiguo');
  assert.equal(ambiguo.cantidad, 2);

  assert.equal(evaluarCuenta([{ ...activa, rol: 'RECEPCIONISTA' }]).resultado, 'fila-invalida');
});

// ===============================
// Login contra la fuente de identidad inyectada
// ===============================

test('el login resuelve el correo normalizado a minúsculas y sin espacios', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await iniciarSesion(emisor, `  ${CORREO_ADMIN.toUpperCase()}  `);
  assert.equal(respuesta.status, 200);

  const { carga } = decodificarJwt((await cuerpoJson(respuesta)).accessToken);
  assert.equal(carga.sub, AUTH_USER_ADMIN);
  assert.equal(carga.email, CORREO_ADMIN);
  assert.equal(carga.tenantId, ORGANIZACION_A, 'tenantId sale de organizacion_id');

  assert.deepEqual(emisor.repositorio.consultas.at(-1), ['correo', CORREO_ADMIN]);
});

test('una cuenta que no existe responde 401 con el mensaje genérico', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await iniciarSesion(emisor, 'nadie@dev-auth.local');
  assert.equal(respuesta.status, 401);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 401);
  assert.equal(cuerpo.error, 'Unauthorized');
  assert.equal(cuerpo.mensaje, 'Credenciales inválidas');
  assert.equal(cookiesDe(respuesta).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('una cuenta dada de baja responde 403 explicando que la cuenta está inactiva', async (t) => {
  const emisor = await levantarEmisor(t, { filas: filasPropias([[0, { activo: false }]]) });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN);
  assert.equal(respuesta.status, 403);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 403);
  assert.equal(cuerpo.error, 'Forbidden');
  assert.match(cuerpo.mensaje, /cuenta/i);
  assert.match(cuerpo.mensaje, /baja/i);
  assert.ok(!('accessToken' in cuerpo), 'una cuenta dada de baja no recibe token');
  assert.equal(cookiesDe(respuesta).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('un médico dado de baja responde 403 explicando que el médico ya no está activo', async (t) => {
  const emisor = await levantarEmisor(t, { filas: filasPropias([[1, { medico_activo: false }]]) });

  const respuesta = await iniciarSesion(emisor, CORREO_MEDICO);
  assert.equal(respuesta.status, 403);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 403);
  assert.match(cuerpo.mensaje, /m[eé]dico/i);
  assert.match(cuerpo.mensaje, /activo/i);
  assert.ok(!('accessToken' in cuerpo));
});

test('el mismo correo en dos organizaciones responde 409 sin desambiguar', async (t) => {
  const filas = filasPropias();
  filas.push({
    ...filas[0],
    auth_user_id: 'a1b2c3d4-0000-4000-8000-000000000009',
    organizacion_id: ORGANIZACION_B,
  });
  const emisor = await levantarEmisor(t, { filas });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN);
  assert.equal(respuesta.status, 409);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 409);
  assert.equal(cuerpo.error, 'Conflict');
  assert.match(cuerpo.mensaje, /organizaci/i);
  assert.ok(!('accessToken' in cuerpo));
  assert.equal(cookiesDe(respuesta).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('la contraseña se comprueba antes que el estado de la cuenta', async (t) => {
  const emisor = await levantarEmisor(t, { filas: filasPropias([[0, { activo: false }]]) });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN, 'contraseña-equivocada');
  assert.equal(respuesta.status, 401, 'sin la contraseña correcta no se revela el estado de la cuenta');
  assert.equal((await cuerpoJson(respuesta)).mensaje, 'Credenciales inválidas');
});

test('una cuenta con varios candidatos y contraseña incorrecta sigue respondiendo 401', async (t) => {
  const filas = filasPropias();
  filas.push({ ...filas[0], auth_user_id: 'a1b2c3d4-0000-4000-8000-000000000009', organizacion_id: ORGANIZACION_B });
  const emisor = await levantarEmisor(t, { filas });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN, 'contraseña-equivocada');
  assert.equal(respuesta.status, 401);
});

test('si la base de datos falla en el login, responde 500 con un mensaje claro y sin el error crudo', async (t) => {
  const emisor = await levantarEmisor(t, { repositorio: repositorioQueFallaEnConsulta() });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN);
  assert.equal(respuesta.status, 500);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 500);
  assert.match(cuerpo.mensaje, /tabla usuario/i);
  assert.ok(!/ECONNREFUSED/.test(cuerpo.mensaje), 'no se copia el error crudo del driver');
});

test('una fila que no puede convertirse en claims responde 500 nombrando el campo', async (t) => {
  const emisor = await levantarEmisor(t, { filas: filasPropias([[0, { rol: 'RECEPCIONISTA' }]]) });

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN);
  assert.equal(respuesta.status, 500);

  const cuerpo = await cuerpoJson(respuesta);
  assert.match(cuerpo.mensaje, /rol/);
  assert.ok(!('accessToken' in cuerpo));
});

// ===============================
// El refresco también relee la fuente
// ===============================

test('el refresco relee la fila: un cambio de organización se refleja en el token nuevo', async (t) => {
  const emisor = await levantarEmisor(t);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const fila = emisor.repositorio.filas[0];

  fila.organizacion_id = ORGANIZACION_B;
  const refresco = await enviar(emisor, '/auth/refresh', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(tokenDeRefrescoDe(login)),
  });
  assert.equal(refresco.status, 200);

  const { carga } = decodificarJwt((await cuerpoJson(refresco)).accessToken);
  assert.equal(carga.tenantId, ORGANIZACION_B);
  assert.deepEqual(emisor.repositorio.consultas.at(-1), ['auth_user_id', AUTH_USER_ADMIN]);
});

test('el refresco de una cuenta dada de baja responde 401 y no emite cookie', async (t) => {
  const emisor = await levantarEmisor(t);
  const login = await iniciarSesion(emisor, CORREO_ADMIN);

  emisor.repositorio.filas[0].activo = false;
  const refresco = await enviar(emisor, '/auth/refresh', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(tokenDeRefrescoDe(login)),
  });

  assert.equal(refresco.status, 401);
  assert.equal(cookiesDe(refresco).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('el refresco rechaza un sub que no es UUID aunque la firma sea del emisor', async (t) => {
  const emisor = await levantarEmisor(t);
  const ahora = Math.floor(Date.now() / 1000);

  // Firmado con la clave del emisor: lo único inválido es el `sub`, que además reventaría la
  // comparación contra la columna UUID si llegara a la base.
  const token = tokenDeRefrescoForjado({ sub: 'no-es-un-uuid', iat: ahora, exp: ahora + 600 });

  const respuesta = await enviar(emisor, '/auth/refresh', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(token),
  });

  assert.equal(respuesta.status, 401);
  assert.equal(
    emisor.repositorio.consultas.some((consulta) => consulta[0] === 'auth_user_id'),
    false,
    'ni siquiera se consulta la tabla con un identificador inválido',
  );
});

// ===============================
// la semilla dejó de ser la fuente
// ===============================

test('el arranque no lee usuarios.json: el archivo es solo la semilla documentada', () => {
  for (const archivo of ['server.mjs', 'usuarios.mjs']) {
    const fuente = sinComentarios(readFileSync(path.join(DIRECTORIO, archivo), 'utf8'));
    assert.ok(
      !/usuarios\.json/.test(fuente),
      `${archivo} no debe referenciar usuarios.json en el código: la cuenta se resuelve en la tabla usuario`,
    );
  }
});

/**
 * Quita comentarios de bloque y de línea para distinguir «lo menciona la documentación» de «el
 * código lo usa». Las cadenas con `//` van a inicio de línea en muy pocos casos, así que basta
 * con ignorar los comentarios de línea que empiezan la línea.
 */
function sinComentarios(fuente) {
  return fuente.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/**
 * Repositorio que arranca bien y falla en la consulta de login: así el 500 se prueba sin una
 * base de datos real.
 */
function repositorioQueFallaEnConsulta() {
  return {
    async verificarConexion() {},
    async buscarPorCorreo() {
      throw new Error('connect ECONNREFUSED 127.0.0.1:5432');
    },
    async buscarPorAuthUserId() {
      throw new Error('connect ECONNREFUSED 127.0.0.1:5432');
    },
    async cerrar() {},
  };
}
