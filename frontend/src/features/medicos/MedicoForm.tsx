import { useState } from 'react';
import { Button, Input, Select, Alert, Modal } from '@/components/ui';
import { IdCard, Phone, Plus } from 'lucide-react';
import type { Medico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import type { MedicoPayload } from './medicos.service';
import { mensajeDelBackend } from '@/lib/apiError';
import {
  erroresDeMedico,
  type CampoDeMedico,
  type ErroresDeMedico,
} from './validacionMedico';

const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar el médico. Revisa la conexión con el servicio e inténtalo de nuevo.';

const EMPTY_FORM = {
  nombre: '',
  especialidad: '',
  cedula: '',
  telefono: '',
  tarifaBase: '',
  consultorioId: '',
};

type FormularioMedico = typeof EMPTY_FORM;

function formularioDeMedico(medico: Medico): FormularioMedico {
  return {
    nombre: medico.nombre,
    especialidad: medico.especialidad || '',
    cedula: medico.cedula || '',
    telefono: medico.telefono || '',
    tarifaBase: medico.tarifaBase != null ? String(medico.tarifaBase) : '',
    consultorioId: medico.consultorioId,
  };
}

export interface MedicoFormProps {
  onClose: () => void;
  /** Médico en edición, o `null` para un alta. */
  editingMedico: Medico | null;
  /** Solo los consultorios activos de la organización (S13): un médico siempre necesita uno. */
  consultoriosDisponibles: Consultorio[];
  /** Hace la llamada real (crear o actualizar, según corresponda) y aplica sus efectos de éxito;
   *  un rechazo se relanza para que el formulario lo muestre sin cerrar el diálogo (S1, S2). */
  onSubmit: (payload: MedicoPayload) => Promise<void>;
}

/**
 * Modal de alta/edición de médico (S1-S3 del pedido de validación).
 *
 * El consultorio es inmutable una vez creado el médico (R13): al editar, el campo queda
 * bloqueado con el valor actual. El aviso de un rechazo del backend vive **dentro** del diálogo,
 * junto a los botones: pintarlo en la página detrás del modal deja un botón que no responde.
 *
 * El llamador (`MedicosPage`) solo monta este componente mientras el diálogo debe estar abierto,
 * con una `key` que cambia en cada «Nuevo Médico» o «Editar»: así cada apertura es una instancia
 * nueva que arranca limpia desde su estado inicial, sin necesitar un efecto que la reinicie.
 */
export function MedicoForm({
  onClose,
  editingMedico,
  consultoriosDisponibles,
  onSubmit,
}: MedicoFormProps) {
  const [form, setForm] = useState<FormularioMedico>(() =>
    editingMedico
      ? formularioDeMedico(editingMedico)
      : { ...EMPTY_FORM, consultorioId: consultoriosDisponibles[0]?.id || '' },
  );
  const [erroresForm, setErroresForm] = useState<ErroresDeMedico>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const actualizarCampo = (campo: CampoDeMedico, valor: string) => {
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

    // S7: las mismas reglas del backend, aplicadas antes de enviar.
    const errores = erroresDeMedico(form);
    setErroresForm(errores);
    if (Object.keys(errores).length > 0 || !form.consultorioId) return;

    const payload: MedicoPayload = {
      consultorioId: form.consultorioId,
      nombre: form.nombre,
      especialidad: form.especialidad || null,
      cedula: form.cedula || null,
      telefono: form.telefono || null,
      tarifaBase: form.tarifaBase ? Number(form.tarifaBase) : null,
    };

    try {
      setIsSubmitting(true);
      setSaveError(null);
      await onSubmit(payload);
    } catch (err) {
      setSaveError(mensajeDelBackend(err, MENSAJE_ERROR_GUARDADO));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={editingMedico ? 'Editar Médico' : 'Registrar Nuevo Médico'}
      description="Datos profesionales del médico y el consultorio donde atiende."
      maxWidth="md"
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <Input
          label="Nombre"
          value={form.nombre}
          onChange={(e) => actualizarCampo('nombre', e.target.value)}
          error={erroresForm.nombre}
          required
          maxLength={120}
        />
        <Select
          label="Consultorio"
          value={form.consultorioId}
          onChange={(e) => setForm((f) => ({ ...f, consultorioId: e.target.value }))}
          options={consultoriosDisponibles.map((c) => ({ value: c.id, label: c.nombre }))}
          placeholder="Selecciona..."
          required
          disabled={editingMedico !== null}
          helperText={
            editingMedico !== null
              ? 'El consultorio no se puede cambiar una vez creado el médico.'
              : undefined
          }
        />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Input
            label="Especialidad"
            value={form.especialidad}
            onChange={(e) => actualizarCampo('especialidad', e.target.value)}
            error={erroresForm.especialidad}
            maxLength={80}
          />
          <Input
            label="Cédula profesional"
            value={form.cedula}
            onChange={(e) => actualizarCampo('cedula', e.target.value)}
            error={erroresForm.cedula}
            leftIcon={<IdCard className="w-4 h-4" />}
            maxLength={10}
          />
          <Input
            label="Teléfono"
            value={form.telefono}
            onChange={(e) => actualizarCampo('telefono', e.target.value)}
            error={erroresForm.telefono}
            leftIcon={<Phone className="w-4 h-4" />}
            maxLength={20}
          />
          <Input
            label="Tarifa base"
            type="number"
            min="0"
            step="0.01"
            value={form.tarifaBase}
            onChange={(e) => setForm((f) => ({ ...f, tarifaBase: e.target.value }))}
          />
        </div>

        {saveError && (
          <Alert variant="error" title="No se pudo guardar el médico">
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
            leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
          >
            {editingMedico ? 'Guardar Cambios' : 'Registrar Médico'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
