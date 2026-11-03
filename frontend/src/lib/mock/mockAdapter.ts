import type { AxiosInstance } from 'axios';
import {
  mockUsers,
  mockPatients,
  mockDoctors,
  mockClinicRooms,
  mockAppointments,
  mockPayments,
  mockSoapNotes,
  mockCie10Catalog,
  mockDiagnoses,
  mockPrescriptions,
} from './mockData';
import type { Cita, EstadoCita } from '@/features/citas/citas.types';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import type { PatientIdentification, SoapNote } from '@/types/futuro.types';

type PacienteConFicha = Paciente & { ficha?: PatientIdentification };

/**
 * Decisión única de activación de mocks: **fail-closed** (R-SEG-07, plan §2 guardarraíl 3).
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

  // Mutable in-memory store for mocks
  const patients: PacienteConFicha[] = [...mockPatients];
  let appointments = [...mockAppointments];
  let soapNotes = [...mockSoapNotes];

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
      const user = mockUsers.find((u) => u.email === data.email) || mockUsers[0];

      // Simulamos token JWT RS256 codificando la carga útil.
      // Vigencia alineada con ADR-012: **30 minutos**, los mismos que la ventana de inactividad.
      // Un token de 8 h volvía decorativo el cierre por inactividad.
      const iat = Math.floor(Date.now() / 1000);
      const fakePayload = {
        sub: user.id,
        email: user.email,
        role: user.rol,
        tenantId: user.organizacion_id,
        medicoId: user.medico_id,
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

    // ─────────────────────────────────────────
    // PACIENTES
    // ─────────────────────────────────────────
    if (url.includes('/pacientes') && method === 'get') {
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

    if (url.includes('/pacientes') && method === 'post') {
      await delay();
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
      const newPatientId = `pac-${Date.now()}`;
      const newPatient: PacienteConFicha = {
        id: newPatientId,
        organizacion_id: 'org-001',
        activo: true,
        created_at: new Date().toISOString(),
        ...body,
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

    // Actualizar paciente existente (datos básicos + ficha NOM-004)
    if (url.includes('/pacientes/') && (method === 'put' || method === 'patch')) {
      await delay();
      const match = url.match(/\/pacientes\/([^/]+)/);
      const id = match ? match[1] : '';
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
    // MEDICOS Y CONSULTORIOS
    // ─────────────────────────────────────────
    if (url.includes('/medicos') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: mockDoctors,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/consultorios') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: mockClinicRooms,
        status: 200,
        statusText: 'OK',
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
      const medico = mockDoctors.find((m) => m.id === body.medico_id) || mockDoctors[0];
      const consultorio = mockClinicRooms.find((c) => c.id === body.consultorio_id) || mockClinicRooms[0];

      const newApt: Cita = {
        id: `apt-${Date.now()}`,
        organizacion_id: 'org-001',
        paciente_id: body.paciente_id,
        medico_id: body.medico_id,
        consultorio_id: body.consultorio_id,
        fecha_hora: body.fecha_hora,
        duracion_min: body.duracion_min || medico.duracion_consulta_min || 30,
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
    // EXPEDIENTE CLINICO & SOAP
    // ─────────────────────────────────────────
    if (url.includes('/expediente/notas') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: [...soapNotes],
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

    // Diagnósticos y Prescripciones
    if (url.includes('/expediente/diagnosticos') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: mockDiagnoses,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      });
      return config;
    }

    if (url.includes('/expediente/prescripciones') && method === 'get') {
      await delay();
      config.adapter = async () => ({
        data: mockPrescriptions,
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
