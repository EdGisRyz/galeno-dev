-- V3 · Coherencia entre el usuario y su médico
--
-- El módulo de usuario necesita que el rol y la fila de médico no puedan contradecirse, y que un
-- usuario nunca apunte a un médico de otra organización. Hasta ahora eso solo podía vivir en el
-- servicio: la base aceptaba cualquier combinación, incluida la de un usuario de una clínica
-- asignado a un médico de otra.

-- Sin esta restricción no se puede referenciar la pareja (id, organización) desde otra tabla.
ALTER TABLE medico ADD CONSTRAINT medico_id_organizacion_key
    UNIQUE (id, organizacion_id);

-- Un usuario solo puede apuntar a un médico de su propia organización. La clave compuesta lo hace
-- imposible de violar incluso por SQL directo, que es lo que se pedía: que la regla viva en la base
-- y no solo en el servicio.
--
-- El orden de las columnas es posicional: (medico_id, organizacion_id) apunta a (id, organizacion_id).
ALTER TABLE usuario ADD CONSTRAINT usuario_medico_misma_organizacion_fkey
    FOREIGN KEY (medico_id, organizacion_id) REFERENCES medico (id, organizacion_id);

-- Un usuario con rol MEDICO siempre tiene su fila de médico: sin ella no puede tener agenda ni
-- aparecer en el directorio. Los demás roles pueden tenerla o no (un administrador que también
-- atiende), así que la restricción solo exige en una dirección.
--
-- Cuando `medico_id` es nulo la clave foránea anterior no se evalúa (semántica MATCH SIMPLE), que es
-- justo lo que hace falta para los usuarios sin consultorio.
ALTER TABLE usuario ADD CONSTRAINT usuario_rol_medico_coherente_check
    CHECK (rol <> 'MEDICO' OR medico_id IS NOT NULL);
