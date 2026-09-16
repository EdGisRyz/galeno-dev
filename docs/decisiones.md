# Decisiones de Arquitectura

**ADR** = Architecture Decision Record (Registro de Decisión de Arquitectura).
Es una ficha corta que documenta: qué problema había, qué decidimos, qué consecuencias tiene.

Objetivo: que en algún tiempo nadie pregunte "¿por qué hicimos esto así?"

---

## ADR-001 · Repositorio nuevo desde cero

**Estado:** Aceptado (15-sep-2026)

**Contexto:** El repo mezclaba historial de 4 autores ajenos a la residencia
con módulos fuera (medinflow-ia, frontend legado, prototipos).

**Decisión:** Crear repo nuevo (galeno-dev) con solo backend Spring Boot + frontend Vite.
Historial limpio: solo commits de Martha y Eduardo desde el 15-sep-2026.

**Consecuencias:**
- (+) Evidencia clara del periodo
- (+) Control total de CI/CD y dependabot desde el día 1
- (-) Pierde historial (archivado como referencia)

---

## ADR-002 · Spring Boot 4.0.8 + Java 21

**Estado:** Aceptado (15-sep-2026)

**Contexto:** Spring Initializr ya no ofrece Boot 3.5.x (fin de soporte OSS).

**Decisión:** Boot 4.0.8 (estable, LTS) + Java 21 (LTS).

**Consecuencias:**
- (+) Alineado con Initializr
- (+) Soporte hasta ~2029

---

## ADR-003 · Naming de packages: com.intoglobe.clinicas

**Estado:** Aceptado (15-sep-2026)

**Contexto:** El legado usaba `com.clinica.mi_app` (guion bajo por restricción Java).

**Decisión:** `com.intoglobe.clinicas` (sin guiones bajos).

**Consecuencias:**
- (+) Convención Java estándar
- (+) Dominio semántico claro

---

## ADR-004 · Namespace del repositorio: galeno-dev

**Estado:** Aceptado (15-sep-2026)

**Contexto:** Crear org llamada `intoglobe` sin autorización escrita ocuparía
el namespace de la empresa empleadora.

**Decisión:** En GitHub personal. Nombre del proyecto: "galeno-dev".

**Consecuencias:**
- (+) No ocupa marca ajena sin permiso
- (+) Nombre corto, en español, memorable

---

## ADR-005 · Convención de rutas REST

**Estado:** Aceptado (15-sep-2026)

**Decisión:** 
* Prefijo `/api/v1/` obligatorio.
* Auth en `/api/v1/auth/*`.
* Dominios en plural.
* Recursos anidados con ID.
* Acciones con verbo final.

**Ejemplos:**
- `POST /api/v1/auth/login`
- `GET /api/v1/pacientes/{id}/citas`
- `POST /api/v1/citas/{id}/cancelar`

**Consecuencias:**
- (+) Permite coexistencia con v2+ sin romper clientes
- (+) Convención REST estándar

---

## ADR-006 · Gestión de configuración

**Estado:** Aceptado (15-sep-2026)

**Decisión:** `application.properties` NO se commitea. Cada dev crea el suyo
copiando `application-example.properties`. 

Las variables reales se inyectan
vía Environment Variables de IntelliJ (o archivo `.env` local).

**Consecuencias:**
- (+) Cero riesgo de fuga accidental de credenciales
- (+) Cada dev ajusta a su entorno local
- (-) Setup inicial requiere copiar archivo (documentado en README)

---

## ADR-007 · .gitignore para node_modules

**Estado:** Aceptado (15-sep-2026)

**Contexto:** Inicialmente teníamos reglas específicas (`frontend/node_modules/`)
que no cubrían `node_modules/` en la raíz.

**Decisión:** `.gitignore` usa patrones genéricos sin prefijo de ruta:
`node_modules/`, `dist/`, `.env`.

**Consecuencias:**
- (+) Cubre cualquier nivel del árbol
- (+) Evita commitear `node_modules/` accidentalmente al firmar tokens con npx