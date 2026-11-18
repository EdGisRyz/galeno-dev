import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MedicosPage } from '@/features/medicos/MedicosPage';
import type { HorarioMedico, Medico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Pantalla de Médicos y su panel de horarios.
 *
 * Se comprueba por la interfaz pública: textos, roles y las llamadas al cliente HTTP con la URL
 * exacta. Hay dos reglas propias que se cubren aparte:
 *
 * - El backend permite a MEDICO gestionar **su** agenda y le responde 403 con la de otro; la
 *   pantalla replica esa regla con el claim `medicoId`.
 * - El borrado de un bloque es físico (la tabla no tiene baja lógica), así que la confirmación
 *   es la única barrera: el `DELETE` no puede salir antes de aceptarla.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);
const postMock = vi.mocked(api.post);
const putMock = vi.mocked(api.put);
const deleteMock = vi.mocked(api.delete);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios`;

const CONSULTORIO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio de prueba',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const MEDICO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: CONSULTORIO.id,
  nombre: 'Médico de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: '5551234567',
  tarifaBase: 500,
  activo: true,
};

const HORARIO: HorarioMedico = {
  id: 'hor-0001',
  medicoId: MEDICO.id,
  consultorioId: CONSULTORIO.id,
  consultorioNombre: CONSULTORIO.nombre,
  diaSemana: 1,
  horaInicio: '09:00:00',
  horaFin: '13:00:00',
  duracionConsulta: 30,
};

/**
 * Médico tal como lo guardó la base antes de existir una regla de formato: es el caso de la
 * captura (nombre `52454255`, cédula `ccwcwcc`, teléfono `vvvvvuvu`). S8 exige que se siga
 * pudiendo leer, y S7 que no se pueda volver a guardar sin corregirlo.
 */
const MEDICO_CON_DATOS_NO_CONFORMES: Medico = {
  ...MEDICO,
  nombre: '52454255',
  especialidad: '2334',
  cedula: 'ccwcwcc',
  telefono: 'vvvvvuvu',
};

const RUTA_HORARIOS = `/medicos/${MEDICO.id}/horarios`;
const RUTA_CONSULTORIOS_ASIGNADOS = `/medicos/${MEDICO.id}/consultorios`;

/** Deja listas las cuatro lecturas de la pantalla: médicos, consultorios, asignados y una agenda. */
function cargarCon(horarios: HorarioMedico[] = [], medicos: Medico[] = [MEDICO]) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon(medicos));
    if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
    if (url === RUTA_CONSULTORIOS_ASIGNADOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
    if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon(horarios));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });
}

function abrirSesion(role: 'ADMIN' | 'MEDICO' | 'RECEPCIONISTA', medicoId: string | null = null) {
  useAuthStore.setState({
    accessToken: null,
    user: {
      id: 'usr-0001',
      email: 'prueba@correo.mx',
      role,
      tenantId: ORGANIZACION_ID,
      medicoId,
    },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
}

/** Fila de la tabla donde aparece el nombre del médico. */
function filaDe(medico: Medico): HTMLElement {
  const fila = screen.getByText(medico.nombre).closest('tr');
  if (!fila) throw new Error(`El médico ${medico.nombre} no está en la tabla`);
  return fila;
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
  abrirSesion('ADMIN');
  // Los fallos se registran en consola a propósito (resumen sin secretos): se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · listado', () => {
  it('muestra los médicos y el nombre de su consultorio', async () => {
    cargarCon();

    render(<MedicosPage />);

    expect(await screen.findByText('Médico de prueba')).toBeInTheDocument();
    expect(screen.getByText('Consultorio de prueba')).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledWith(RUTA_MEDICOS);
  });
});

describe('MedicosPage · alta', () => {
  it('registra en la colección de la organización con el contrato del backend', async () => {
    cargarCon();
    postMock.mockResolvedValue(respuestaCon({ ...MEDICO, id: 'med-0002', nombre: 'Médico nuevo' }));

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/nombre/i), { target: { value: 'Médico nuevo' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_MEDICOS, {
        consultorioId: CONSULTORIO.id,
        nombre: 'Médico nuevo',
        especialidad: null,
        cedula: null,
        telefono: null,
        tarifaBase: null,
      }),
    );
  });
});

describe('MedicosPage · estados de carga', () => {
  it('distingue un fallo de carga y permite reintentar', async () => {
    getMock
      .mockRejectedValueOnce(new Error('sin conexión'))
      .mockImplementation((url: string) => {
        if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
        if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
        return Promise.resolve(respuestaCon([]));
      });

    render(<MedicosPage />);

    expect(await screen.findByText(/no se pudieron cargar los médicos/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /reintentar/i }));

    expect(await screen.findByText('Médico de prueba')).toBeInTheDocument();
  });
});

describe('MedicosPage · reglas de formato del médico', () => {
  it('no envía el alta cuando el nombre trae dígitos', async () => {
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/^nombre/i), { target: { value: '52454255' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    // El error aparece junto al campo, sin esperar la respuesta del servicio (S7).
    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía la edición de un médico con cédula con letras hasta corregirla', async () => {
    cargarCon([], [MEDICO_CON_DATOS_NO_CONFORMES]);

    render(<MedicosPage />);
    expect(await screen.findByText('52454255')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('Editar médico'));
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    expect(await screen.findByText(/entre 5 y 10 dígitos/i)).toBeInTheDocument();
    expect(putMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando el teléfono trae letras', async () => {
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/^nombre/i), { target: { value: 'Médico de prueba' } });
    fireEvent.change(screen.getByLabelText(/teléfono/i), { target: { value: 'vvvvvuvu' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    expect(await screen.findByText(/entre 7 y 20 caracteres/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('envía lo escrito cuando los datos cumplen las reglas', async () => {
    cargarCon();
    postMock.mockResolvedValue(respuestaCon({ ...MEDICO, id: 'med-0002', nombre: 'Dra. Elena Ruiz' }));

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/^nombre/i), { target: { value: 'Dra. Elena Ruiz' } });
    fireEvent.change(screen.getByLabelText(/especialidad/i), {
      target: { value: 'Medicina general' },
    });
    fireEvent.change(screen.getByLabelText(/cédula/i), { target: { value: '1234567' } });
    fireEvent.change(screen.getByLabelText(/teléfono/i), { target: { value: '9511111111' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_MEDICOS, {
        consultorioId: CONSULTORIO.id,
        nombre: 'Dra. Elena Ruiz',
        especialidad: 'Medicina general',
        cedula: '1234567',
        telefono: '9511111111',
        tarifaBase: null,
      }),
    );
  });

  it('sigue mostrando en el directorio un médico con datos no conformes', async () => {
    // S8: la regla nueva impide guardar, no leer. Una ficha ya guardada antes de la regla se
    // sigue viendo, con su cédula tal cual, para que el administrador pueda corregirla.
    cargarCon([], [MEDICO_CON_DATOS_NO_CONFORMES]);

    render(<MedicosPage />);

    expect(await screen.findByText('52454255')).toBeInTheDocument();
    expect(screen.getByText('ccwcwcc')).toBeInTheDocument();
    expect(screen.getByText('vvvvvuvu')).toBeInTheDocument();
  });

  it('muestra el mensaje del backend cuando el servicio rechaza el formato', async () => {
    // Si el backend rechaza por una regla que la pantalla no conoce, su mensaje es el que se
    // muestra: la validación local no lo reemplaza por un texto genérico.
    cargarCon();
    postMock.mockRejectedValueOnce({ response: { data: { mensaje: 'El campo cedula es inválido' } } });

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/^nombre/i), { target: { value: 'Médico de prueba' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    expect(await screen.findByText('El campo cedula es inválido')).toBeInTheDocument();
  });
});

describe('MedicosPage · horarios según el rol', () => {
  it('deja que un MEDICO gestione la agenda propia', async () => {
    abrirSesion('MEDICO', MEDICO.id);
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));

    expect(await screen.findByRole('button', { name: /agregar bloque/i })).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledWith(RUTA_HORARIOS);
  });

  it('no ofrece la agenda de otro médico: el botón no aparece en esa fila (S4)', async () => {
    // Antes el botón estaba en todas las filas y abrirlo terminaba en un 403 del backend que la
    // pantalla contaba como fallo de red. Donde la agenda no es accesible no hay nada que ofrecer.
    abrirSesion('MEDICO', 'med-ajeno');
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    expect(screen.queryByRole('button', { name: /horarios/i })).not.toBeInTheDocument();
    expect(getMock).not.toHaveBeenCalledWith(RUTA_HORARIOS);
  });

  it('muestra el botón en la fila del propio médico aunque haya otros en el directorio (S4)', async () => {
    const OTRO_MEDICO: Medico = {
      ...MEDICO,
      id: 'med-0002',
      nombre: 'Colega de prueba',
    };
    abrirSesion('MEDICO', MEDICO.id);
    cargarCon([], [MEDICO, OTRO_MEDICO]);

    render(<MedicosPage />);
    await screen.findByText('Colega de prueba');

    // Una sola fila ofrece la agenda: la del médico de la sesión.
    const botones = screen.getAllByRole('button', { name: /horarios/i });
    expect(botones).toHaveLength(1);
    expect(within(filaDe(MEDICO)).getByRole('button', { name: /horarios/i })).toBe(botones[0]);
  });

  it('la administración ve la agenda de todas las filas (S4)', async () => {
    const OTRO_MEDICO: Medico = {
      ...MEDICO,
      id: 'med-0002',
      nombre: 'Colega de prueba',
    };
    abrirSesion('ADMIN');
    cargarCon([], [MEDICO, OTRO_MEDICO]);

    render(<MedicosPage />);
    await screen.findByText('Colega de prueba');

    expect(screen.getAllByRole('button', { name: /horarios/i })).toHaveLength(2);
    expect(within(filaDe(MEDICO)).getByRole('button', { name: /horarios/i })).toBeInTheDocument();
    expect(within(filaDe(OTRO_MEDICO)).getByRole('button', { name: /horarios/i })).toBeInTheDocument();
  });

  it('recepción sigue consultando la agenda de cualquier médico (S4)', async () => {
    // El backend le concede la lectura: recepción necesita responder dónde y cuándo atiende cada
    // médico. Esconder el botón donde la lectura está permitida sería quitarle una capacidad.
    abrirSesion('RECEPCIONISTA');
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));

    expect(await screen.findByText(/horarios de médico de prueba/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /agregar bloque/i })).not.toBeInTheDocument();
  });

  it('muestra el mensaje del backend cuando el rechazo de la agenda no es de red (S4)', async () => {
    abrirSesion('MEDICO', MEDICO.id);
    getMock.mockImplementation((url: string) => {
      if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
      if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
      if (url === RUTA_CONSULTORIOS_ASIGNADOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
      if (url === RUTA_HORARIOS) {
        return Promise.reject({
          response: { status: 403, data: { mensaje: 'No puedes consultar la agenda de otro médico' } },
        });
      }
      return Promise.reject(new Error(`URL inesperada: ${url}`));
    });

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));

    // Un rechazo de permisos no se cuenta como fallo de conexión: se muestra lo que dijo el
    // backend, que es lo único que explica el motivo.
    expect(
      await screen.findByText('No puedes consultar la agenda de otro médico'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/revisa la conexión con el servicio/i)).not.toBeInTheDocument();
  });
});

describe('MedicosPage · borrado de un bloque', () => {
  it('abre la confirmación en una capa superior al panel de horarios', async () => {
    // El diálogo se abre desde dentro del panel. Con la misma capa, el panel —que aparece
    // después en el documento— lo tapaba: se veía el fondo oscurecido pero no el aviso.
    cargarCon([HORARIO]);

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
    fireEvent.click(await screen.findByTitle('Eliminar bloque'));

    const dialogo = (await screen.findByText('Eliminar bloque de disponibilidad')).closest('.fixed');
    const panel = screen.getByText(/^Horarios de /i).closest('.fixed');
    expect(dialogo?.className).toContain('z-[60]');
    expect(panel?.className).toContain('z-50');
    expect(panel?.className).not.toContain('z-[60]');
  });

  it('pide confirmación y no llama al servicio hasta aceptarla', async () => {
    cargarCon([HORARIO]);

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
    fireEvent.click(await screen.findByTitle('Eliminar bloque'));

    const titulo = await screen.findByText('Eliminar bloque de disponibilidad');
    expect(deleteMock).not.toHaveBeenCalled();

    const confirmacion = titulo.closest('[role="dialog"]');
    expect(confirmacion).not.toBeNull();
    fireEvent.click(within(confirmacion as HTMLElement).getByRole('button', { name: /eliminar bloque/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/horarios/${HORARIO.id}`));
  });

  it('muestra el mensaje del backend cuando el bloque se rechaza', async () => {
    // Mismo camino que el borrado, del lado del alta: si el backend explica el rechazo, ese
    // texto es lo que permite corregir el bloque. El genérico hablaba solo de rango y duración,
    // y no cubría el solapamiento, que es la otra causa posible.
    cargarCon([HORARIO]);
    postMock.mockRejectedValueOnce({
      response: { data: { mensaje: 'El bloque se solapa con otro del mismo día' } },
    });

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
    fireEvent.click(await screen.findByRole('button', { name: /agregar bloque/i }));

    expect(await screen.findByText('El bloque se solapa con otro del mismo día')).toBeInTheDocument();
  });

  it('muestra el mensaje del backend cuando el borrado se rechaza', async () => {
    // El rechazo no se reemplaza por un texto genérico: el backend sabe por qué no se puede
    // borrar y ese motivo es lo único que le permite decidir a quien está en el mostrador.
    cargarCon([HORARIO]);
    deleteMock.mockRejectedValueOnce({
      response: { data: { mensaje: 'El bloque no puede eliminarse porque tiene citas agendadas' } },
    });

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
    fireEvent.click(await screen.findByTitle('Eliminar bloque'));

    const titulo = await screen.findByText('Eliminar bloque de disponibilidad');
    const confirmacion = titulo.closest('[role="dialog"]');
    expect(confirmacion).not.toBeNull();
    fireEvent.click(
      within(confirmacion as HTMLElement).getByRole('button', { name: /eliminar bloque/i }),
    );

    expect(
      await screen.findByText('El bloque no puede eliminarse porque tiene citas agendadas'),
    ).toBeInTheDocument();
  });
});
