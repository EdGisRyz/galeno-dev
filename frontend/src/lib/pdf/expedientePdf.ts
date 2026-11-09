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

function identificacionMedico(medico: Medico | undefined): string {
  if (!medico) return 'Médico no identificado';
  const especialidad = medico.especialidad ? ` · ${medico.especialidad}` : '';
  const cedula = medico.cedula ? ` · Céd. Prof. ${medico.cedula}` : '';
  return `${medico.nombre}${especialidad}${cedula}`;
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
}

/** Genera y descarga la receta de una prescripción individual (NOM-004: contenido mínimo). */
export function descargarRecetaPdf({ organizacion, medico, paciente, prescripcion }: DatosReceta) {
  const doc = new jsPDF();
  encabezadoEstablecimiento(doc, organizacion, 'Receta médica');

  let y = 40;
  doc.setFontSize(10);
  doc.text(`Paciente: ${paciente.nombre}`, MARGEN, y);
  y += 6;
  if (paciente.fechaNacimiento) {
    doc.text(`Fecha de nacimiento: ${paciente.fechaNacimiento}`, MARGEN, y);
    y += 6;
  }
  doc.text(`Fecha de emisión: ${new Date().toLocaleDateString('es-MX')}`, MARGEN, y);
  y += 10;

  doc.setDrawColor(226, 232, 240);
  doc.line(MARGEN, y, MARGEN + ANCHO_UTIL, y);
  y += 8;

  doc.setFontSize(12);
  doc.setFont('helvetica', 'bold');
  doc.text(prescripcion.medicamento, MARGEN, y);
  if (prescripcion.nombre_comercial) {
    // El ancho se mide con la fuente aún en negritas/12pt: medirlo después de cambiar a
    // cursiva/10pt subestima el espacio real que ocupó "medicamento" y monta el texto encima.
    const anchoMedicamento = doc.getTextWidth(prescripcion.medicamento);
    doc.setFont('helvetica', 'italic');
    doc.setFontSize(10);
    doc.text(`(${prescripcion.nombre_comercial})`, MARGEN + anchoMedicamento + 3, y);
  }
  y += 8;

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(10);
  doc.text(`Dosis: ${prescripcion.dosis}`, MARGEN, y);
  y += 6;
  if (prescripcion.via_administracion) {
    doc.text(`Vía de administración: ${prescripcion.via_administracion}`, MARGEN, y);
    y += 6;
  }
  doc.text(`Frecuencia: ${prescripcion.frecuencia}`, MARGEN, y);
  y += 6;
  doc.text(`Duración del tratamiento: ${prescripcion.duracion}`, MARGEN, y);
  y += 6;

  if (prescripcion.instrucciones) {
    escribirParrafo(doc, 'Instrucciones', prescripcion.instrucciones, y + 4);
  }

  // Firma e identificación del médico (NOM-004): nunca al pie como texto suelto, siempre sobre
  // una línea de firma explícita.
  const yFirma = doc.internal.pageSize.getHeight() - 40;
  doc.setDrawColor(11, 28, 48);
  doc.line(MARGEN, yFirma, MARGEN + 80, yFirma);
  doc.setFontSize(9);
  doc.text('Firma del médico', MARGEN, yFirma + 5);
  doc.setFont('helvetica', 'bold');
  doc.text(identificacionMedico(medico), MARGEN, yFirma + 12);

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
        const texto = `${new Date(c.created_at).toLocaleDateString('es-MX')} (${identificacionMedico(medicosPorId.get(c.medico_id))}): ${c.texto}`;
        const lineas = doc.splitTextToSize(texto, ANCHO_UTIL);
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
