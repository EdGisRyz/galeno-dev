package com.intoglobe.clinicas.model;

/**
 * Roles posibles de una cuenta de usuario.
 *
 * <p>Las constantes son espejo EXACTO del CHECK {@code usuario_rol_check} de la tabla
 * {@code usuario} —la migración V2 agregó {@code RECEPCIONISTA} de forma aditiva sobre el
 * CHECK de V1—. No se agregan valores aquí sin migrar primero dicho constraint, y no se
 * retira ninguno: {@code PACIENTE} existe en el esquema aunque hoy no tenga ninguna celda en
 * la matriz de autorización, y {@code RECEPCIONISTA} se admite como rol sin concederle
 * todavía ninguna operación.</p>
 *
 * <p>Es un enum y no un {@code String} porque el rol es un conjunto cerrado: con el tipo, un
 * JSON que traiga un rol desconocido no se puede deserializar y la petición responde 400
 * antes de tocar el servicio, en lugar de llegar a la base y terminar en un 500 por el CHECK.
 * El proyecto ya usa este patrón con {@link PlanTipo}.</p>
 */
public enum RolUsuario {

    /** Administra las cuentas de la organización. */
    ADMIN,

    /** Profesional que atiende; exige su fila de médico. */
    MEDICO,

    /** Paciente con acceso propio a sus datos. */
    PACIENTE,

    /** Personal de recepción. Admitido por el esquema, todavía sin operaciones concedidas. */
    RECEPCIONISTA
}
