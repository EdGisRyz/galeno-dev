-- V5 · Ajustes del horario médico
--
-- Lo que el módulo de horarios dejó anotado como pendiente. Nada de esto cambia columnas existentes
-- que el código ya mapee, así que la aplicación sigue arrancando sin tocar entidades.

-- PostgreSQL no crea índices por las claves foráneas, y el listado de la agenda consulta por médico.
CREATE INDEX IF NOT EXISTS idx_horario_medico_medico ON horario_medico (medico_id);

ALTER TABLE horario_medico ADD COLUMN created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL;

-- Un bloque invertido no puede existir, ni siquiera escribiendo por fuera de la API. Sin esta
-- restricción, un rango invertido no se superpone con ningún otro bloque y desactivaría la
-- validación de solapamientos, que es la regla siguiente en importancia.
ALTER TABLE horario_medico ADD CONSTRAINT horario_medico_rango_check
    CHECK (hora_fin > hora_inicio);

-- La convención de días se deducía del código. Queda en el esquema, donde se consulta.
COMMENT ON COLUMN horario_medico.dia_semana
    IS 'Convención de Date.getDay(): 0 = domingo, 1 = lunes, ... 6 = sábado';

-- Queda pendiente el rechazo de solapamientos en la base (`EXCLUDE USING gist` con `btree_gist`).
-- No entra acá porque H2 no lo soporta y la suite aplica las migraciones sobre H2: exige cambiar la
-- integración continua a PostgreSQL real antes de poder escribirlo.
