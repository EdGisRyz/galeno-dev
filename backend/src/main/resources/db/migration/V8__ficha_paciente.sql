-- V8 · Ficha administrativa del paciente y unicidad de la CURP por organización
--
-- La ficha que el mostrador ya captura y que hasta ahora se descartaba en silencio: ocupación,
-- domicilio y los datos del responsable, más la CURP. Todos los campos son NULABLES porque el alta
-- de un paciente no puede exigirlos: atender una urgencia, a un menor o a un extranjero es el caso
-- diario de la recepción, y un NOT NULL aquí obligaría a inventar el dato para poder registrar a la
-- persona.
--
-- La CURP, en cambio, cuando existe es única dentro de la organización. La restricción vive en la
-- base y no solo en el servicio: dos recepcionistas registrando al mismo paciente a la vez pueden
-- pasar las dos la comprobación previa, y entonces la última palabra tiene que ser del esquema. Los
-- valores nulos no colisionan en una restricción única ni en PostgreSQL ni en H2, así que
-- varios pacientes sin CURP conviven en la misma organización sin necesidad de un índice parcial.
-- Y como la clave incluye `organizacion_id`, la misma CURP puede existir en dos clínicas: son dos
-- registros independientes, igual que el correo de las cuentas en la V2.

-- 1. Las seis columnas de la ficha. Los largos máximos acompañan al dato —la CURP tiene 18
-- posiciones por definición— y son los que la capa de entrada valida; la columna los replica para
-- que el límite no dependa solo del código.
ALTER TABLE paciente ADD COLUMN curp VARCHAR(18);
ALTER TABLE paciente ADD COLUMN ocupacion VARCHAR(120);
ALTER TABLE paciente ADD COLUMN domicilio VARCHAR(240);
ALTER TABLE paciente ADD COLUMN nombre_responsable VARCHAR(160);
ALTER TABLE paciente ADD COLUMN telefono_responsable VARCHAR(20);
ALTER TABLE paciente ADD COLUMN parentesco VARCHAR(40);

-- 2. La unicidad de la CURP dentro de la organización.
ALTER TABLE paciente ADD CONSTRAINT paciente_organizacion_id_curp_key
    UNIQUE (organizacion_id, curp);

-- 3. Documentación de las columnas, en el estilo de las migraciones anteriores.
COMMENT ON COLUMN paciente.curp
    IS 'CURP del paciente; nula si no se registró. Única dentro de la organización';
COMMENT ON COLUMN paciente.ocupacion IS 'Ocupación del paciente; nula si no se registró';
COMMENT ON COLUMN paciente.domicilio IS 'Domicilio del paciente; nulo si no se registró';
COMMENT ON COLUMN paciente.nombre_responsable IS 'Nombre del responsable del paciente; nulo si no se registró';
COMMENT ON COLUMN paciente.telefono_responsable IS 'Teléfono del responsable del paciente; nulo si no se registró';
COMMENT ON COLUMN paciente.parentesco
    IS 'Parentesco del responsable con el paciente (madre, tutor, ...); nulo si no se registró';
