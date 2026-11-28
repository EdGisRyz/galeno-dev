import { useState } from 'react';
import { Button, Input, Alert, Modal } from '@/components/ui';
import { MapPin, Phone, Plus } from 'lucide-react';
import type { Consultorio } from './consultorios.types';
import type { ConsultorioPayload } from './consultorios.service';
import { mensajeDelBackend } from '@/lib/apiError';
import {
  erroresDeConsultorio,
  type CampoDeConsultorio,
  type ErroresDeConsultorio,
} from './validacionConsultorio';

const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';

const EMPTY_FORM = {
  nombre: '',
  direccion: '',
  telefono: '',
};

type FormularioConsultorio = typeof EMPTY_FORM;

function formularioDeConsultorio(consultorio: Consultorio): FormularioConsultorio {
  return {
    nombre: consultorio.nombre,
    direccion: consultorio.direccion || '',
    telefono: consultorio.telefono || '',
  };
}

export interface ConsultorioFormProps {
  onClose: () => void;
  /** Consultorio en edición, o `null` para un alta. */
  editingConsultorio: Consultorio | null;
  /** Hace la llamada real (crear o actualizar, según corresponda) y aplica sus efectos de éxito;
   *  un rechazo se relanza para que el formulario lo muestre sin cerrar el diálogo (S1, S2). */
  onSubmit: (payload: ConsultorioPayload) => Promise<void>;
}

/**
 * Modal de alta/edición de consultorio (S1-S3 del pedido de validación).
 *
 * El llamador (`ConsultoriosPage`) solo monta este componente mientras el diálogo debe estar
 * abierto, con una `key` que cambia en cada «Nuevo Consultorio» o «Editar»: así cada apertura es
 * una instancia nueva que arranca limpia desde su estado inicial, sin necesitar un efecto que la
 * reinicie.
 */
export function ConsultorioForm({ onClose, editingConsultorio, onSubmit }: ConsultorioFormProps) {
  const [form, setForm] = useState<FormularioConsultorio>(() =>
    editingConsultorio ? formularioDeConsultorio(editingConsultorio) : EMPTY_FORM,
  );
  const [erroresForm, setErroresForm] = useState<ErroresDeConsultorio>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const actualizarCampo = (campo: CampoDeConsultorio, valor: string) => {
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

    // S5: las mismas reglas del backend, aplicadas antes de enviar.
    const errores = erroresDeConsultorio(form);
    setErroresForm(errores);
    if (Object.keys(errores).length > 0) return;

    const payload: ConsultorioPayload = {
      nombre: form.nombre,
      direccion: form.direccion || null,
      telefono: form.telefono || null,
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
      title={editingConsultorio ? 'Editar Consultorio' : 'Registrar Nuevo Consultorio'}
      description="Datos del espacio físico de la clínica."
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
        <Input
          label="Dirección"
          value={form.direccion}
          onChange={(e) => setForm((f) => ({ ...f, direccion: e.target.value }))}
          leftIcon={<MapPin className="w-4 h-4" />}
          helperText="Campo libre: incluye calle, número exterior e interior"
        />
        <Input
          label="Teléfono"
          value={form.telefono}
          onChange={(e) => actualizarCampo('telefono', e.target.value)}
          error={erroresForm.telefono}
          leftIcon={<Phone className="w-4 h-4" />}
          maxLength={20}
        />

        {saveError && (
          <Alert variant="error" title="No se pudo guardar el consultorio">
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
            {editingConsultorio ? 'Guardar Cambios' : 'Registrar Consultorio'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
