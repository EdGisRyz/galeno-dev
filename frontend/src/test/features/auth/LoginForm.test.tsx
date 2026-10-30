import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { LoginForm } from '@/features/auth/LoginForm';

/**
 * El formulario de login no embebe credenciales (plan §2 guardarraíl 5, R-SEG-07):
 * ni contraseña por defecto, ni usuarios de prueba en el DOM.
 */

// Credenciales que existían hardcodeadas en el formulario antes de T6a.
const CREDENCIALES_PROHIBIDAS = [
  'password123',
  'dra.elena@sanrafael.com',
  'dr.roberto@sanrafael.com',
  'admin@sanrafael.com',
];

function renderLoginForm() {
  return render(
    <MemoryRouter>
      <LoginForm />
    </MemoryRouter>,
  );
}

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
