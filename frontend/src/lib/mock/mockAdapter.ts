import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import {
  mockUsers,
  mockPatients,
  mockDoctors,
  mockDoctorSchedules,
  mockClinicRooms,
  mockOrganization,
  mockAppointments,
  mockPayments,
  mockSoapNotes,
  mockCie10Catalog,
  mockDiagnoses,
  mockPrescriptions,
} from './mockData';
import type { Cita, EstadoCita } from '@/features/citas/citas.types';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import type { Medico, HorarioMedico } from '@/features/medicos/medicos.types';
import type { Usuario } from '@/features/usuarios/usuarios.types';
import type { Diagnostico } from '@/features/expediente/expediente.types';
import type { MarcaClinica, PatientIdentification, SoapNote, Prescription } from '@/types/futuro.types';

type PacienteConFicha = Paciente & { ficha?: PatientIdentification };
type MedicoMock = Medico & { duracionConsultaMin?: number };

/**
 * Decisión única de activación de mocks: **fail-closed** (R-SEG-07, plan sección 2 guardarraíl 3).
 *
 * Solo se usan mocks con `import.meta.env.DEV` **y** `VITE_USE_MOCKS === 'true'`. Si la variable
 * no está definida, NO hay mocks: un despliegue mal configurado debe apuntar a la red real y
 * fallar limpio, nunca fabricar datos clínicos ni aceptar inicios de sesión falsos.
 * Se exporta para poder probar la decisión sin instanciar Axios.
 */
export function debenUsarseMocks(): boolean {
  return Boolean(import.meta.env.DEV) && import.meta.env.VITE_USE_MOCKS === 'true';
}

/**
 * Vigencia del token simulado, en segundos: 30 minutos (ADR-012).
 * El store rechaza tokens vencidos, así que el mock no puede fabricar una vigencia mayor que la
 * que decide el contrato.
 */
const VIGENCIA_TOKEN_SEGUNDOS = 30 * 60;

/**
 * Registra interceptores de Mock en la instancia de Axios cuando `debenUsarseMocks()`.
 * Permite desarrollar y probar todos los flujos UI de forma 100% autónoma.
 */
export function setupMockAdapter(axiosInstance: AxiosInstance) {
  // Simular retraso de red para dar retroalimentación visual realista
  const delay = (ms = 250) => new Promise((resolve) => setTimeout(resolve, ms));

  /**
   * Decodifica el `sub` del token de la sesión desde la cabecera `Authorization`, para las reglas
   * de "nadie opera sobre su propia cuenta" (R11) de Usuarios. Se llama solo dentro de
   * `config.adapter`, nunca en el cuerpo del interceptor: este mock se registra antes que el
   * interceptor que adjunta `Authorization` (ver `lib/axios.ts`), así que la cabecera todavía no
   * existe cuando el cuerpo del interceptor corre, pero sí cuando Axios por fin invoca el adapter.
   */
  const subDeLaSesion = (cfg: InternalAxiosRequestConfig): string | null => {
    const header = cfg.headers?.get?.('Authorization') as string | undefined;
    const token = header?.replace(/^Bearer\s+/i, '');
    const payload = token?.split('.')[1];
    if (!payload) return null;
    try {
      return JSON.parse(atob(payload)).sub ?? null;
    } catch {
      return null;
    }
  };

  // Mutable in-memory store for mocks
  const patients: PacienteConFicha[] = [...mockPatients];
  const consultorios: Consultorio[] = [...mockClinicRooms];
  const medicos: MedicoMock[] = [...mockDoctors];
  const horarios: HorarioMedico[] = [...mockDoctorSchedules];
  const usuarios: Usuario[] = [...mockUsers];
  let appointments = [...mockAppointments];
  let soapNotes = [...mockSoapNotes];
  const diagnosticos: Diagnostico[] = [...mockDiagnoses];
  const prescripciones: Prescription[] = [...mockPrescriptions];
  // Marca por organización: sin contrato real, cada tenant parte de los mismos valores por
  // defecto hasta que el admin los personalice (no existe fila en ninguna tabla real).
  const marcasPorOrganizacion = new Map<string, MarcaClinica>();

  axiosInstance.interceptors.request.use(async (config) => {
    // Si los mocks no están explícitamente habilitados, no intervenir (va a la red real)
    if (!debenUsarseMocks()) return config;

    const url = config.url || '';
    const method = (config.method || 'get').toLowerCase();

    // ─────────────────────────────────────────
    // AUTH
    // ─────────────────────────────────────────
    if (url.includes('/auth/login') && method === 'post') {
      await delay(350);
      const data = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const user = mockUsers.find((u) => u.email === data.email);

      // Un correo que no está en la fixture del mock debe fallar, no entrar como si fuera el
      // primer usuario de la lista: ese fallback silencioso era indistinguible de iniciar sesión
      // de verdad con esa cuenta, y typear mal el correo (o usar las credenciales del backend
      // real por error) terminaba abriendo sesión como admin sin ningún aviso.
      if (!user) {
        config.adapter = async () => ({
          data: { mensaje: 'Correo o contraseña incorrectos.' },
          status: 401,
          statusText: 'Unauthorized',
          headers: {},
          config,
        });
        return config;
      }

      // Simulamos token JWT RS256 codificando la carga útil.
      // Vigencia alineada con ADR-012: **30 minutos**, los mismos que la ventana de inactividad.
      // Un token de 8 h volvía decorativo el cierre por inactividad.
      const iat = Math.floor(Date.now() / 1000);
      const fakePayload = {
        sub: user.id,
        email: user.email,
        role: user.rol,
        tenantId: user.organizacionId,
        medicoId: user.medicoId,
        iat,
        exp: iat + VIGENCIA_TOKEN_SEGUNDOS,
      };

      const fakeToken = `eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.${btoa(
        JSON.stringify(fakePayload)
      )}.fake_signature_rs256`;

      config.adapter = async () => ({
        data: {
          accessToken: fakeToken,
          user: fakePayload,
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // El refresco y el cierre de sesión dependen de la cookie `httpOnly` que pone el emisor real:
    // el login simulado de arriba nunca la pone, así que no hay nada que leer aquí. Si estas dos
    // rutas no se interceptan, Axios las deja pasar a la red real (`VITE_AUTH_URL`) y, si ese
    // emisor sigue corriendo con una cookie de una sesión real anterior, reabre esa sesión real
    // por encima de lo que se acaba de hacer con el mock — sin aviso y sin relación con el correo
    // que se usó para entrar. Interceptarlas aquí, aunque sea para fallar, es lo que mantiene el
    // modo mock aislado de la red real.
    if (url.includes('/auth/refresh') && method === 'post') {
      await delay(150);
      config.adapter = async () => ({
        data: { mensaje: 'No hay una sesión que refrescar en el mock.' },
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/auth/logout') && method === 'post') {
      await delay(100);
      config.adapter = async () => ({
        data: undefined,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // ORGANIZACIÓN (contrato real: GET /organizaciones/{id})
    // ─────────────────────────────────────────
    // Ruta plana y exacta: no debe atrapar las rutas anidadas de abajo (marca, pacientes,
    // consultorios, médicos) que también cuelgan de `/organizaciones/{id}/...`.
    const organizacionPorId = url.match(/^\/organizaciones\/([^/]+)\/?$/);

    if (organizacionPorId && method === 'get') {
      await delay();
      const id = organizacionPorId[1];
      if (id !== mockOrganization.id) {
        config.adapter = async () => ({
          data: { message: 'Organización no encontrada' },
          status: 404,
          statusText: 'Not Found',
          headers: {},
          config,
        });
        return config;
      }
      config.adapter = async () => ({
        data: mockOrganization,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // USUARIOS (HU14-16, contrato real)
    // ─────────────────────────────────────────
    // Mismo contrato que Paciente/Consultorio/Médico: colección anidada a la organización dueña
    // e ítem plano. Reglas propias (R11, R8) que el mock replica: nadie opera sobre su propia
    // cuenta (cambio de rol o baja) y la última cuenta ADMIN activa no puede perder ese rol ni
    // darse de baja.
    const coleccionDeUsuarios = url.match(/^\/organizaciones\/([^/]+)\/usuarios\/?$/);
    const usuarioPorId = url.match(/^\/usuarios\/([^/]+)\/?$/);
    const usuarioActivar = url.match(/^\/usuarios\/([^/]+)\/activar\/?$/);

    if (coleccionDeUsuarios && method === 'get') {
      await delay();
      const organizacionId = coleccionDeUsuarios[1];
      const deLaOrganizacion = usuarios
        .filter((u) => u.organizacionId === organizacionId)
        .sort((a, b) => a.email.localeCompare(b.email));
      config.adapter = async () => ({
        data: deLaOrganizacion,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (coleccionDeUsuarios && method === 'post') {
      await delay();
      const organizacionId = coleccionDeUsuarios[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const email = String(body.email || '').toLowerCase();

      if (body.rol === 'MEDICO' && !body.medicoId) {
        config.adapter = async () => ({
          data: { message: 'El rol MEDICO requiere un médico asociado' },
          status: 400,
          statusText: 'Bad Request',
          headers: {},
          config,
        });
        return config;
      }
      if (usuarios.some((u) => u.organizacionId === organizacionId && u.email === email)) {
        config.adapter = async () => ({
          data: { message: 'El correo ya está en uso dentro de la organización' },
          status: 409,
          statusText: 'Conflict',
          headers: {},
          config,
        });
        return config;
      }

      const nuevoUsuario: Usuario = {
        id: `usr-${Date.now()}`,
        activo: true,
        createdAt: new Date().toISOString(),
        ...body,
        email,
        organizacionId,
      };
      usuarios.push(nuevoUsuario);
      config.adapter = async () => ({
        data: nuevoUsuario,
        status: 201,
        statusText: 'Created',
        headers: { Location: `/api/v1/usuarios/${nuevoUsuario.id}` },
        config,
      });
      return config;
    }

    if (usuarioActivar && method === 'post') {
      await delay();
      const id = usuarioActivar[1];
      const idx = usuarios.findIndex((u) => u.id === id);
      if (idx !== -1) usuarios[idx] = { ...usuarios[idx], activo: true };

      config.adapter = async () => ({
        data: undefined,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      });
      return config;
    }

    if (usuarioPorId && method === 'get') {
      await delay();
      const id = usuarioPorId[1];
      const found = usuarios.find((u) => u.id === id);
      config.adapter = async () =>
        found
          ? { data: found, status: 200, statusText: 'OK', headers: {}, config }
          : {
              data: { message: 'Cuenta no encontrada' },
              status: 404,
              statusText: 'Not Found',
              headers: {},
              config,
            };
      return config;
    }

    if (usuarioPorId && method === 'put') {
      await delay();
      const id = usuarioPorId[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;

      config.adapter = async () => {
        const idx = usuarios.findIndex((u) => u.id === id);
        if (idx === -1) {
          return { data: { message: 'Cuenta no encontrada' }, status: 404, statusText: 'Not Found', headers: {}, config };
        }
        const actual = usuarios[idx];
        const esUnoMismo = subDeLaSesion(config) === actual.id;
        if (esUnoMismo && body.rol !== actual.rol) {
          return {
            data: { message: 'No puedes cambiarte el rol a ti mismo' },
            status: 403,
            statusText: 'Forbidden',
            headers: {},
            config,
          };
        }
        const dejariaSinAdmin =
          actual.rol === 'ADMIN' &&
          body.rol !== 'ADMIN' &&
          usuarios.filter((u) => u.organizacionId === actual.organizacionId && u.rol === 'ADMIN' && u.activo)
            .length <= 1;
        if (dejariaSinAdmin) {
          return {
            data: { message: 'La organización quedaría sin ninguna cuenta ADMIN activa' },
            status: 409,
            statusText: 'Conflict',
            headers: {},
            config,
          };
        }
        const email = String(body.email || actual.email).toLowerCase();
        if (usuarios.some((u) => u.id !== id && u.organizacionId === actual.organizacionId && u.email === email)) {
          return {
            data: { message: 'El correo ya está en uso dentro de la organización' },
            status: 409,
            statusText: 'Conflict',
            headers: {},
            config,
          };
        }
        usuarios[idx] = { ...actual, ...body, email, organizacionId: actual.organizacionId };
        return { data: usuarios[idx], status: 200, statusText: 'OK', headers: {}, config };
      };
      return config;
    }

    if (usuarioPorId && method === 'delete') {
      await delay();
      const id = usuarioPorId[1];

      config.adapter = async () => {
        const idx = usuarios.findIndex((u) => u.id === id);
        if (idx === -1) {
          return { data: { message: 'Cuenta no encontrada' }, status: 404, statusText: 'Not Found', headers: {}, config };
        }
        const actual = usuarios[idx];
        if (subDeLaSesion(config) === actual.id) {
          return {
            data: { message: 'No puedes darte de baja a ti mismo' },
            status: 403,
            statusText: 'Forbidden',
            headers: {},
            config,
          };
        }
        const esUltimaAdminActiva =
          actual.rol === 'ADMIN' &&
          actual.activo &&
          usuarios.filter((u) => u.organizacionId === actual.organizacionId && u.rol === 'ADMIN' && u.activo)
            .length <= 1;
        if (esUltimaAdminActiva) {
          return {
            data: { message: 'Es la última cuenta ADMIN activa de la organización' },
            status: 409,
            statusText: 'Conflict',
            headers: {},
            config,
          };
        }
        // Baja lógica idempotente: igual que el backend real, repetirla sigue devolviendo 204.
        usuarios[idx] = { ...actual, activo: false };
        return { data: undefined, status: 204, statusText: 'No Content', headers: {}, config };
      };
      return config;
    }

    // DIRECTORIO PÚBLICO DE CLÍNICA
    // ─────────────────────────────────────────
    // Sin contrato real: ningún GET de organizaciones o médicos es público en el backend (toda
    // la cadena de filtros exige un token válido, igual que /organizaciones). El prefijo
    // /publico/ distingue estas rutas de sus equivalentes reales autenticadas; no existen en el
    // backend y no deben confundirse con la colección de /organizaciones/{id}/medicos.
    const clinicaPublica = url.match(/^\/publico\/clinicas\/([^/]+)\/?$/);
    const medicosPublicos = url.match(/^\/publico\/clinicas\/([^/]+)\/medicos\/?$/);

    if (clinicaPublica && method === 'get') {
      await delay();
      const id = clinicaPublica[1];
      const organizacion = id === mockOrganization.id ? mockOrganization : null;
      config.adapter = async () =>
        organizacion
          ? { data: organizacion, status: 200, statusText: 'OK', headers: {}, config }
          : {
              data: { mensaje: 'Clínica no encontrada' },
              status: 404,
              statusText: 'Not Found',
              headers: {},
              config,
            };
      return config;
    }

    if (medicosPublicos && method === 'get') {
      await delay();
      const id = medicosPublicos[1];
      const medicos = mockDoctors
        .filter((m) => m.organizacionId === id && m.activo)
        .map((m) => ({
          ...m,
          horarios: mockDoctorSchedules.filter((h) => h.medicoId === m.id),
          consultorio: mockClinicRooms.find((c) => c.id === m.consultorioId)?.nombre,
        }));
      config.adapter = async () => ({
        data: medicos,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // MARCA DE LA CLÍNICA (personalización, panel de administración)
    // ─────────────────────────────────────────
    // Sin contrato real: `configuracion_tenant` no tiene columnas de marca y ningún GET/PUT de
    // organizaciones es público ni acepta estos campos todavía. Ruta exclusiva del mock.
    const marcaDeOrganizacion = url.match(/^\/organizaciones\/([^/]+)\/marca\/?$/);

    if (marcaDeOrganizacion && method === 'get') {
      await delay();
      const id = marcaDeOrganizacion[1];
      const marca = marcasPorOrganizacion.get(id) || {
        organizacion_id: id,
        color_primario: '#0A4D68',
        fuente_titulos: 'Plus Jakarta Sans',
      };
      config.adapter = async () => ({
        data: marca,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (marcaDeOrganizacion && method === 'put') {
      await delay();
      const id = marcaDeOrganizacion[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const actualizada: MarcaClinica = {
        organizacion_id: id,
        color_primario: body.color_primario,
        fuente_titulos: body.fuente_titulos,
      };
      marcasPorOrganizacion.set(id, actualizada);
      config.adapter = async () => ({
        data: actualizada,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // ONBOARDING · REGISTRO PÚBLICO DE CLÍNICA
    // ─────────────────────────────────────────
    // Sin contrato real: `POST /api/v1/organizaciones` del backend está bloqueado a propósito
    // (exige un token ya autenticado y aun así responde 403 hasta el onboarding de Sprint 2 —
    // ver `OrganizacionController`). Esta ruta es exclusiva del mock para poder dibujar la
    // pantalla; no existe en el backend real y nunca debe confundirse con la colección.
    if (url === '/onboarding/registro-clinica' && method === 'post') {
      await delay(400);
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      config.adapter = async () => ({
        data: {
          organizacionId: `org-${Date.now()}`,
          nombreClinica: body.nombreClinica,
        },
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // PACIENTES
    // ─────────────────────────────────────────
    // Las rutas replican el contrato del backend: la colección cuelga de la organización dueña y
    // el ítem es plano. La comparación es exacta a propósito: el mock no puede ser más permisivo
    // que el backend, o el desarrollo local diría que una pantalla funciona cuando responde 404.
    const coleccionDePacientes = url.match(/^\/organizaciones\/([^/]+)\/pacientes\/?$/);
    const pacientePorId = url.match(/^\/pacientes\/([^/]+)\/?$/);

    if (coleccionDePacientes && method === 'get') {
      await delay();
      config.adapter = async () => ({
        // Copia defensiva: nunca devolver el array mutable por referencia, o un POST/PUT
        // posterior (que muta el store vía unshift/índice) duplicaría registros en el estado
        // de React que ya lo recibió.
        data: [...patients],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (coleccionDePacientes && method === 'post') {
      await delay();
      // La organización dueña llega por el path: el cuerpo no puede cambiar de tenant.
      const organizacionId = coleccionDePacientes[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newPatientId = `pac-${Date.now()}`;
      const newPatient: PacienteConFicha = {
        id: newPatientId,
        activo: true,
        createdAt: new Date().toISOString(),
        ...body,
        // El dueño lo fija el path: si el cuerpo trae otro, no puede cambiarlo.
        organizacionId: organizacionId,
        ficha: body.ficha
          ? { id: `ficha-${Date.now()}`, paciente_id: newPatientId, ...body.ficha }
          : undefined,
      };
      patients.unshift(newPatient);
      config.adapter = async () => ({
        data: newPatient,
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    // Actualizar paciente existente (datos básicos + ficha NOM-004): solo la ruta plana del ítem.
    if (pacientePorId && method === 'put') {
      await delay();
      const id = pacientePorId[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const idx = patients.findIndex((p) => p.id === id);

      if (idx !== -1) {
        const current = patients[idx];
        const { ficha: fichaBody, ...patientBody } = body as { ficha?: Partial<PatientIdentification> } & Partial<Paciente>;
        patients[idx] = {
          ...current,
          ...patientBody,
          ficha: fichaBody
            ? { id: current.ficha?.id || `ficha-${Date.now()}`, paciente_id: id, ...current.ficha, ...fichaBody }
            : current.ficha,
        };
      }

      config.adapter = async () => ({
        data: patients[idx],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // MEDICOS (listado plano, sin contrato real: la Agenda lo consume así porque Citas no tiene
    // backend todavía)
    // ─────────────────────────────────────────
    // Comparación exacta: las rutas anidadas de Médicos y Horarios usan patrones distintos más
    // abajo y no deben quedar atrapadas por un `.includes('/medicos')` ni colisionar con él.
    if (url === '/medicos' && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: [...medicos],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // MEDICOS (HU3-5, contrato real)
    // ─────────────────────────────────────────
    // Mismo contrato que Consultorios: colección anidada a la organización dueña
    // (`/organizaciones/{id}/medicos`) e ítem plano (`/medicos/{id}`). El consultorio del médico
    // viaja en el cuerpo (no es el dueño), pero el PUT real rechaza con 400 un cambio de
    // consultorio (MedicoService.actualizar) — el mock reproduce esa misma regla.
    const coleccionDeMedicos = url.match(/^\/organizaciones\/([^/]+)\/medicos\/?$/);
    const medicoPorId = url.match(/^\/medicos\/([^/]+)\/?$/);

    if (coleccionDeMedicos && method === 'get') {
      await delay();
      const organizacionId = coleccionDeMedicos[1];
      const activos = medicos
        .filter((m) => m.organizacionId === organizacionId && m.activo)
        .sort((a, b) => a.nombre.localeCompare(b.nombre));
      config.adapter = async () => ({
        data: activos,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (coleccionDeMedicos && method === 'post') {
      await delay();
      const organizacionId = coleccionDeMedicos[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newMedico: MedicoMock = {
        id: `med-${Date.now()}`,
        activo: true,
        ...body,
        // El dueño lo fija el path: si el cuerpo trae otro, no puede cambiarlo.
        organizacionId,
      };
      medicos.unshift(newMedico);
      config.adapter = async () => ({
        data: newMedico,
        status: 201,
        statusText: 'Created',
        headers: { Location: `/api/v1/medicos/${newMedico.id}` },
        config,
      });
      return config;
    }

    if (medicoPorId && method === 'get') {
      await delay();
      const id = medicoPorId[1];
      const found = medicos.find((m) => m.id === id);
      if (!found) {
        config.adapter = async () => ({
          data: { message: 'Médico no encontrado' },
          status: 404,
          statusText: 'Not Found',
          headers: {},
          config,
        });
        return config;
      }
      config.adapter = async () => ({
        data: found,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (medicoPorId && method === 'put') {
      await delay();
      const id = medicoPorId[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const idx = medicos.findIndex((m) => m.id === id);

      if (idx !== -1 && body.consultorioId !== medicos[idx].consultorioId) {
        config.adapter = async () => ({
          data: { message: 'El consultorio no se modifica mediante la actualización' },
          status: 400,
          statusText: 'Bad Request',
          headers: {},
          config,
        });
        return config;
      }

      if (idx !== -1) {
        // El ítem es plano: la organización dueña no se envía ni se puede cambiar.
        const editable = body as Partial<MedicoMock>;
        medicos[idx] = { ...medicos[idx], ...editable, organizacionId: medicos[idx].organizacionId };
      }

      config.adapter = async () => ({
        data: medicos[idx],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (medicoPorId && method === 'delete') {
      await delay();
      const id = medicoPorId[1];
      const idx = medicos.findIndex((m) => m.id === id);
      // Baja lógica idempotente: igual que el backend real, repetir la baja sigue devolviendo 204.
      if (idx !== -1) medicos[idx] = { ...medicos[idx], activo: false };

      config.adapter = async () => ({
        data: undefined,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // HORARIOS DE MEDICO (HU3-5, contrato real)
    // ─────────────────────────────────────────
    // Colección anidada al médico dueño de la agenda (`/medicos/{medicoId}/horarios`) e ítem plano
    // (`/horarios/{id}`). El backend real valida también rango horario y solapamiento entre
    // bloques del mismo médico; el mock simplifica a las dos validaciones expresables sin
    // consultar el resto de la agenda (rango y duración), documentado aquí para no prometer una
    // paridad que no tiene.
    const coleccionDeHorarios = url.match(/^\/medicos\/([^/]+)\/horarios\/?$/);
    const horarioPorId = url.match(/^\/horarios\/([^/]+)\/?$/);
    const DURACIONES_VALIDAS = new Set([20, 30, 45, 60]);

    if (coleccionDeHorarios && method === 'get') {
      await delay();
      const medicoId = coleccionDeHorarios[1];
      const delMedico = horarios
        .filter((h) => h.medicoId === medicoId)
        .sort((a, b) => a.diaSemana - b.diaSemana || a.horaInicio.localeCompare(b.horaInicio));
      config.adapter = async () => ({
        data: delMedico,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (coleccionDeHorarios && method === 'post') {
      await delay();
      const medicoId = coleccionDeHorarios[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;

      if (body.horaInicio >= body.horaFin || !DURACIONES_VALIDAS.has(body.duracionConsulta)) {
        config.adapter = async () => ({
          data: { message: 'Rango horario inválido o duración no permitida' },
          status: 400,
          statusText: 'Bad Request',
          headers: {},
          config,
        });
        return config;
      }

      const newHorario: HorarioMedico = {
        id: `horario-${Date.now()}`,
        ...body,
        medicoId,
      };
      horarios.unshift(newHorario);
      config.adapter = async () => ({
        data: newHorario,
        status: 201,
        statusText: 'Created',
        headers: { Location: `/api/v1/horarios/${newHorario.id}` },
        config,
      });
      return config;
    }

    if (horarioPorId && method === 'put') {
      await delay();
      const id = horarioPorId[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const idx = horarios.findIndex((h) => h.id === id);

      if (body.horaInicio >= body.horaFin || !DURACIONES_VALIDAS.has(body.duracionConsulta)) {
        config.adapter = async () => ({
          data: { message: 'Rango horario inválido o duración no permitida' },
          status: 400,
          statusText: 'Bad Request',
          headers: {},
          config,
        });
        return config;
      }

      if (idx !== -1) {
        // El médico dueño de la agenda no se envía ni se puede cambiar.
        const editable = body as Partial<HorarioMedico>;
        horarios[idx] = { ...horarios[idx], ...editable, medicoId: horarios[idx].medicoId };
      }

      config.adapter = async () => ({
        data: horarios[idx],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (horarioPorId && method === 'delete') {
      await delay();
      const id = horarioPorId[1];
      const idx = horarios.findIndex((h) => h.id === id);
      if (idx === -1) {
        // Borrado físico y no idempotente: repetirlo sobre un bloque ya eliminado es 404.
        config.adapter = async () => ({
          data: { message: 'Bloque de disponibilidad no encontrado' },
          status: 404,
          statusText: 'Not Found',
          headers: {},
          config,
        });
        return config;
      }
      horarios.splice(idx, 1);

      config.adapter = async () => ({
        data: undefined,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // CONSULTORIOS (HU3-5)
    // ─────────────────────────────────────────
    // Mismo contrato real que Pacientes: colección anidada a la organización dueña
    // (`/organizaciones/{id}/consultorios`) e ítem plano (`/consultorios/{id}`). La comparación es
    // exacta para no colisionar con el listado plano `/consultorios` que todavía consume la Agenda
    // (sin contrato real de Citas) ni con la ruta anidada.
    if (url === '/consultorios' && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: [...consultorios],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    const coleccionDeConsultorios = url.match(/^\/organizaciones\/([^/]+)\/consultorios\/?$/);
    const consultorioPorId = url.match(/^\/consultorios\/([^/]+)\/?$/);

    if (coleccionDeConsultorios && method === 'get') {
      await delay();
      const organizacionId = coleccionDeConsultorios[1];
      const activos = consultorios
        .filter((c) => c.organizacionId === organizacionId && c.activo)
        .sort((a, b) => a.nombre.localeCompare(b.nombre));
      config.adapter = async () => ({
        data: activos,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (coleccionDeConsultorios && method === 'post') {
      await delay();
      const organizacionId = coleccionDeConsultorios[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newConsultorio: Consultorio = {
        id: `room-${Date.now()}`,
        activo: true,
        createdAt: new Date().toISOString(),
        ...body,
        // El dueño lo fija el path: si el cuerpo trae otro, no puede cambiarlo.
        organizacionId,
      };
      consultorios.unshift(newConsultorio);
      config.adapter = async () => ({
        data: newConsultorio,
        status: 201,
        statusText: 'Created',
        headers: { Location: `/api/v1/consultorios/${newConsultorio.id}` },
        config,
      });
      return config;
    }

    if (consultorioPorId && method === 'get') {
      await delay();
      const id = consultorioPorId[1];
      const found = consultorios.find((c) => c.id === id);
      if (!found) {
        config.adapter = async () => ({
          data: { message: 'Consultorio no encontrado' },
          status: 404,
          statusText: 'Not Found',
          headers: {},
          config,
        });
        return config;
      }
      config.adapter = async () => ({
        data: found,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (consultorioPorId && method === 'put') {
      await delay();
      const id = consultorioPorId[1];
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const idx = consultorios.findIndex((c) => c.id === id);

      if (idx !== -1) {
        // El ítem es plano: la organización dueña no se envía ni se puede cambiar.
        const editable = body as Partial<Consultorio>;
        consultorios[idx] = { ...consultorios[idx], ...editable, organizacionId: consultorios[idx].organizacionId };
      }

      config.adapter = async () => ({
        data: consultorios[idx],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (consultorioPorId && method === 'delete') {
      await delay();
      const id = consultorioPorId[1];
      const idx = consultorios.findIndex((c) => c.id === id);
      // Baja lógica idempotente: igual que el backend real, repetir la baja sigue devolviendo 204.
      if (idx !== -1) consultorios[idx] = { ...consultorios[idx], activo: false };

      config.adapter = async () => ({
        data: undefined,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // CITAS & AGENDA
    // ─────────────────────────────────────────
    if (url.includes('/citas') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: [...appointments],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/citas') && method === 'post') {
      await delay();
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const paciente = patients.find((p) => p.id === body.paciente_id) || patients[0];
      const medico = medicos.find((m) => m.id === body.medico_id) || medicos[0];
      const consultorio = consultorios.find((c) => c.id === body.consultorio_id) || consultorios[0];

      const newApt: Cita = {
        id: `apt-${Date.now()}`,
        organizacion_id: 'org-001',
        paciente_id: body.paciente_id,
        medico_id: body.medico_id,
        consultorio_id: body.consultorio_id,
        fecha_hora: body.fecha_hora,
        duracion_min: body.duracion_min || medico.duracionConsultaMin || 30,
        estado: 'SIN_CONFIRMAR',
        motivo: body.motivo,
        created_at: new Date().toISOString(),
        paciente,
        medico,
        consultorio,
      };

      appointments.unshift(newApt);
      config.adapter = async () => ({
        data: newApt,
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    // Cambiar estado de cita (/citas/:id/estado)
    if (url.includes('/citas/') && url.includes('/estado') && (method === 'patch' || method === 'put')) {
      await delay();
      const match = url.match(/\/citas\/([^/]+)\/estado/);
      const id = match ? match[1] : '';
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newStatus = body.estado as EstadoCita;

      appointments = appointments.map((apt) =>
        apt.id === id
          ? {
              ...apt,
              estado: newStatus,
              cancelada_por: newStatus === 'CANCELADA' ? body.cancelada_por || 'ADMIN' : undefined,
              cancelada_en: newStatus === 'CANCELADA' ? new Date().toISOString() : undefined,
            }
          : apt
      );

      const updated = appointments.find((a) => a.id === id);
      config.adapter = async () => ({
        data: updated,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // COBROS & RESUMEN
    // ─────────────────────────────────────────
    if (url.includes('/cobros') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: mockPayments,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // EXPEDIENTE CLINICO & SOAP (sin contrato real: NOM-004/NOM-024, declarado fuera de alcance de
    // V1 — ver `futuro.types.ts`. Las rutas filtran por `paciente_id` en query string porque la
    // pantalla siempre entra desde un paciente elegido, nunca lista notas de toda la clínica.)
    // ─────────────────────────────────────────
    if (
      url.includes('/expediente/notas') &&
      method === 'get' &&
      !url.includes('/cerrar') &&
      !url.includes('/correccion')
    ) {
      await delay();
      const pacienteId = new URLSearchParams(url.split('?')[1] || '').get('paciente_id');
      const filtradas = pacienteId ? soapNotes.filter((n) => n.paciente_id === pacienteId) : soapNotes;
      config.adapter = async () => ({
        data: [...filtradas],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/expediente/notas') && method === 'post' && !url.includes('/cerrar') && !url.includes('/correccion')) {
      await delay();
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newNote: SoapNote = {
        id: `note-${Date.now()}`,
        organizacion_id: 'org-001',
        paciente_id: body.paciente_id,
        medico_id: body.medico_id || 'med-001',
        cita_id: body.cita_id,
        subjetivo: body.subjetivo,
        objetivo: body.objetivo,
        signos_vitales: body.signos_vitales,
        analisis: body.analisis,
        plan: body.plan,
        inmutable: false,
        created_at: new Date().toISOString(),
      };
      soapNotes.unshift(newNote);
      config.adapter = async () => ({
        data: newNote,
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    // Cerrar nota SOAP (hacer inmutable según NOM-004)
    if (url.includes('/expediente/notas/') && url.includes('/cerrar') && method === 'post') {
      await delay();
      const match = url.match(/\/expediente\/notas\/([^/]+)\/cerrar/);
      const noteId = match ? match[1] : '';

      soapNotes = soapNotes.map((n) => (n.id === noteId ? { ...n, inmutable: true } : n));
      const closedNote = soapNotes.find((n) => n.id === noteId);

      config.adapter = async () => ({
        data: closedNote,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // Agregar corrección a nota cerrada (NOM-004)
    if (url.includes('/expediente/notas/') && url.includes('/correccion') && method === 'post') {
      await delay();
      const match = url.match(/\/expediente\/notas\/([^/]+)\/correccion/);
      const noteId = match ? match[1] : '';
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;

      soapNotes = soapNotes.map((n) => {
        if (n.id === noteId) {
          const correcciones = n.correcciones || [];
          return {
            ...n,
            correcciones: [
              ...correcciones,
              {
                id: `corr-${Date.now()}`,
                nota_evolucion_id: noteId,
                medico_id: body.medico_id || 'med-001',
                texto: body.texto,
                created_at: new Date().toISOString(),
              },
            ],
          };
        }
        return n;
      });

      const updated = soapNotes.find((n) => n.id === noteId);
      config.adapter = async () => ({
        data: updated,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // Búsqueda en catálogo CIE-10
    if (url.includes('/expediente/cie10/buscar') && method === 'get') {
      await delay(100);
      const searchParams = new URLSearchParams(url.split('?')[1] || '');
      const query = (searchParams.get('q') || '').toLowerCase();
      const results = mockCie10Catalog.filter(
        (item) => item.codigo.toLowerCase().includes(query) || item.descripcion.toLowerCase().includes(query)
      );

      config.adapter = async () => ({
        data: results,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // DIAGNÓSTICOS (sin contrato real, mismo alcance que Notas SOAP)
    // ─────────────────────────────────────────
    if (url.includes('/expediente/diagnosticos') && method === 'get') {
      await delay();
      const pacienteId = new URLSearchParams(url.split('?')[1] || '').get('paciente_id');
      const filtrados = pacienteId
        ? diagnosticos.filter((d) => d.paciente_id === pacienteId)
        : diagnosticos;
      config.adapter = async () => ({
        data: [...filtrados],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/expediente/diagnosticos') && method === 'post') {
      await delay();
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newDiagnostico: Diagnostico = {
        id: `diag-${Date.now()}`,
        estado: 'ACTIVO',
        ...body,
        created_at: new Date().toISOString(),
      };
      diagnosticos.unshift(newDiagnostico);
      config.adapter = async () => ({
        data: newDiagnostico,
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    // ─────────────────────────────────────────
    // PRESCRIPCIONES (sin contrato real, mismo alcance que Notas SOAP)
    // ─────────────────────────────────────────
    if (
      url.includes('/expediente/prescripciones') &&
      method === 'get' &&
      !url.includes('/desactivar')
    ) {
      await delay();
      const pacienteId = new URLSearchParams(url.split('?')[1] || '').get('paciente_id');
      const filtradas = pacienteId
        ? prescripciones.filter((p) => p.paciente_id === pacienteId)
        : prescripciones;
      config.adapter = async () => ({
        data: [...filtradas],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (
      url.includes('/expediente/prescripciones') &&
      method === 'post' &&
      !url.includes('/desactivar')
    ) {
      await delay();
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newPrescripcion: Prescription = {
        id: `rx-${Date.now()}`,
        activo: true,
        ...body,
        created_at: new Date().toISOString(),
      };
      prescripciones.unshift(newPrescripcion);
      config.adapter = async () => ({
        data: newPrescripcion,
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/expediente/prescripciones/') && url.includes('/desactivar') && method === 'post') {
      await delay();
      const match = url.match(/\/expediente\/prescripciones\/([^/]+)\/desactivar/);
      const id = match ? match[1] : '';
      const idx = prescripciones.findIndex((p) => p.id === id);
      if (idx !== -1) prescripciones[idx] = { ...prescripciones[idx], activo: false };

      config.adapter = async () => ({
        data: prescripciones[idx],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    return config;
  });
}
