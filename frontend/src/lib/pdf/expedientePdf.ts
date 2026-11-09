// Generación de PDF del expediente clínico y de recetas individuales.
//
// NOM-004-SSA3-2012 exige que cada nota, receta o modificación identifique al personal de salud
// que la generó (nombre, cédula profesional y especialidad): por eso todo documento aquí recibe
// el `Medico` autor y lo imprime, nunca solo un `medico_id` opaco. El resto de NOM-004 (datos del
// establecimiento, paciente, fecha) y el formato de impresión de la receta también se cubren aquí;
// NOM-024-SSA3-2012 (interoperabilidad entre sistemas) queda fuera: es una norma de arquitectura,
// no de contenido de un documento, y no está resuelta por generar un PDF.

import { jsPDF } from 'jspdf';
import autoTable from 'jspdf-autotable';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Organizacion } from '@/features/organizaciones/organizaciones.types';
import type { Diagnostico } from '@/features/expediente/expediente.types';
import type { SoapNote, Prescription } from '@/types/futuro.types';

const MARGEN = 14;
const ANCHO_UTIL = 210 - MARGEN * 2; // A4 en mm

// jspdf-autotable tipa su parámetro `doc` como `any` (no expone `jsPDFDocument` real), así que
// `lastAutoTable` tampoco aparece en los tipos de `jsPDF`. Esta interfaz acota ese único campo en
// vez de recurrir a `any` en el resto del archivo.
interface JsPdfConUltimaTabla extends jsPDF {
  lastAutoTable?: { finalY: number };
}

function nombreYEspecialidad(medico: Medico | undefined): string {
  if (!medico) return 'Médico no identificado';
  return medico.especialidad ? `${medico.nombre} · ${medico.especialidad}` : medico.nombre;
}

function identificacionMedico(medico: Medico | undefined): string {
  if (!medico) return 'Médico no identificado';
  const cedula = medico.cedula ? ` · Céd. Prof. ${medico.cedula}` : '';
  return `${nombreYEspecialidad(medico)}${cedula}`;
}

function calcularEdad(fechaNacimiento?: string | null): string {
  if (!fechaNacimiento) return '—';
  const nacimiento = new Date(fechaNacimiento);
  const hoy = new Date();
  let edad = hoy.getFullYear() - nacimiento.getFullYear();
  const diasAunNoCumplidos =
    hoy.getMonth() < nacimiento.getMonth() ||
    (hoy.getMonth() === nacimiento.getMonth() && hoy.getDate() < nacimiento.getDate());
  if (diasAunNoCumplidos) edad -= 1;
  return `${edad} años`;
}

function encabezadoEstablecimiento(doc: jsPDF, organizacion: Organizacion, titulo: string) {
  doc.setFontSize(14);
  doc.setFont('helvetica', 'bold');
  doc.text(organizacion.nombre, MARGEN, 18);
  doc.setFontSize(10);
  doc.setFont('helvetica', 'normal');
  doc.text(titulo, MARGEN, 25);
  doc.setDrawColor(10, 77, 104);
  doc.line(MARGEN, 29, MARGEN + ANCHO_UTIL, 29);
}

/** Asegura espacio vertical en la página actual; si no alcanza, agrega una nueva y reinicia `y`. */
function asegurarEspacio(doc: jsPDF, y: number, necesario: number): number {
  const altoPagina = doc.internal.pageSize.getHeight();
  if (y + necesario > altoPagina - MARGEN) {
    doc.addPage();
    return MARGEN + 10;
  }
  return y;
}

function escribirParrafo(doc: jsPDF, label: string, texto: string, y: number): number {
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(9);
  doc.text(label, MARGEN, y);
  doc.setFont('helvetica', 'normal');
  const lineas = doc.splitTextToSize(texto || '—', ANCHO_UTIL);
  doc.text(lineas, MARGEN, y + 5);
  return y + 5 + lineas.length * 4.5;
}

export interface DatosReceta {
  organizacion: Organizacion;
  medico: Medico | undefined;
  paciente: Paciente;
  prescripcion: Prescription;
  /** Diagnósticos del paciente: se listan los activos en el campo "Diagnóstico" de la receta. */
  diagnosticos: Diagnostico[];
}

const COLOR_PRIMARIO: [number, number, number] = [10, 77, 104]; // #0A4D68
const COLOR_ACENTO: [number, number, number] = [6, 182, 212]; // #06B6D4
const COLOR_ACENTO_CLARO: [number, number, number] = [136, 189, 220]; // #88BDDC

/**
 * Genera y descarga la receta de una prescripción individual con formato de documento clínico
 * (encabezado del establecimiento, datos del paciente, cuerpo de la prescripción y firma del
 * médico — NOM-004: contenido mínimo). Horizontal para aprovechar el ancho en vez de dejar la
 * mitad inferior de una hoja vertical en blanco. No incluye franja de contacto
 * (teléfono/domicilio/redes): la organización real no tiene esos campos en el backend y no se
 * inventan (plan sección 2, guardarraíl 4).
 */
export function descargarRecetaPdf({
  organizacion,
  medico,
  paciente,
  prescripcion,
  diagnosticos,
}: DatosReceta) {
  const doc = new jsPDF({ orientation: 'landscape' });
  const anchoPagina = doc.internal.pageSize.getWidth();
  const anchoUtil = anchoPagina - MARGEN * 2;

  // Encabezado: franja de color con el establecimiento, el médico y su cédula.
  doc.setFillColor(...COLOR_PRIMARIO);
  doc.rect(0, 0, anchoPagina, 30, 'F');
  doc.setFillColor(...COLOR_ACENTO);
  doc.rect(0, 30, anchoPagina, 3, 'F');

  doc.setTextColor(255, 255, 255);
  doc.setFontSize(16);
  doc.setFont('helvetica', 'bold');
  doc.text(organizacion.nombre, MARGEN, 13);

  doc.setTextColor(...COLOR_ACENTO_CLARO);
  doc.setFontSize(11);
  doc.setFont('helvetica', 'normal');
  doc.text(nombreYEspecialidad(medico), MARGEN, 21);

  doc.setTextColor(255, 255, 255);
  doc.setFontSize(9);
  const textoCedula = `Cédula Profesional: ${medico?.cedula || '—'}`;
  doc.text(textoCedula, anchoPagina - MARGEN - doc.getTextWidth(textoCedula), 11);

  doc.setTextColor(0, 0, 0);

  // Datos del paciente: nunca se deja un campo en blanco esperando que alguien lo complete a
  // mano, se imprime lo que ya se conoce. El diagnóstico lista los diagnósticos ACTIVOS del
  // paciente (Prescription no tiene un vínculo directo a un diagnóstico puntual).
  let y = 44;
  const anchoCol = anchoUtil / 3;

  const campo = (label: string, valor: string, x: number, yPos: number) => {
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(10);
    doc.text(label, x, yPos);
    doc.setFont('helvetica', 'normal');
    doc.text(valor, x + doc.getTextWidth(label) + 3, yPos);
  };

  campo('Paciente:', paciente.nombre, MARGEN, y);
  campo('Edad:', calcularEdad(paciente.fechaNacimiento), MARGEN + anchoCol, y);
  campo('Fecha:', new Date().toLocaleDateString('es-MX'), MARGEN + anchoCol * 2, y);
  y += 8;

  const textoDiagnostico =
    diagnosticos.filter((d) => d.estado === 'ACTIVO').map((d) => d.descripcion).join(', ') || '—';
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(10);
  doc.text('Diagnóstico:', MARGEN, y);
  doc.setFont('helvetica', 'normal');
  doc.text(
    doc.splitTextToSize(textoDiagnostico, anchoUtil - doc.getTextWidth('Diagnóstico:') - 3),
    MARGEN + doc.getTextWidth('Diagnóstico:') + 3,
    y,
  );
  y += 10;

  doc.setDrawColor(203, 213, 225);
  doc.line(MARGEN, y, MARGEN + anchoUtil, y);
  y += 14;

  // Cuerpo de la receta: el espacio que en un talonario queda en blanco para escribir a mano.
  // "Rx" en texto plano: las fuentes estándar de jsPDF (WinAnsi) no incluyen el glifo ℞ y lo
  // sustituyen por un carácter de reemplazo.
  const SANGRIA = 14;
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(14);
  doc.text('Rx', MARGEN, y);
  doc.text(prescripcion.medicamento, MARGEN + SANGRIA, y);
  if (prescripcion.nombre_comercial) {
    const anchoMedicamento = doc.getTextWidth(prescripcion.medicamento);
    doc.setFont('helvetica', 'italic');
    doc.setFontSize(11);
    doc.text(`(${prescripcion.nombre_comercial})`, MARGEN + SANGRIA + anchoMedicamento + 3, y);
  }
  y += 10;

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(10);
  doc.text(`Dosis: ${prescripcion.dosis}`, MARGEN + SANGRIA, y);
  y += 6;
  if (prescripcion.via_administracion) {
    doc.text(`Vía de administración: ${prescripcion.via_administracion}`, MARGEN + SANGRIA, y);
    y += 6;
  }
  doc.text(`Frecuencia: ${prescripcion.frecuencia}`, MARGEN + SANGRIA, y);
  y += 6;
  doc.text(`Duración del tratamiento: ${prescripcion.duracion}`, MARGEN + SANGRIA, y);
  y += 6;

  if (prescripcion.instrucciones) {
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(9);
    doc.text('Instrucciones', MARGEN + SANGRIA, y + 4);
    doc.setFont('helvetica', 'normal');
    const lineas = doc.splitTextToSize(prescripcion.instrucciones, anchoUtil - SANGRIA);
    doc.text(lineas, MARGEN + SANGRIA, y + 9);
  }

  // Firma e identificación del médico (NOM-004): nunca al pie como texto suelto, siempre sobre
  // una línea de firma explícita.
  const yFirma = doc.internal.pageSize.getHeight() - 30;
  doc.setDrawColor(11, 28, 48);
  doc.line(MARGEN, yFirma, MARGEN + 80, yFirma);
  doc.setFontSize(9);
  doc.setFont('helvetica', 'normal');
  doc.text('Firma del médico', MARGEN, yFirma + 5);
  doc.setFont('helvetica', 'bold');
  doc.text(identificacionMedico(medico), MARGEN, yFirma + 12);

  // Franja inferior de cierre, puramente decorativa (sin datos de contacto inventados).
  doc.setFillColor(...COLOR_ACENTO_CLARO);
  const altoPagina = doc.internal.pageSize.getHeight();
  doc.rect(0, altoPagina - 8, anchoPagina, 8, 'F');

  doc.save(`receta-${paciente.nombre.replace(/\s+/g, '-')}-${prescripcion.id}.pdf`);
}

export interface DatosExpediente {
  organizacion: Organizacion;
  paciente: Paciente;
  medicosPorId: Map<string, Medico>;
  soapNotes: SoapNote[];
  diagnosticos: Diagnostico[];
  prescripciones: Prescription[];
}

/** Genera y descarga el expediente clínico completo de un paciente (notas, diagnósticos, Rx). */
export function descargarExpedientePdf({
  organizacion,
  paciente,
  medicosPorId,
  soapNotes,
  diagnosticos,
  prescripciones,
}: DatosExpediente) {
  const doc = new jsPDF();
  encabezadoEstablecimiento(doc, organizacion, 'Expediente clínico');

  let y = 40;
  doc.setFontSize(10);
  doc.text(`Paciente: ${paciente.nombre}`, MARGEN, y);
  y += 6;
  if (paciente.fechaNacimiento) {
    doc.text(`Fecha de nacimiento: ${paciente.fechaNacimiento}`, MARGEN, y);
    y += 6;
  }
  doc.text(`Generado: ${new Date().toLocaleString('es-MX')}`, MARGEN, y);
  y += 10;

  // ───────────────────────────── Notas SOAP
  doc.setFontSize(12);
  doc.setFont('helvetica', 'bold');
  doc.text('Notas de evolución (SOAP)', MARGEN, y);
  y += 7;

  if (soapNotes.length === 0) {
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(9);
    doc.text('Sin notas registradas.', MARGEN, y);
    y += 8;
  }

  for (const nota of soapNotes) {
    y = asegurarEspacio(doc, y, 40);
    doc.setFontSize(9);
    doc.setFont('helvetica', 'bold');
    doc.text(
      `${new Date(nota.created_at).toLocaleString('es-MX')} · ${nota.inmutable ? 'Cerrada' : 'Abierta'}`,
      MARGEN,
      y,
    );
    doc.setFont('helvetica', 'normal');
    doc.text(identificacionMedico(medicosPorId.get(nota.medico_id)), MARGEN + 90, y);
    y += 6;

    y = asegurarEspacio(doc, y, 20);
    y = escribirParrafo(doc, 'S · Subjetivo', nota.subjetivo, y);
    y = asegurarEspacio(doc, y, 20);
    y = escribirParrafo(doc, 'O · Objetivo', nota.objetivo, y);
    y = asegurarEspacio(doc, y, 20);
    y = escribirParrafo(doc, 'A · Análisis', nota.analisis, y);
    y = asegurarEspacio(doc, y, 20);
    y = escribirParrafo(doc, 'P · Plan', nota.plan, y);

    if (nota.correcciones && nota.correcciones.length > 0) {
      y = asegurarEspacio(doc, y, 10 + nota.correcciones.length * 5);
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(9);
      doc.text('Correcciones', MARGEN, y);
      y += 5;
      doc.setFont('helvetica', 'normal');
      for (const c of nota.correcciones) {
        const lineas = doc.splitTextToSize(`Aclaración: ${c.texto}`, ANCHO_UTIL);
        doc.text(lineas, MARGEN, y);
        y += lineas.length * 4.5;
      }
    }

    y += 6;
    doc.setDrawColor(226, 232, 240);
    doc.line(MARGEN, y, MARGEN + ANCHO_UTIL, y);
    y += 8;
  }

  // ───────────────────────────── Diagnósticos
  y = asegurarEspacio(doc, y, 20);
  doc.setFontSize(12);
  doc.setFont('helvetica', 'bold');
  doc.text('Diagnósticos', MARGEN, y);
  y += 4;

  autoTable(doc, {
    startY: y,
    margin: { left: MARGEN, right: MARGEN },
    head: [['CIE-10', 'Descripción', 'Tipo', 'Estado', 'Fecha']],
    body:
      diagnosticos.length > 0
        ? diagnosticos.map((d) => [
            d.codigo_cie10,
            d.descripcion,
            d.tipo,
            d.estado,
            d.fecha_diagnostico || '—',
          ])
        : [['—', 'Sin diagnósticos registrados.', '', '', '']],
    styles: { fontSize: 8 },
    headStyles: { fillColor: [10, 77, 104] },
  });

  // ───────────────────────────── Prescripciones
  const yTrasDiagnosticos = (doc as JsPdfConUltimaTabla).lastAutoTable!.finalY + 10;
  doc.setFontSize(12);
  doc.setFont('helvetica', 'bold');
  doc.text('Prescripciones', MARGEN, yTrasDiagnosticos);

  autoTable(doc, {
    startY: yTrasDiagnosticos + 4,
    margin: { left: MARGEN, right: MARGEN },
    head: [['Medicamento', 'Dosis', 'Frecuencia', 'Duración', 'Médico', 'Estado']],
    body:
      prescripciones.length > 0
        ? prescripciones.map((p) => [
            p.medicamento,
            p.dosis,
            p.frecuencia,
            p.duracion,
            identificacionMedico(medicosPorId.get(p.medico_id)),
            p.activo ? 'Activa' : 'Suspendida',
          ])
        : [['—', 'Sin prescripciones registradas.', '', '', '', '']],
    styles: { fontSize: 8 },
    headStyles: { fillColor: [10, 77, 104] },
  });

  doc.save(`expediente-${paciente.nombre.replace(/\s+/g, '-')}.pdf`);
}
