import { useState } from 'react';
import { Button, Input, Select, Alert, Modal } from '@/components/ui';
import { Phone, Mail, IdCard, Home, UserPlus } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import {
  SUGERENCIAS_DE_PARENTESCO,
  erroresDePaciente,
  type CampoDePaciente,
  type ErroresDePaciente,
} from './validacionPaciente';
import type { Paciente } from './pacientes.types';
import type { PacientePayload } from './pacientes.service';

/**
 * Respaldo del alta y de la edición. El rechazo por CURP duplicada (409) trae su propio motivo y
 * se muestra tal cual (S7); este texto solo cubre un fallo que no vino con mensaje.
 */
const MENSAJE_ERROR_GUARDAR =
  'No fue posible guardar la ficha del paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';

const EMPTY_FORM = {
  nombre: '',
  telefono: '',
  fechaNacimiento: '',
  sexo: '' as 'M' | 'F' | 'O' | '',
  email: '',
  notas: '',
  curp: '',
  ocupacion: '',
  domicilio: '',
  nombreResponsable: '',
  telefonoResponsable: '',
  parentesco: '',
};

type FormularioPaciente = typeof EMPTY_FORM;

/**
 * La ficha se lee de los campos planos del paciente (S6): es la misma forma en la que el backend
 * la devuelve, así que no hay nada que desenvolver ni campos que se pierdan al editar.
 */
function formularioDePaciente(patient: Paciente): FormularioPaciente {
  return {
    nombre: patient.nombre,
    telefono: patient.telefono || '',
    fechaNacimiento: patient.fechaNacimiento || '',
    sexo: patient.sexo || '',
    email: patient.email || '',
    notas: patient.notas || '',
    curp: patient.curp || '',
    ocupacion: patient.ocupacion || '',
    domicilio: patient.domicilio || '',
    nombreResponsable: patient.nombreResponsable || '',
    telefonoResponsable: patient.telefonoResponsable || '',
    parentesco: patient.parentesco || '',
  };
}

export interface PacienteFormProps {
  onClose: () => void;
  /** Paciente en edición, o `null` para un alta. */
  editingPaciente: Paciente | null;
  /** Hace la llamada real (crear o actualizar, según corresponda) y aplica sus efectos de éxito;
   *  un rechazo se relanza para que el formulario lo muestre sin cerrar el diálogo. */
  onSubmit: (payload: PacientePayload) => Promise<void>;
}

/**
 * Modal de alta/edición de la ficha del paciente (NOM-004).
 *
 * El llamador (`PacientesPage`) solo monta este componente mientras el diálogo debe estar
 * abierto, con una `key` que cambia en cada «Nuevo Paciente» o «Ficha»: así cada apertura es una
 * instancia nueva que arranca limpia desde su estado inicial, sin necesitar un efecto que la
 * reinicie.
 */
export function PacienteForm({ onClose, editingPaciente, onSubmit }: PacienteFormProps) {
  const [form, setForm] = useState<FormularioPaciente>(() =>
    editingPaciente ? formularioDePaciente(editingPaciente) : EMPTY_FORM,
  );
  const [erroresForm, setErroresForm] = useState<ErroresDePaciente>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  /**
   * Escribe un campo con regla de formato y retira su error: el mensaje describía el valor
   * anterior y dejarlo visible mientras se corrige el dato es una advertencia falsa.
   */
  const actualizarCampo = (campo: CampoDePaciente, valor: string) => {
    setForm((f) => ({ ...f, [campo]: valor }));
    setErroresForm((prev) => {
      if (!prev[campo]) return prev;
      const siguiente = { ...prev };
      delete siguiente[campo];
      return siguiente;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // S9: las mismas reglas del backend, aplicadas antes de enviar. Un dato mal formado no sale de
    // la pantalla y el mensaje queda junto al campo, sin esperar la respuesta del servicio.
    const errores = erroresDePaciente(form);
    setErroresForm(errores);
    if (Object.keys(errores).length > 0) return;

    // S6/S8: los seis campos de la ficha van planos, al mismo nivel que el resto y en camelCase.
    // El objeto anidado `ficha` de antes no lo declaraba el backend y Spring lo descartaba en
    // silencio: la recepcionista escribía los datos y la tabla quedaba vacía sin ningún error.
    const payload: PacientePayload = {
      nombre: form.nombre,
      telefono: form.telefono || null,
      fechaNacimiento: form.fechaNacimiento || null,
      sexo: form.sexo || null,
      email: form.email || null,
      notas: form.notas || null,
      curp: form.curp || null,
      ocupacion: form.ocupacion || null,
      domicilio: form.domicilio || null,
      nombreResponsable: form.nombreResponsable || null,
      telefonoResponsable: form.telefonoResponsable || null,
      parentesco: form.parentesco || null,
    };

    setSaveError(null);
    try {
      setIsSubmitting(true);
      await onSubmit(payload);
    } catch (err) {
      // El rechazo por CURP duplicada llega como 409 con el motivo en `mensaje`: se muestra tal
      // cual. El modal se queda abierto y la fila no se toca, para que el usuario corrija la CURP
      // sin volver a escribir la ficha.
      setSaveError(mensajeDelBackend(err, MENSAJE_ERROR_GUARDAR));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={editingPaciente ? 'Editar Ficha del Paciente' : 'Registrar Nuevo Paciente'}
      description="Datos básicos y ficha de identificación según NOM-004."
      maxWidth="lg"
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <div>
          <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
            Datos Básicos
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="Nombre Completo"
              value={form.nombre}
              onChange={(e) => actualizarCampo('nombre', e.target.value)}
              error={erroresForm.nombre}
              required
            />
            <Select
              label="Sexo"
              value={form.sexo}
              onChange={(e) => setForm((f) => ({ ...f, sexo: e.target.value as 'M' | 'F' | 'O' }))}
              options={[
                { value: 'M', label: 'Masculino' },
                { value: 'F', label: 'Femenino' },
                { value: 'O', label: 'Otro' },
              ]}
              placeholder="Selecciona..."
            />
            <Input
              label="Teléfono"
              value={form.telefono}
              onChange={(e) => actualizarCampo('telefono', e.target.value)}
              error={erroresForm.telefono}
              leftIcon={<Phone className="w-4 h-4" />}
            />
            <Input
              label="Fecha de Nacimiento"
              type="date"
              value={form.fechaNacimiento}
              onChange={(e) => setForm((f) => ({ ...f, fechaNacimiento: e.target.value }))}
            />
            <Input
              label="Correo Electrónico"
              type="email"
              value={form.email}
              onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
              leftIcon={<Mail className="w-4 h-4" />}
              className="sm:col-span-2"
            />
          </div>
          <div className="mt-4">
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Notas Clínicas Generales
            </label>
            <textarea
              rows={2}
              className="block w-full rounded-lg border border-[#CBD5E1] bg-white p-3 text-sm text-[#0B1C30] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15"
              placeholder="Ej. Alergias, padecimientos crónicos..."
              value={form.notas}
              onChange={(e) => setForm((f) => ({ ...f, notas: e.target.value }))}
            />
          </div>
        </div>

        <div className="pt-4 border-t border-[#F1F5F9]">
          <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
            Ficha de Identificación (NOM-004)
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="CURP"
              value={form.curp}
              onChange={(e) => setForm((f) => ({ ...f, curp: e.target.value.toUpperCase() }))}
              leftIcon={<IdCard className="w-4 h-4" />}
              maxLength={18}
            />
            <Input
              label="Ocupación"
              value={form.ocupacion}
              onChange={(e) => actualizarCampo('ocupacion', e.target.value)}
              error={erroresForm.ocupacion}
            />
            <Input
              label="Domicilio Completo"
              value={form.domicilio}
              onChange={(e) => actualizarCampo('domicilio', e.target.value)}
              error={erroresForm.domicilio}
              leftIcon={<Home className="w-4 h-4" />}
              className="sm:col-span-2"
            />
            <Input
              label="Nombre del Responsable"
              value={form.nombreResponsable}
              onChange={(e) => actualizarCampo('nombreResponsable', e.target.value)}
              error={erroresForm.nombreResponsable}
              helperText="Para menores de edad o urgencias"
            />
            {/* El parentesco es texto con sugerencias, no una lista cerrada (S6): «amigo»,
                «vecino» o «institución» son casos reales y siguen siendo válidos. Las formas
                «hermano/a» van en singular porque la barra no pertenece al conjunto de caracteres
                que la regla acepta. */}
            <Input
              label="Parentesco"
              value={form.parentesco}
              onChange={(e) => actualizarCampo('parentesco', e.target.value)}
              error={erroresForm.parentesco}
              list="sugerencias-parentesco"
              helperText="Relación con el paciente: madre, tutor, hermano..."
            />
            <datalist id="sugerencias-parentesco">
              {SUGERENCIAS_DE_PARENTESCO.map((parentesco) => (
                <option key={parentesco} value={parentesco} />
              ))}
            </datalist>
            <Input
              label="Teléfono del Responsable"
              value={form.telefonoResponsable}
              onChange={(e) => actualizarCampo('telefonoResponsable', e.target.value)}
              error={erroresForm.telefonoResponsable}
            />
          </div>
        </div>

        {saveError && (
          <Alert variant="error" title="No se pudo guardar la ficha del paciente">
            {saveError}
          </Alert>
        )}

        <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
          <Button type="button" variant="outline" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            type="submit"
            variant="primary"
            isLoading={isSubmitting}
            leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
          >
            {editingPaciente ? 'Guardar Cambios' : 'Registrar Paciente'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
