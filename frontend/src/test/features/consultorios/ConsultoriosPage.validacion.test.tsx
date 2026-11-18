import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { ConsultoriosPage } from '@/features/consultorios/ConsultoriosPage';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Reglas de formato del consultorio (S1 a S6).
 *
 * El formulario aceptaba nombre `454545` y teléfono `btsbtbrb4554545`. Aquí se fija que la pantalla
 * lo detecte antes de enviar y **no llame a la API** (S5), que la dirección siga siendo libre y
 * acepte números (S3) y que un consultorio ya guardado con datos no conformes se siga leyendo (S6).
 *
 * Todo se comprueba por la interfaz pública: etiquetas, texto visible y el cuerpo de la petición.
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

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/consultorios`;

const CONSULTORIO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Centro',
  direccion: 'Av. Insurgentes Sur 1234',
  telefono: '9511111111',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

/** Consultorio guardado antes de existir la regla: el caso de la captura (S6). */
const CONSULTORIO_NO_CONFORME: Consultorio = {
  ...CONSULTORIO,
  id: 'con-0002',
  nombre: '454545',
  direccion: '454545',
  telefono: 'btsbtbrb4554545',
};

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  putMock.mockReset();
  vi.mocked(api.delete).mockReset();
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

function escribir(etiqueta: RegExp, valor: string) {
  fireEvent.change(screen.getByLabelText(etiqueta), { target: { value: valor } });
}

async function abrirAlta() {
  fireEvent.click(await screen.findByRole('button', { name: /nuevo consultorio/i }));
}

describe('ConsultoriosPage · reglas de formato (S1, S2, S5)', () => {
  it('guarda un consultorio cuyo nombre lleva número y letras (Consultorio 2)', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO]));
    postMock.mockResolvedValue(
      respuestaCon<Consultorio>({ ...CONSULTORIO, id: 'con-0004', nombre: 'Consultorio 2' }),
    );

    render(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    await abrirAlta();

    // `Consultorio 2` y `Sala 3` son nombres reales: los dígitos se admiten si hay al menos una
    // letra. El teléfono entre paréntesis tampoco es un error: así se escribe un fijo.
    escribir(/^nombre\b/i, 'Consultorio 2');
    escribir(/^teléfono$/i, '(951) 111 1111');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_COLECCION, {
        nombre: 'Consultorio 2',
        direccion: null,
        telefono: '(951) 111 1111',
      }),
    );
    expect(screen.queryByText(/entre 7 y 20 caracteres/i)).not.toBeInTheDocument();
  });

  it('no envía el alta cuando el nombre no tiene ninguna letra', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO]));

    render(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    await abrirAlta();

    escribir(/^nombre\b/i, '454545');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();

    // Tampoco un nombre de solo símbolos: la regla exige al menos una letra.
    escribir(/^nombre\b/i, '---');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta cuando el teléfono trae letras', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO]));

    render(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    await abrirAlta();

    escribir(/^nombre\b/i, 'Consultorio de prueba');
    escribir(/^teléfono$/i, 'btsbtbrb4554545');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    expect(await screen.findByText(/entre 7 y 20 caracteres/i)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('sí envía la dirección con números y letras (S3)', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO]));
    postMock.mockResolvedValue(
      respuestaCon<Consultorio>({ ...CONSULTORIO, id: 'con-0003', nombre: 'Consultorio nuevo' }),
    );

    render(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    await abrirAlta();

    escribir(/^nombre\b/i, 'Consultorio nuevo');
    escribir(/dirección/i, 'Calle 5 de Mayo 12-B');
    escribir(/^teléfono$/i, '+52 55 5555 1234');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_COLECCION, {
        nombre: 'Consultorio nuevo',
        direccion: 'Calle 5 de Mayo 12-B',
        telefono: '+52 55 5555 1234',
      }),
    );
  });

  it('muestra el mensaje del backend cuando rechaza igual', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO]));
    postMock.mockRejectedValueOnce({
      response: { data: { mensaje: 'El campo nombre es inválido' } },
    });

    render(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    await abrirAlta();

    escribir(/^nombre\b/i, 'Consultorio de prueba');
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    expect(await screen.findByText('El campo nombre es inválido')).toBeInTheDocument();
  });
});

describe('ConsultoriosPage · lo ya guardado se sigue leyendo (S6)', () => {
  it('muestra el consultorio con datos no conformes en el directorio', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO_NO_CONFORME]));

    render(<ConsultoriosPage />);

    // El nombre no conforme está en la celda del nombre y en la de dirección: la fila se lee
    // completa, sin que la pantalla la esconda por no cumplir las reglas nuevas.
    expect(await screen.findAllByText('454545')).not.toHaveLength(0);
    expect(screen.getByText('btsbtbrb4554545')).toBeInTheDocument();
  });

  it('no deja guardar la edición de ese consultorio sin corregir los datos', async () => {
    getMock.mockResolvedValue(respuestaCon<Consultorio[]>([CONSULTORIO_NO_CONFORME]));

    render(<ConsultoriosPage />);
    await screen.findAllByText('454545');

    fireEvent.click(screen.getByTitle('Editar consultorio'));
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    expect(await screen.findByText(/solo admite letras/i)).toBeInTheDocument();
    expect(putMock).not.toHaveBeenCalled();
  });
});
