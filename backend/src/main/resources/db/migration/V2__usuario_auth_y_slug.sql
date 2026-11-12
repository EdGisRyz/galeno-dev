-- V2 · Usuario respaldado por el servicio de autenticación, rol de recepción y slug de organización
--
-- Contexto: las credenciales no viven en esta base. El servicio de autenticación guarda las
-- contraseñas y emite el token; aquí solo se conserva a qué usuario de ese servicio corresponde
-- cada fila, para poder atribuir la operación. Decisión de producto, 2026-11-03.
--
-- Requisitos de la migración:
--   1. Debe ejecutarse igual en PostgreSQL (runtime) y en H2 en modo PostgreSQL (tests), porque
--      los tests aplican Flyway sobre H2 y el esquema lo crea la migración, no Hibernate.
--   2. La tabla `usuario` debe estar vacía: `auth_user_id` es obligatorio y no existe ninguna
--      fuente de la que derivar un identificador para filas anteriores. La migración falla si hay
--      filas, que es el comportamiento correcto: inventar el dato sería peor que detenerse.
--      Verificado el 2026-11-11: 0 filas en desarrollo y en la base local del equipo.
--      En PostgreSQL el DDL es transaccional, así que un fallo revierte la migración completa.
--      En H2 no lo es: la sentencia que falla deja las anteriores aplicadas y hay que recrear el
--      esquema antes de reintentar. Por eso la precondición se comprueba antes de ejecutarla.

-- 1. Fuera la contraseña, dentro el identificador del servicio de autenticación.
ALTER TABLE usuario DROP COLUMN password_hash;

ALTER TABLE usuario ADD COLUMN auth_user_id UUID NOT NULL;

-- Único dentro de cada organización, no global: el mismo identificador no se repite en dos
-- clínicas, pero cada clínica conserva su propio espacio.
ALTER TABLE usuario ADD CONSTRAINT usuario_auth_user_id_key
    UNIQUE (organizacion_id, auth_user_id);

-- 2. El correo deja de ser único global.
-- El servicio de autenticación identifica a un usuario por (organización, sistema, correo), así que
-- el mismo correo puede existir en dos organizaciones como dos cuentas independientes. Un único
-- global lo impediría sin motivo y obligaría a inventar correos distintos por clínica.
ALTER TABLE usuario DROP CONSTRAINT usuario_email_key;

ALTER TABLE usuario ADD CONSTRAINT usuario_correo_organizacion_key
    UNIQUE (organizacion_id, email);

-- 3. El rol admite al personal de recepción.
-- Cambio aditivo: no se retira ningún valor ni se migran filas. `PACIENTE` se conserva porque el
-- rol sigue existiendo aunque hoy no haya cuentas de ese tipo; `RECEPCIONISTA` es un rol de
-- personal, no una transformación del rol de paciente. Decisión de producto, 2026-11-03.
ALTER TABLE usuario DROP CONSTRAINT usuario_rol_check;

ALTER TABLE usuario ADD CONSTRAINT usuario_rol_check
    CHECK (rol IN ('ADMIN', 'MEDICO', 'PACIENTE', 'RECEPCIONISTA'));

-- 4. Slug de la organización.
-- El servicio de autenticación emite el identificador de la organización como texto (slug), no como
-- UUID. La aplicación sigue trabajando con el UUID y resuelve el slug contra esta columna en el
-- filtro de entrada, de modo que ninguna de las rutas ni de las pruebas existentes cambia.
--
-- La columna queda nulable a propósito: rellenarla con un valor derivado del nombre produciría un
-- slug que no coincide con el del servicio de autenticación, y el filtro rechazaría un token
-- legítimo con un error imposible de diagnosticar. Mientras no haya integración real, el emisor de
-- desarrollo envía el UUID y esta columna queda en nulo sin afectar nada. Cuando la integración
-- exista, cada organización debe cargar su slug.
ALTER TABLE organizacion ADD COLUMN slug VARCHAR(60);

ALTER TABLE organizacion ADD CONSTRAINT organizacion_slug_key UNIQUE (slug);
