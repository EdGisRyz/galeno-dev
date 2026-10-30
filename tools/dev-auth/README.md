# dev-auth — emisor local de tokens RS256 (solo desarrollo)

Este directorio contiene un **servicio HTTP mínimo que firma tokens RS256 para trabajar en
local**. Es una **herramienta de desarrollo, no el AuthService del producto**: no valida
contraseñas de usuarios reales, no tiene base de datos, no emite tokens en ningún entorno
compartido y no forma parte del artefacto de ninguna aplicación.

El nombre `dev-auth` es deliberado: dice en voz alta que es de desarrollo. Un nombre neutro
(`auth-service`, `auth`) invitaría a confundirlo con el servicio real y a reutilizarlo fuera
de local.

## Por qué existe

El backend **solo valida** tokens (ADR-008, R8.2): no firma, no emite y no toca contraseñas.
Sin un emisor no hay nada que validar, así que el login del frontend no se puede integrar ni
probar de extremo a extremo. Este proceso llena ese hueco mientras no exista el AuthService de
InToGlobe, y nada más.

## Cómo se ejecuta

```bash
# 1) variable de entorno con la contraseña de los usuarios de la fixture (no está en el repo)
export DEV_AUTH_PASSWORD='la-que-elijas'

# 2) ruta al PEM de TU clave privada (ver «Cómo obtener la clave privada»)
export AUTH_PRIVATE_KEY_PATH="$HOME/.vault/galeno-dev/privada.pem"

# 3) arrancar
node tools/dev-auth/server.mjs
```

Salida esperada al arrancar (extracto):

```
dev-auth · emisor de tokens RS256 para DESARROLLO LOCAL. No es el AuthService del producto.
Escuchando en http://localhost:4001 (el backend espera este servicio en AUTH_SERVICE_URL).
iss=galeno-dev · aud=galeno-api · access=1800s · refresh=1800s
Usuarios de la fixture: 3 (roles: ADMIN, MEDICO, PACIENTE). La contraseña sale de DEV_AUTH_PASSWORD.
Huella SHA-256 de la clave pública (DER SPKI): 9F:3C:...:A1
Compare esta huella con la clave de AUTH_PUBLIC_KEY del backend: si no coincide, el backend rechazará todos los tokens.
```

El **arranque falla con un mensaje explícito** si falta `AUTH_PRIVATE_KEY_PATH` o
`DEV_AUTH_PASSWORD`, si el archivo no se puede leer o si su contenido no es una clave RSA. La
huella se imprime siempre: es la forma de detectar el error de emparejar la clave equivocada,
que si no aparece como «todos los tokens son inválidos» sin causa visible.

## Variables de entorno

| Variable | Obligatoria | Default | Para qué |
|---|---|---|---|
| `AUTH_PRIVATE_KEY_PATH` | **Sí** | — | Ruta al PEM de la clave privada **RSA**. Nunca se commitea. |
| `DEV_AUTH_PASSWORD` | **Sí** | — | Contraseña de **todos** los usuarios de la fixture. No está en el repositorio. |
| `AUTH_ISSUER` | No | `galeno-dev` | Claim `iss` del token. |
| `AUTH_AUDIENCE` | No | `galeno-api` | Claim `aud` del token. |
| `DEV_AUTH_PORT` | No | `4001` | Puerto local. El default coincide con `AUTH_SERVICE_URL` del backend. |

## Cómo obtener la clave privada

La generación y la custodia del par RSA ya están documentadas para el equipo en la guía
externa **`guia-claves-rs256.md`** (carpeta `Antecedentes_IntoGlobe`). No se duplica aquí para
que no existan dos versiones de las mismas instrucciones.

Resumen de lo que esta herramienta espera: la **clave privada en PEM** en la ruta que indique
`AUTH_PRIVATE_KEY_PATH`. El archivo vive en el Vault del IDE o en el gestor de secretos del
desarrollador; **nunca** en el repositorio, en el historial ni en un log. `.gitignore` ya cubre
`*.pem`, `*.key`, `*.p12`, `*.jks` y `.env*`.

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
| | `401` | Credenciales inválidas. Mismo cuerpo y mismo tiempo tanto si el correo no existe como si la contraseña es incorrecta: no se puede enumerar usuarios. |
| | `400` | Body ausente, no JSON o sin `email`/`password`. |
| `POST /auth/refresh` | `200` `{ accessToken }` | Lee la cookie de refresco, verifica firma, tipo, `iss`/`aud` y vencimiento, y **rota** la cookie (deslizante: vuelve a 30 minutos). |
| | `401` | Cookie ausente, falsificada, de otro tipo (por ejemplo el access token), vencida o de un usuario que ya no está en la fixture. |
| `POST /auth/logout` | `204` | Borra la cookie (`Max-Age=0`). Sin cuerpo. No invalida el access token ya emitido: no hay revocación (ADR-012). |
| `GET /health` | `200` | JSON pequeño (`estado`, `servicio`, `algoritmo`, TTLs, `iss`/`aud`). Sin secretos. Útil como sonda del frontend. |

`/auth/refresh` y `/auth/logout` se autentican por cookie, así que entran en el radio de CSRF:
además de `SameSite=Strict`, si la petición trae `Origin` debe ser exactamente
`http://localhost:5173`; si no, responden `403`.

### Claims y vigencia

El access token lleva exactamente el contrato de ADR-010:

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
- `email` y `medicoId` son opcionales; `medicoId` solo aparece con rol `MEDICO`.
- `jti` no lo exige nadie: existe para que dos tokens emitidos en el mismo segundo no sean
  idénticos (útil al comparar rotaciones).
- **Access token: 30 minutos (1800 s).** **Refresh: máximo 30 minutos, deslizante**; cada
  refresco reemite la cookie con 30 minutos nuevos. Sin actividad durante 30 minutos, el
  refresco vence y hay que volver a iniciar sesión. Es lo que hace real el cierre por
  inactividad del frontend (ADR-012).
- `iss` sale de `AUTH_ISSUER`, `aud` de `AUTH_AUDIENCE`. **Configurar `auth.issuer` y
  `auth.audience` en el backend es lo que ejercita la validación de `iss`/`aud`**: con esas
  propiedades en blanco el backend omite ambas comprobaciones (supuesto de §5.6), así que un
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

## Usuarios de la fixture

`usuarios.json` es el directorio de identidad **de desarrollo**: el equivalente a la proyección
de la tabla `usuario` que en producción lee el AuthService en solo-lectura (D2.1). Rol,
organización y médico viven aquí porque son hechos de autorización.

| Correo | Rol | tenantId | medicoId |
|---|---|---|---|
| `admin@dev-auth.local` | `ADMIN` | `1111…1111` (clínica A) | — |
| `medico@dev-auth.local` | `MEDICO` | `1111…1111` (clínica A) | `b2c3…0002` |
| `paciente@dev-auth.local` | `PACIENTE` | `2222…2222` (clínica B) | — |

Los correos son sintéticos (`dev-auth.local` no existe). **La contraseña no está en el
repositorio**: es la de `DEV_AUTH_PASSWORD` para todos los usuarios de la fixture. Cambiar un
rol o mover un usuario de clínica se hace editando este archivo; el siguiente `/auth/refresh`
ya emite el token con los datos nuevos, porque el refresco relee la fixture.

## Pruebas

Sin dependencias: `node:test` nativo. Cada prueba arranca el emisor real en un **puerto
efímero** (`DEV_AUTH_PORT=0`), con un par RSA generado **en memoria** y un directorio temporal
fuera del árbol de trabajo que se borra al terminar.

```bash
node --test tools/dev-auth/test/*.test.mjs
```

> Node 24 ya no acepta un directorio como argumento de `--test` (los argumentos posicionales
> se interpretan como archivos o globs), así que `node --test tools/dev-auth` falla con
> `MODULE_NOT_FOUND`. Usa el glob de arriba. Verificado en Node v24.21.0; no se probó en Node
> 22, donde el runner histórico sí aceptaba directorios.

Cubren, entre otras cosas: login válido con un token que verifica con la clave pública
derivada, contraseña incorrecta y usuario inexistente con el mismo `401`, claims obligatorios y
`exp` de 30 minutos, ausencia de cualquier camino que emita un token sin `exp`, refresco con
rotación de cookie, refresco sin cookie, logout que borra la cookie, preflight CORS con
credenciales, `Origin` ajeno rechazado y un `Authorization` basura que no concede nada.

## Límites declarados

- **No es producción y no debe usarse fuera de local.** La contraseña es una sola para todos
  los usuarios, la fixture no tiene estado y el token no se puede revocar.
- No hay revocación: un access token robado sigue siendo válido hasta su `exp` (ADR-012).
- No registra peticiones: ni credenciales ni tokens terminan en la salida del proceso. Solo
  imprime el resumen de arranque.
- Zero dependencias: `node:crypto`, `node:http`, `node:fs` y utilidades de ruta de Node. No hay
  `package.json` en `tools/`.

## Cuándo se borra

**Disparador de eliminación explícito.** Este directorio se elimina cuando se cumplan las dos
condiciones:

1. Llegue el contrato real del emisor de tokens de InToGlobe (pregunta **1.5** a Santiago:
   duración, refresh, logout, JWKS vs clave fija), y
2. exista un AuthService real desplegado en un entorno de staging contra el que se pueda
   integrar el login.

En ese momento `tools/dev-auth/` se borra entero, y con él la fixture y la contraseña de
desarrollo. El backend no cambia: sigue siendo validate-only; solo pasa a apuntar (vía
`auth.public.key`/`AUTH_PUBLIC_KEY`, o el bean que lea el JWKS si 1.5 lo pide) a las claves del
emisor real.
