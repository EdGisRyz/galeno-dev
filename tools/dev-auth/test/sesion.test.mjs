// Pruebas de sesión: refresco deslizante en cookie httpOnly, logout y CORS con
// credenciales para el frontend de desarrollo.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  CLAVE_PRIVADA_AJENA,
  CONTRASENA,
  CORREO_ADMIN,
  ORIGEN_FRONTEND,
  cabeceraDeCookie,
  cookiesDe,
  cuerpoJson,
  decodificarJwt,
  enviar,
  firmaEsValida,
  iniciarSesion,
  levantarEmisor,
  tokenDeRefrescoDe,
  tokenDeRefrescoForjado,
} from './ayuda.mjs';

test('login deja una cookie de refresco httpOnly, SameSite=Strict, Path=/auth y 30 minutos', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await iniciarSesion(emisor, CORREO_ADMIN);
  const cookie = cookiesDe(respuesta).find((valor) => valor.startsWith('refresh_token='));

  assert.ok(cookie, 'el login debe setear la cookie de refresco');
  assert.match(cookie, /; HttpOnly/);
  assert.match(cookie, /; SameSite=Strict/);
  assert.match(cookie, /; Path=\/auth/);
  assert.match(cookie, /; Max-Age=1800/);
  assert.equal(firmaEsValida(tokenDeRefrescoDe(respuesta)), true);
});

test('refresh con cookie válida devuelve un access token nuevo y rota la cookie', async (t) => {
  const emisor = await levantarEmisor(t);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const primerAccess = (await cuerpoJson(login)).accessToken;
  const primerRefresco = tokenDeRefrescoDe(login);

  const refresco = await enviar(emisor, '/auth/refresh', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(primerRefresco),
  });
  assert.equal(refresco.status, 200);

  const segundoAccess = (await cuerpoJson(refresco)).accessToken;
  const segundoRefresco = tokenDeRefrescoDe(refresco);

  assert.notEqual(segundoAccess, primerAccess, 'el refresh emite un access token nuevo');
  assert.equal(firmaEsValida(segundoAccess), true);
  assert.ok(segundoRefresco, 'el refresh rota la cookie');
  assert.notEqual(segundoRefresco, primerRefresco, 'el token de refresco rotado es distinto');
  assert.equal(firmaEsValida(segundoRefresco), true);

  const cookie = cookiesDe(refresco).find((valor) => valor.startsWith('refresh_token='));
  assert.match(cookie, /; HttpOnly/);
  assert.match(cookie, /; SameSite=Strict/);
  assert.match(cookie, /; Max-Age=1800/, 'el refresco es deslizante: vuelve a 30 minutos');

  // La cookie anterior ya no sirve para nada distinto que la nueva: ambas se verifican,
  // pero la rotación renueva el vencimiento.
  const { carga } = decodificarJwt(segundoRefresco);
  assert.equal(carga.tipo, 'refresh');
  assert.equal(carga.exp - carga.iat, 1800);
});

test('refresh sin cookie responde 401 con el contrato de error', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await enviar(emisor, '/auth/refresh', { sinCuerpo: true });
  assert.equal(respuesta.status, 401);
  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 401);
  assert.equal(cuerpo.error, 'Unauthorized');
  assert.equal(cuerpo.ruta, '/auth/refresh');
  assert.equal(cookiesDe(respuesta).some((cookie) => cookie.startsWith('refresh_token=')), false);
});

test('refresh con cookie falsificada, de otro tipo o con el token de acceso responde 401', async (t) => {
  const emisor = await levantarEmisor(t);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const cuerpoLogin = await cuerpoJson(login);

  const falsificada = `${tokenDeRefrescoDe(login)}x`;
  for (const cookie of [falsificada, 'no-es-un-jwt', cuerpoLogin.accessToken]) {
    const respuesta = await enviar(emisor, '/auth/refresh', {
      sinCuerpo: true,
      cabeceras: cabeceraDeCookie(cookie),
    });
    assert.equal(respuesta.status, 401, `la cookie ${cookie.slice(0, 12)}… debe rechazarse`);
    assert.equal(
      cookiesDe(respuesta).some((valor) => valor.startsWith('refresh_token=')),
      false,
      'un refresco rechazado no emite cookie nueva',
    );
  }
});

test('refresh con un token bien firmado pero vencido responde 401 y no emite cookie', async (t) => {
  const emisor = await levantarEmisor(t);
  const { sub } = (await cuerpoJson(await iniciarSesion(emisor, CORREO_ADMIN))).user;

  const ahora = Math.floor(Date.now() / 1000);
  const vencido = tokenDeRefrescoForjado({ sub, iat: ahora - 3600, exp: ahora - 60 });

  // La firma es la del emisor: lo único que lo descalifica es el vencimiento, así que el
  // 401 prueba la comprobación de `exp` y no la de la firma.
  assert.equal(firmaEsValida(vencido), true, 'el token forjado sí verifica con la pública');

  const respuesta = await enviar(emisor, '/auth/refresh', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(vencido),
  });

  assert.equal(respuesta.status, 401);
  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.codigo, 401);
  assert.equal(
    cookiesDe(respuesta).some((valor) => valor.startsWith('refresh_token=')),
    false,
    'un refresco vencido no emite cookie nueva',
  );
});

test('refresh rechaza alg=none y una firma de otra clave RSA', async (t) => {
  const emisor = await levantarEmisor(t);
  const { sub } = (await cuerpoJson(await iniciarSesion(emisor, CORREO_ADMIN))).user;
  const ahora = Math.floor(Date.now() / 1000);
  const vigente = ahora + 600;

  const sinFirma = tokenDeRefrescoForjado({ sub, exp: vigente, algoritmo: 'none', firmar: false });
  const claveAjena = tokenDeRefrescoForjado({ sub, exp: vigente, clavePrivada: CLAVE_PRIVADA_AJENA });

  // Los dos tokens están bien construidos: el primero declara otro algoritmo y el segundo
  // verifica con OTRA clave, no con la del emisor (por eso firmaEsValida da false).
  assert.equal(decodificarJwt(sinFirma).cabecera.alg, 'none');
  assert.equal(decodificarJwt(sinFirma).firma, '');
  assert.equal(firmaEsValida(claveAjena), false, 'la firma ajena no verifica con la pública del emisor');

  for (const [caso, token] of [
    ['alg=none', sinFirma],
    ['clave RSA ajena', claveAjena],
  ]) {
    const respuesta = await enviar(emisor, '/auth/refresh', {
      sinCuerpo: true,
      cabeceras: cabeceraDeCookie(token),
    });
    assert.equal(respuesta.status, 401, `el refresco debe rechazar el token con ${caso}`);
    assert.equal(
      cookiesDe(respuesta).some((valor) => valor.startsWith('refresh_token=')),
      false,
      `un refresco rechazado por ${caso} no emite cookie nueva`,
    );
  }
});

test('logout borra la cookie de refresco y responde 204', async (t) => {
  const emisor = await levantarEmisor(t);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const respuesta = await enviar(emisor, '/auth/logout', {
    sinCuerpo: true,
    cabeceras: cabeceraDeCookie(tokenDeRefrescoDe(login)),
  });

  assert.equal(respuesta.status, 204);
  assert.equal(await respuesta.text(), '');

  const cookie = cookiesDe(respuesta).find((valor) => valor.startsWith('refresh_token='));
  assert.ok(cookie, 'el logout debe reescribir la cookie');
  assert.match(cookie, /refresh_token=;/);
  assert.match(cookie, /; Max-Age=0/);
  assert.match(cookie, /; Path=\/auth/);
  assert.match(cookie, /; HttpOnly/);
});

test('health responde 200 con un JSON pequeño y sin secretos', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await enviar(emisor, '/health', { metodo: 'GET', sinCuerpo: true });
  assert.equal(respuesta.status, 200);

  const cuerpo = await cuerpoJson(respuesta);
  assert.equal(cuerpo.estado, 'ok');
  assert.equal(cuerpo.servicio, 'dev-auth');
  const texto = JSON.stringify(cuerpo);
  assert.ok(!/BEGIN|PRIVATE|password|contrasena/i.test(texto), 'el health no filtra secretos');
});

test('el preflight OPTIONS devuelve el origen permitido con credenciales', async (t) => {
  const emisor = await levantarEmisor(t);

  for (const ruta of ['/auth/login', '/auth/refresh', '/auth/logout']) {
    const respuesta = await enviar(emisor, ruta, {
      metodo: 'OPTIONS',
      sinCuerpo: true,
      cabeceras: {
        Origin: ORIGEN_FRONTEND,
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type',
      },
    });
    assert.equal(respuesta.status, 204, `el preflight de ${ruta} debe responder 204`);
    assert.equal(respuesta.headers.get('access-control-allow-origin'), ORIGEN_FRONTEND);
    assert.equal(respuesta.headers.get('access-control-allow-credentials'), 'true');
    assert.match(respuesta.headers.get('access-control-allow-methods') ?? '', /POST/);
    assert.match(respuesta.headers.get('access-control-allow-headers') ?? '', /content-type/i);
  }
});

test('CORS: un origen no permitido no recibe cabeceras de permiso', async (t) => {
  const emisor = await levantarEmisor(t);

  const preflight = await enviar(emisor, '/auth/login', {
    metodo: 'OPTIONS',
    sinCuerpo: true,
    cabeceras: { Origin: 'http://malicioso.example', 'Access-Control-Request-Method': 'POST' },
  });
  assert.equal(preflight.headers.get('access-control-allow-origin'), null);
  assert.equal(preflight.headers.get('access-control-allow-credentials'), null);

  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  assert.equal(login.headers.get('access-control-allow-origin'), null);
});

test('el login responde con CORS para el origen del frontend', async (t) => {
  const emisor = await levantarEmisor(t);

  const respuesta = await enviar(emisor, '/auth/login', {
    cabeceras: { Origin: ORIGEN_FRONTEND },
    cuerpo: { email: CORREO_ADMIN, password: CONTRASENA },
  });
  assert.equal(respuesta.status, 200);
  assert.equal(respuesta.headers.get('access-control-allow-origin'), ORIGEN_FRONTEND);
  assert.equal(respuesta.headers.get('access-control-allow-credentials'), 'true');
});

test('refresh y logout rechazan un Origin distinto al del frontend (403)', async (t) => {
  const emisor = await levantarEmisor(t);
  const login = await iniciarSesion(emisor, CORREO_ADMIN);
  const cookie = cabeceraDeCookie(tokenDeRefrescoDe(login));

  for (const ruta of ['/auth/refresh', '/auth/logout']) {
    const respuesta = await enviar(emisor, ruta, {
      sinCuerpo: true,
      cabeceras: { ...cookie, Origin: 'http://malicioso.example' },
    });
    assert.equal(respuesta.status, 403, `${ruta} debe rechazar un Origin ajeno`);
    const cuerpo = await cuerpoJson(respuesta);
    assert.equal(cuerpo.codigo, 403);
  }
});

test('una ruta desconocida responde 404 y un método no permitido responde 405', async (t) => {
  const emisor = await levantarEmisor(t);

  const desconocida = await enviar(emisor, '/auth/nada', { sinCuerpo: true });
  assert.equal(desconocida.status, 404);

  const metodo = await enviar(emisor, '/auth/login', { metodo: 'GET', sinCuerpo: true });
  assert.equal(metodo.status, 405);
  assert.match(metodo.headers.get('allow') ?? '', /POST/);
});
