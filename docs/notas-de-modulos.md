# Notas de módulos

Registro de decisiones por módulo. Cada sección responde tres preguntas: qué se
decidió, por qué, y qué queda abierto o sin verificar. Estas notas acompañan al
código; **no sustituyen la review humana ni la review externa**.

## Estado de lectura rápida

- Módulo `organizacion`: implementación inicial y pruebas enfocadas por tarea.
  **Todavía no es la referencia de los módulos 1-10**: requiere una revisión línea por
  línea contra la tarjeta del módulo 00 y una revisión externa antes de recibir ese
  estatus.
- La suite completa `sh ./mvnw -q test` pasó en la verificación independiente de T5
  (2026-10-12 01:20 UTC): salida real **0**, **45 tests**, 0 fallos, 0 errores y
  0 omitidos (total calculado desde cuatro reportes Surefire actuales).
  Evidencia: `backend/target/organizacion-verificacion.txt`. `-q` no imprimió
  `BUILD SUCCESS`; no se inventa esa línea.
- Módulo `configuracion-tenant`: alcance parcial (entidad + repositorio + tests de
  integración), replicando el patrón del módulo 00. La aceptación nativa y la review
  externa del módulo 00 siguen pendientes y NO se asumen aprobadas.
- Módulo `consultorio`: alcance **COMPLETO** (dominio, repositorio, DTOs, mapper,
  service, controller, tests y notas). Es el molde de los módulos 3-10. Rutas
  anidadas para colección y planas para ítem: desviación justificada de GAL-001 §5.8.
- Módulo `medico`: alcance **COMPLETO**. **Cierra los 3 modelos de la rama**
  (`Organizacion`, `Consultorio`, `Medico`). Misma convención de rutas. Incluye la
  primera excepción de negocio del proyecto que responde **400** y la primera
  verificación hecha **a mano contra PostgreSQL** (cascada organización→médicos).
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

### Limitaciones declaradas del contrato de error

Documentadas, **no** resueltas. Ninguna afecta el comportamiento real de la API: las
respuestas que devuelve son las correctas y están cubiertas por tests.

1. **El "Example Value" de Swagger es el mismo para todos los errores y muestra
   siempre un 404.** `ErrorResponse` declara `example` en sus componentes (`codigo`
   `"404"`, `error` `"Not Found"`, y un `mensaje`/`ruta` que además nombran
   `Organizacion`), y como todos los status comparten el mismo esquema, Swagger UI
   reutiliza ese ejemplo también en los 400 — y también en los endpoints de
   `consultorio`. Comprobado **en vivo** el 2026-10-16:
   `GET /api/v1/organizaciones/abc/consultorios` responde **400** con
   `"error": "Bad Request"` y `"mensaje": "El parámetro 'organizacionId' tiene un
   formato inválido"`, mientras la documentación muestra el ejemplo del 404. **El
   contrato documentado (status + `description`) sí es correcto**; lo que engaña es
   solo el ejemplo autogenerado.
   **Remediación si se decide (opción B):** quitar de `ErrorResponse` los `example`
   que dependen del status y dejar solo `description`, porque **un esquema compartido
   no puede llevar un ejemplo específico de status**. Coste: un archivo, cero cambio
   de comportamiento; a cambio Swagger muestra los valores por defecto del tipo,
   menos ilustrativos pero no falsos. Si un revisor pidiera ejemplos concretos por
   status, la variante acotada es declarar los ejemplos nombrados **una sola vez** y
   referenciarlos por `ref` (una línea por status), en lugar de escribirlos en cada
   `@ApiResponse`.
2. **Una URL no mapeada no pasa por el contrato de error.** Un 404 por ruta
   inexistente —por ejemplo `/api/v1/organizaciones/{id}/consultorio` en singular, o
   cualquier path sin `@RequestMapping`— devuelve la **página por defecto de Spring
   Boot** (`Whitelabel Error Page`, con su propio formato y su propio timestamp) en
   lugar de `ErrorResponse`. Es decir: el contrato único cubre las excepciones que el
   `GlobalExceptionHandler` conoce, **no** los errores de enrutamiento. Observado en
   vivo el 2026-10-16. Remediación posible cuando se aborde el endurecimiento
   (mapear `NoHandlerFoundException` o un `ErrorController` propio); **no** se
   implementa ahora.

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

---

## configuracion-tenant

Módulo 01 en **alcance parcial** según GAL-001 §6: entidad, repositorio y tests de
integración. **No** hay DTO, mapper, service ni controller: leer las API keys por
organización pertenece a un sprint distinto. La tabla no tiene `activo` ni
`updated_at`, así que el módulo no define borrado lógico ni auditoría de modificación.

### Decisión 1 — Relación 1-1 unidireccional y obligatoria con `organizacion`

**Contexto.** El plan §6 pide practicar `@OneToOne` "opcional", pero V1 declara
`organizacion_id UUID NOT NULL` con `UNIQUE` y `FOREIGN KEY ... ON DELETE CASCADE`.

**Decisión.**
- `@OneToOne(fetch = FetchType.LAZY, optional = false)` con
  `@JoinColumn(name = "organizacion_id", nullable = false, unique = true)`.
- La relación vive **solo** en `ConfiguracionTenant`: `Organizacion` no recibe
  colección, ni getter inverso, ni ningún otro cambio.
- La palabra "opcional" del plan se interpreta como **ausencia de navegación
  inversa**, no como relación nullable: la columna es NOT NULL y el mapeo la espeja.

**Por qué.** Una relación unidireccional mantiene acotado el grafo de entidades, y
`LAZY` evita cargar la organización cuando solo se necesita su configuración.

**Corrección registrada.** La verificación independiente detectó que el test de `LAZY`
era vacuo: `Hibernate.isInitialized(null)` devuelve `false`, así que pasaba aunque la
asociación fuera nula. Ahora el test afirma primero que la asociación existe y recién
después que está sin inicializar. Los módulos 2-10 deben copiar esa forma.

### Decisión 2 — Las API keys son datos sensibles

**Contexto.** Las cuatro columnas (`notif_api_key`, `storage_api_key`,
`payment_api_key`, `audit_api_key`) son `TEXT` **nullable**, sin formato ni longitud
declarados en V1.

**Decisión.**
- Mapean a `String` nullable, sin `length` y **sin validaciones Jakarta** en la
  entidad: no se inventan reglas (obligatoriedad, formato, longitud) que V1 no declara.
- `toString()` **no expone ninguna de las cuatro**, ni total ni parcialmente.
  Filtrarlas a un log sería un incidente de seguridad.
- Sus getters existen porque el sprint que consuma las credenciales los necesitará;
  hoy nadie las lee.

**Por qué.** Este módulo es el molde de los módulos 2-10: si aquí una credencial se
filtra en `toString()`, el error se replica diez veces.

### Decisión 3 — Dos constructores de negocio, sin setters ni reflexión

**Contexto.** El módulo 00 ya sentó que la entidad no lleva setters ni validaciones.
Sin setters, los tests solo podían poblar las columnas con `ReflectionTestUtils`, es
decir manipulando campos privados.

**Decisión.**
- `protected ConfiguracionTenant()` (requerido por JPA) y **dos** constructores de
  negocio: `ConfiguracionTenant(Organizacion)` para el alta de un tenant que todavía
  no tiene credenciales, y el de payload completo
  `ConfiguracionTenant(Organizacion, notifApiKey, storageApiKey, paymentApiKey,
  auditApiKey, whatsappConectado, whatsappNumero)`.
- **Prohibido** añadir setters o métodos de dominio de escritura, y **prohibido**
  poblar la entidad por reflexión en los tests.
- El inicializador `whatsappConectado = false` se conserva: es lo que cubre el
  constructor de un argumento.

**Por qué.** Sin una vía legítima de poblar sus columnas, la entidad no es usable ni
testeable sin trampas. El precedente está en el módulo 00, cuyo constructor de
negocio ya incluía el campo opcional `subscriptionId`. Este ajuste se aplicó el
2026-10-14 con aprobación humana explícita (`Modulo_01_Configuracion_Tenant.md` §9/R10).

### Duda abierta

> **(a) ¿`Organizacion` deberá exponer `getConfiguracionTenant()`** cuando exista el
> sprint que lea las API keys? Hoy la relación es unidireccional por decisión, no por
> olvido. **Sin decidir.**

Contexto de roadmap que este módulo **no** responde y para el que no se inventan reglas:
- **(b) Lectura y rotación de las API keys:** no se define si serán cacheables, si se
  rotan, ni si se cifran en reposo. Hoy se guardan tal como llegan.
- **(c) Alta por defecto:** ¿toda organización nueva debería tener su fila de
  `configuracion_tenant` creada automáticamente (trigger, listener o servicio de
  onboarding)? La FK/UNIQUE actual no lo exige.

### Limitación declarada (no se parchea con un test artificial)

El assert de que `whatsappConectado` nace en `false` **no discrimina** el inicializador
de campo: un `boolean` primitivo ya vale `false` sin inicializador, así que el test
pasaría igual si se borrara `= false`. La distinción no es observable en Java. El
inicializador existe porque espeja el `DEFAULT FALSE` de V1 y queda verificado por
lectura de código; se deja registrado en lugar de escribir un test teatral. El assert
de `isNotNull()` sobre la asociación sí se agregó porque ahí el falso verde era real.

### Alcance y archivos protegidos

| Área | Estado |
|------|--------|
| `src/main/resources/db/migration/**` (V1) | Congelado, sin cambios |
| `model/Organizacion.java`, `model/PlanTipo.java` | Sin cambios |
| `security/**`, incluido `SecurityConfig.java` | Sin cambios: el módulo 01 no toca seguridad |
| `config/**` y su `.gitkeep` | Congelado, sin cambios |
| `pom.xml` | Sin cambios: no hizo falta ninguna dependencia nueva |
| `application*.properties` | Sin cambios |
| `docs/notas-de-modulos.md` | Única edición de documentación del módulo |
| `.gitattributes` (raíz) | Unidad S0: normalización de finales de línea |

### Límites de verificación

- Suite completa: `sh ./mvnw -q test` con salida **0**; **54 tests** con 0 fallos, 0
  errores y 0 omitidos (el test nuevo aporta 9). `-q` **no** imprime `BUILD SUCCESS`:
  no se inventa esa línea.
- Verificación independiente: 13/13 comprobaciones en la primera ronda y 6/6 tras
  corregir el test vacuo de `LAZY`.
- Los tests cursan sobre **H2 en modo PostgreSQL**. H2 **no** prueba PostgreSQL: la
  validación contra la base real queda pendiente.
- El UNIQUE de `organizacion_id` se afirma como `DataIntegrityViolationException` a
  través del repositorio (`saveAndFlush`), no con la excepción cruda de Hibernate: así
  se valida la traducción de Spring en lugar del texto del mensaje, que cambia entre
  versiones.

### Estado nativo del módulo

El módulo 01 **no tiene review nativa aprobada**. La compuerta se abrió sobre la rama
completa (`review-4d13340da30650d0`, tier `high`, 30 archivos / 2831 líneas) y su
ejecución quedó bloqueada por indisponibilidad (429) del proveedor del modelo. El
interruptor de RDD se apagó a nivel de clone por decisión de Eduardo, hasta cerrar la
fase de seguridad del backend. **Tests verdes no equivalen a review aprobada.**

### Checklist de lectura

- [ ] Confirmé que la sección tiene **3 decisiones** y **1 duda**.
- [ ] Confirmé que la duda queda abierta y que (b) y (c) son contexto de roadmap, no respuestas.
- [ ] Confirmé que `toString()` no expone ninguna de las cuatro API keys.
- [ ] Confirmé que el ajuste de constructores se hizo con aprobación humana, no por cuenta propia.
- [ ] Confirmé que la limitación del default de WhatsApp está declarada, no maquillada.
- [ ] Confirmé que no hay conteos de tests ni éxito de suite inventados.
- [ ] Confirmé que este módulo **no** tiene review nativa aprobada.

---

## consultorio

Módulo 02 con alcance **COMPLETO** (GAL-001 §6): dominio, repositorio, tests de
integración, DTOs, mapper, service, tests unitarios, controller, tests MVC y estas
notas. Es el molde de los módulos 3-10.

Expone cinco endpoints bajo `/api/v1`:

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/api/v1/organizaciones/{organizacionId}/consultorios` | 201 + `Location` |
| `GET` | `/api/v1/organizaciones/{organizacionId}/consultorios` | 200 (solo activos, por nombre) |
| `GET` | `/api/v1/consultorios/{id}` | 200 |
| `PUT` | `/api/v1/consultorios/{id}` | 200 |
| `DELETE` | `/api/v1/consultorios/{id}` | 204 (baja lógica, idempotente) |

### Decisión 1 — La baja es lógica porque las FK la obligan

**Contexto.** V1 declara `activo BOOLEAN DEFAULT TRUE NOT NULL` y además:
`medico.consultorio_id UUID NOT NULL → REFERENCES consultorio (id)` **sin
`ON DELETE`**, y lo mismo `cita.consultorio_id`.

**Decisión.** `desactivar()` pone `activo = false`, es idempotente, y **no se expone
ningún borrado físico**. `DELETE` responde 204.

**Por qué.** No es preferencia estética: un `DELETE` físico de un consultorio con
médicos o citas **fallaría con violación de FK**, porque la ausencia de `ON DELETE`
significa `NO ACTION`. La baja lógica es la única opción viable y además preserva la
lectura histórica de las citas y los médicos que lo referencian.

*Precisión de evidencia:* esa consecuencia se **deriva** de la FK de V1 y **no se
ejercitó** con un test, porque `medico` y `cita` todavía no tienen entidades en el
repositorio. El único comportamiento de FK que sí está probado es el inverso:
eliminar la organización borra sus consultorios por el `ON DELETE CASCADE` (caso 9 del
test de repositorio).

### Decisión 2 — `ConsultorioResponse` plano, sin anidar la organización

**Decisión.** La respuesta lleva `organizacionId` (UUID) y **no** la entidad
`Organizacion`.

**Por qué.** Evita depender del `LAZY` durante la serialización —serializar la
asociación dispararía una consulta o un `LazyInitializationException` fuera de
transacción— y no expone la organización completa a quien consulta un consultorio.

**Nota verificada.** El mapper obtiene el identificador con
`getOrganizacion().getId()`. Un test dedicado afirma que **esa lectura no inicializa el
proxy** (`Hibernate.isInitialized(...)` sigue en `false` después del `getId()`), así
que el listado no incurre en N+1. La ausencia de consultas a `organizacion` en ese caso
se reportó con atribución por testcase del XML de Surefire: la **aserción del test es
la evidencia primaria** y el conteo de SQL la corroboración. Si en el futuro el listado se vuelve caliente, la
optimización es una proyección DTO en el repositorio.

### Decisión 3 — `organizacionId` no es un campo del DTO: la inmutabilidad es estructural

**Contexto.** Un consultorio no puede cambiar de organización: sus médicos y citas
quedarían huérfanos.

**Decisión.** `ConsultorioRequest` tiene exactamente tres campos (`nombre`,
`direccion`, `telefono`). La organización **no viaja en el cuerpo**: llega por el
path en la creación y no existe en la actualización. Un único DTO sirve para POST y
PUT.

**Por qué.** Al no existir el campo no existe el caso de discrepancia: no hace falta
un 400 específico, ni ignorar un valor en silencio, ni un DTO separado para el PUT.
La prohibición es *estructural* en lugar de una validación. Y preserva la decisión
del módulo 00 de no diversificar la validación entre crear y actualizar.

*Nota de proceso:* esta decisión no se eligió entre opciones, **se disolvió** al
confirmar la ruta anidada para las colecciones. Las tres alternativas barajadas (400
al discrepar, ignorar en silencio, DTO separado) quedaron descartadas por
innecesarias.

### Decisión 4 — El listado anidado responde 404 si el padre no existe, y la creación también

**Contexto.** Al adoptar rutas anidadas para las colecciones, la validez del padre
pasa a ser parte de la dirección.

**Decisión.** `GET /api/v1/organizaciones/{organizacionId}/consultorios` con un
`organizacionId` inexistente responde **404** (no `200 []`); con una organización
existente y sin consultorios responde `200 []`. **Extensión:** el `POST` con
organización inexistente también responde **404**.

**Por qué.** Un `200` vacío ante un id mal escrito oculta un error del cliente. Y en
la creación hay un motivo obligatorio: sin la comprobación, la FK rechazaría el
`INSERT`, `DataIntegrityViolationException` **no está mapeada** en
`GlobalExceptionHandler`, y el cliente recibiría un **500** en lugar de un 404. Un
`organizacionId` **malformado** sigue siendo 400.

**Costo aceptado.** `ConsultorioService` depende de `OrganizacionRepository`
(`existsById` al listar; `findById` al crear, que además aporta la entidad que el
mapper necesita, así que ahí no es una consulta extra).

### Desviaciones deliberadas respecto del plan

- **GAL-001 §7 (slicing).** La tarjeta define 3 unidades; el módulo se commitea en **4**.
  Se separó `test(consultorio): el proxy LAZY no se inicializa al leer el id` porque el
  test que cierra ese invariante es de **integración** (vive en
  `ConsultorioRepositoryTest`, archivo de la unidad de dominio) y no encajaba en el
  mensaje `contratos y servicio con tests unitarios`. Separarlo mantiene cada unidad con
  un propósito único y deja el invariante visible en el historial. El caso está
  sancionado por §6, dentro del "mínimo 3" que exige el repositorio.
- **GAL-001 §5.8** dicta `@RequestMapping("/api/v1/<recurso>")`. Este módulo usa **dos
  rutas base**: la colección cuelga de la organización y el ítem va plano. Se adoptó
  ADR-005 (*recursos anidados con ID*) porque el tenant es contexto real de la
  colección, porque deja el `organizacionId` del path como el lugar natural para
  validar el `tenantId` del token cuando llegue el JWT (una comprobación por request
  en lugar de un query param falsificable) y porque decidirlo **antes de que exista un
  consumidor** evita necesitar `/api/v2` después.
- **GAL-001 §4.1** muestra el `@Query` del repositorio **sin `ORDER BY`**. Aquí sí lo
  lleva, porque §3 de la tarjeta exige "ordenados por nombre" y el caso 4 de §6 lo
  afirma. El snippet del plan ilustra la regla (nombre en español + `@Query`
  explícito, sin name-derivation), no el texto exacto de la consulta; el módulo 00 ya
  ordena.

### Duda abierta

> **¿De dónde sale `organizacionId` cuando llegue el JWT?**

Hoy llega por el path, porque `SecurityConfig` está abierto y no hay tenant en el
`SecurityContext`. Es la continuación de R8.3(a) del módulo 00. Hipótesis registrada
como tal, **no decidida**: con JWT, el `tenantId` del claim reemplaza al parámetro
para roles MEDICO/PACIENTE (el tenant ES la organización) y el parámetro queda solo
para ADMIN. No se inventan reglas ahora.

### Riesgos declarados (documentarlos no los resuelve)

1. **El `POST` no es idempotente y V1 no tiene unicidad que lo frene.** No hay
   `UNIQUE` sobre `nombre` ni sobre `(organizacion_id, nombre)`, así que un reintento
   del cliente por un timeout **crea un consultorio duplicado en silencio**. Salidas
   conocidas: encabezado `Idempotency-Key` respaldado por tabla, o unicidad por clave
   natural. Decisión de fase posterior. Aplica también a `organizacion`, `medico`,
   `paciente`, `cita`, `pago`, `adjunto` y `notificacion`.
2. **`direccion` es `TEXT` sin límite.** El DTO no la acota a propósito (V1 no declara
   límite y fijar uno sería inventar una regla), así que acepta entrada de tamaño
   arbitrario: punto para la fase de endurecimiento. Se evaluaron rangos de referencia
   (~150/250 caracteres, normativa fiscal mexicana) y se descartaron por ahora;
   quedaría para una normalización futura de la tabla.
3. **`organizacion_id` viaja en cada `UPDATE`.** Hibernate incluye la columna porque
   está mapeada, aunque el atributo Java nunca se reasigne. Se evaluó
   `@JoinColumn(..., updatable = false)` y **se descartó**: haría que un cambio
   indebido de organización se **ignore en silencio** (divergencia memoria↔base sin
   error), mientras que sin la anotación el cambio **falla de forma visible** y lo
   frenan la regla de dominio y el review. El valor escrito es el mismo que ya tenía:
   cero efecto sobre los datos.
4. **Una aserción del test unitario es tautológica.** En
   `actualizar_shouldReplaceEditableFieldsAndPreserveImmutableOnes`, las comprobaciones
   de `id` y `createdAt` comparan un valor stubbeado consigo mismo, así que no prueban
   esa inmutabilidad a nivel de valor. Se deja declarado y **no** se corrigió porque la
   aserción no es incorrecta sino redundante: la cobertura real está en el test de
   repositorio (caso 6, con persistencia real y estado releído) y la inmutabilidad es
   además estructural (la entidad no tiene setters).

### Alcance y archivos protegidos

| Área | Estado |
|------|--------|
| `src/main/resources/db/migration/**` (V1) | Congelado, sin cambios |
| `model/Organizacion.java`, `model/PlanTipo.java`, `model/ConfiguracionTenant.java` | Sin cambios |
| `security/**`, incluido `SecurityConfig.java` | Sin cambios: el módulo 02 no toca seguridad |
| `config/**` y su `.gitkeep` | Congelado, sin cambios |
| `ErrorResponse`, `GlobalExceptionHandler`, `ResourceNotFoundException` | **Reutilizados** sin editar: contrato de error único |
| `pom.xml` | Sin cambios: no hizo falta ninguna dependencia nueva |
| `application*.properties` | Sin cambios |

### Límites de verificación

- Suite completa: `sh ./mvnw -q test` con salida **0**; **94 tests** con 0 fallos, 0
  errores y 0 omitidos (15 de controller, 13 de service, 12 de repositorio del módulo
  —40 en total— más los **54 previos** de los módulos 00 y 01: 1 de contexto + 21 de
  controller de organizacion + 9 de configuracion-tenant + 10 de repositorio de
  organizacion + 13 de service de organizacion). `-q` **no** imprime `BUILD SUCCESS`:
  no se inventa esa línea.
- Los tests cursan sobre **H2 en modo PostgreSQL**. H2 **no** prueba PostgreSQL: la
  validación contra la base real queda pendiente.
- La violación de FK se afirma como `DataIntegrityViolationException` **a través del
  repositorio** (`saveAndFlush`), y la violación la produce el motor (`SQLSTATE 23506`),
  no una validación previa de Hibernate.
- Commits del módulo, en orden: dominio y repositorio; contratos y servicio;
  invariante del proxy; API REST y estas notas.

### Estado nativo del módulo

El módulo 02 **no tiene review nativa aprobada**. RDD quedó apagado a nivel de clone
por decisión de Eduardo, tras el bloqueo por indisponibilidad (429) del proveedor del
modelo durante el módulo 01. **Tests verdes no equivalen a review aprobada.**

### Checklist de lectura

- [ ] Confirmé que la sección tiene **4 decisiones** y **1 duda**.
- [ ] Confirmé que la duda (tenant con JWT) queda abierta y con la hipótesis marcada como tal.
- [ ] Confirmé que las desviaciones respecto de GAL-001 §4.1 y §5.8 están justificadas, no silenciadas.
- [ ] Confirmé que los 4 riesgos declarados están declarados y que se dice que documentarlos no los resuelve.
- [ ] Confirmé que la aserción tautológica está declarada y que su cobertura real vive en el test de repositorio.
- [ ] Confirmé que este módulo **no** tiene review nativa aprobada.
- [ ] Confirmé que no hay conteos de tests ni éxito de suite inventados.

---

## medico

Módulo 03 con alcance **COMPLETO** (GAL-001 §6). **Cierra los 3 modelos de la rama**
(`Organizacion`, `Consultorio`, `Medico`).

Expone cinco endpoints bajo `/api/v1`, con la convención de rutas del proyecto (**la
colección cuelga de su dueño inmediato**, el ítem va plano):

| Método | Ruta | Respuesta |
|---|---|---|
| `POST` | `/api/v1/organizaciones/{organizacionId}/medicos` | 201 + `Location` |
| `GET` | `/api/v1/organizaciones/{organizacionId}/medicos` | 200 (solo activos, por nombre) |
| `GET` | `/api/v1/medicos/{id}` | 200 |
| `PUT` | `/api/v1/medicos/{id}` | 200 |
| `DELETE` | `/api/v1/medicos/{id}` | 204 (baja lógica, idempotente) |

### Decisión 1 — La baja es lógica porque las TRES FK entrantes lo obligan

**Contexto.** Tres tablas apuntan a `medico`, y **cada una se comporta distinto**:

| Tabla | Columna | `ON DELETE` | Efecto de un borrado físico del médico |
|---|---|---|---|
| `horario_medico` | `medico_id` NOT NULL | **CASCADE** | sus horarios se borran **en silencio** |
| `cita` | `medico_id` NOT NULL | **NO ACTION** | **falla** si tiene citas |
| `usuario` | `medico_id` (nullable) | **SET NULL** | el usuario sobrevive huérfano |

**Decisión.** `desactivar()` pone `activo = false`, es idempotente, y **no hay endpoint de
borrado físico**.

**Por qué.** El borrado físico **o está bloqueado o destruye datos sin avisar**. No es una
preferencia: es el esquema. Es el mismo razonamiento que en `consultorio`, pero aquí con tres
comportamientos distintos conviviendo, y uno de ellos (CASCADE sobre horarios) es silencioso
— el peor de los tres.

### Decisión 2 — `tarifaBase` en `BigDecimal`, y el mínimo es una regla de APLICACIÓN

**Decisión.**
- `BigDecimal` con `@Column(precision = 10, scale = 2)` (nunca `double`: `0.1 + 0.2 != 0.3`).
- En el DTO: **`@Digits(integer = 8, fraction = 2)`**, que **espeja la precisión declarada** y
  convierte un desborde de **500** (el `INSERT` revienta y `DataIntegrityViolationException`
  no está mapeada) en un **400**.
- **`@DecimalMin("0.0")`**: V1 **no declara** ningún `CHECK` sobre `tarifa_base`, así que una
  tarifa negativa **es válida para la base**. Este mínimo es una **regla de aplicación
  autorizada por Eduardo**, no una regla del esquema, y así queda declarado.
- La tarifa es **nullable** (V1 lo es): sin `@NotNull`.

**Regla de test vinculante.** Las aserciones de `BigDecimal` se escriben con
**`isEqualByComparingTo(...)`**, nunca `isEqualTo(...)`: `equals` compara **escala**, y
`new BigDecimal("500.0").equals(new BigDecimal("500.00"))` es **false**.

### Decisión 3 — El módulo no expone listado de médicos por consultorio

**Decisión.** No existe `GET /consultorios/{id}/medicos`.

**Por qué.** (a) V1 **no tiene índice** sobre `medico.consultorio_id` (el único es
`idx_medico_org`): ese endpoint haría *seq scan*; (b) el plan §4.1 dicta únicamente
`listarPorOrganizacion`; (c) sería inventar superficie de API sin demanda comprobada.

**Consecuencia declarada.** Si la UI necesita los médicos de un consultorio, hoy filtra en
cliente sobre el listado de la organización. Si se vuelve necesidad real, entra junto con el
índice en el **V2 agrupado**.

### Decisión 4 — Un 400 de negocio usa excepción propia y un caso nuevo en el traductor

**Contexto.** En el `PUT`, un `consultorioId` distinto del actual debe responder **400**. Esa
condición **depende del estado de la base**, así que no es expresable con anotaciones del
DTO, y el handler no tenía ningún caso de negocio que devolviera 400.

**Decisión.** El service hace la comparación y lanza
`exception/CambioDeConsultorioNoPermitidoException`; `GlobalExceptionHandler` suma **un**
`@ExceptionHandler` que la traduce a 400 reusando el `construir(...)` existente.

**Por qué esta y no otra.** Se evaluaron tres caminos:
- **Elegida:** excepción **específica** + un caso en el traductor único. El service no sabe de
  HTTP, el controller no tiene lógica de negocio, y el status se decide en un solo lugar. El
  criterio decisivo **no es el handler**: es que el tipo propio permite afirmar
  `isInstanceOf(CambioDeConsultorioNoPermitidoException.class)` en el test, lo que distingue
  *esa* regla de cualquier otra.
- **Descartada:** una `ReglaDeNegocioException` genérica. Menos boilerplate, pero el tipo no
  distingue reglas: un test con `isInstanceOf` pasaría **aunque se lanzara la regla
equivocada**, y habría que afirmar el texto del mensaje.
- **Descartada:** validar en el controller. Mete lógica de negocio en la capa web, exige una
  **consulta extra** y construye un `ErrorResponse` **a mano** — un segundo camino de error,
  justo lo que prohíbe la Decisión 2 del módulo 00.

**Alcance de la autorización.** Extender el traductor **no** es crear un camino paralelo;
reestructurarlo o duplicarlo sí. La tarjeta (R20) quedó enmendada para dejarlo explícito.

### Duda abierta

> **¿Un médico puede cambiar de consultorio?**

Hoy **no**: el `PUT` responde 400 si el cuerpo trae un consultorio distinto (Decisión 4). Es
la opción coherente con «un 200 que no aplica lo pedido miente», pero **no está decidido** si
la realidad del negocio permite la mudanza. Si la permite, corresponde un método de dominio
`reasignarConsultorio(...)` y un endpoint propio — **fuera** del alcance de este módulo.

*Contexto de roadmap que este módulo no responde:* ¿`cedula` debe ser única? V1 no lo declara.
La cédula profesional identifica a una **persona**, lo que sugeriría unicidad global, pero un
médico que cambie de clínica chocaría si se duplica la fila. Decisión de negocio pendiente; si
es que sí, entra en el V2 agrupado.

### Riesgos y observaciones declaradas (documentarlos no los resuelve)

1. **`medico` no tiene `created_at`** en V1. La entidad **no** lo mapea: agregarlo sin
   migración rompería `ddl-auto=validate`. Va al **V2 agrupado**, y cuando llegue habrá que
   retocar **4 capas** (entidad, `MedicoResponse`, mapper y tests). Un `DEFAULT NOW()` en su
   momento **no** recupera la fecha real de las filas preexistentes.
2. **No se valida que el consultorio pertenezca a la organización del médico.** El esquema no
   lo impide y este módulo tampoco: la API **acepta** esa combinación sin avisar. Es un
   agujero de **integridad de datos** (distinto del aislamiento por autenticación del T8). Con
   la API hoy abierta (`permitAll`), no es peor que el resto; el día que llegue RS256 pasa a
   importar mucho más.
3. **`cedula` sin `UNIQUE`** (ver la duda de arriba).
4. **Una tarifa negativa sigue siendo válida para el esquema.** El `@DecimalMin` la frena en
   la aplicación, pero un `INSERT` directo la acepta.
5. **El mensaje del 400 de tarifa es genérico.** `@Digits` y `@DecimalMin` no están en el mapa
   `MENSAJES_POR_CODIGO` del handler, así que ambos responden «El campo tarifaBase es
   inválido», sin decir **por qué**. Mejorarlo es agregar dos entradas a ese mapa.
6. **La cascada organización→médicos está VERIFICADA en PostgreSQL** (2026-10-26, con datos
   desechables sobre una base de desarrollo). `DELETE FROM organizacion` **funciona**. Motivo:
   `medico.consultorio_id` es **`NO ACTION` y no `RESTRICT`**, y `NO ACTION` verifica la FK **al
   final de la sentencia**, cuando el médico ya fue borrado por la otra cascada. **H2 diverge**
   (falla por el orden en que aplica las cascadas), y por eso el test coloca el consultorio en
   otra organización: así prueba el cascade `medico.organizacion_id` sin que el orden de H2 lo
   estorbe. La app **nunca** hace ese `DELETE` (todas las bajas son lógicas).
   **Límite de esta evidencia:** la verificación la hizo el humano responsable **a mano** sobre
   su PostgreSQL de desarrollo; **no hay script ni salida versionados en el repo**, así que un
   tercero no puede reproducirla desde el código — la suite automática solo cubre H2. Si en el
   futuro se quiere una prueba reproducible, el camino es un test de integración contra un
   PostgreSQL real en CI (Testcontainers o equivalente), que **hoy no existe**.
7. **Aserciones débiles o tautológicas del test unitario, declaradas:** los `isSameAs(...)`
   sobre `organizacion`/`consultorio` son inviolables por construcción (`actualizarDatos` no
   las recibe); funcionan como **canario de evolución de la firma**, no como prueba de
   comportamiento. `hasMessageContaining("consultorio")` no verifica que se pasen los UUID
   correctos a la excepción. Y el caso «mismo consultorio procede» no afirma que se hayan
   aplicado los 5 campos (la brecha la cubre el caso «actualizar ok»).

### Alcance y archivos protegidos

| Área | Estado |
|------|--------|
| `src/main/resources/db/migration/**` (V1) | Congelado, sin cambios |
| `model/Organizacion.java`, `PlanTipo.java`, `ConfiguracionTenant.java`, `Consultorio.java` | Sin cambios |
| `security/**`, incluido `SecurityConfig.java` | Sin cambios: el módulo 03 no toca seguridad |
| `config/**` y su `.gitkeep` | Congelado, sin cambios |
| `ErrorResponse`, `ResourceNotFoundException` | **Reutilizados** sin editar |
| `GlobalExceptionHandler` | **Extendido**: +1 `@ExceptionHandler`, cero líneas existentes modificadas (R20) |
| `pom.xml` | Sin cambios: no hizo falta ninguna dependencia nueva |
| `application*.properties` | Sin cambios |

### Límites de verificación

- Suite completa: `sh ./mvnw -q test` con salida **0**; **139 tests** con 0 fallos, 0 errores y
  0 omitidos. `-q` **no** imprime `BUILD SUCCESS`: no se inventa esa línea.
- Los tests cursan sobre **H2 en modo PostgreSQL**. H2 **no** prueba PostgreSQL: la cascada
  multi-tabla es el caso concreto donde los dos motores **divergen**, y quedó verificado a
  mano contra PostgreSQL el 2026-10-26.
- Las violaciones de FK se afirman como `DataIntegrityViolationException` **a través del
  repositorio**; en los `DELETE` se usa `delete` + `flush`, porque un `DELETE` no tiene
  `saveAndFlush`.
- Commits del módulo, en orden: `dominio y repositorio` · `contratos y servicio` · **`API REST y todas estas notas`** (el tercero se crea junto con esta misma unidad).

### Estado nativo del módulo

El módulo 03 **no tiene review nativa aprobada**. RDD sigue **apagado a nivel de clone** por
decisión de Eduardo. **Tests verdes no equivalen a review aprobada.**

### Checklist de lectura

- [ ] Confirmé que la sección tiene **4 decisiones** y **1 duda**.
- [ ] Confirmé que la duda (¿puede un médico cambiar de consultorio?) queda abierta y con la salida propuesta.
- [ ] Confirmé que el `@DecimalMin` está declarado como **regla de aplicación** y no como regla del esquema.
- [ ] Confirmé que la ausencia de `created_at` está declarada con su costo de 4 capas.
- [ ] Confirmé que el hueco de integridad (consultorio de otra organización) está declarado y NO resuelto.
- [ ] Confirmé que la cascada está declarada como **verificada en PostgreSQL** y que **H2 diverge**.
- [ ] Confirmé que las aserciones débiles están declaradas, no maquilladas.
- [ ] Confirmé que este módulo **no** tiene review nativa aprobada.
- [ ] Confirmé que no hay conteos de tests ni éxito de suite inventados.
