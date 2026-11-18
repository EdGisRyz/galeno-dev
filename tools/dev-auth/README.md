# dev-auth — emisor local de tokens RS256 (solo desarrollo)

Este directorio contiene un **servicio HTTP mínimo que firma tokens RS256 para trabajar en
local**. Es una **herramienta de desarrollo, no el AuthService del producto**: la contraseña es
una sola para todos, no guarda credenciales, no emite tokens en ningún entorno compartido y no
forma parte del artefacto de ninguna aplicación.

El nombre `dev-auth` es deliberado: dice en voz alta que es de desarrollo. Un nombre neutro
(`auth-service`, `auth`) invitaría a confundirlo con el servicio real y a reutilizarlo fuera
de local.

## Por qué existe

El backend **solo valida** tokens (ADR-008, R8.2): no firma, no emite y no toca contraseñas.
Sin un emisor no hay nada que validar, así que el login del frontend no se puede integrar ni
probar de extremo a extremo. Este proceso llena ese hueco mientras no exista el AuthService de
InToGlobe, y nada más.

### El emisor no debe ser más listo que la plataforma real

Este proceso es un **sustituto**, no una fuente de verdad. Todo comportamiento que se le enseñe
existe solo acá: si revelara algo que la plataforma real no revela —por ejemplo, el estado de una
cuenta en el refresco, donde hoy responde un 401 genérico **a propósito**—, el frontend se
escribiría contra un contrato que producción no cumple, y quedaría cubierto por pruebas que nunca
se ejecutan. Peor todavía: este código es también **documentación ejecutable** de cómo se comporta
el servicio de identidad, así que lo que invente se leerá como si fuera real.

**Regla**: imitarlo, incluidas sus limitaciones; cuando falte un dato, resolverlo del lado del
cliente con la información que sí existe, en lugar de inventarla acá. Un emisor que sabe más deja
de ser un simulador y se convierte en una ficción cómoda: en local todo funciona y algo se rompe en
el primer ambiente real.

## De dónde salen las cuentas

De la tabla **`usuario` de PostgreSQL** (más el `activo` de su médico). **No** de un archivo: el
alta de una clínica crea un administrador nuevo en esa tabla, y un archivo estático no lo
conocería, así que ese administrador no podría iniciar sesión en desarrollo. La tabla es la
única fuente de verdad de la identidad también en local.

`usuarios.json` sigue en el directorio, pero **no se lee al arrancar**: es la semilla que se
carga en la tabla (ver «Semilla de usuarios»).

La resolución está en `usuarios.mjs` y se apoya en dos funciones puras, probables sin base de
datos:

| Función | Qué hace |
|---|---|
| `traducirFilaAClaims(fila)` | Traduce una fila de `usuario` a los claims del token. |
| `evaluarCuenta(filas)` | Decide el resultado del login: `ok`, `inexistente`, `inactiva`, `medico-inactivo`, `ambiguo` o `fila-invalida`. |

## Cómo se ejecuta

```bash
# 1) una sola vez: instala la dependencia pg (única del directorio)
cd tools/dev-auth && npm install

# 2) la contraseña de todos los usuarios (no está en el repo)
export DEV_AUTH_PASSWORD='la-que-elijas'

# 3) ruta al PEM de TU clave privada (ver «Cómo obtener la clave privada»)
export AUTH_PRIVATE_KEY_PATH="$HOME/.vault/galeno-dev/privada.pem"

# 4) conexión a la base: o DATABASE_URL, o el conjunto PG*
export DATABASE_URL='postgresql://postgres:postgres@localhost:5432/galeno_clinica'

# 5) arrancar
npm start
```

`npm start` ejecuta `node server.mjs`; también vale `node tools/dev-auth/server.mjs` desde la
raíz del repositorio. El emisor **no se conecta al arrancar sin comprobar primero** la conexión:
si PostgreSQL no responde, falla ahí y no en el primer login.

Salida esperada al arrancar (extracto):

```
dev-auth · emisor de tokens RS256 para DESARROLLO LOCAL. No es el AuthService del producto.
Escuchando en http://localhost:4001 (el backend espera este servicio en AUTH_SERVICE_URL).
iss=galeno-dev · aud=galeno-api · access=1800s · refresh=1800s
Cuentas de la tabla usuario en localhost:5432/galeno_clinica. La contraseña es la de DEV_AUTH_PASSWORD para todas.
Huella SHA-256 de la clave pública (DER SPKI): 9F:3C:...:A1
Compare esta huella con la clave de AUTH_PUBLIC_KEY del backend: si no coincide, el backend rechazará todos los tokens.
```

El **arranque falla con un mensaje explícito** (código de salida 1) si:

- falta `AUTH_PRIVATE_KEY_PATH` o `DEV_AUTH_PASSWORD`;
- falta `DATABASE_URL` y también `PGDATABASE` (el mensaje nombra las dos alternativas);
- el PEM no se puede leer o no es una clave RSA;
- PostgreSQL no responde o la consulta de comprobación falla;
- la dependencia `pg` no está instalada (el mensaje dice `npm install`).

La huella se imprime siempre: es la forma de detectar el error de emparejar la clave equivocada,
que si no aparece como «todos los tokens son inválidos» sin causa visible.

## Variables de entorno

| Variable | Obligatoria | Default | Para qué |
|---|---|---|---|
| `AUTH_PRIVATE_KEY_PATH` | **Sí** | — | Ruta al PEM de la clave privada **RSA**. Nunca se commitea. |
| `DEV_AUTH_PASSWORD` | **Sí** | — | Contraseña de **todos** los usuarios de desarrollo. No está en el repositorio. |
| `DATABASE_URL` | **Sí**, o el conjunto `PG*` | — | Cadena de conexión (`postgresql://usuario:clave@host:puerto/base`). Tiene prioridad sobre `PG*`. |
| `PGDATABASE` | **Sí** si no hay `DATABASE_URL` | — | Nombre de la base. Es la variable que exige el conjunto `PG*`. |
| `PGHOST` | No | `localhost` | Host de PostgreSQL. |
| `PGPORT` | No | `5432` | Puerto de PostgreSQL. |
| `PGUSER` | No | usuario del sistema | Usuario de PostgreSQL. |
| `PGPASSWORD` | No | — | Contraseña de PostgreSQL. Mejor en `PGPASSWORD`/`.pgpass` que en `DATABASE_URL` versionada. |
| `AUTH_ISSUER` | No | `galeno-dev` | Claim `iss` del token. |
| `AUTH_AUDIENCE` | No | `galeno-api` | Claim `aud` del token. |
| `DEV_AUTH_PORT` | No | `4001` | Puerto local. El default coincide con `AUTH_SERVICE_URL` del backend. |

Ninguna credencial vive en el repositorio: ni la contraseña de los usuarios, ni la clave
privada, ni la de PostgreSQL. `.gitignore` ya cubre `*.pem`, `*.key`, `*.p12`, `*.jks` y `.env*`.

## Cómo obtener la clave privada

La generación y la custodia del par RSA ya están documentadas para el equipo en la guía
externa **`guia-claves-rs256.md`**. No se duplica aquí para
que no existan dos versiones de las mismas instrucciones.

Resumen de lo que esta herramienta espera: la **clave privada en PEM** en la ruta que indique
`AUTH_PRIVATE_KEY_PATH`. El archivo vive en el Vault del IDE o en el gestor de secretos del
desarrollador; **nunca** en el repositorio, en el historial ni en un log.

## Regla de emparejamiento con el backend

El backend recibe **la clave pública**, no la privada, en `AUTH_PUBLIC_KEY`: Base64 del DER
`SubjectPublicKeyInfo` (SPKI) en una sola línea (ADR-009). Como la huella que imprime este
emisor es el SHA-256 de **ese mismo DER SPKI** de la clave pública derivada de la privada, la
comprobación es mecánica:

1. Arranca `dev-auth` y copia la huella SHA-256 (`XX:XX:…`).
2. Calcula la huella de la clave que tiene el backend:

   ```bash
   printf '%s' "$AUTH_PUBLIC_KEY" | base64 -d | sha256sum
   ```

3. Si no coinciden, están emparejando pares distintos y **todos** los tokens serán rechazados
   con `401` aunque la firma sea correcta. Es el fallo más confuso de este montaje, y por eso
   la huella se imprime en cada arranque.

## Endpoints

| Método y ruta | Respuesta | Notas |
|---|---|---|
| `POST /auth/login` | `200` `{ accessToken, user }` | Body `{ "email", "password" }`. Setea la cookie de refresco. |
| | `400` | Body ausente, no JSON o sin `email`/`password`. |
| | `401` | Credenciales inválidas. El correo **no existe** o la contraseña es incorrecta: mismo cuerpo y mismo mensaje, así que no se puede enumerar usuarios. |
| | `403` | La cuenta existe y la contraseña es correcta, pero la **cuenta está dada de baja** (`usuario.activo = false`). |
| | `403` | La cuenta es de un **médico que ya no está activo** (`medico.activo = false`). Decisión de producto: el acceso se cierra en el borde y la cuenta no se toca; reactivar al médico devuelve el acceso. |
| | `409` | El correo está registrado en **dos organizaciones**. El esquema lo permite (el correo es único por organización) y el emisor de desarrollo no tiene con qué desambiguar. |
| | `500` | No se pudo consultar la tabla `usuario`, o hay una fila que no puede convertirse en claims (por ejemplo `rol` nulo o fuera del contrato de claims, un `email` sin `@`, o un identificador que no es un UUID canónico). |
| `POST /auth/refresh` | `200` `{ accessToken }` | Lee la cookie de refresco, verifica firma, tipo, `iss`/`aud` y vencimiento, y **rota** la cookie (deslizante: vuelve a 30 minutos). **Relee la fila de `usuario`**, así que un cambio de organización o una baja se reflejan aquí. |
| | `401` | Cookie ausente, falsificada, de otro tipo (por ejemplo el access token), vencida, o de una cuenta que ya no existe, está dada de baja o es de un médico inactivo. |
| | `500` | No se pudo consultar la tabla `usuario`. |
| `POST /auth/logout` | `204` | Borra la cookie (`Max-Age=0`). Sin cuerpo. No invalida el access token ya emitido: no hay revocación (ADR-012). |
| `GET /health` | `200` | JSON pequeño (`estado`, `servicio`, `algoritmo`, TTLs, `iss`/`aud`). Sin secretos. Útil como sonda del frontend. |

La contraseña se comprueba **antes** de consultar la identidad: sin la contraseña correcta no se
revela el estado de ninguna cuenta, y el `401` del correo inexistente es idéntico al de la
contraseña equivocada. Los `403` y el `409` sí dicen el estado de la cuenta; es un intercambio
consciente de una herramienta de desarrollo, cuyas cuentas son sintéticas y cuya contraseña es
una sola.

`/auth/refresh` y `/auth/logout` se autentican por cookie, así que entran en el radio de CSRF:
además de `SameSite=Strict`, si la petición trae `Origin` debe ser exactamente
`http://localhost:5173`; si no, responden `403`.

### Claims y vigencia

El access token lleva exactamente el contrato de ADR-010, con los valores tomados de la fila de
`usuario`:

| Claim | Origen en la tabla `usuario` |
|---|---|
| `sub` | `auth_user_id` |
| `email` | `email` (normalizado a minúsculas) |
| `role` | `rol` |
| `tenantId` | `organizacion_id` (UUID canónico: el backend lo exige) |
| `medicoId` | `medico_id`, solo con rol `MEDICO` |

```json
{
  "sub": "a1b2c3d4-0000-4000-8000-000000000002",
  "email": "medico@dev-auth.local",
  "role": "MEDICO",
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "medicoId": "b2c3d4e5-0000-4000-8000-000000000002",
  "iat": 1761710400,
  "exp": 1761712200,
  "iss": "galeno-dev",
  "aud": "galeno-api",
  "jti": "0f6fd7c2-3a4b-4c56-8d90-1a2b3c4d5e6f"
}
```

- `sub`, `role`, `tenantId` y `exp` son **obligatorios** para el backend: sin ellos el token se
  rechaza. Ningún camino de este emisor produce un token sin `exp`.
- `email` y `medicoId` son opcionales; `medicoId` solo aparece con rol `MEDICO`, aunque la fila
  tenga médico (un administrador que también atiende no lo lleva).
- `jti` no lo exige nadie: existe para que dos tokens emitidos en el mismo segundo no sean
  idénticos (útil al comparar rotaciones).
- **Access token: 30 minutos (1800 s).** **Refresh: máximo 30 minutos, deslizante**; cada
  refresco reemite la cookie con 30 minutos nuevos. Sin actividad durante 30 minutos, el
  refresco vence y hay que volver a iniciar sesión. Es lo que hace real el cierre por
  inactividad del frontend (ADR-012).
- `iss` sale de `AUTH_ISSUER`, `aud` de `AUTH_AUDIENCE`. **Configurar `auth.issuer` y
  `auth.audience` en el backend es lo que ejercita la validación de `iss`/`aud`**: con esas
  propiedades en blanco el backend omite ambas comprobaciones (supuesto de sección 5.6), así que un
  `iss` mal puesto pasaría inadvertido. Parea siempre emisor y backend con los mismos valores.

### Cookie de refresco

`refresh_token` con `HttpOnly`, `SameSite=Strict`, `Path=/auth` y `Max-Age=1800`. **No** lleva
`Secure` porque el emisor de desarrollo escucha en `http://localhost`; el servicio real deberá
agregarlo. El token de acceso nunca se guarda en una cookie: el cliente lo mantiene en memoria
(ADR-011).

### CORS

Origen explícito `http://localhost:5173`, `Access-Control-Allow-Credentials: true`, métodos
`GET, POST, OPTIONS`, cabeceras `Content-Type, Accept` y preflight `OPTIONS`. Nunca comodín:
con credenciales, `*` no es una opción válida. Un origen distinto no recibe ninguna cabecera de
permiso, así que el navegador bloquea la respuesta.

## Semilla de usuarios (`usuarios.json`)

`usuarios.json` **ya no es la fuente de identidad**: es la semilla con la que se cargan las
cuentas de desarrollo en la tabla `usuario`. Se mantiene en el repositorio para que cualquiera
pueda levantar su entorno con las mismas cuentas, y su formato no cambia.

| Campo del JSON | Columna de `usuario` |
|---|---|
| `sub` | `auth_user_id` |
| `tenantId` | `organizacion_id` |
| `role` | `rol` |
| `medicoId` | `medico_id` (nulo si no es médico) |
| `email` | `email` |
| `activo` | `activo` |

Correos sintéticos (`dev-auth.local` no existe) y **sin contraseña**: la de todos es
`DEV_AUTH_PASSWORD`.

| Correo | Rol | Organización | médico |
|---|---|---|---|
| `admin@dev-auth.local` | `ADMIN` | `1111…1111` (clínica A) | — |
| `medico@dev-auth.local` | `MEDICO` | `1111…1111` (clínica A) | `b2c3…0002` |
| `paciente@dev-auth.local` | `PACIENTE` | `2222…2222` (clínica B) | — |
| `recepcion@dev-auth.local` | `RECEPCIONISTA` | `1111…1111` (clínica A) | — |

Para cargarla hace falta que existan antes la organización y el médico referenciados (los crea
la migración o la propia aplicación):

```bash
# desde la raíz del repositorio
SEMILLA="$(node -e "const fs=require('node:fs');process.stdout.write(JSON.stringify(JSON.parse(fs.readFileSync('tools/dev-auth/usuarios.json','utf8')).usuarios))")"

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 <<SQL
INSERT INTO usuario (auth_user_id, organizacion_id, email, rol, medico_id, activo)
SELECT (u->>'sub')::uuid,
       (u->>'tenantId')::uuid,
       lower(u->>'email'),
       u->>'role',
       (u->>'medicoId')::uuid,
       COALESCE((u->>'activo')::boolean, true)
  FROM jsonb_array_elements('$SEMILLA'::jsonb) AS u
ON CONFLICT (organizacion_id, email) DO NOTHING;
SQL
```

`(u->>'medicoId')::uuid` deja `NULL` cuando el JSON trae `null`, así que las cuentas sin médico
se cargan sin tocar `medico_id`.

A partir de ahí la fuente es la tabla: un usuario nuevo creado por el alta de clínica puede
iniciar sesión sin editar ningún archivo, y una baja (`activo = false`) o un médico inactivo se
notan en el siguiente login o refresco.

## Pruebas

`pg` solo se carga cuando el emisor se conecta de verdad: las pruebas **inyectan un repositorio
de identidad en memoria**, así que no necesitan PostgreSQL ni `node_modules` y pueden probar
cada estado de la cuenta sin crear filas.

```bash
cd tools/dev-auth && npm test          # node --test test/*.test.mjs
# desde la raíz del repositorio
node --test tools/dev-auth/test/*.test.mjs
```

Cada prueba arranca el emisor real en un **puerto efímero** (`DEV_AUTH_PORT=0`), con un par RSA
generado **en memoria** y un directorio temporal fuera del árbol de trabajo que se borra al
terminar.

> Node 24 ya no acepta un directorio como argumento de `--test` (los argumentos posicionales
> se interpretan como archivos o globs), así que `node --test tools/dev-auth` falla con
> `MODULE_NOT_FOUND`. Usa el glob. Verificado en Node v24.21.0; no se probó en Node 22, donde el
> runner histórico sí aceptaba directorios.

Cubren, entre otras cosas:

- **Funciones puras**: `normalizarCorreo`; `traducirFilaAClaims` (fila → claims, omisión de
  `medicoId` fuera de `MEDICO`, rechazo de roles y de identificadores que no son UUID canónicos);
  `evaluarCuenta` para cada estado.
- **Estados de la cuenta**: correo inexistente (`401` genérico), cuenta dada de baja (`403`),
  médico inactivo (`403`), correo en dos organizaciones (`409`), fila inválida (`500`) y base de
  datos caída (`500` sin el error crudo del driver). La contraseña incorrecta sigue dando `401`
  incluso cuando la cuenta está de baja, así que no se puede sondear el estado sin credenciales.
- **Emisión**: login válido con token que verifica con la clave pública derivada, claims
  obligatorios y `exp` de 30 minutos, ausencia de cualquier camino que emita un token sin `exp`.
- **Sesión**: refresco con rotación de cookie, refresco que relee la fila (cambio de organización
  y baja), refresco sin cookie, `alg=none`, firma de una clave ajena, logout que borra la cookie.
- **Arranque**: fallo con mensaje explícito si falta la clave, si la base no está configurada o
  si falta `pg`; huella publicada igual a la del DER SPKI.
- **CORS y contrato de error**: preflight con credenciales, `Origin` ajeno rechazado, `404`/`405`,
  y un `Authorization` basura que no concede nada.
- **Semilla**: `usuarios.json` declara los roles y las columnas necesarias y no guarda
  contraseñas; el código del emisor **no lo referencia**, así que no se lee al arrancar.

## Límites declarados

- **No es producción y no debe usarse fuera de local.** La contraseña es una sola para todos los
  usuarios, no hay estados de sesión propia y el token no se puede revocar.
- No hay revocación: un access token robado sigue siendo válido hasta su `exp` (ADR-012).
- **Una sola dependencia**: `pg`. Es la que lleva el emisor a la tabla `usuario`; no se agregan
  otras ni se usa un ORM.
- No registra peticiones: ni credenciales, ni tokens, ni la cadena de conexión terminan en la
  salida del proceso. Solo imprime el resumen de arranque.
- El correo se busca con `lower(email)`: no hay índice funcional para eso. A la escala de
  desarrollo no hace falta; si algún día hiciera falta, se agrega en una migración.

## Cuándo se borra

**Disparador de eliminación explícito.** Este directorio se elimina cuando se cumplan las dos
condiciones:

1. Llegue el contrato real del emisor de tokens de InToGlobe (pregunta **1.5** a Santiago:
   duración, refresh, logout, JWKS vs clave fija), y
2. exista un AuthService real desplegado en un entorno de staging contra el que se pueda
   integrar el login.

En ese momento `tools/dev-auth/` se borra entero, y con él la semilla y la contraseña de
desarrollo. El backend no cambia: sigue siendo validate-only; solo pasa a apuntar (vía
`auth.public.key`/`AUTH_PUBLIC_KEY`, o el bean que lea el JWKS si 1.5 lo pide) a las claves del
emisor real.
