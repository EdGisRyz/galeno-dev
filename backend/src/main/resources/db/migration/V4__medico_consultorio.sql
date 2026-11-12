-- V4 · Varios consultorios por médico, y los pendientes del médico
--
-- Un médico puede atender en más de un consultorio, siempre de su misma organización. Esta
-- migración es la mitad de expansión del cambio: crea la relación nueva y copia los datos, pero
-- conserva `medico.consultorio_id`.
--
-- Por qué se conserva: la entidad `Medico` todavía mapea esa columna y la aplicación arranca con
-- `ddl-auto=validate`. Quitarla en esta misma migración dejaría el backend sin arrancar y rompería
-- las pruebas del módulo. Se retira en una migración posterior, junto con el cambio de la entidad,
-- el servicio y los contratos.

-- Habilita la referencia compuesta que garantiza la misma organización.
ALTER TABLE consultorio ADD CONSTRAINT consultorio_id_organizacion_key
    UNIQUE (id, organizacion_id);

CREATE TABLE IF NOT EXISTS medico_consultorio (
    medico_id       UUID                     NOT NULL,
    consultorio_id  UUID                     NOT NULL,
    organizacion_id UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    CONSTRAINT medico_consultorio_pkey PRIMARY KEY (medico_id, consultorio_id),
    -- Las dos claves comparten `organizacion_id`: si un médico y un consultorio fueran de clínicas
    -- distintas, ninguna de las dos referencias podría satisfacerse.
    CONSTRAINT medico_consultorio_medico_fkey
        FOREIGN KEY (medico_id, organizacion_id) REFERENCES medico (id, organizacion_id)
        ON DELETE CASCADE,
    CONSTRAINT medico_consultorio_consultorio_fkey
        FOREIGN KEY (consultorio_id, organizacion_id) REFERENCES consultorio (id, organizacion_id)
);

CREATE INDEX IF NOT EXISTS idx_medico_consultorio_consultorio
    ON medico_consultorio (consultorio_id);

-- Copia la relación anterior (un consultorio por médico). Si alguna fila quedó incoherente —un
-- médico con el consultorio de otra organización—, esta sentencia falla y lo señala:
--   SELECT m.id FROM medico m JOIN consultorio c ON c.id = m.consultorio_id
--    WHERE m.organizacion_id <> c.organizacion_id;
INSERT INTO medico_consultorio (medico_id, consultorio_id, organizacion_id)
SELECT id, consultorio_id, organizacion_id FROM medico;

-- Pendientes del médico.
ALTER TABLE medico ADD COLUMN created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL;

ALTER TABLE medico ADD COLUMN duracion_consulta_min SMALLINT;

-- Sin restricción a propósito: este valor solo prellena el formulario de un bloque nuevo. La
-- duración que importa es la del bloque (`horario_medico.duracion_consulta`), que sí está limitada
-- a 20, 30, 45 y 60 porque define el tamaño del hueco en la agenda. Un médico que en su práctica
-- trabaja con bloques de 25 minutos debería poder dejarlo como valor por defecto.

-- La cédula es única por organización y opcional: un médico puede registrarse sin ella. Los nulos
-- no colisionan entre sí, así que varios médicos pueden estar pendientes a la vez.
ALTER TABLE medico ADD CONSTRAINT medico_organizacion_cedula_key
    UNIQUE (organizacion_id, cedula);

-- Mientras la cédula no esté confirmada, queda marcada: los documentos que la exigen (recetas) se
-- bloquean hasta que se capture.
ALTER TABLE medico ADD COLUMN cedula_verificada BOOLEAN DEFAULT FALSE NOT NULL;
