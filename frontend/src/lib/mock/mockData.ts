import type {
  Organization,
  Doctor,
  ClinicRoom,
  DoctorSchedule,
  Patient,
  PatientIdentification,
  Appointment,
  Payment,
  SoapNote,
  Diagnosis,
  Prescription,
  User,
} from '@/types/models';

export const mockOrganization: Organization = {
  id: 'org-001',
  nombre: 'Clínica Médica San Rafael',
  plan: 'CLINICA',
  activo: true,
  created_at: new Date('2026-01-01').toISOString(),
};

export const mockUsers: User[] = [
  {
    id: 'usr-admin-1',
    organizacion_id: 'org-001',
    email: 'admin@sanrafael.com',
    rol: 'ADMIN',
    activo: true,
    created_at: new Date('2026-01-01').toISOString(),
  },
  {
    id: 'usr-doc-1',
    organizacion_id: 'org-001',
    medico_id: 'med-001',
    email: 'dra.elena@sanrafael.com',
    rol: 'MEDICO',
    activo: true,
    created_at: new Date('2026-01-02').toISOString(),
  },
  {
    id: 'usr-doc-2',
    organizacion_id: 'org-001',
    medico_id: 'med-002',
    email: 'dr.roberto@sanrafael.com',
    rol: 'MEDICO',
    activo: true,
    created_at: new Date('2026-01-03').toISOString(),
  },
];

export const mockClinicRooms: ClinicRoom[] = [
  {
    id: 'room-101',
    organizacion_id: 'org-001',
    nombre: 'Consultorio 101 - Medicina General',
    direccion: 'Planta Baja, Pasillo Central',
    telefono: '951-501-2001',
    activo: true,
    created_at: new Date('2026-01-01').toISOString(),
  },
  {
    id: 'room-102',
    organizacion_id: 'org-001',
    nombre: 'Consultorio 102 - Pediatría y Nutrición',
    direccion: 'Planta Alta, Módulo B',
    telefono: '951-501-2002',
    activo: true,
    created_at: new Date('2026-01-01').toISOString(),
  },
];

export const mockDoctors: Doctor[] = [
  {
    id: 'med-001',
    organizacion_id: 'org-001',
    consultorio_id: 'room-101',
    nombre: 'Dra. Elena Ruiz Morales',
    especialidad: 'Medicina General',
    cedula: 'CED-10928374',
    telefono: '951-111-2233',
    tarifa_base: 500,
    duracion_consulta_min: 30,
    activo: true,
  },
  {
    id: 'med-002',
    organizacion_id: 'org-001',
    consultorio_id: 'room-102',
    nombre: 'Dr. Roberto Vargas Silva',
    especialidad: 'Pediatría y Control Infantil',
    cedula: 'CED-55667788',
    telefono: '951-444-5566',
    tarifa_base: 700,
    duracion_consulta_min: 45,
    activo: true,
  },
];

export const mockDoctorSchedules: DoctorSchedule[] = [
  // Dra. Elena (Lunes a Viernes 09:00 a 14:00)
  ...[1, 2, 3, 4, 5].map((dia) => ({
    id: `sch-elena-${dia}`,
    medico_id: 'med-001',
    dia_semana: dia,
    hora_inicio: '09:00:00',
    hora_fin: '14:00:00',
    duracion_consulta: 30 as const,
  })),
  // Dr. Roberto (Lunes, Miércoles, Viernes 10:00 a 16:00)
  ...[1, 3, 5].map((dia) => ({
    id: `sch-roberto-${dia}`,
    medico_id: 'med-002',
    dia_semana: dia,
    hora_inicio: '10:00:00',
    hora_fin: '16:00:00',
    duracion_consulta: 45 as const,
  })),
];

export const mockPatients: (Patient & { ficha?: PatientIdentification })[] = [
  {
    id: 'pac-001',
    organizacion_id: 'org-001',
    nombre: 'María del Carmen Sánchez Gómez',
    telefono: '951-234-5678',
    fecha_nacimiento: '1988-05-14',
    sexo: 'F',
    email: 'carmen.sanchez@email.com',
    notas: 'Alergia conocida a la Penicilina.',
    activo: true,
    created_at: new Date('2026-02-10').toISOString(),
    ficha: {
      id: 'ficha-001',
      paciente_id: 'pac-001',
      curp: 'SAGC880514MOCMRL09',
      ocupacion: 'Docente de Secundaria',
      domicilio_completo: 'Calle Benito Juárez 402, Centro, Oaxaca, Oax.',
      nombre_responsable: 'Carlos Sánchez (Hermano)',
      telefono_responsable: '951-987-6543',
    },
  },
  {
    id: 'pac-002',
    organizacion_id: 'org-001',
    nombre: 'Juan Carlos Pérez Hernández',
    telefono: '951-345-6789',
    fecha_nacimiento: '1975-11-22',
    sexo: 'M',
    email: 'jcperez@email.com',
    notas: 'Hipertensión arterial controlada.',
    activo: true,
    created_at: new Date('2026-02-15').toISOString(),
    ficha: {
      id: 'ficha-002',
      paciente_id: 'pac-002',
      curp: 'PEHJ751122HOCRN03',
      ocupacion: 'Contador Público',
      domicilio_completo: 'Av. Las Palmas 120, Col. Reforma, Oaxaca, Oax.',
      nombre_responsable: 'Ana Laura Gómez (Esposa)',
      telefono_responsable: '951-654-3210',
    },
  },
  {
    id: 'pac-003',
    organizacion_id: 'org-001',
    nombre: 'Mateo López Díaz',
    telefono: '951-456-7890',
    fecha_nacimiento: '2020-03-08',
    sexo: 'M',
    email: 'mama.mateo@email.com',
    notas: 'Paciente pediátrico en esquema de vacunación.',
    activo: true,
    created_at: new Date('2026-03-01').toISOString(),
    ficha: {
      id: 'ficha-003',
      paciente_id: 'pac-003',
      curp: 'LODM200308HOCPN01',
      ocupacion: 'Estudiante preescolar',
      domicilio_completo: 'Privada Los Pinos 14, Santa Rosa Panzacola, Oaxaca.',
      nombre_responsable: 'Lorena Díaz Ruiz (Madre)',
      telefono_responsable: '951-456-7890',
    },
  },
];

export const mockAppointments: Appointment[] = [
  {
    id: 'apt-001',
    organizacion_id: 'org-001',
    paciente_id: 'pac-001',
    medico_id: 'med-001',
    consultorio_id: 'room-101',
    fecha_hora: new Date(new Date().setHours(10, 0, 0, 0)).toISOString(),
    duracion_min: 30,
    estado: 'CONFIRMADA',
    motivo: 'Seguimiento de control de presión y cefalea intermitente',
    created_at: new Date('2026-03-10').toISOString(),
    paciente: mockPatients[0],
    medico: mockDoctors[0],
    consultorio: mockClinicRooms[0],
  },
  {
    id: 'apt-002',
    organizacion_id: 'org-001',
    paciente_id: 'pac-002',
    medico_id: 'med-001',
    consultorio_id: 'room-101',
    fecha_hora: new Date(new Date().setHours(11, 0, 0, 0)).toISOString(),
    duracion_min: 30,
    estado: 'SIN_CONFIRMAR',
    motivo: 'Revisión general y fatiga persistente',
    created_at: new Date('2026-03-11').toISOString(),
    paciente: mockPatients[1],
    medico: mockDoctors[0],
    consultorio: mockClinicRooms[0],
  },
  {
    id: 'apt-003',
    organizacion_id: 'org-001',
    paciente_id: 'pac-003',
    medico_id: 'med-002',
    consultorio_id: 'room-102',
    fecha_hora: new Date(new Date().setHours(12, 0, 0, 0)).toISOString(),
    duracion_min: 45,
    estado: 'CONFIRMADA',
    motivo: 'Control de crecimiento de los 6 años y certificado médico',
    created_at: new Date('2026-03-12').toISOString(),
    paciente: mockPatients[2],
    medico: mockDoctors[1],
    consultorio: mockClinicRooms[1],
  },
];

export const mockPayments: Payment[] = [
  {
    id: 'pay-001',
    organizacion_id: 'org-001',
    cita_id: 'apt-001',
    monto: 500,
    metodo: 'EFECTIVO',
    concepto: 'Consulta General - Dra. Elena Ruiz',
    estado: 'PAGADO',
    pagado_en: new Date().toISOString(),
    created_at: new Date().toISOString(),
  },
  {
    id: 'pay-002',
    organizacion_id: 'org-001',
    cita_id: 'apt-002',
    monto: 500,
    metodo: 'TRANSFERENCIA',
    concepto: 'Consulta General - Dra. Elena Ruiz',
    estado: 'PENDIENTE',
    created_at: new Date().toISOString(),
  },
];

export const mockSoapNotes: SoapNote[] = [
  {
    id: 'note-001',
    organizacion_id: 'org-001',
    paciente_id: 'pac-001',
    medico_id: 'med-001',
    cita_id: 'apt-001',
    subjetivo: 'Paciente femenina de 38 años refiere cefalea holocraneana de 3 días de evolución, EVA 6/10. Niega náusea.',
    objetivo: 'Paciente consciente, orientada. Signos vitales estables. Campos pulmonares limpios, ruidos cardíacos rítmicos.',
    signos_vitales: {
      temperatura: 36.6,
      presion: '120/80',
      fc: 74,
      fr: 18,
      peso: 62.5,
      talla: 1.63,
      imc: 23.52,
    },
    analisis: 'Cefalea tensional primaria secundaria a estrés laboral. Presión arterial dentro de parámetros normales.',
    plan: 'Se prescribe paracetamol 500mg vía oral cada 8h por 3 días. Medidas higiénico-dietéticas, hidratación abundante y reducción de pantallas.',
    inmutable: true,
    created_at: new Date(Date.now() - 86400000 * 5).toISOString(),
    correcciones: [
      {
        id: 'corr-001',
        nota_evolucion_id: 'note-001',
        medico_id: 'med-001',
        texto: 'Aclaración: Se indicó no exceder 3 gramos de paracetamol al día.',
        created_at: new Date(Date.now() - 86400000 * 4).toISOString(),
      },
    ],
  },
];

export const mockCie10Catalog = [
  { codigo: 'G44.2', descripcion: 'Cefalea debida a tensión' },
  { codigo: 'I10', descripcion: 'Hipertensión esencial (primaria)' },
  { codigo: 'E11.9', descripcion: 'Diabetes mellitus tipo 2 sin mención de complicación' },
  { codigo: 'J00', descripcion: 'Rinofaringitis aguda [resfriado común]' },
  { codigo: 'K29.7', descripcion: 'Gastritis, no especificada' },
  { codigo: 'J02.9', descripcion: 'Faringitis aguda, no especificada' },
  { codigo: 'Z00.0', descripcion: 'Examen médico general' },
];

export const mockDiagnoses: Diagnosis[] = [
  {
    id: 'diag-001',
    paciente_id: 'pac-001',
    codigo_cie10: 'G44.2',
    descripcion: 'Cefalea tensional primaria',
    descripcion_cie10: 'Cefalea debida a tensión',
    tipo: 'PRINCIPAL',
    estado: 'ACTIVO',
    fecha_diagnostico: '2026-03-01',
    created_at: new Date().toISOString(),
  },
];

export const mockPrescriptions: Prescription[] = [
  {
    id: 'rx-001',
    organizacion_id: 'org-001',
    paciente_id: 'pac-001',
    medico_id: 'med-001',
    medicamento: 'Paracetamol',
    nombre_comercial: 'Tempra',
    dosis: '500 mg',
    via_administracion: 'Oral',
    frecuencia: 'Cada 8 horas',
    duracion: '3 días',
    instrucciones: 'Tomar después de los alimentos. Suspender al ceder el dolor.',
    activo: true,
    created_at: new Date().toISOString(),
  },
];
