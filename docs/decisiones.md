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

> **Aclaración (29-oct-2026) — quién sirve `/api/v1/auth/*`:** esa ruta pertenece al
> **AuthService**, no al backend. R8.2 fija que el backend solo valida tokens y nunca los emite, así
> que esta convención define la **forma** de la ruta de autenticación, no el servicio que la
> expone. Consecuencia para el frontend: el login, el refresco y el cierre de sesión usan
> `VITE_AUTH_URL` (el origen del emisor), separada de `VITE_API_URL`, y el navegador le habla
> directamente con credenciales. Si el AuthService real resulta ser backend-a-backend, se agrega un
> proxy en el backend y **solo cambia esa variable**.

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

---

## ADR-008 · Autenticación RS256: el backend solo valida tokens

**Estado:** Aceptado (29-oct-2026). Dos puntos quedan como **supuesto** pendientes de
validación externa: dónde viven las credenciales y quién es la autoridad del directorio de
identidad.

**Contexto:** Hasta ahora los endpoints estaban abiertos con `permitAll()`, como excepción
temporal autorizada para revisar Swagger en local. Esa apertura no aporta aislamiento entre
tenants y no puede llegar a producción. La dirección ya estaba fijada: RS256, y el backend
nunca genera tokens ni almacena contraseñas.

**Decisión:**
- El backend **solo verifica** tokens RS256 con la clave pública. No emite tokens, no firma y
  no hashea contraseñas: en el código no existe ni debe existir un `PasswordEncoder`.
- La verificación usa **jjwt 0.13.0**, que ya estaba en el `pom.xml`. No se agregó ninguna
  dependencia; se descartó `spring-boot-starter-oauth2-resource-server` por eso.
- El emisor de tokens es un servicio externo. En desarrollo se usa un emisor mínimo propio,
  **fuera del backend**, escuchando en `AUTH_SERVICE_URL`; así el backend sigue sin emitir
  tokens incluso en desarrollo.
- **Supuesto:** las credenciales viven en el emisor y la tabla `usuario` es el **directorio de
  identidad** (rol, organización y médico al que pertenece cada usuario), que el emisor lee en
  solo lectura. Motivo: el rol y el tenant son hechos de autorización; duplicarlos en dos
  lugares produce el fallo silencioso de que un médico trasladado de clínica siga con un token
  válido para la clínica anterior.

**Consecuencias:**
- (+) Un token válido no puede falsificarse sin la clave privada del emisor.
- (+) El backend no guarda secretos de usuario, así que una filtración de su base no expone
  contraseñas de nadie.
- (-) La columna `usuario.password_hash` queda **sin escritor**. Es deuda declarada: cualquier
  alta de usuario futura necesita decidir qué se guarda ahí.
- (-) El backend no puede autenticar por sí mismo: si el emisor no está disponible, no hay login.
- (-) Si la validación externa responde que las credenciales viven en nuestra base, este ADR se
  revisa y el backend sí necesitará hashear.

---

## ADR-009 · Clave pública: formato, custodia y arranque fail-fast

**Estado:** Aceptado (29-oct-2026)

**Contexto:** La propiedad `auth.public.key` existía declarada pero ningún código la leía. Una
clave mal configurada debía impedir el arranque, no producir un servicio que acepta o rechaza
todo en silencio.

**Decisión:**
- `AUTH_PUBLIC_KEY` transporta **la clave como texto**, no una ruta: Base64 del DER
  `SubjectPublicKeyInfo` (SPKI) en **una sola línea** (392 caracteres para RSA 2048). No es PEM,
  no es PKCS#1, no es OpenSSH ni un certificado.
- Se decodifica con `X509EncodedKeySpec` y se mantiene en memoria: no hay lectura de archivos ni
  llamada de red por petición.
- El arranque **falla** con un mensaje que nombra `auth.public.key` y `AUTH_PUBLIC_KEY` si la
  clave falta, está vacía, no es Base64 válido o no es una clave RSA. La comprobación corre en
  **todos** los perfiles, incluido `test`.
- La clave privada nunca entra al repositorio, al historial ni a un log. Vive en el Vault del
  IDE del desarrollador y en la custodia del emisor. Cada desarrollador tiene su propio par; los
  entornos compartidos usan un par propio y las pruebas generan el par **en memoria**.
- La configuración del perfil `test` vive en `src/test/resources`, no en `src/main/resources`, para
  que no viaje dentro del artefacto de producción.

**Consecuencias:**
- (+) Un despliegue sin clave no arranca: el fallo es inmediato y con causa legible.
- (+) La clave pública no es un secreto, así que puede viajar en configuración.
- (-) Rotar la clave exige cambiar la variable de entorno y reiniciar; no hay recarga en caliente.
- (-) Una variable de entorno exportada en la shell tiene precedencia sobre la configuración del
  perfil y puede romper pruebas que no tienen relación con el cambio en curso.

---

## ADR-010 · Contrato de claims del token

**Estado:** Aceptado (29-oct-2026). Los nombres definitivos dependen de la validación externa
pendiente.

**Contexto:** El frontend ya leía claims del token para decidir rutas y mostrar la organización,
y el backend necesitaba tomarlos de ahí en lugar de confiar en la URL.

**Decisión:** El token lleva exactamente estos claims:

| Claim | Obligatorio | Tipo | Significado |
|---|---|---|---|
| `sub` | Sí | UUID | Identificador del usuario |
| `role` | Sí | texto | `ADMIN`, `MEDICO` o `PACIENTE`; cualquier otro valor se rechaza |
| `tenantId` | Sí | UUID | Organización dueña de la sesión |
| `email` | No | texto | Correo del usuario |
| `medicoId` | No | UUID | Médico asociado, cuando el rol lo tiene |
| `exp` | Sí | numérico | Vencimiento; sin él el token se rechaza |
| `iat` | No | numérico | Emisión |
| `iss` | Configurable | texto | Emisor; se valida si `auth.issuer` está configurado |
| `aud` | Configurable | texto | Audiencia; se valida si `auth.audience` está configurado |

Un token con un claim obligatorio ausente, con un rol fuera de la lista o con un identificador
que no sea UUID se rechaza con `401` y el contrato de error habitual.

**Consecuencias:**
- (+) El backend deja de confiar en el `organizacionId` de la URL como fuente de verdad.
- (+) Los nombres coinciden con los que el cliente ya leía, así que no hubo que cambiar el
  frontend para esto.
- (-) `iss` y `aud` vacíos en desarrollo **desactivan** esas dos validaciones. Es aceptable en
  local, pero antes de producción deben estar configurados.
- (-) Si el servicio real emite otros nombres de claim, hay que cambiar el filtro y este ADR.

---

## ADR-011 · Sesión del cliente: token en memoria y refresh en cookie httpOnly

**Estado:** Aceptado (29-oct-2026)

**Contexto:** El frontend guardaba el token en `localStorage`, legible por cualquier XSS, junto con
el objeto de usuario. En una aplicación clínica eso equivale a una sesión exfiltrable.

**Decisión:**
- El token de acceso vive **solo en memoria**. No se escribe nada de sesión en `localStorage`.
- El refresco viaja en una cookie `httpOnly` con `SameSite=Strict`.
- El cliente HTTP se configura con credenciales y reintenta **una sola vez** ante `401`,
  refrescando antes de repetir la petición original, sin disparar refrescos en paralelo.
- El backend habilita CORS con credenciales y origen explícito, nunca comodín.
- El endpoint de refresco se protege con `SameSite=Strict` más validación del encabezado `Origin`.

**Consecuencias:**
- (+) Un XSS ya no puede leer un token del disco.
- (+) Recargar la página recupera la sesión sin pedir credenciales de nuevo.
- (-) El alcance crece: sin CORS con credenciales y sin estrategia anti-CSRF para el refresco, el
  diseño no funciona. El backend hoy no tiene CORS configurado.
- (-) Aparece una dependencia nueva del emisor: debe implementar el refresco y el cierre de sesión.

---

## ADR-012 · Vigencia del token y ausencia de revocación

**Estado:** Aceptado como supuesto (29-oct-2026), pendiente de validación externa.

**Contexto:** El frontend ya cierra la sesión por inactividad a los 30 minutos, pero el emisor de
pruebas firmaba tokens de 8 horas: el cierre era cosmético. Además, revocar tokens exige estado
en el servidor, lo que rompe el diseño sin sesión.

**Decisión:**
- Token de acceso de **30 minutos**, alineado con la ventana de inactividad.
- Refresco de vida **máxima 30 minutos**, deslizante mientras hay actividad.
- **Sin revocación en el servidor.** Cerrar sesión borra la cookie de refresco y limpia la memoria
  del cliente, pero no invalida un token de acceso ya emitido.

**Consecuencias:**
- (+) El cierre por inactividad deja de ser decorativo.
- (+) El backend sigue sin estado, que es lo que permite escalarlo sin replicar sesiones.
- (-) **Un token robado sigue siendo válido hasta 30 minutos** y no hay forma de matarlo antes.
  Es la deuda que se acepta a cambio de no mantener una tabla de revocación.
- (-) Si se exigiera revocación inmediata, haría falta una tabla de sesiones y una migración de
  esquema.

---

## ADR-013 · Autorización: matriz por rol y aislamiento por tenant

**Estado:** Aceptado (29-oct-2026)

**Contexto:** Cualquier petición podía leer y mutar recursos de cualquier organización, porque el
tenant se tomaba de la URL. Leer o modificar por identificador no comprobaba pertenencia: era
posible acceder a datos de otra clínica con solo conocer un UUID.

**Decisión:**
- El `tenantId` del token es la autoridad. El `{organizacionId}` de la ruta solo se acepta si
  coincide con el claim, **sin excepción de rol**.
- **`403`** cuando el path no coincide con el claim; **`404`** cuando se pide por identificador un
  recurso de otra organización, para no revelar que existe.

> **Corrección (29-oct-2026, antes del merge):** la redacción original de este ADR decía "salvo
> para `ADMIN`". Eso contradecía la matriz de abajo, que limita a `ADMIN` a *su* organización, y
> contradecía el criterio de salida de la feature: un token de la organización A no puede leer ni
> mutar recursos de B, con ningún rol. No existe un rol de plataforma que justifique la excepción;
> la única operación sin tenant en la ruta es el alta de organización, tratada aparte.
- Matriz aplicada a los endpoints actuales:

| Acción | ADMIN | MEDICO | PACIENTE |
|---|---|---|---|
| Crear organización | 403 | 403 | 403 |
| Leer y modificar su organización | solo la suya | 403 | 403 |
| Crear, modificar y desactivar consultorios y médicos | su organización | 403 | 403 |
| Leer consultorios y médicos | su organización | su organización | 403 |

- `PACIENTE` recibe `403` en todos los endpoints actuales porque todavía no existe ningún
  contrato de paciente; su matriz se define cuando exista.
- La autogestión del médico (editar sus propios datos) **no** se resuelve dentro de la
  actualización de médicos: requiere un endpoint propio con su propio contrato, para no ramificar
  autorización campo por campo en un reemplazo completo.

**Consecuencias:**
- (+) Un usuario de una organización no puede leer ni mutar datos de otra.
- (+) El `404` por identificador no confirma la existencia de recursos ajenos.
- (-) **El alta de organizaciones queda bloqueada (`403` para todos)** hasta que exista un flujo de
  alta propio (Sprint 2). Se decidió el 29-oct-2026 al comprobar que, con el aislamiento por tenant
  aplicado, una organización recién creada queda inalcanzable para todos los roles: el creador
  recibe `201` y un `Location` que después da `403` en lectura, en alta de hijos y en baja, con la
  fila activa para siempre. En desarrollo la clínica se siembra por SQL.
- (-) La distinción `403`/`404` obliga a que el manejador de errores conozca las excepciones de
  autorización; si no, el `403` sale con el formato por defecto del framework y rompe el contrato
  de error único.

---

## ADR-014 · Auditoría: eventos de seguridad ahora, accesos clínicos después

**Estado:** Aceptado (29-oct-2026)

**Contexto:** El servicio de auditoría está configurado pero ningún módulo lo consume. Al mismo
tiempo, la aplicación imprime en el log una contraseña generada por el framework en cada arranque,
lo que contradice el requisito de no registrar credenciales.

**Decisión:**
- Se registran **eventos de seguridad sin datos personales ni clínicos**: intentos rechazados con
  actor, ruta y resultado; nunca el token, ni claims sensibles, ni contenido de un paciente.
- La auditoría de **accesos a datos clínicos** se difiere hasta que exista el primer endpoint con
  información de paciente. Hoy no hay nada clínico que auditar: organización, consultorio y médico
  son configuración.
- Se elimina la contraseña generada por el framework del arranque, excluyendo la
  autoconfiguración que la produce.

**Consecuencias:**
- (+) Un `401` o `403` deja rastro útil para investigar sin filtrar datos sensibles.
- (+) El log deja de contener credenciales.
- (-) La trazabilidad clínica que una norma de expediente exige queda pendiente, con disparador
  explícito y no como olvido.

---

## ADR-015 · Verificación automatizada del frontend

**Estado:** Aceptado (29-oct-2026)

**Contexto:** El frontend no tenía ninguna herramienta de prueba: ni Vitest, ni Jest, ni Testing
Library. Sin eso, el manejo de sesión, expiración y rutas protegidas solo podía verificarse a mano.

**Decisión:** Incorporar **Vitest + Testing Library** y tratar como comportamiento sujeto a prueba
todo lo que no necesita navegador: interceptor de `401`, chequeo de vencimiento, limpieza de
sesión y decisión de rutas por rol.

**Consecuencias:**
- (+) Las reglas de sesión dejan de depender de una inspección manual.
- (+) Las dependencias nuevas son de desarrollo y no viajan al artefacto.
- (-) La prueba funcional en navegador sigue siendo necesaria para el flujo real: tests verdes no
  demuestran que la interfaz funcione.

---

## ADR-016 · Supuestos pendientes de validación externa

**Estado:** Abierto (29-oct-2026)

**Contexto:** Hay decisiones de producto que corresponden al Product Owner y que fueron
consultadas por escrito. Al no haber respuesta y con el cronograma atrasado, se adoptó un criterio
propio para no bloquear el trabajo. Un supuesto no es una decisión: tiene fecha, dueño y una
pregunta que lo puede revocar.

**Decisión:** Continuar con los supuestos de ADR-008 y ADR-012, dejando este registro como único
lugar donde se declaran, y respetar dos reglas:
- Todo supuesto se implementa **declarado como supuesto**, nunca presentado como decisión cerrada.
- El diseño queda preparado para cambiar de fuente **sin reescribirse**. Concretamente, la
  validación se construye detrás de un componente que hoy lee la clave pública configurada: si el
  emisor real publica un endpoint de claves (JWKS), se cambia ese componente y no la validación ni
  el mapeo de claims.

**Pendiente de respuesta:**

| Pregunta | Qué define | Supuesto adoptado |
|---|---|---|
| Dónde viven las credenciales | Si el backend hashea o no | Viven en el emisor |
| Qué se hace con `password_hash` | Esquema y alta de usuarios | Queda sin escritor |
| Qué claims emite el servicio real | Contrato del token | Los de ADR-010 |
| Cómo se entrega la clave pública | Clave fija o JWKS | Clave fija configurada |
| Duración y refresco del token | Vigencia de sesión | 30 minutos |
| Cierre por inactividad | Requisito normativo | 30 minutos |
| Alcance de la auditoría | Trazabilidad clínica | Diferida |

**Consecuencias:**
- (+) El trabajo avanza sin bloquearse y sin esconder la deuda.
- (-) Si la respuesta contradice un supuesto, hay retrabajo acotado y conocido de antemano.
- (-) La documentación debe revisarse cuando lleguen las respuestas: un supuesto vencido es peor
  que un supuesto declarado.