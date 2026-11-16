// Pruebas de arranque fail-fast: el emisor no levanta sin clave privada RSA válida, sin
// contraseña de desarrollo ni sin base de datos configurada, y publica la huella de la clave
// para emparejarla con el backend.

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { huellaFormateada, imprimirArranque, iniciarServidor, leerConfiguracion } from '../server.mjs';
import { crearRepositorioPostgres, descripcionDeBaseDeDatos } from '../usuarios.mjs';

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

/** Configuración mínima con base de datos por variables PG*. */
function configuracionValida(sobrescrituras = {}) {
  return {
    AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem',
    DEV_AUTH_PASSWORD: CONTRASENA,
    PGDATABASE: 'galeno_clinica_dev',
    ...sobrescrituras,
  };
}

test('sin AUTH_PRIVATE_KEY_PATH el arranque falla nombrando la variable', () => {
  assert.throws(
    () => leerConfiguracion({ DEV_AUTH_PASSWORD: CONTRASENA, PGDATABASE: 'galeno_clinica_dev' }),
    /AUTH_PRIVATE_KEY_PATH/,
  );
});

test('sin DEV_AUTH_PASSWORD el arranque falla nombrando la variable', () => {
  assert.throws(
    () => leerConfiguracion({ AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem', PGDATABASE: 'x' }),
    /DEV_AUTH_PASSWORD/,
  );
});

test('sin DATABASE_URL ni PGDATABASE el arranque falla nombrando las variables', () => {
  assert.throws(
    () => leerConfiguracion({ AUTH_PRIVATE_KEY_PATH: '/tmp/privada.pem', DEV_AUTH_PASSWORD: CONTRASENA }),
    /DATABASE_URL/,
    'el mensaje dice qué variable falta para no adivinar por qué no arranca',
  );
});

test('DATABASE_URL arma la conexión y su descripción no expone la contraseña', () => {
  const config = leerConfiguracion(
    configuracionValida({
      PGDATABASE: '',
      DATABASE_URL: 'postgresql://emisor:s3creto@db.local:5433/galeno_clinica_dev',
    }),
  );

  assert.equal(config.baseDatos.url, 'postgresql://emisor:s3creto@db.local:5433/galeno_clinica_dev');
  assert.equal(config.baseDatos.descripcion, 'db.local:5433/galeno_clinica_dev');
  assert.ok(!config.baseDatos.descripcion.includes('s3creto'), 'la descripción se imprime al arrancar');
});

test('sin DATABASE_URL la conexión se arma con PGHOST, PGPORT, PGDATABASE, PGUSER y PGPASSWORD', () => {
  const config = leerConfiguracion(
    configuracionValida({
      PGHOST: 'db.local',
      PGPORT: '5433',
      PGUSER: 'emisor',
      PGPASSWORD: 's3creto',
    }),
  );

  assert.equal(config.baseDatos.url, null);
  assert.equal(config.baseDatos.host, 'db.local');
  assert.equal(config.baseDatos.puerto, 5433);
  assert.equal(config.baseDatos.base, 'galeno_clinica_dev');
  assert.equal(config.baseDatos.usuario, 'emisor');
  assert.equal(config.baseDatos.contrasena, 's3creto');
  assert.equal(config.baseDatos.descripcion, 'db.local:5433/galeno_clinica_dev');
});

test('un PGPORT inválido falla con un mensaje explícito', () => {
  assert.throws(() => leerConfiguracion(configuracionValida({ PGPORT: 'no-es-un-puerto' })), /PGPORT/);
});

test('descripcionDeBaseDeDatos nunca incluye credenciales', () => {
  const descripcion = descripcionDeBaseDeDatos({
    url: 'postgres://usuario:clave@localhost:5432/galeno',
  });
  assert.equal(descripcion, 'localhost:5432/galeno');
  assert.equal(descripcionDeBaseDeDatos({ url: 'no-es-una-url' }), 'la base configurada en DATABASE_URL');
});

test('sin la dependencia pg el arranque falla indicando npm install', async (t) => {
  const pgDisponible = await import('pg').then(
    () => true,
    () => false,
  );
  if (pgDisponible) {
    t.skip('la dependencia pg está instalada: el caso de la falta no aplica');
    return;
  }
  await assert.rejects(
    crearRepositorioPostgres({ url: 'postgres://x@localhost/base' }),
    /npm install/,
    'el mensaje dice cómo resolverlo en vez de fallar con un error de módulo crudo',
  );
});

test('con una ruta de clave inexistente el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const config = leerConfiguracion(
    configuracionValida({ AUTH_PRIVATE_KEY_PATH: path.join(directorio, 'no-existe.pem') }),
  );

  await assert.rejects(iniciarServidor(config), /no-existe\.pem/);
});

test('con una clave que no es RSA el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'ec.pem');
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  writeFileSync(rutaClave, privateKey.export({ type: 'pkcs8', format: 'pem' }));

  const config = leerConfiguracion(configuracionValida({ AUTH_PRIVATE_KEY_PATH: rutaClave }));

  await assert.rejects(iniciarServidor(config), /RSA/);
});

test('con un PEM ilegible como clave el arranque falla', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'basura.pem');
  writeFileSync(rutaClave, 'esto no es una clave\n');

  const config = leerConfiguracion(configuracionValida({ AUTH_PRIVATE_KEY_PATH: rutaClave }));

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
  const porDefecto = leerConfiguracion(configuracionValida());
  assert.equal(porDefecto.emisor, 'galeno-dev');
  assert.equal(porDefecto.audiencia, 'galeno-api');
  assert.equal(porDefecto.puerto, 4001, 'el puerto por defecto coincide con AUTH_SERVICE_URL');

  const propio = leerConfiguracion(
    configuracionValida({
      AUTH_ISSUER: 'otro-emisor',
      AUTH_AUDIENCE: 'otra-audiencia',
      DEV_AUTH_PORT: '0',
    }),
  );
  assert.equal(propio.emisor, 'otro-emisor');
  assert.equal(propio.audiencia, 'otra-audiencia');
  assert.equal(propio.puerto, 0);
});

test('el resumen de arranque publica la huella, la URL y contra qué resuelve los usuarios', async (t) => {
  const emisor = await levantarEmisor(t);

  let salida = '';
  imprimirArranque(emisor, (texto) => {
    salida += texto;
  });

  assert.match(salida, new RegExp(huellaFormateada(huellaEsperada())), 'la huella impresa es la de la clave');
  assert.match(salida, /http:\/\/localhost:\d+/, 'imprime la URL real (puerto efímero en las pruebas)');
  assert.match(salida, /no es el AuthService del producto/i);
  assert.match(salida, /tabla usuario/i, 'dice de dónde salen las cuentas');
  assert.match(salida, /galeno_clinica_dev/, 'dice contra qué base resuelve, sin credenciales');
  assert.ok(!/PRIVATE KEY/.test(salida), 'la salida no filtra la clave privada');
  assert.ok(!/s3creto|PGPASSWORD/.test(salida), 'la salida no filtra credenciales');
});

test('el proceso termina con código 1 y un mensaje claro si falta la clave', async (t) => {
  const proceso = spawn(process.execPath, [SERVIDOR], {
    env: {
      ...process.env,
      AUTH_PRIVATE_KEY_PATH: '',
      DEV_AUTH_PASSWORD: CONTRASENA,
      PGDATABASE: 'galeno_clinica_dev',
      DEV_AUTH_PORT: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    if (proceso.exitCode === null) {
      proceso.kill('SIGTERM');
    }
  });

  const { codigo, errores, salida } = await esperarFin(proceso);

  assert.equal(codigo, 1);
  assert.match(errores, /AUTH_PRIVATE_KEY_PATH/, 'el mensaje nombra la variable que falta');
  assert.equal(salida, '', 'no anuncia un arranque que no ocurrió');
});

test('el proceso termina con código 1 y un mensaje claro si no hay base de datos configurada', async (t) => {
  const directorio = directorioTemporal(t);
  const rutaClave = path.join(directorio, 'privada.pem');
  writeFileSync(rutaClave, CLAVE_PRIVADA);

  const entorno = { ...process.env, AUTH_PRIVATE_KEY_PATH: rutaClave, DEV_AUTH_PASSWORD: CONTRASENA };
  for (const variable of ['DATABASE_URL', 'PGDATABASE', 'PGHOST', 'PGPORT', 'PGUSER', 'PGPASSWORD']) {
    delete entorno[variable];
  }

  const proceso = spawn(process.execPath, [SERVIDOR], {
    env: { ...entorno, DEV_AUTH_PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    if (proceso.exitCode === null) {
      proceso.kill('SIGTERM');
    }
  });

  const { codigo, errores, salida } = await esperarFin(proceso);

  assert.equal(codigo, 1, 'sin base configurada el emisor no arranca');
  assert.match(errores, /DATABASE_URL/, 'el mensaje nombra la configuración que falta');
  assert.match(errores, /PGDATABASE/);
  assert.equal(salida, '', 'no anuncia un arranque que no ocurrió');
});

/** Acumula la salida del proceso hasta que termina. */
function esperarFin(proceso) {
  return new Promise((resolver) => {
    let salida = '';
    let errores = '';
    proceso.stdout.on('data', (trozo) => {
      salida += trozo.toString();
    });
    proceso.stderr.on('data', (trozo) => {
      errores += trozo.toString();
    });
    proceso.once('exit', (codigo) => resolver({ codigo, salida, errores }));
  });
}
