-- V6 · El bloque de horario pertenece a un consultorio
--
-- Un médico no atiende siempre en el mismo espacio: es habitual que use un consultorio la mayor
-- parte de la semana y otro un día. Hasta ahora el bloque de horario solo decía cuándo, no dónde,
-- así que la agenda no podía responder a qué consultorio va un paciente.
--
-- El consultorio del bloque se elige entre los **asignados** al médico (`medico_consultorio`), no
-- entre todos los de la organización. La asignación es una operación propia del administrador y
-- esta migración la deja exigible por la base.

-- 1. Columnas nuevas, nulables por ahora: las filas existentes todavía no las tienen.
ALTER TABLE horario_medico ADD COLUMN organizacion_id UUID;
ALTER TABLE horario_medico ADD COLUMN consultorio_id UUID;

-- 2. Reponer la asignación desde el consultorio principal del médico.
-- La tabla `medico_consultorio` se pobló una sola vez en la V4, pero su clave foránea al médico
-- tiene ON DELETE CASCADE: cualquier borrado y recarga de médicos (el script de demostración lo
-- hace) la deja vacía. Sin este paso, la clave del punto 4 no se podría crear sobre datos reales.
INSERT INTO medico_consultorio (medico_id, consultorio_id, organizacion_id)
SELECT m.id, m.consultorio_id, m.organizacion_id
  FROM medico m
 WHERE NOT EXISTS (
     SELECT 1 FROM medico_consultorio mc
      WHERE mc.medico_id = m.id AND mc.consultorio_id = m.consultorio_id);

-- 3. Copiar el dato a los bloques existentes: hoy todos apuntan al consultorio principal del
-- médico, que es la única relación que existía.
UPDATE horario_medico SET
    organizacion_id = (SELECT m.organizacion_id FROM medico m WHERE m.id = horario_medico.medico_id),
    consultorio_id  = (SELECT m.consultorio_id  FROM medico m WHERE m.id = horario_medico.medico_id);

-- 4. Claves que garantizan la coherencia de lo que sí se escriba.
--
-- Las dos columnas quedan NULABLES a propósito, y esto es una etapa, no el estado final: la entidad
-- `HorarioMedico` todavía no las mapea, así que el código actual inserta bloques sin consultorio y
-- un NOT NULL aquí rompería todas las creaciones. Mientras son nulables, las claves se aplican solo
-- a las filas que traen valor: un bloque con consultorio no puede mentir, y un bloque sin
-- consultorio sigue siendo válido hasta que el código lo escriba siempre.
--
-- Una migración posterior (V7) las pondrá NOT NULL cuando el servicio y los DTOs las exijan.
-- Es el mismo orden que se usó con `medico.consultorio_id` en la V4: primero el esquema preparado,
-- después el código, y recién entonces el cierre.

-- Las dos claves comparten `organizacion_id`: si el médico y el consultorio fueran de clínicas
-- distintas, ninguna referencia podría satisfacerse.
ALTER TABLE horario_medico ADD CONSTRAINT horario_medico_medico_organizacion_fkey
    FOREIGN KEY (medico_id, organizacion_id) REFERENCES medico (id, organizacion_id);

ALTER TABLE horario_medico ADD CONSTRAINT horario_medico_consultorio_organizacion_fkey
    FOREIGN KEY (consultorio_id, organizacion_id) REFERENCES consultorio (id, organizacion_id);

-- Y esta es la regla de la asignación, expresada en la base: un bloque solo puede apuntar a un
-- consultorio que esté asignado a ese médico. Sin ella, la operación de asignar sería decorativa.
ALTER TABLE horario_medico ADD CONSTRAINT horario_medico_consultorio_asignado_fkey
    FOREIGN KEY (medico_id, consultorio_id) REFERENCES medico_consultorio (medico_id, consultorio_id);
