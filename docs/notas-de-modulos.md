# Notas de módulos

Registro de decisiones por módulo. Cada sección responde tres preguntas: qué se
decidió, por qué, y qué queda abierto o sin verificar. Estas notas acompañan al
código; **no sustituyen la review humana ni la review externa**.

## Estado de lectura rápida

- Módulo `organizacion`: implementación inicial y pruebas enfocadas por tarea.
  **Todavía no es la referencia de los módulos 1-10**: requiere review línea por
  línea de Eduardo (`/home/gise/Plan + Cards/Modulo_00_Organizacion.md` §11) y
  review externa antes de recibir ese estatus.
- La suite completa `sh ./mvnw -q test` pasó en la verificación independiente de T5
  (2026-10-12 01:20 UTC): salida real **0**, **45 tests**, 0 fallos, 0 errores y
  0 omitidos (total calculado desde cuatro reportes Surefire actuales).
  Evidencia: `backend/target/organizacion-verificacion.txt`. `-q` no imprimió
  `BUILD SUCCESS`; no se inventa esa línea.
- Fuente de verdad estructural: la tabla `organizacion` de la migración V1. La
  fuente de verdad de negocio es la tarjeta del módulo. Regla rectora:
  **Prohibido texto inventado.**

---

## organizacion

Módulo de referencia estilística (tenant dueño de los datos clínicos). Expone
`/api/v1/organizaciones` con alta, consulta, listado de activas, reemplazo y baja
lógica idempotente. La validación de entrada vive en el DTO de request; la
entidad no lleva validaciones Jakarta.

### Decisión 1 — Paquete `mapper/` nuevo con mapeo explícito y DTO reutilizado

**Contexto.** El skeleton del repo no tenía paquete `mapper/`. Se necesitaba un
único punto de traducción entre entidad y DTOs, sin librerías externas.

**Decisión.**
- Se crea `mapper/OrganizacionMapper`, clase `final` con métodos estáticos
  `aEntidad`, `aResponse` y `actualizarEntidad`.
- El mapeo cubre **exactamente los cuatro campos editables**: `nombre`, `plan`,
  `subscriptionId` y `trialHasta`. Preserva `id`, `createdAt` y `activo`, y nunca
  reactiva una organización inactiva.
- PUT es un **reemplazo total** de esos cuatro campos: un `null` en los opcionales
  (`subscriptionId`, `trialHasta`) borra el valor anterior.
- Se reutiliza el mismo record `OrganizacionRequest` para **POST y PUT**, de modo
  que la validación del cuerpo sea idéntica en ambas operaciones (evita divergencia
  de contratos entre crear y actualizar).

**Por qué.** El módulo 0 se replica en los módulos 1-10; un mapper explícito y un
DTO de entrada único hacen visible qué campos se pueden modificar y cuáles no.

### Decisión 2 — Un solo contrato de error `ErrorResponse` con 400 extendido

**Contexto.** El skeleton no tenía un handler de errores. Se necesitaba un
contrato único para evitar que cada endpoint inventara su propio formato.

**Decisión.**
- Existe **un único** cuerpo de error, `dto/response/ErrorResponse`, con los campos
  `OffsetDateTime marcaTemporal`, `int codigo`, `String error`, `String mensaje` y
  `String ruta`.
- `exception/GlobalExceptionHandler` (`@RestControllerAdvice`) traduce todas las
  excepciones conocidas a ese cuerpo: inexistente → **404**; DTO inválido → **400**.
- El **400 está extendido** a las variantes nombradas por la tarjeta: JSON mal
  formado, enum/fecha inválidos (`HttpMessageNotReadableException`) y UUID de path
  inválido (`MethodArgumentTypeMismatchException`), además de la validación de
  Bean Validation (`MethodArgumentNotValidException`).
- Los mensajes son resúmenes seguros en español: **no exponen stacktraces ni
  detalles internos del parser**.

**Por qué.** Un solo cuerpo de error es el contrato de referencia que los módulos
1-10 deben copiar, en lugar de reinventar formatos.

### Decisión 3 — `SecurityConfig` abierto y sin estado: temporal, NO de producción

**Contexto.** El filtro JWT y el RBAC están fuera de alcance en esta fase (R8.1).

**Decisión.**
- Se autoriza **únicamente** `security/SecurityConfig.java` como excepción mínima
  de comprobación: `@Configuration @EnableWebSecurity` con
  `authorizeHttpRequests(anyRequest().permitAll())`, `csrf(disable)` y
  `sessionManagement(STATELESS)`, más el Javadoc obligatorio dictado por R8.1bis.
- Los tests de controller importan la clase explícitamente
  (`@Import(SecurityConfig.class)`).
- **No es seguridad de producción ni aislamiento entre tenants.** Los endpoints
  quedan abiertos a propósito.
- Dirección ya decidida para el sprint de seguridad (R8.2, no reabrir): el backend
  **solo valida** tokens RS256 emitidos por el AuthService de InToGlobe con la clave
  pública `AUTH_PUBLIC_KEY`; **nunca** genera tokens ni hashea contraseñas.
  `tenantId` y `role` se extraen de los claims y viven en el `SecurityContext`. No
  existe legado HS256 en este repo.

**Por qué.** Dejar la excepción mínima documentada evita que un módulo futuro
interprete los endpoints abiertos como el modelo de seguridad definitivo.

### Decisión 4 — Deuda de seguridad temporal documentada (`permitAll`)

**Contexto.** R8.1bis (versión final) mantiene `permitAll()` siempre para que
Eduardo pueda verificar Swagger a mano en local; el filtro JWT/RBAC sigue fuera de
alcance en esta fase.

**Decisión (texto dictado).**
> Seguridad temporal: permitAll() en SecurityConfig con deuda documentada. Sprint 1 (RS256) resuelve antes de prod. Pipeline de CI/CD (futuro) debe fallar si detecta permitAll() en rama main/prod.

- La razón de esta apertura es la **verificación manual de Swagger por Eduardo**,
  **no** una garantía de seguridad ni aislamiento entre tenants.
- `SecurityConfig` lleva el Javadoc obligatorio de R8.1bis con la advertencia corta
  y el texto completo, incluido que el Sprint 1 reemplaza la cadena abierta y que el
  backend solo validará tokens del AuthService InToGlobe.
- El **pipeline de CI/CD es trabajo FUTURO y NO está implementado**: hoy ninguna
  compuerta falla al detectar `permitAll()` en `main`/`prod`.

**Por qué.** Documentar la deuda evita que los módulos 1-10 copien la apertura como
si fuera el modelo de seguridad definitivo.

**Hallazgo nativo abierto — review NO aprobada.** La review nativa reportó un
hallazgo **CRÍTICO** de mutaciones anónimas de organizaciones
(`security/SecurityConfig.java:28`: create/replace/deactivate sin autenticación) y su
veredicto fue `correction_required`, **NO aprobado**. Esta decisión es documentación
autorizada por el humano: **no corrige el hallazgo ni hace desaparecer la
vulnerabilidad**, y no debe leerse como aprobación de seguridad. La disposición del
hallazgo sigue pendiente.

### Duda abierta

> **R8.3(a) — ¿Cómo filtrará `listarActivas` por tenant cuando llegue el JWT?**

Las demás operaciones ya tendrán `tenantId` en el `SecurityContext`; el listado
global es el caso a resolver. Hipótesis registrada (pendiente de confirmar, **no
decidida**): para roles MEDICO/PACIENTE el tenant es la organización
(`tenantId == organizacion.id`) y listar se convierte en `buscarPorId(tenantId)`,
quedando el listado global solo para ADMIN. Se resolverá al implementar la
seguridad; no se inventan reglas ahora.

**Contexto de roadmap (no es respuesta a la duda).** R8.3 también menciona la
matriz RBAC de los endpoints (§b) y si el alta de tenants pasará por este controller
o por un `OnboardingService` dedicado (§c, Sprint 2). Se documentan solo como
contexto de roadmap: **no están respondidas aquí** y no se inventan reglas para
ellas.

### Alcance y archivos protegidos

| Área | Estado |
|------|--------|
| `src/main/resources/db/migration/**` (V1) | Congelado, sin cambios |
| `src/main/java/com/intoglobe/clinicas/config/**` y su `.gitkeep` | Congelado, sin cambios |
| `security/` salvo `SecurityConfig.java`, incluido `security/.gitkeep` | Congelado, sin cambios |
| `application.properties`, `application-test.properties` | Preservados; la modificación local preexistente NO la produce este módulo y se excluye del diff |
| `BackendApplicationTests` y bootstrap | Sin cambios |
| `pom.xml` | Solo la dependencia de test autorizada; el resto congelado |
| `.gitkeep` de paquetes que ganaron archivos reales | Eliminados (controller, dto/request, dto/response, exception, model, repository, service) |

**Dependencia agregada (única autorizada).**
`org.springframework.boot:spring-boot-starter-data-jpa-test`, `scope=test`, **sin
versión** (la gestiona el BOM). Ninguna otra dependencia fue tocada.

### Límites de verificación

- **Suite completa verificada en T5:** salida 0; reportes actuales de contexto (1),
  controller (21), repositorio (10) y service (13), todos con 0 fallos, 0 errores y
  0 omitidos. La evidencia real de `echo $?` y las líneas Surefire está en
  `backend/target/organizacion-verificacion.txt`; RED/GREEN enfocado en
  `odd/tasks/organizacion.md`.
- **No se declara una línea `BUILD SUCCESS` no observada**: `-q` la suprimió.
- Los tests de repositorio cursan sobre H2 en modo PostgreSQL. **H2 no es prueba
  suficiente para PostgreSQL**: la validación con la base real queda pendiente.
- Nota técnica (T2): `hashCode()` se calcula desde `Organizacion.class` y no desde
  `id`, de modo que el hash permanece estable al asignar el UUID. Así la entidad no
  se corrompe ni se pierde dentro de colecciones basadas en hash (por ejemplo
  `HashSet`) al pasar de transitoria a persistida. `equals` sigue comparando solo el
  `id`.

### Checklist de lectura

- [ ] Confirmé que la sección tiene **4 decisiones** y **1 duda**.
- [ ] Confirmé que la duda (tenant en `listarActivas`) queda abierta, con hipótesis
      marcada como tal.
- [ ] Confirmé que `SecurityConfig` se declara temporal y no de producción.
- [ ] Confirmé que el pipeline CI/CD que debe fallar ante `permitAll()` es futuro y
      **NO está implementado**.
- [ ] Confirmé que el hallazgo nativo **CRÍTICO** sigue sin resolver y la review
      **NO está aprobada**: documentar no es corregir.
- [ ] Confirmé que no hay conteos de tests ni éxito de suite completa inventados.
