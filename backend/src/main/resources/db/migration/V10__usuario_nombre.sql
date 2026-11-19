-- V10 · Nombre y teléfono del trabajador, que el registro público y el alta administrativa piden
--
-- El formulario de la landing ya pedía el nombre de la persona que administra la clínica, y hasta
-- ahora no había dónde guardarlo: la tabla `usuario` solo tenía el correo, que es el identificador de
-- acceso, no el nombre. Aceptarlo y descartarlo en silencio es la pérdida de datos que este proyecto
-- ya sufrió con la ficha del paciente, así que la columna entra (S2 quinquies, T7).
--
-- El teléfono es el contacto del trabajador, con el mismo tipo que ya usan `medico` y `paciente`. El
-- registro público no lo pide —su formulario no tiene ese campo— y por eso las cuentas que crea el
-- onboarding nacen sin teléfono; la columna queda lista para el alta administrativa de cuentas, que
-- es la tarea siguiente.
--
-- Las DOS columnas son NULABLES a propósito:
--   * las cuentas que ya existen no tienen nombre ni teléfono, y no hay de dónde derivarlos;
--   * una fila sin ellos sigue siendo válida: el sistema funciona sin nombre y sin teléfono, porque
--     ninguno identifica a la cuenta —ése es el correo— y ninguno participa en una unicidad.
-- Que el alta nueva exija el nombre es una regla del contrato de entrada (el DTO), no de la tabla:
-- una columna NOT NULL obligaría a inventar un valor para cada fila anterior o para cada alta que
-- todavía no lo pide, y el dato inventado es peor que el dato ausente.
--
-- El máximo de 120 es el mismo que el nombre de una organización y el de un local, y el que el
-- formulario valida en el cliente. El de 20 es el de los teléfonos de médico y de paciente.
--
-- Sobre el plan `ENTERPRISE`: no se toca nada. El CHECK `organizacion_plan_check` de la V1 ya declara
-- `plan IN ('SOLO', 'CLINICA', 'ENTERPRISE')`, así que el valor que la interfaz ofrece existe en el
-- esquema desde el principio. Reemplazar el CHECK aquí no agregaría nada y sí reescribiría una
-- restricción que ya está en uso; se deja constancia en vez de migrar por migrar.
--
-- Va en una migración nueva y no dentro de la V1 a propósito: la V1 ya corrió en las bases locales y
-- Flyway guarda la suma de verificación de cada migración aplicada, así que cambiar su contenido
-- haría fallar el arranque de cualquier base existente. ADD COLUMN sin NOT NULL es aditivo y no
-- bloquea las filas existentes, en PostgreSQL y en H2 en modo PostgreSQL.
ALTER TABLE usuario ADD COLUMN nombre VARCHAR(120);

ALTER TABLE usuario ADD COLUMN telefono VARCHAR(20);

COMMENT ON COLUMN usuario.nombre
    IS 'Nombre de la persona; nulo en las cuentas anteriores a la V10 y obligatorio en las altas nuevas';

COMMENT ON COLUMN usuario.telefono
    IS 'Teléfono de contacto del trabajador; opcional y nulo en las cuentas anteriores a la V10';
