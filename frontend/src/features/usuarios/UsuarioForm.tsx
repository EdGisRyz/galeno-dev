import { useState } from 'react';
import { Button, Input, Select, Alert, Modal } from '@/components/ui';
import { Phone } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import { errorDeCorreoDeCuenta } from '@/lib/patronesDeFormato';
import {
  erroresDeCuenta,
  LONGITUD_MAXIMA_NOMBRE,
  type ErroresDeCuenta,
} from './validacionUsuario';
import type { Usuario, RolUsuario } from './usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import type { UsuarioPayload } from './usuarios.service';

const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar los cambios. Revisa que el correo no esté en uso, que el rol MEDICO tenga un médico asociado, y que la organización conserve al menos una cuenta ADMIN activa.';

/**
 * El correo es el identificador de inicio de sesión y el alta no se envía sin él. La misma
 * validación corre en el backend.
 */
const MENSAJE_CORREO_OBLIGATORIO = 'El correo es obligatorio.';

// S6: la historia de usuario nombra tres roles —administrador, recepcionista y médico—, así que
// `PACIENTE` no se ofrece: no tiene ninguna celda en la matriz y la cuenta quedaría creada sin
// poder hacer nada. El tipo `RolUsuario` conserva los cuatro valores porque la base y las
// respuestas del backend los admiten; lo que cambia es lo que el selector **ofrece**.
const ROLES: RolUsuario[] = ['ADMIN', 'MEDICO', 'RECEPCIONISTA'];

const EMPTY_FORM = {
  nombre: '',
  telefono: '',
  email: '',
  rol: 'RECEPCIONISTA' as RolUsuario,
  medicoId: '',
};

type FormularioUsuario = typeof EMPTY_FORM;

/** Mensajes del formulario: los de los datos de la persona y el de la forma del correo. */
type ErroresDelFormulario = ErroresDeCuenta & { email?: string };

function formularioDeUsuario(usuario: Usuario): FormularioUsuario {
  return {
    // Una cuenta anterior a la V10 no tiene nombre: el campo nace vacío y guardarla obliga a
    // completarlo. No se rellena con nada derivado del correo.
    nombre: usuario.nombre ?? '',
    telefono: usuario.telefono ?? '',
    email: usuario.email,
    rol: usuario.rol,
    medicoId: usuario.medicoId || '',
  };
}

export interface UsuarioFormProps {
  onClose: () => void;
  /** Cuenta en edición, o `null` para un alta. */
  editingUsuario: Usuario | null;
  /** Nadie puede cambiarse el rol a sí mismo: deshabilita el selector cuando aplica. */
  editandoLaPropiaCuenta: boolean;
  medicos: Medico[];
  /** Hace la llamada real (crear o actualizar, según corresponda) y aplica sus efectos de éxito;
   *  un rechazo se relanza para que el formulario lo muestre sin cerrar el diálogo (S1, S2). */
  onSubmit: (payload: UsuarioPayload) => Promise<void>;
}

/**
 * Modal de alta/edición de cuenta.
 *
 * El llamador (`UsuariosPage`) solo monta este componente mientras el diálogo debe estar abierto,
 * con una `key` que cambia en cada «Nueva Cuenta» o «Editar»: así cada apertura es una instancia
 * nueva que arranca limpia desde su estado inicial, sin necesitar un efecto que la reinicie.
 */
export function UsuarioForm({
  onClose,
  editingUsuario,
  editandoLaPropiaCuenta,
  medicos,
  onSubmit,
}: UsuarioFormProps) {
  const [form, setForm] = useState<FormularioUsuario>(() =>
    editingUsuario ? formularioDeUsuario(editingUsuario) : EMPTY_FORM,
  );
  const [erroresForm, setErroresForm] = useState<ErroresDelFormulario>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // S6: los espacios de los extremos no forman parte del dato. Se recorta antes de validar y se
    // envía recortado, porque un espacio invisible al pegar rechazaba un correo válido con un
    // mensaje sobre su forma —el mismo recorte que hace el backend antes de su patrón—.
    const email = form.email.trim();
    const nombre = form.nombre.trim();
    const telefono = form.telefono.trim();

    // S1, S4 y S7: el nombre es obligatorio y el teléfono opcional, con las reglas compartidas y
    // el mensaje junto al campo. El correo conserva su regla estructural, que es la del
    // identificador de inicio de sesión.
    const errorEmail = !email ? MENSAJE_CORREO_OBLIGATORIO : errorDeCorreoDeCuenta(email);
    const errores: ErroresDelFormulario = {
      ...erroresDeCuenta({ nombre, telefono }),
      ...(errorEmail ? { email: errorEmail } : {}),
    };
    setErroresForm(errores);
    if (Object.keys(errores).length > 0) return;
    if (form.rol === 'MEDICO' && !form.medicoId) return;

    const payload: UsuarioPayload = {
      email,
      rol: form.rol,
      medicoId: form.rol === 'MEDICO' ? form.medicoId : null,
      nombre,
      // El teléfono en blanco es «no registrado»: viaja nulo, igual que en el contrato del backend.
      telefono: telefono === '' ? null : telefono,
    };

    try {
      setIsSubmitting(true);
      setSaveError(null);
      await onSubmit(payload);
    } catch (err) {
      // El rechazo del servicio explica el motivo —el correo ya está en uso, o su forma no es
      // válida— y se muestra tal cual; sin mensaje se cae al texto de la pantalla.
      setSaveError(mensajeDelBackend(err, MENSAJE_ERROR_GUARDADO));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={editingUsuario ? 'Editar Cuenta' : 'Registrar Nueva Cuenta'}
      description="Datos de la persona, correo de acceso y rol."
      maxWidth="md"
    >
      {/*
        `noValidate`: los mensajes son los de la pantalla —en español y junto al campo—, no el
        aviso del navegador, que cada uno escribe en su idioma y bloquea el envío antes de que
        este formulario pueda explicar qué falta.
      */}
      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <Input
          label="Nombre Completo"
          type="text"
          autoComplete="name"
          value={form.nombre}
          onChange={(e) => {
            setForm((f) => ({ ...f, nombre: e.target.value }));
            setErroresForm((prev) => ({ ...prev, nombre: undefined }));
          }}
          error={erroresForm.nombre}
          required
          maxLength={LONGITUD_MAXIMA_NOMBRE}
        />
        <Input
          label="Teléfono"
          type="text"
          inputMode="tel"
          autoComplete="tel"
          value={form.telefono}
          onChange={(e) => {
            setForm((f) => ({ ...f, telefono: e.target.value }));
            setErroresForm((prev) => ({ ...prev, telefono: undefined }));
          }}
          error={erroresForm.telefono}
          leftIcon={<Phone className="w-4 h-4" />}
          helperText="Opcional."
        />
        {/*
          `type="text"` con `inputMode="email"` en lugar de `type="email"`: el navegador rechazaría
          por su cuenta parte de los correos sin forma de correo (`juan@`, `juan@.com`) con un aviso
          en su propio idioma y sin llegar a este formulario, y la regla —con su mensaje en
          español, junto al campo— vive aquí y en el backend.
        */}
        <Input
          label="Correo"
          type="text"
          inputMode="email"
          autoComplete="email"
          value={form.email}
          onChange={(e) => {
            setForm((f) => ({ ...f, email: e.target.value }));
            setErroresForm((prev) => ({ ...prev, email: undefined }));
          }}
          error={erroresForm.email}
          required
          maxLength={180}
        />
        <Select
          label="Rol"
          value={form.rol}
          onChange={(e) => setForm((f) => ({ ...f, rol: e.target.value as RolUsuario }))}
          options={ROLES.map((r) => ({ value: r, label: r }))}
          disabled={editandoLaPropiaCuenta}
          helperText={editandoLaPropiaCuenta ? 'No puedes cambiarte el rol a ti mismo.' : undefined}
        />
        {form.rol === 'MEDICO' && (
          <Select
            label="Médico asociado"
            value={form.medicoId}
            onChange={(e) => setForm((f) => ({ ...f, medicoId: e.target.value }))}
            options={medicos.map((m) => ({ value: m.id, label: m.nombre }))}
            placeholder="Selecciona..."
            required
          />
        )}

        {saveError && (
          <Alert variant="error" title="No se pudo guardar la cuenta">
            {saveError}
          </Alert>
        )}

        <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
          <Button type="button" variant="outline" onClick={onClose}>
            Cancelar
          </Button>
          <Button type="submit" variant="primary" isLoading={isSubmitting}>
            {editingUsuario ? 'Guardar Cambios' : 'Registrar Cuenta'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
