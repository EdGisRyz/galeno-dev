-- V9 · La CURP vive en mayúsculas también en la base, no solo en el código
--
-- La V8 agregó la CURP a la ficha del paciente con UNIQUE (organizacion_id, curp), pero una
-- restricción única compara cadenas tal como están guardadas. Mientras la forma canónica solo se
-- aplicara en Paciente.normalizarCurp, una carga directa por SQL —una importación, un script de
-- datos, un cliente conectado a la base— podía insertar 'sasc850101mdfrrn01' junto a
-- 'SASC850101MDFRRN01' en la misma organización sin violar ninguna restricción, y la unicidad que
-- la V8 declara dejaba de ser cierta: dos filas para el mismo paciente.
--
-- Este CHECK cierra ese hueco (S3): la base no depende del código para una regla que ya declara.
-- El efecto aceptado es que esa carga ahora falla en lugar de duplicar en silencio.
--
-- Los valores nulos pasan el CHECK sin problema: una comparación con nulo no es falsa, así que los
-- pacientes sin CURP —urgencia, menor, extranjero— siguen registrándose igual que antes y siguen
-- conviviendo dentro de la organización.
--
-- Va en una migración nueva y no dentro de la V8 a propósito: la V8 ya corrió en las bases locales
-- y Flyway guarda la suma de verificación de cada migración aplicada, así que cambiar su contenido
-- haría fallar el arranque de cualquier base existente. UPPER() existe con la misma semántica en
-- PostgreSQL y en H2 en modo PostgreSQL, que son las dos bases que usa el proyecto.
ALTER TABLE paciente ADD CONSTRAINT paciente_curp_mayusculas_check
    CHECK (curp = upper(curp));

COMMENT ON COLUMN paciente.curp
    IS 'CURP del paciente en mayúsculas; nula si no se registró. Única dentro de la organización';
