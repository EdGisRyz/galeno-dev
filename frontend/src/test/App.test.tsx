import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '@/App';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import type { UserRole } from '@/features/auth/auth.types';
import { CLAIMS, makeToken } from './helpers/jwt';

/**
 * T7 · cableado de roles en `App` (ADR-013).
 *
 * `/agenda` la comparten `ADMIN` y `MEDICO`. S5 suma `RECEPCIONISTA` a las cinco secciones
 * clínicas —agenda, pacientes, consultorios, médicos y expediente— y deja Administración y
 * Usuarios solo para `ADMIN`: recepción no tiene celdas sobre cuentas ni sobre la organización.
 * `PACIENTE` sigue sin pantalla: se le manda al login con `reason=sin_acceso` en vez de dejarlo
 * entrar a una agenda clínica que la matriz de ADR-013 no le concede.
 *
 * Cada pantalla real se sustituye por un marcador —haría red— y el emisor se mockea en su
 * frontera: el refresco de arranque sí se ejerce, porque es el que establece la sesión.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/features/agenda/AgendaPage', () => ({
  AgendaPage: () => 'agenda-clinica',
}));

vi.mock('@/features/pacientes/PacientesPage', () => ({
  PacientesPage: () => 'pantalla-pacientes',
}));

vi.mock('@/features/consultorios/ConsultoriosPage', () => ({
  ConsultoriosPage: () => 'pantalla-consultorios',
}));

vi.mock('@/features/medicos/MedicosPage', () => ({
  MedicosPage: () => 'pantalla-medicos',
}));

vi.mock('@/features/expediente/ExpedientePage', () => ({
  ExpedientePage: () => 'pantalla-expediente',
}));

vi.mock('@/features/administracion/MarcaClinicaPage', () => ({
  MarcaClinicaPage: () => 'pantalla-marca',
}));

vi.mock('@/features/usuarios/UsuariosPage', () => ({
  UsuariosPage: () => 'pantalla-usuarios',
}));

const servicio = vi.mocked(authService);

/** Secciones clínicas que S5 abre también a recepción. */
const SECCIONES_CLINICAS = [
  { ruta: '/agenda', marcador: 'agenda-clinica' },
  { ruta: '/pacientes', marcador: 'pantalla-pacientes' },
  { ruta: '/consultorios', marcador: 'pantalla-consultorios' },
  { ruta: '/medicos', marcador: 'pantalla-medicos' },
  { ruta: '/expediente', marcador: 'pantalla-expediente' },
];

/** Secciones que S5 mantiene vedadas para recepción (solo ADMIN). */
const SECCIONES_DE_ADMINISTRACION = [
  { ruta: '/administracion/marca', marcador: 'pantalla-marca' },
  { ruta: '/administracion/usuarios', marcador: 'pantalla-usuarios' },
];

/** Sesión anónima con el refresco de arranque todavía en vuelo, como al abrir la pestaña. */
function reiniciarSesion() {
  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: true,
    tenantSlug: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
}

/** Arranca `App` con un refresco que entrega una sesión del rol indicado. */
function montarAppConSesion(role: UserRole, ruta = '/agenda') {
  servicio.refresh.mockResolvedValue({ accessToken: makeToken({ ...CLAIMS, role }) });

  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  );
}

/** Etiquetas de la barra lateral, en orden: es lo que cada rol ve en el menú. */
function etiquetasDeLaBarra(): string[] {
  return screen.getAllByRole('link').map((enlace) => enlace.textContent?.trim() ?? '');
}

beforeEach(() => {
  localStorage.clear();
  servicio.refresh.mockReset();
  servicio.login.mockReset();
  servicio.logout.mockReset();

  reiniciarSesion();
});

describe('App · /agenda según ADR-013', () => {
  it('un ADMIN llega a la agenda', async () => {
    montarAppConSesion('ADMIN');

    expect(await screen.findByText('agenda-clinica')).toBeInTheDocument();
  });

  it('un MEDICO llega a la agenda', async () => {
    montarAppConSesion('MEDICO');

    expect(await screen.findByText('agenda-clinica')).toBeInTheDocument();
  });

  it('un PACIENTE no llega a la agenda: se queda en el login', async () => {
    montarAppConSesion('PACIENTE');

    expect(await screen.findByText('Iniciar Sesión')).toBeInTheDocument();
    expect(screen.queryByText('agenda-clinica')).not.toBeInTheDocument();
  });
});

describe('App · recepción entra a las secciones clínicas (S5)', () => {
  it.each(SECCIONES_CLINICAS)('un RECEPCIONISTA llega a $ruta', async ({ ruta, marcador }) => {
    montarAppConSesion('RECEPCIONISTA', ruta);

    expect(await screen.findByText(marcador)).toBeInTheDocument();
  });

  it.each(SECCIONES_DE_ADMINISTRACION)(
    'un RECEPCIONISTA no llega a $ruta: vuelve al login',
    async ({ ruta, marcador }) => {
      montarAppConSesion('RECEPCIONISTA', ruta);

      expect(await screen.findByText('Iniciar Sesión')).toBeInTheDocument();
      // La sección vedada no se renderiza: la guarda anidada de ADMIN decide primero.
      expect(screen.queryByText(marcador)).not.toBeInTheDocument();
    },
  );

  it('en la barra ve lo mismo que un MEDICO y ninguna sección de administración', async () => {
    montarAppConSesion('MEDICO');
    await screen.findByText('agenda-clinica');
    const barraDelMedico = etiquetasDeLaBarra();
    cleanup();

    reiniciarSesion();
    montarAppConSesion('RECEPCIONISTA');
    await screen.findByText('agenda-clinica');
    const barraDeRecepcion = etiquetasDeLaBarra();

    // Recepción ve exactamente el mismo menú que un médico: ni más ni menos módulos.
    expect(barraDeRecepcion).toEqual(barraDelMedico);
    // Agenda, expediente y cobros se anuncian pero no navegan (no tienen backend todavía), así
    // que el nombre está en la barra sin ser un enlace: se comprueba la mitad que importa acá.
    expect(screen.getByText('Agenda y Citas')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Agenda y Citas' })).toBeNull();
    expect(barraDeRecepcion).not.toContain('Administración');
    expect(barraDeRecepcion).not.toContain('Usuarios');
    // El badge del perfil identifica el rol de la sesión, no un valor por defecto.
    expect(screen.getByText('RECEPCIONISTA')).toBeInTheDocument();
  });
});
