/**
 * Identidad de desarrollo: resolución de la cuenta contra la tabla `usuario` de PostgreSQL.
 *
 * <p><strong>Por qué no un archivo.</strong> El alta de una clínica crea un administrador nuevo
 * en la tabla `usuario`, y un archivo estático no lo conoce: esa persona no podría iniciar
 * sesión en desarrollo. La tabla es la única fuente de verdad de la identidad también en local.</p>
 *
 * <p><strong>Qué se conserva.</strong> La firma, la vigencia y los claims del token no cambian;
 * solo cambia de dónde sale la cuenta. La traducción de fila a claims
 * ({@link traducirFilaAClaims}) y la decisión del login ({@link evaluarCuenta}) son funciones
 * puras: se prueban sin base de datos, que es justamente lo que las hace verificables.</p>
 *
 * <p><strong>Nunca se registra</strong> el correo consultado, la fila leída ni la cadena de
 * conexión. Los errores del driver se envuelven en un mensaje propio para no copiar detalles de
 * la conexión a la respuesta HTTP.</p>
 */

/** Roles que el backend admite en el claim `role` (R-SEG-02). La base acepta más. */
const ROLES_DEL_CONTRATO = new Set(['ADMIN', 'MEDICO', 'PACIENTE']);

/** Forma canónica de un UUID: el backend la exige para `tenantId` y compara contra columnas UUID. */
const PATRON_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const PUERTO_PG_POR_DEFECTO = 5432;
const HOST_PG_POR_DEFECTO = 'localhost';

/**
 * Columnas que necesita el emisor de una fila de `usuario`, más el `activo` de su médico. El
 * `LEFT JOIN` es a propósito: los roles que no son MEDICO no siempre tienen fila de médico.
 */
const COLUMNAS = `
         u.auth_user_id,
         u.email,
         u.rol,
         u.organizacion_id,
         u.medico_id,
         u.activo,
         m.activo AS medico_activo
    FROM usuario u
    LEFT JOIN medico m ON m.id = u.medico_id`;

/**
 * El login normaliza el correo a minúsculas, así que la comparación también. El índice único es
 * `(organizacion_id, email)` y no hay índice funcional sobre `lower(email)`; a la escala de
 * desarrollo no hace falta, y si alguna vez hiciera falta se agrega en una migración, no aquí.
 */
const SQL_POR_CORREO = `SELECT ${COLUMNAS}\n   WHERE lower(u.email) = $1`;

/**
 * `auth_user_id` es único por organización, no global (V2), así que esta consulta puede devolver
 * más de una fila. El refresco lo trata como sesión inválida: sin más datos no hay a quién
 * atribuir el token.
 */
const SQL_POR_AUTH_USER_ID = `SELECT ${COLUMNAS}\n   WHERE u.auth_user_id = $1`;

// ===============================
// Funciones puras
// ===============================

/**
 * Normaliza un correo para buscarlo y compararlo: recorta espacios y pasa a minúsculas.
 *
 * @param {unknown} valor correo tal como llega en la petición o en la fila
 * @returns {string} correo normalizado, o cadena vacía si no es texto
 */
export function normalizarCorreo(valor) {
  return typeof valor === 'string' ? valor.trim().toLowerCase() : '';
}

/**
 * Traduce una fila de `usuario` (con el estado de su médico) a los claims del token.
 *
 * <p>Es una función pura: sin base de datos, sin reloj, sin entorno. Falla cerrado si la fila no
 * puede producir un token que el backend acepte, porque emitirlo daría un `401` del backend sin
 * causa visible.</p>
 *
 * @param {object} fila fila de `usuario` con la columna extra `medico_activo`
 * @returns {{sub: string, email: string, role: string, tenantId: string, medicoId?: string}}
 */
export function traducirFilaAClaims(fila) {
  if (fila === null || typeof fila !== 'object') {
    throw new Error('la fila de usuario está vacía');
  }

  const claims = {
    sub: uuidCanonico(fila.auth_user_id, 'auth_user_id'),
    email: normalizarCorreo(fila.email),
    role: rolDelContrato(fila.rol),
    tenantId: uuidCanonico(fila.organizacion_id, 'organizacion_id'),
  };
  if (claims.email === '' || !claims.email.includes('@')) {
    throw new Error('el campo "email" no es un correo');
  }

  if (claims.role === 'MEDICO') {
    claims.medicoId = uuidCanonico(fila.medico_id, 'medico_id');
  }
  // Un usuario de otro rol puede tener médico asociado (un administrador que también atiende).
  // El contrato de claims solo lleva `medicoId` con rol MEDICO, así que aquí se omite a propósito.
  return claims;
}

function uuidCanonico(valor, campo) {
  if (typeof valor !== 'string' || !PATRON_UUID.test(valor)) {
    throw new Error(`el campo "${campo}" no es un UUID canónico`);
  }
  return valor;
}

function rolDelContrato(valor) {
  if (!ROLES_DEL_CONTRATO.has(valor)) {
    throw new Error(
      `el campo "rol" no es ADMIN, MEDICO ni PACIENTE, así que el token que produciría no sería válido para el backend`,
    );
  }
  return valor;
}

/**
 * Decide el resultado del login a partir de las filas que devolvió la búsqueda por correo.
 * Función pura y total: nunca lanza, siempre devuelve un resultado accionable.
 *
 * @param {Array<object>} filas filas de `usuario` con `medico_activo`
 * @returns {{resultado: 'inexistente'}
 *   | {resultado: 'ambiguo', cantidad: number}
 *   | {resultado: 'inactiva'}
 *   | {resultado: 'medico-inactivo'}
 *   | {resultado: 'fila-invalida', detalle: string}
 *   | {resultado: 'ok', claims: object}}
 */
export function evaluarCuenta(filas) {
  if (!Array.isArray(filas) || filas.length === 0) {
    return { resultado: 'inexistente' };
  }
  // El correo es único por organización, no global. Con dos candidatos no hay forma de saber
  // cuál quiere usar quien inicia sesión, así que se responde antes de mirar su estado: el
  // problema es la ambigüedad, no la baja.
  if (filas.length > 1) {
    return { resultado: 'ambiguo', cantidad: filas.length };
  }

  const [fila] = filas;
  if (fila === null || typeof fila !== 'object') {
    return { resultado: 'fila-invalida', detalle: 'la fila de usuario está vacía' };
  }
  // Fail-closed: sin un `true` explícito la cuenta no está vigente.
  if (!fila.activo) {
    return { resultado: 'inactiva' };
  }
  // Decisión de producto: el acceso del médico se cierra en el borde cuando su fila de médico
  // deja de estar activa; la cuenta no se toca, así que reactivar al médico devuelve el acceso.
  if (fila.rol === 'MEDICO' && !fila.medico_activo) {
    return { resultado: 'medico-inactivo' };
  }

  try {
    return { resultado: 'ok', claims: traducirFilaAClaims(fila) };
  } catch (causa) {
    return { resultado: 'fila-invalida', detalle: causa.message };
  }
}

// ===============================
// Repositorio PostgreSQL
// ===============================

/**
 * Descripción apta para imprimir y para mensajes de error: host, puerto y base, nunca usuario ni
 * contraseña. La cadena de conexión puede llevar credenciales, así que no se copia entera.
 *
 * @param {{url?: string|null, host?: string|null, puerto?: number, base?: string|null}} baseDatos
 * @returns {string}
 */
export function descripcionDeBaseDeDatos(baseDatos) {
  if (baseDatos?.url) {
    try {
      const analizada = new URL(baseDatos.url);
      const base = analizada.pathname.replace(/^\//, '');
      return `${analizada.hostname}:${analizada.port || PUERTO_PG_POR_DEFECTO}/${base}`;
    } catch {
      return 'la base configurada en DATABASE_URL';
    }
  }
  const host = baseDatos?.host ?? HOST_PG_POR_DEFECTO;
  const puerto = baseDatos?.puerto ?? PUERTO_PG_POR_DEFECTO;
  return `${host}:${puerto}/${baseDatos?.base ?? ''}`;
}

function opcionesDeConexion(baseDatos) {
  const comunes = {
    // Un tope corto convierte «PostgreSQL no está levantado» en un error visible en segundos en
    // vez de una espera larga que parece que el emisor se colgó.
    connectionTimeoutMillis: 5000,
    application_name: 'dev-auth',
  };
  if (baseDatos.url) {
    return { ...comunes, connectionString: baseDatos.url };
  }
  return {
    ...comunes,
    host: baseDatos.host ?? undefined,
    port: baseDatos.puerto ?? undefined,
    database: baseDatos.base ?? undefined,
    user: baseDatos.usuario ?? undefined,
    password: baseDatos.contrasena ?? undefined,
  };
}

/**
 * Construye el repositorio de identidad contra PostgreSQL.
 *
 * <p>La dependencia `pg` se carga de forma diferida: así el emisor no arrastra el driver cuando
 * las pruebas inyectan un repositorio propio, y la falta de la dependencia da un mensaje que
 * dice cómo resolverla.</p>
 *
 * @param {ReturnType<typeof import('./server.mjs').leerConfiguracion>['baseDatos']} baseDatos
 * @returns {Promise<{verificarConexion: () => Promise<void>, buscarPorCorreo: (correo: string) => Promise<Array<object>>, buscarPorAuthUserId: (authUserId: string) => Promise<Array<object>>, cerrar: () => Promise<void>}>}
 */
export async function crearRepositorioPostgres(baseDatos) {
  let Pool;
  try {
    ({ Pool } = await import('pg'));
  } catch {
    throw new Error(
      'No se encontró la dependencia "pg". Ejecute `npm install` dentro de tools/dev-auth antes de arrancar el emisor.',
    );
  }

  const origen = descripcionDeBaseDeDatos(baseDatos);
  const pool = new Pool(opcionesDeConexion(baseDatos));

  async function consultar(sql, parametros) {
    try {
      const { rows } = await pool.query(sql, parametros);
      return rows;
    } catch (causa) {
      // El mensaje del driver no incluye credenciales, pero sí la dirección y el motivo; se
      // envuelve para que quien lo lea sepa qué revisar y de qué tabla se trata.
      throw new Error(`No se pudo consultar la tabla usuario en ${origen}: ${causa.message}`);
    }
  }

  return {
    async verificarConexion() {
      await consultar('SELECT 1 AS ok', []);
    },
    buscarPorCorreo(correo) {
      const normalizado = normalizarCorreo(correo);
      if (normalizado === '') {
        return Promise.resolve([]);
      }
      return consultar(SQL_POR_CORREO, [normalizado]);
    },
    buscarPorAuthUserId(authUserId) {
      return consultar(SQL_POR_AUTH_USER_ID, [authUserId]);
    },
    cerrar() {
      return pool.end();
    },
  };
}
