// Personalización de marca por clínica (branding): color primario y tipografía de encabezados.
//
// Sin contrato real todavía: `configuracion_tenant` no tiene columnas de marca y ningún
// endpoint de organizaciones acepta estos campos (ver `mockAdapter.ts`, sección "Marca de la
// clínica"). Esta pantalla solo habla con el mock mientras ese contrato no exista.

import { useState, useEffect } from 'react';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
import { useAuthStore } from '@/store/auth.store';
import { Button, Select, Alert, Card, CardContent } from '@/components/ui';
import type { MarcaClinica } from '@/types/futuro.types';
import { Palette, Stethoscope, Check } from 'lucide-react';
import { cn } from '@/lib/utils';

const PALETA_COLORES = [
  { valor: '#0A4D68', nombre: 'Zafiro clínico (por defecto)' },
  { valor: '#088395', nombre: 'Cian clínico' },
  { valor: '#15803D', nombre: 'Verde salud' },
  { valor: '#7C3AED', nombre: 'Violeta' },
  { valor: '#B45309', nombre: 'Ámbar' },
  { valor: '#BE123C', nombre: 'Rosa clínico' },
];

const OPCIONES_FUENTE = [
  { value: 'Plus Jakarta Sans', label: 'Plus Jakarta Sans (por defecto)' },
  { value: 'Poppins', label: 'Poppins' },
  { value: 'Merriweather', label: 'Merriweather (serif)' },
];

const MENSAJE_ERROR_CARGA =
  'No fue posible cargar la configuración de marca. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar los cambios de marca. Revisa la conexión con el servicio e inténtalo de nuevo.';

export function MarcaClinicaPage() {
  // Organización dueña de la configuración: sale del claim `tenantId` de la sesión y nunca se
  // rellena con un valor por defecto (plan sección 2, guardarraíl 4).
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  const ruta = organizacionId ? `/organizaciones/${organizacionId}/marca` : null;

  const [colorPrimario, setColorPrimario] = useState('#0A4D68');
  const [fuenteTitulos, setFuenteTitulos] = useState('Plus Jakarta Sans');
  const [isLoading, setIsLoading] = useState(() => ruta !== null);
  const [loadError, setLoadError] = useState<string | null>(() => (ruta ? null : MENSAJE_ERROR_CARGA));
  const [isSaving, setIsSaving] = useState(false);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    if (!ruta) return;
    api
      .get<MarcaClinica>(ruta)
      .then((res) => {
        setColorPrimario(res.data.color_primario);
        setFuenteTitulos(res.data.fuente_titulos);
      })
      .catch((err) => {
        console.error('Error al cargar la marca de la clínica:', resumenDeErrorHttp(err));
        setLoadError(MENSAJE_ERROR_CARGA);
      })
      .finally(() => setIsLoading(false));
  }, [ruta]);

  const handleGuardar = async () => {
    if (!ruta) return;
    try {
      setIsSaving(true);
      setSaveError(null);
      await api.put<MarcaClinica>(ruta, {
        color_primario: colorPrimario,
        fuente_titulos: fuenteTitulos,
      });
      setSaveNotice('Los cambios de marca se guardaron correctamente.');
      setTimeout(() => setSaveNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar la marca de la clínica:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
          Marca de la clínica
        </h1>
        <p className="text-xs text-[#64748B] mt-1">
          Personaliza el color y la tipografía que verán tus pacientes en la página pública de tu
          clínica.
        </p>
      </div>

      {loadError && (
        <Alert variant="error" title="Error">
          {loadError}
        </Alert>
      )}
      {saveNotice && <Alert variant="success">{saveNotice}</Alert>}
      {saveError && (
        <Alert variant="error" title="Error">
          {saveError}
        </Alert>
      )}

      {isLoading ? (
        <p className="text-sm text-[#64748B]">Cargando configuración...</p>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <Card>
            <CardContent className="p-6 space-y-6">
              <div>
                <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-3">
                  <Palette className="w-3.5 h-3.5" /> Color primario
                </p>
                <div className="grid grid-cols-3 gap-2">
                  {PALETA_COLORES.map((opcion) => (
                    <button
                      key={opcion.valor}
                      type="button"
                      onClick={() => setColorPrimario(opcion.valor)}
                      title={opcion.nombre}
                      className={cn(
                        'h-12 rounded-lg border-2 flex items-center justify-center transition-all cursor-pointer',
                        colorPrimario === opcion.valor
                          ? 'border-[#0B1C30] shadow-sm'
                          : 'border-transparent hover:border-[#CBD5E1]',
                      )}
                      style={{ backgroundColor: opcion.valor }}
                    >
                      {colorPrimario === opcion.valor && <Check className="w-4 h-4 text-white" />}
                    </button>
                  ))}
                </div>
                <label className="flex items-center gap-2 mt-3 text-xs text-[#64748B]">
                  Color personalizado
                  <input
                    type="color"
                    value={colorPrimario}
                    onChange={(e) => setColorPrimario(e.target.value)}
                    className="h-8 w-14 rounded border border-[#CBD5E1] cursor-pointer"
                  />
                  <span className="font-mono text-[#0B1C30]">{colorPrimario}</span>
                </label>
              </div>

              <Select
                label="Tipografía de encabezados"
                value={fuenteTitulos}
                onChange={(e) => setFuenteTitulos(e.target.value)}
                options={OPCIONES_FUENTE}
              />

              <div className="flex justify-end pt-2 border-t border-[#F1F5F9]">
                <Button variant="primary" isLoading={isSaving} onClick={handleGuardar}>
                  Guardar cambios
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* ─────────────────────────────────────────
              VISTA PREVIA
              ───────────────────────────────────────── */}
          <Card>
            <CardContent className="p-0 overflow-hidden">
              <div
                className="py-10 text-center text-white"
                style={{ backgroundColor: colorPrimario }}
              >
                <div
                  className="w-12 h-12 rounded-2xl bg-white/10 flex items-center justify-center mx-auto mb-4"
                  style={{ color: '#FFFFFF' }}
                >
                  <Stethoscope className="w-6 h-6" />
                </div>
                <h2
                  className="font-bold text-2xl"
                  style={{ fontFamily: `'${fuenteTitulos}', sans-serif` }}
                >
                  Tu Clínica
                </h2>
                <p className="mt-2 text-sm text-white/80">
                  Así se verá el encabezado de tu página pública.
                </p>
              </div>
              <div className="p-4 text-xs text-[#64748B]">
                Vista previa en vivo — los cambios se aplican al guardar.
              </div>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}
