-- V7 · El bloque de horario siempre dice en qué consultorio se dicta
--
-- Cierra lo que la V6 dejó abierto a propósito. La V6 agregó `organizacion_id` y
-- `consultorio_id` a `horario_medico` y las dejó NULABLES porque la entidad `HorarioMedico`
-- todavía no las mapeaba: con un NOT NULL, toda creación de bloque habría fallado. Era una
-- etapa, no el estado final —el mismo orden que se usó con `medico.consultorio_id` en la V4:
-- primero el esquema preparado, después el código, y recién entonces el cierre—.
--
-- Ahora el código escribe las dos columnas siempre: el servicio resuelve el consultorio
-- (el pedido, o el principal del médico si el request no lo trae), comprueba que esté
-- asignado y toma la organización del médico. La base puede exigirlas.

-- 1. Reponer asignaciones que falten antes de exigir la clave del punto 3.
-- La V6 ya lo hizo una vez, pero entre una migración y otra el alta de médicos por la API no
-- creaba la fila en `medico_consultorio` (era el hueco conocido). Sin este paso, un bloque que
-- apunte al consultorio principal de un médico sin asignación no podría satisfacer la clave.
INSERT INTO medico_consultorio (medico_id, consultorio_id, organizacion_id)
SELECT m.id, m.consultorio_id, m.organizacion_id
  FROM medico m
 WHERE NOT EXISTS (
     SELECT 1 FROM medico_consultorio mc
      WHERE mc.medico_id = m.id AND mc.consultorio_id = m.consultorio_id);

-- 2. Reponer los bloques que quedaron sin consultorio: el código anterior a esta rebanada
-- insertaba bloques sin consultorio, y su consultorio natural es el principal del médico, que
-- es además el que la V6 copió en los bloques que ya existían.
--
-- Las dos columnas se reponen en sentencias SEPARADAS, y cada una toca solo las filas donde su
-- propia columna es nula. Una sola sentencia con `WHERE organizacion_id IS NULL OR
-- consultorio_id IS NULL` arrastraría a las filas que solo tienen partida la organización y
-- sobrescribiría su consultorio: un bloque que ya se había movido al consultorio secundario
-- volvería al principal en silencio, que es precisamente lo que el alcance A2 no puede hacer.
UPDATE horario_medico SET
    organizacion_id = (SELECT m.organizacion_id FROM medico m WHERE m.id = horario_medico.medico_id)
 WHERE organizacion_id IS NULL;

UPDATE horario_medico SET
    consultorio_id = (SELECT m.consultorio_id FROM medico m WHERE m.id = horario_medico.medico_id)
 WHERE consultorio_id IS NULL;

-- 3. El cierre. Desde aquí un bloque no puede existir sin decir dónde se dicta, y las tres
-- claves de la V6 dejan de ser condicionales: se aplican a todas las filas.
--
-- Las claves de la V6 no se tocan: siguen siendo las mismas tres —médico y consultorio en la
-- misma organización, y el par (medico_id, consultorio_id) presente en `medico_consultorio`—.
-- Lo único que cambia es que ahora ninguna fila puede eludirlas con un NULL.
ALTER TABLE horario_medico ALTER COLUMN organizacion_id SET NOT NULL;
ALTER TABLE horario_medico ALTER COLUMN consultorio_id SET NOT NULL;

COMMENT ON COLUMN horario_medico.consultorio_id
    IS 'Consultorio donde se dicta el bloque; uno de los asignados al médico (medico_consultorio)';
