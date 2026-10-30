// Pruebas de arranque fail-fast: el emisor no levanta sin clave privada RSA válida ni sin
// contraseña de desarrollo, y publica la huella de la clave para emparejarla con el backend.

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { huellaFormateada, iniciarServidor, leerConfiguracion } from '../server.mjs';

import { CLAVE_PRIVADA, CONTRASENA, huellaEsperada, levantarEmisor } from './ayuda.mjs';

const SERVIDOR = path.join(
  path.dirname(path.dirname(fileURLToPath(import.meta.url))),
  'server.mjs',
);

/** Directorio temporal propio de una prueba, con limpieza garantizada. */
function directorioTemporal(t) {
  const directorio = mkdtempSync(path.join(tmpdir(), 'dev-auth-config-'));
  t.after(() => rmSync(directorio, { recursive: true, force: true }));
  return directorio;
}

test('sin AUTH_PRIVATE_KEY_PATH el arranque falla nombrando la variable', () => {
  assert.throws(
    () => leerConfiguracion({ DEV_AUTH_PASSWORD: CONTRASENA }),
    /AUTH_PRIVATE_KEY_PATH/,
  );
});

test('sin DEV_AUTH_PASSWORD el arranque falla nombrando la variable', () => {
  assert.throws(
    () => leerConfiguracion({ AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem' }),
    /DEV_AUTH_PASSWORD/,
  );
});

test('con una ruta de clave inexistente el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const config = leerConfiguracion({
    AUTH_PRIVATE_KEY_PATH: path.join(directorio, 'no-existe.pem'),
    DEV_AUTH_PASSWORD: CONTRASENA,
  });

  await assert.rejects(iniciarServidor(config), /no-existe\.pem/);
});

test('con una clave que no es RSA el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'ec.pem');
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  writeFileSync(rutaClave, privateKey.export({ type: 'pkcs8', format: 'pem' }));

  const config = leerConfiguracion({
    AUTH_PRIVATE_KEY_PATH: rutaClave,
    DEV_AUTH_PASSWORD: CONTRASENA,
  });

  await assert.rejects(iniciarServidor(config), /RSA/);
});

test('con un PEM ilegible como clave el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'basura.pem');
  writeFileSync(rutaClave, 'esto no es una clave\n');

  const config = leerConfiguracion({
    AUTH_PRIVATE_KEY_PATH: rutaClave,
    DEV_AUTH_PASSWORD: CONTRASENA,
  });

  await assert.rejects(iniciarServidor(config), /clave privada/i);
});

test('la huella publicada es el SHA-256 del DER SPKI de la clave pública', async (t) => {
  const emisor = await levantarEmisor(t);

  assert.match(emisor.huella, /^[0-9a-f]{64}$/, 'la huella es SHA-256 en hexadecimal');
  assert.equal(emisor.huella, huellaEsperada(), 'coincide con el DER SPKI de la pública derivada');

  // Segunda comprobación independiente: la receta que el README le da al desarrollador para
  // emparejar con el backend es Base64 del DER SPKI -> SHA-256. Se recalcula aquí con
  // node:crypto y con el camino base64, sin reutilizar las funciones bajo prueba.
  const derSpki = createPublicKey(createPrivateKey(CLAVE_PRIVADA)).export({ type: 'spki', format: 'der' });
  const huellaPorReceta = createHash('sha256')
    .update(Buffer.from(derSpki.toString('base64'), 'base64'))
    .digest('hex');
  assert.equal(emisor.huella, huellaPorReceta, 'coincide con la receta base64 -d | sha256sum del README');
});

test('iss/aud salen del entorno y por defecto son galeno-dev / galeno-api', () => {
  const porDefecto = leerConfiguracion({
    AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem',
    DEV_AUTH_PASSWORD: CONTRASENA,
  });
  assert.equal(porDefecto.emisor, 'galeno-dev');
  assert.equal(porDefecto.audiencia, 'galeno-api');
  assert.equal(porDefecto.puerto, 4001, 'el puerto por defecto coincide con AUTH_SERVICE_URL');

  const propio = leerConfiguracion({
    AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem',
    DEV_AUTH_PASSWORD: CONTRASENA,
    AUTH_ISSUER: 'otro-emisor',
    AUTH_AUDIENCE: 'otra-audiencia',
    DEV_AUTH_PORT: '0',
  });
  assert.equal(propio.emisor, 'otro-emisor');
  assert.equal(propio.audiencia, 'otra-audiencia');
  assert.equal(propio.puerto, 0);
});

test('el proceso imprime la huella de la clave pública al arrancar', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'privada.pem');
  writeFileSync(rutaClave, CLAVE_PRIVADA);

  const proceso = spawn(process.execPath, [SERVIDOR], {
    env: {
      ...process.env,
      AUTH_PRIVATE_KEY_PATH: rutaClave,
      DEV_AUTH_PASSWORD: CONTRASENA,
      DEV_AUTH_PORT: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    if (proceso.exitCode === null) {
      proceso.kill('SIGTERM');
    }
  });

  const salida = await esperarSalida(proceso, /Huella SHA-256/);
  assert.match(salida, new RegExp(huellaFormateada(huellaEsperada())), 'la huella impresa es la de la clave');
  assert.match(salida, /http:\/\/localhost:\d+/, 'imprime la URL real (puerto efímero en las pruebas)');
  assert.match(salida, /no es el AuthService del producto/i);
  assert.ok(!/PRIVATE KEY/.test(salida), 'la salida no filtra la clave privada');

  proceso.kill('SIGTERM');
  if (proceso.exitCode === null) {
    await new Promise((resolver) => proceso.once('exit', resolver));
  }
});

test('el proceso termina con código 1 y un mensaje claro si falta la clave', async (t) => {
  const proceso = spawn(process.execPath, [SERVIDOR], {
    env: { ...process.env, AUTH_PRIVATE_KEY_PATH: '', DEV_AUTH_PASSWORD: CONTRASENA, DEV_AUTH_PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    if (proceso.exitCode === null) {
      proceso.kill('SIGTERM');
    }
  });

  let errores = '';
  let salida = '';
  proceso.stderr.on('data', (trozo) => {
    errores += trozo.toString();
  });
  proceso.stdout.on('data', (trozo) => {
    salida += trozo.toString();
  });
  const codigo = await new Promise((resolver) => proceso.once('exit', resolver));

  assert.equal(codigo, 1);
  assert.match(errores, /AUTH_PRIVATE_KEY_PATH/, 'el mensaje nombra la variable que falta');
  assert.equal(salida, '', 'no anuncia un arranque que no ocurrió');
});

/**
 * Acumula stdout hasta que aparece el patrón esperado o se agota el tiempo.
 *
 * @param {import('node:child_process').ChildProcess} proceso
 * @param {RegExp} patron
 */
function esperarSalida(proceso, patron) {
  return new Promise((resolver, rechazar) => {
    let acumulado = '';
    const temporizador = setTimeout(() => {
      rechazar(new Error(`El emisor no imprimió ${patron} a tiempo. Salida:\n${acumulado}`));
    }, 15000);

    proceso.stdout.on('data', (trozo) => {
      acumulado += trozo.toString();
      if (patron.test(acumulado)) {
        clearTimeout(temporizador);
        resolver(acumulado);
      }
    });
    proceso.once('exit', (codigo) => {
      clearTimeout(temporizador);
      rechazar(new Error(`El emisor terminó antes de imprimir (código ${codigo}):\n${acumulado}`));
    });
  });
}
