import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Reglas de formato de la ficha del paciente (S1 a S10).
 *
 * El formulario aceptaba y guardaba `Arturo Beltránñntty354545 5.` como nombre, `Jubilado454545`
 * como ocupación y `54545454` como parentesco. Aquí se fija lo que la pantalla tiene que hacer
 * antes de enviar (S9): mostrar el error junto al campo y **no llamar a la API**. También se fija
 * lo que sigue permitido: el domicilio con números (S4), un parentesco fuera de las sugerencias
 * (S6) y un paciente ya guardado con datos no conformes, que se sigue leyendo (S10).
 *
 * Todo se comprueba por la interfaz pública: etiquetas, texto visible y el cuerpo de la petición.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);
const postMock = vi.mocked(api.post);
const putMock = vi.mocked(api.put);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes`;

const PACIENTE: Paciente = {
  id: 'pac-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'María López Ramírez',
  telefono: '9514445566',
  fechaNacimiento: '1985-03-14',
  sexo: 'F',
  email: null,
  notas: null,
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
  curp: null,
  ocupacion: 'Comerciante',
  domicilio: 'Av. Juárez 210, Col. Centro',
  nombreResponsable: 'Rosa Ramírez Silva',
  telefonoResponsable: '5550001111',
  parentesco: 'Madre',
};

/**
 * Paciente guardado antes de existir la regla: es el caso de la captura. S10 exige que se siga
 * viendo en el directorio y en su ficha; lo que no se puede es volver a guardarlo sin corregirlo.
 */
const PACIENTE_NO_CONFORME: Paciente = {
  ...PACIENTE,
  id: 'pac-0002',
  nombre: 'Arturo Beltránñntty354545 5.',
  telefono: '9514445566ttnynt',
  ocupacion: 'Jubilado454545',
  nombreResponsable: '45454545',
  telefonoResponsable: '35tgrgegegege',
  parentesco: '54545454',
};

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  putMock.mockReset();
  // Los fallos se registran en consola a propósito (resumen sin secretos): se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});

  useAuthStore.setState({
    accessToken: null,
    user: { id: 'usr-0001', email: 'admin@correo.mx', role: 'ADMIN', tenantId: ORGANIZACION_ID },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
});

/** Escribe en el campo localizado por su etiqueta visible. */
function escribir(etiqueta: RegExp, valor: string) {
  fireEvent.change(screen.getByLabelText(etiqueta), { target: { value: valor } });
}

/** Abre el formulario de alta sobre un directorio ya cargado. */
async function abrirAlta() {
  fireEvent.click(await screen.findByRole('button', { name: /nuevo paciente/i }));
}

describe('PacientesPage · reglas de formato (S1 a S7, S9)', () => {
  it('no envía el alta cuando el nombre trae dígitos', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Arturo Beltránñntty354545 5.');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando el teléfono trae letras', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Paciente de prueba');
    escribir(/^teléfono$/i, '9514445566ttnynt');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    expect(await screen.findByText(/entre 7 y 20 caracteres/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando la ocupación trae dígitos', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Paciente de prueba');
    escribir(/ocupación/i, 'Jubilado454545');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando el nombre o el teléfono del responsable no cumplen', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Paciente de prueba');
    escribir(/nombre del responsable/i, '45454545');
    escribir(/teléfono del responsable/i, '35tgrgegegege');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    // Los dos mensajes aparecen a la vez, cada uno junto a su campo.
    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(screen.getByText(/entre 7 y 20 caracteres/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando el parentesco es numérico', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Paciente de prueba');
    escribir(/parentesco/i, '54545454');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('sí envía el domicilio con números y un parentesco fuera de las sugerencias', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));
    postMock.mockResolvedValue(respuestaCon<Paciente>({ ...PACIENTE, id: 'pac-0009' }));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'María López Ramírez');
    escribir(/^teléfono$/i, '9514445566');
    escribir(/ocupación/i, 'Jubilada');
    // La dirección lleva número de casa: el campo es libre a propósito (S4).
    escribir(/domicilio completo/i, 'Calle 5 de Mayo 12-B, Centro');
    escribir(/nombre del responsable/i, 'Rosa Ramírez Silva');
    escribir(/teléfono del responsable/i, '+52 55 5555 8765');
    escribir(/parentesco/i, 'Vecino');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [ruta, cuerpo] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(ruta).toBe(RUTA_COLECCION);
    expect(cuerpo.domicilio).toBe('Calle 5 de Mayo 12-B, Centro');
    expect(cuerpo.parentesco).toBe('Vecino');
  });

  it('muestra el mensaje del backend cuando rechaza igual', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));
    postMock.mockRejectedValueOnce({
      response: { data: { mensaje: 'El campo parentesco es inválido' } },
    });

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/^nombre completo\b/i, 'Paciente de prueba');
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    expect(await screen.findByText('El campo parentesco es inválido')).toBeInTheDocument();
  });
});

describe('PacientesPage · parentesco con sugerencias (S6)', () => {
  it('ofrece las opciones comunes en un datalist y deja escribir cualquier otra cosa', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);
    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    const campo = screen.getByLabelText(/parentesco/i);
    // Es un campo de texto con sugerencias, no una lista cerrada.
    expect(campo.tagName).toBe('INPUT');

    const idLista = campo.getAttribute('list');
    expect(idLista).toBeTruthy();
    const lista = document.getElementById(idLista as string);
    expect(lista?.tagName).toBe('DATALIST');

    const opciones = Array.from(lista?.querySelectorAll('option') ?? []).map((o) =>
      (o as HTMLOptionElement).value.toLowerCase(),
    );
    expect(opciones).toEqual(
      expect.arrayContaining(['madre', 'padre', 'tutor', 'hermano', 'hermana', 'cónyuge']),
    );
  });
});

describe('PacientesPage · lo ya guardado se sigue leyendo (S10)', () => {
  it('muestra el paciente con datos no conformes y no deja guardar la edición sin corregir', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE_NO_CONFORME]));

    render(<PacientesPage />);

    expect(await screen.findByText(PACIENTE_NO_CONFORME.nombre)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /ficha/i }));
    expect(screen.getByLabelText(/^nombre completo\b/i)).toHaveValue(PACIENTE_NO_CONFORME.nombre);

    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    // Los errores aparecen junto a cada campo y el PUT no sale: uno por campo, con su etiqueta.
    expect(
      await screen.findByText(
        'El nombre solo admite letras, espacios, punto, guion y apóstrofo; no admite dígitos.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'La ocupación solo admite letras, espacios, punto, guion y apóstrofo; no admite dígitos.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'El nombre del responsable solo admite letras, espacios, punto, guion y apóstrofo; no admite dígitos.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'El parentesco solo admite letras, espacios, punto, guion y apóstrofo; no admite dígitos.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'El teléfono debe tener entre 7 y 20 caracteres: dígitos, un + inicial opcional, espacios y guiones.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'El teléfono del responsable debe tener entre 7 y 20 caracteres: dígitos, un + inicial opcional, espacios y guiones.',
      ),
    ).toBeInTheDocument();
    expect(putMock).not.toHaveBeenCalled();
    // La fila sigue en el directorio, con el dato tal como está guardado.
    expect(within(screen.getByRole('table')).getByText(PACIENTE_NO_CONFORME.nombre)).toBeInTheDocument();
  });
});
