package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para el intento de crear o modificar un bloque de horario de un médico
 * dado de baja (S10).
 *
 * <p>La agenda solo existe donde alguien puede atender. Con el médico inactivo, agregar
 * disponibilidad produce citas para quien no atiende, y mover un bloque es reescribir una agenda
 * que no está en uso. La regla cierra la misma invariante que S6 y S7 un escalón más abajo: no
 * basta con que el médico activo tenga consultorio activo; su agenda tampoco se edita si él no
 * está activo.</p>
 *
 * <p><strong>Borrar sí se permite</strong> y por eso no hay excepción para {@code DELETE}: hace
 * falta poder limpiar los datos de un médico que ya no atiende, y bloquearlo dejaría registros
 * imposibles de corregir.</p>
 *
 * <p>El código es <b>409</b> y no 400: la petición está bien formada y el médico existe; lo que
 * choca es su estado. El mensaje indica el camino —reactivar al médico—, porque un conflicto
 * genérico dejaría al administrador sin saber qué hacer antes de reintentar. No repite
 * identificadores ni nombres.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a {@code 409}.</p>
 */
public class AgendaDeMedicoInactivoException extends RuntimeException {

    private AgendaDeMedicoInactivoException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de crear o modificar un bloque de un médico que está
     * dado de baja.
     *
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static AgendaDeMedicoInactivoException porSerDeUnMedicoDadoDeBaja() {
        return new AgendaDeMedicoInactivoException(
                "No se puede crear ni modificar un bloque de la agenda de un médico dado de baja. "
                        + "Reactiva primero al médico y después edita sus horarios");
    }
}
