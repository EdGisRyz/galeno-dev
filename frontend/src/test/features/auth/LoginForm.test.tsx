import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginForm } from '@/features/auth/LoginForm';
import { authService } from '@/features/auth/auth.service';

/**
 * El formulario de login no embebe credenciales (plan §2 guardarraíl 5, R-SEG-07):
 * ni contraseña por defecto, ni usuarios de prueba en el DOM.
 *
 * T6b (E): además debe mostrar el mensaje del emisor. dev-auth y el `ErrorResponse` del backend
 * usan la clave `mensaje`; leer `message` dejaba al usuario con el texto genérico siempre.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

// Credenciales que existían hardcodeadas en el formulario antes de T6a.
const CREDENCIALES_PROHIBIDAS = [
  'password123',
  'dra.elena@sanrafael.com',
  'dr.roberto@sanrafael.com',
  'admin@sanrafael.com',
];

const FALLBACK_GENERICO = /credenciales incorrectas o error de comunicación/i;

function renderLoginForm() {
  return render(
    <MemoryRouter>
      <LoginForm />
    </MemoryRouter>,
  );
}

function enviarCredenciales() {
  fireEvent.change(screen.getByLabelText(/correo electrónico/i), {
    target: { value: 'medico@clinica.mx' },
  });
  fireEvent.change(screen.getByLabelText(/contraseña/i), { target: { value: 'incorrecta' } });
  fireEvent.click(screen.getByRole('button', { name: /acceder al sistema/i }));
}

beforeEach(() => {
  servicio.login.mockReset();
});

describe('LoginForm · sin credenciales embebidas', () => {
  it('monta el formulario con correo y contraseña vacíos y sin credenciales en el DOM', () => {
    const { container } = renderLoginForm();

    const email = screen.getByLabelText(/correo electrónico/i) as HTMLInputElement;
    const password = screen.getByLabelText(/contraseña/i) as HTMLInputElement;

    expect(email.value).toBe('');
    expect(password.value).toBe('');

    // Ni en un atributo `value` ni en un `defaultValue` renderizado.
    expect(container.innerHTML).not.toContain('value="password123"');

    const rendered = `${container.innerHTML} ${container.textContent ?? ''}`;
    for (const credencial of CREDENCIALES_PROHIBIDAS) {
      expect(rendered).not.toContain(credencial);
    }
  });
});

describe('LoginForm · mensaje de error del emisor', () => {
  it('muestra el `mensaje` del cuerpo 401, no el texto genérico', async () => {
    servicio.login.mockRejectedValue({
      response: { status: 401, data: { mensaje: 'Credenciales inválidas.' } },
    });

    renderLoginForm();
    enviarCredenciales();

    expect(await screen.findByText('Credenciales inválidas.')).toBeInTheDocument();
    expect(screen.queryByText(FALLBACK_GENERICO)).not.toBeInTheDocument();
  });

  it('cae al texto genérico cuando el error no trae mensaje del emisor', async () => {
    servicio.login.mockRejectedValue(new Error('sin red'));

    renderLoginForm();
    enviarCredenciales();

    expect(await screen.findByText(FALLBACK_GENERICO)).toBeInTheDocument();
  });
});
