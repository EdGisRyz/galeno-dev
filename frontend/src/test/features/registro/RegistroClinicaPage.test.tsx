import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { RegistroClinicaPage } from '@/features/registro/RegistroClinicaPage';
import type { PlanDisponible } from '@/features/registro/registro.schema';

/**
 * S7 · el formulario de la landing habla con el contrato real del onboarding.
 *
 * Lo que se fija aquí, por la interfaz pública (etiquetas, texto visible y el cuerpo de la petición):
 *
 * - Los límites **no se eligen**: se pintan en solo lectura desde `GET /onboarding/planes`, cambian
 *   con el plan y **no viajan** en el cuerpo del registro.
 * - La contraseña y su confirmación se validan en el cliente y **no se envían**.
 * - Después de registrar, la pantalla dice con qué entrar: el correo de la cuenta y la contraseña
 *   del entorno, y aclara que la contraseña escrita no se guardó.
 * - Un dato inválido deja el mensaje junto al campo y no llama a la API; un rechazo del backend se
 *   muestra con su propio mensaje.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  // El store conecta el cliente HTTP al cargarse; aquí no hay red que conectar.
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);
const postMock = vi.mocked(api.post);

const RUTA_PLANES = '/onboarding/planes';
const RUTA_REGISTRO = '/onboarding/registro-clinica';

/** Mismo texto que muestra la pantalla cuando el catálogo de planes no carga. */
const MENSAJE_PLANES =
  'No fue posible cargar los planes. Sin ellos no se pueden mostrar los límites de la clínica.';

const PLANES: PlanDisponible[] = [
  { plan: 'SOLO', etiqueta: 'Solo (un profesional)', maxMedicos: 1, maxConsultorios: 1 },
  { plan: 'CLINICA', etiqueta: 'Clínica (varios profesionales)', maxMedicos: 10, maxConsultorios: 6 },
  { plan: 'ENTERPRISE', etiqueta: 'Empresarial (varias sedes)', maxMedicos: null, maxConsultorios: null },
];

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Rechazo HTTP con la forma que consumen `resumenDeErrorHttp` y `mensajeDelBackend`. */
function rechazoConMensaje(estado: number, mensaje: string): Error {
  return Object.assign(new Error(`Request failed with status code ${estado}`), {
    config: { method: 'post', url: RUTA_REGISTRO },
    response: { status: estado, data: { codigo: estado, error: 'Bad Request', mensaje } },
  });
}

function renderizar() {
  return render(
    <MemoryRouter>
      <RegistroClinicaPage />
    </MemoryRouter>,
  );
}

/**
 * Campos del formulario, con el patrón de su etiqueta accesible.
 *
 * El patrón de la contraseña está anclado al principio a propósito: sin la ancla también casaría con
 * «Confirmar contraseña» y el selector encontraría dos campos.
 */
const CAMPOS_VALIDOS: ReadonlyArray<readonly [RegExp, string]> = [
  [/^nombre de la clínica/i, 'Clínica del Valle'],
  [/^nombre del administrador/i, 'Administración General'],
  [/^correo electrónico/i, 'administracion@example.com'],
  [/^contraseña/i, 'secreto-123'],
  [/^confirmar contraseña/i, 'secreto-123'],
];

/** Rellena los campos del formulario con datos válidos, con las excepciones indicadas. */
function completarFormulario(sobrescribir: Record<string, string> = {}) {
  for (const [patron, valor] of CAMPOS_VALIDOS) {
    const reemplazo = Object.entries(sobrescribir).find(([etiqueta]) => patron.test(etiqueta));
    fireEvent.change(screen.getByLabelText(patron), {
      target: { value: reemplazo ? reemplazo[1] : valor },
    });
  }
}

beforeEach(() => {
  vi.clearAllMocks();
  getMock.mockResolvedValue(respuestaCon(PLANES));
});

describe('RegistroClinicaPage · contrato del onboarding', () => {
  it('pinta los límites del plan elegido en solo lectura y los cambia con el plan', async () => {
    renderizar();

    const medicos = await screen.findByLabelText(/máximo de médicos/i);
    const consultorios = screen.getByLabelText(/máximo de consultorios/i);

    await waitFor(() => expect(medicos).toHaveValue('1'));
    expect(consultorios).toHaveValue('1');
    expect(medicos).toHaveAttribute('readonly');

    fireEvent.change(screen.getByLabelText(/^plan/i), { target: { value: 'CLINICA' } });

    await waitFor(() => expect(medicos).toHaveValue('10'));
    expect(consultorios).toHaveValue('6');
  });

  it('muestra «Sin límite» cuando el plan no tiene tope', async () => {
    renderizar();

    const medicos = await screen.findByLabelText(/máximo de médicos/i);
    await waitFor(() => expect(medicos).toHaveValue('1'));

    fireEvent.change(screen.getByLabelText(/^plan/i), { target: { value: 'ENTERPRISE' } });

    await waitFor(() => expect(medicos).toHaveValue('Sin límite'));
    expect(screen.getByLabelText(/máximo de consultorios/i)).toHaveValue('Sin límite');
  });

  it('envía solo nombre, plan, nombre y correo del administrador: ni contraseñas ni límites', async () => {
    postMock.mockResolvedValue(
      respuestaCon({ organizacionId: 'org-1', nombreClinica: 'Clínica del Valle' }),
    );
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario();
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));

    const [ruta, cuerpo] = postMock.mock.calls[0];
    expect(ruta).toBe(RUTA_REGISTRO);
    expect(cuerpo).toEqual({
      nombreClinica: 'Clínica del Valle',
      plan: 'SOLO',
      nombreAdmin: 'Administración General',
      correoAdmin: 'administracion@example.com',
    });
    expect(cuerpo).not.toHaveProperty('password');
    expect(cuerpo).not.toHaveProperty('confirmarPassword');
    expect(cuerpo).not.toHaveProperty('maxMedicos');
    expect(cuerpo).not.toHaveProperty('maxConsultorios');
  });

  it('después de registrar explica con qué entrar: su correo y la contraseña del entorno', async () => {
    postMock.mockResolvedValue(
      respuestaCon({ organizacionId: 'org-1', nombreClinica: 'Clínica del Valle' }),
    );
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario();
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    expect(await screen.findByText(/quedó registrada/i)).toBeInTheDocument();
    expect(screen.getByText('administracion@example.com')).toBeInTheDocument();
    expect(screen.getByText(/DEV_AUTH_PASSWORD/)).toBeInTheDocument();
    expect(screen.getByText(/no se guarda/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /ir a iniciar sesión/i })).toBeInTheDocument();
  });

  it('un nombre de clínica sin letras deja el mensaje junto al campo y no llama a la API', async () => {
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario({ 'Nombre de la clínica': '454545' });
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    expect(
      await screen.findByText(/el nombre de la clínica solo admite letras, números/i),
    ).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('un nombre de administrador con dígitos deja el mensaje junto al campo y no llama a la API', async () => {
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario({ 'Nombre del administrador': 'Administrador 123' });
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    expect(
      await screen.findByText(/el nombre del administrador solo admite letras/i),
    ).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('un correo sin forma de correo deja el mensaje junto al campo y no llama a la API', async () => {
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario({ 'Correo electrónico': 'juan@' });
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    expect(await screen.findByText(/el correo debe tener forma de correo/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('muestra el mensaje del backend cuando el registro es rechazado', async () => {
    postMock.mockRejectedValue(
      rechazoConMensaje(400, 'El campo nombreClinica tiene un formato inválido'),
    );
    renderizar();

    await screen.findByLabelText(/máximo de médicos/i);
    completarFormulario();
    fireEvent.click(screen.getByRole('button', { name: /registrar mi clínica/i }));

    expect(
      await screen.findByText(/El campo nombreClinica tiene un formato inválido/),
    ).toBeInTheDocument();
  });

  it('si el catálogo de planes no carga, avisa y ofrece reintentar sin permitir enviar', async () => {
    getMock.mockRejectedValueOnce(
      Object.assign(new Error('Request failed with status code 500'), {
        config: { method: 'get', url: RUTA_PLANES },
        response: { status: 500, data: {} },
      }),
    );
    renderizar();

    expect(await screen.findByText(MENSAJE_PLANES)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /registrar mi clínica/i })).toBeDisabled();

    getMock.mockResolvedValueOnce(respuestaCon(PLANES));
    fireEvent.click(screen.getByRole('button', { name: /reintentar/i }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /registrar mi clínica/i })).toBeEnabled(),
    );
  });
});
