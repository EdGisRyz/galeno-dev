# Galeno — Sistema de Administración para Clínicas y Consultorios Médicos (SaaS)

Residencia profesional InToGlobe · ago-2026 a feb-2027 · ITOaxaca

---

## Prerequisitos

| Herramienta | Versión         | Nota |
|---|-----------------|---|
| Java | 21 (LTS)        | Eclipse Temurin recomendado |
| Maven | —               | wrapper incluido (`mvnw`), no se instala |
| Node | 22 LTS          | |
| pnpm | 12.4.2                | fijado vía `packageManager` en `frontend/package.json` |
| Docker | cualquiera con compose | solo para mocks y BD opcional |
| PostgreSQL | 15+             | local o contenedor |

## Setup por única vez

### 1. Base de datos

```sql
-- Nombre en lowercase
CREATE DATABASE galeno_clinica;
       
-- Opcional: usuario propio en lugar del superuser postgres
CREATE USER tu_usuario WITH PASSWORD 'tu_password';
GRANT ALL PRIVILEGES ON DATABASE galeno_clinica TO tu_usuario;
```

### 2. Configuración del backend

`application.properties` 

**Copiar el example y ajustarlo a tus variables necesarias**

```bash
cp backend/src/main/resources/application-example.properties \
   backend/src/main/resources/application.properties
# edita usuario/password de TU postgres local
```

Alternativa sin archivo: configura las variables `SPRING_DATASOURCE_*`
en IntelliJ (Run → Edit Configurations → Environment variables).

### 3. Configuración del frontend

```bash
cp frontend/.env.example frontend/.env
# ajusta VITE_API_URL si tu backend no corre en localhost:8080
# ajusta VITE_AUTH_URL si tu emisor de tokens no corre en localhost:4001
```

## Levantar el stack (3 comandos)

### No realizar el paso 1 y 2 hasta que exista contenido en el archivo V1__base_schema.sql

```bash
# 1. Mocks de los microservicios InToGlobe (WireMock)
docker compose -f docker-compose.wiremock.yml up -d

# 2. Backend (Spring Boot 4.0.8)
cd backend && ./mvnw spring-boot:run        # Windows: .\mvnw spring-boot:run

# 3. Frontend (Vite + React 19)
cd frontend && pnpm install && pnpm dev
```

| Servicio | URL |
|---|---|
| Frontend | http://localhost:5173 |
| Backend | http://localhost:8080 |
| Swagger (solo con perfil `dev`) | http://localhost:8080/swagger-ui.html |
| Emisor de tokens de desarrollo (`tools/dev-auth`) | http://localhost:4001 |
| Mocks Notif/Storage/Payment/Audit | 4002 / 4003 / 4004 / 4005 |
| PostgreSQL | localhost:5432 |

## Seguridad y entorno

El backend **solo valida** tokens RS256: no los emite, no los firma y no guarda
contraseñas. Las decisiones están en `docs/decisiones.md` (ADR-008 a ADR-016).

### `AUTH_PUBLIC_KEY` es obligatoria

Sin esta variable el backend **no arranca**: la comprobación corre en todos los
perfiles, incluido `test`, y falla si la clave falta, está vacía, no es Base64 o
no es una clave RSA. El mensaje de error nombra `auth.public.key` y
`AUTH_PUBLIC_KEY` (ADR-009).

- **Formato:** Base64 del DER `SubjectPublicKeyInfo` (SPKI) en **una sola línea**
  (392 caracteres para RSA 2048). No es PEM, no es PKCS#1, no es OpenSSH, no es
  un certificado.
- **Dónde se configura:** como variable de entorno en la run config del IDE o en
  la shell, nunca en un archivo versionado (ADR-006). Los defaults seguros viven
  en `backend/src/main/resources/application.yml`.
- **Cómo se genera el par y quién custodia la privada:** guía externa
  `guia-claves-rs256.md`.
- No se duplica aquí para que no existan dos versiones.
- `.gitignore` cubre `*.pem`, `*.key`, `*.p12`, `*.jks` y `.env*`. La clave privada
  no entra al repositorio, ni al historial, ni a un log.
- El perfil `test` trae su propia clave pública en
  `backend/src/test/resources/application-test.properties`: es pública, no es un
  secreto, y existe para que la suite arranque sin configurar nada.

### El frontend habla con dos orígenes

| Variable | Para qué |
|---|---|
| `VITE_API_URL` | El backend (`/api/v1`). Adjunta `Authorization: Bearer <token>`. |
| `VITE_AUTH_URL` | El **emisor** de tokens. Login, refresco y logout van aquí, no al backend. |

Son variables separadas a propósito: si el AuthService real termina siendo
backend-a-backend, se agrega un proxy en el backend y solo cambia `VITE_AUTH_URL`
(ADR-005). En desarrollo apunta a `tools/dev-auth`. Usar `localhost` y **no**
`127.0.0.1`: la cookie de refresco es `SameSite=Strict` y ambos son sitios
distintos, así que la cookie se descartaría.

### Emisor de tokens en desarrollo (`tools/dev-auth`)

Herramienta de desarrollo, no el AuthService del producto: firma RS256 con una
clave privada local, no tiene base de datos y no forma parte de ningún artefacto.

```bash
export DEV_AUTH_PASSWORD='la-que-elijas'                             # obligatoria
export AUTH_PRIVATE_KEY_PATH="$HOME/.vault/galeno-dev/privada.pem"   # obligatoria
node tools/dev-auth/server.mjs                                      # http://localhost:4001
```

| Variable | Obligatoria | Default | Para qué |
|---|---|---|---|
| `AUTH_PRIVATE_KEY_PATH` | Sí | — | Ruta al PEM de la clave privada RSA. |
| `DEV_AUTH_PASSWORD` | Sí | — | Contraseña de los usuarios de la fixture. |
| `DEV_AUTH_PORT` | No | `4001` | Puerto local; el default coincide con `AUTH_SERVICE_URL` del backend. |
| `AUTH_ISSUER` / `AUTH_AUDIENCE` | No | `galeno-dev` / `galeno-api` | Claims `iss`/`aud`; el backend solo los valida si `auth.issuer`/`auth.audience` están configurados. |

Al arrancar imprime la **huella SHA-256 de la clave pública** (DER SPKI). Es la
forma de detectar el error más confuso de este montaje —emparejar pares
distintos, que hace que **todos** los tokens den `401` aunque la firma sea
correcta—:

```bash
printf '%s' "$AUTH_PUBLIC_KEY" | base64 -d | sha256sum   # debe coincidir con la huella del emisor
```

Endpoints (`POST /auth/login`, `/auth/refresh`, `/auth/logout`, `GET /health`),
claims y pruebas: `tools/dev-auth/README.md`.

### Verificación

```bash
cd backend  && ./mvnw test                          # perfil test: H2 en memoria, sin postgres
cd frontend && pnpm test && pnpm lint && pnpm build # Vitest, ESLint, typecheck + bundle
sh scripts/verificar-seguridad.sh                   # compuerta de seguridad (desde cualquier cwd)
```

La compuerta revisa `backend/src/main/java` y falla si aparece un
`anyRequest().permitAll()`, o un `permitAll()` cuya propia línea no declare
`DispatcherType.ERROR`, `RUTA_ERROR` o `RUTAS_PUBLICAS`.

### Qué corre el CI

`.github/workflows/test.yml` tiene tres jobs:

| Job | Qué corre |
|---|---|
| `test-java` | la compuerta de seguridad y `./mvnw clean test -B` |
| `test-frontend` | `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm test`, `pnpm run build` |
| `dependency-review` | **solo en PR**: bloquea dependencias nuevas de severidad `high` |

`dependabot.yml` solo abre actualizaciones de **versión** (Maven, npm, Docker,
GitHub Actions). Las **alertas de seguridad** de dependencias y sus
actualizaciones son una configuración del repositorio en GitHub
(Settings → Code security), no algo que ese archivo active.

## Tests y build

```bash
cd backend  && ./mvnw clean test    # H2 en memoria: NO requiere postgres
cd frontend && pnpm run build       # typecheck + build de producción
```

## Estructura del monorepo

```
├── backend/    Spring Boot 4.0.8 · multicapa (controller → service → repository)
├── frontend/   Vite 8 + React 19 + TS · multicapa (features/, lib/, store/)
├── wiremock/   mappings y __files de los 5 mocks (Aun pendiente)
├── docs/       documentación técnica
└── .github/    CI (java + frontend) y dependabot
```

## Convenciones (leer antes de codear)

- **Rutas REST:** prefijo `/api/v1/` obligatorio
- **Config local:** nunca commitear credenciales
- **Commits:** atómicos, un cambio por commit; features → PR a `develop`
- **Decisiones:** toda decisión de arquitectura se documenta en `docs/decisiones.md`
- **Flyway:** migraciones `V{n}__descripcion.sql`; una migración aplicada NO se edita

## Estado actual

Skeleton completo (backend + frontend + mocks). Esquema de BD (V1)
en diseño — ver `docs/decisiones.md`