// Pruebas de emisión de tokens: login, claims obligatorios, TTL y ausencia de caminos
// que emitan un token sin `exp`.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  CONTRASENA,
  CORREO_ADMIN,
  CORREO_MEDICO,
  CORREO_PACIENTE,
  cabeceraDeCookie,
  cookiesDe,
  cuerpoJson,
  decodificarJwt,
  enviar,
  firmaEsValida,
  iniciarSesion,
  levantarEmisor,
  tokenDeRefrescoDe,
} from './ayuda.mjs';

const UUID_BIEN_FORMADO = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const RUTA_USUARIOS = path.join(
  path.dirname(path.dirname(fileURLToPath(import.meta.url))),
  'usuarios.json',
);

test('la fixture declara un usuario por rol y no guarda contraseñas', () => {
  const fixture = JSON.parse(readFileSync(RUTA_USUARIOS, 'utf8'));
  const roles = new Set(fixture.usuarios.map((usuario) => usuario.role));
  assert.deepEqual([...roles].sort(), ['ADMIN', 'MEDICO', 'PACIENTE']);
  for (const usuario of fixture.usuarios) {
    assert.match(usuario.email, /@dev-auth\.local$/, 'los correos deben ser sintéticos');
    assert.ok(!Object.keys(usuario).some((clave) => /password|contrasena|hash/i.test(clave)));
  }
});

test('login con credenciales válidas devuelve un token que verifica con la clave pública derivada', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await iniciarSesion(emisor, CORREO_MEDICO);
  assert.equal(respuesta.status, 200);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(typeof cuerpo.accessToken, 'string');

  const { cabecera, carga } = decodificarJwt(cuerpo.accessToken);
  assert.equal(cabecera.alg, 'RS256', 'el algoritmo de la cabecera debe ser RS256');
  assert.equal(firmaEsValida(cuerpo.accessToken), true, 'la firma debe verificar con la pública');

  // `user` refleja exactamente los claims del token.
  assert.deepEqual(cuerpo.user, {
    sub: carga.sub,
    email: CORREO_MEDICO,
    role: 'MEDICO',
    tenantId: carga.tenantId,
    medicoId: carga.medicoId,
  });
  assert.match(cuerpo.user.sub, UUID_BIEN_FORMADO);
  assert.match(cuerpo.user.tenantId, UUID_BIEN_FORMADO);
  assert.match(cuerpo.user.medicoId, UUID_BIEN_FORMADO);
});

test('el token lleva los claims obligatorios, iss/aud configurados y exp de 30 minutos', async (t) => {
  const emisor = await levantarEmisor(t);

  for (const correo of [CORREO_ADMIN, CORREO_MEDICO, CORREO_PACIENTE]) {
    const respuesta = await iniciarSesion(emisor, correo);
    assert.equal(respuesta.status, 200, `el login de ${correo} debe responder 200`);

    const { carga } = decodificarJwt((await cuerpoJson(respuesta)).accessToken);
    for (const obligatorio of ['sub', 'role', 'tenantId', 'exp']) {
      assert.ok(carga[obligatorio] !== undefined, `falta el claim obligatorio ${obligatorio}`);
    }
    assert.match(carga.sub, UUID_BIEN_FORMADO);
    assert.match(carga.tenantId, UUID_BIEN_FORMADO);
    assert.equal(carga.iss, 'galeno-dev');
    assert.equal(carga.aud, 'galeno-api');
    assert.equal(typeof carga.exp, 'number');
    assert.equal(carga.exp - carga.iat, 1800, 'el TTL del access token es de 30 minutos');
    assert.ok(carga.exp * 1000 > Date.now(), 'el token nace vigente');
  }
});

test('ningún camino de emisión produce un token sin exp', async (t) => {
  const emisor = await levantarEmisor(t);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const tokens = [(await cuerpoJson(login)).accessToken, tokenDeRefrescoDe(login)];

  // Encadena varios refrescos: cada uno emite un access token y rota el refresco.
  let respuesta = login;
  for (let vuelta = 0; vuelta < 3; vuelta += 1) {
    const token = tokenDeRefrescoDe(respuesta);
    respuesta = await enviar(emisor, '/auth/refresh', {
      sinCuerpo: true,
      cabeceras: cabeceraDeCookie(token),
    });
    assert.equal(respuesta.status, 200);
    tokens.push((await cuerpoJson(respuesta)).accessToken, tokenDeRefrescoDe(respuesta));
  }

  assert.equal(tokens.length, 8);
  for (const token of tokens) {
    assert.equal(typeof token, 'string');
    assert.ok(token.length > 0);
    const { carga } = decodificarJwt(token);
    assert.equal(typeof carga.exp, 'number', 'todo token emitido lleva exp numérico');
    assert.equal(carga.exp - carga.iat, 1800, 'ni el access ni el refresco superan los 30 minutos');
  }
});

test('contraseña incorrecta responde 401 sin emitir token ni cookie', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN, 'contraseña-equivocada');
  assert.equal(respuesta.status, 401);
  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 401);
  assert.equal(cuerpo.mensaje, 'Credenciales inválidas');
  assert.ok(!('accessToken' in cuerpo));
  assert.equal(cookiesDe(respuesta).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('usuario inexistente responde 401 con el mismo cuerpo que una contraseña incorrecta', async (t) => {
  const emisor = await levantarEmisor(t);

  const inexistente = await iniciarSesion(emisor, 'nadie@dev-auth.local', CONTRASENA);
  const equivocada = await iniciarSesion(emisor, CORREO_ADMIN, 'contraseña-equivocada');

  assert.equal(inexistente.status, 401);
  assert.equal(equivocada.status, 401);

  const cuerpoInexistente = await cuerpoJson(inexistente);
  const cuerpoEquivocado = await cuerpoJson(equivocada);
  delete cuerpoInexistente.marcaTemporal;
  delete cuerpoEquivocado.marcaTemporal;
  assert.deepEqual(cuerpoInexistente, cuerpoEquivocado, 'no debe revelarse si el correo existe');
});

test('un Authorization basura no concede nada', async (t) => {
  const emisor = await levantarEmisor(t);
  const basura = { Authorization: 'Bearer basura.no.es-un-token' };

  // El refresco se autentica por cookie, no por cabecera: sin cookie no hay sesión.
  const refresh = await enviar(emisor, '/auth/refresh', { sinCuerpo: true, cabeceras: basura });
  assert.equal(refresh.status, 401);

  // El login sigue exigiendo credenciales válidas aunque traiga cabecera.
  const login = await enviar(emisor, '/auth/login', {
    cabeceras: basura,
    cuerpo: { email: CORREO_ADMIN, password: 'contraseña-equivocada' },
  });
  assert.equal(login.status, 401);

  // El logout es local: borra la cookie y no valida el token que le manden.
  const logout = await enviar(emisor, '/auth/logout', { sinCuerpo: true, cabeceras: basura });
  assert.equal(logout.status, 204);
  assert.equal(await logout.text(), '', 'el 204 no devuelve cuerpo ni token');
});
