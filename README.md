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
| Swagger | http://localhost:8080/swagger-ui.html |
| Mock Auth | http://localhost:4001 |
| Mocks Notif/Storage/Payment/Audit | 4002 / 4003 / 4004 / 4005 |
| PostgreSQL | localhost:5432 |

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