package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para el intento de crear un bloque de horario —o de moverlo— a un
 * consultorio dado de baja (S11).
 *
 * <p>La agenda solo existe donde alguien puede atender: un bloque en una sala cerrada vuelve a
 * dejar citas apuntando a un consultorio inactivo, que es el estado que S6 impide al dar de baja
 * el consultorio principal de un médico activo. Es la misma invariante un escalón más abajo.</p>
 *
 * <p>El código es <b>409</b> y no 400: la petición está bien formada y el consultorio existe; lo
 * que choca es su estado. El mensaje indica el camino —reactivar el consultorio—, porque un
 * conflicto genérico dejaría al administrador sin saber qué hacer antes de reintentar. No repite
 * identificadores ni nombres.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a {@code 409}.</p>
 */
public class ConsultorioInactivoParaBloqueException extends RuntimeException {

    private ConsultorioInactivoParaBloqueException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de agendar un bloque en un consultorio que está dado
     * de baja.
     *
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static ConsultorioInactivoParaBloqueException porEstarDadoDeBaja() {
        return new ConsultorioInactivoParaBloqueException(
                "No se puede agendar un bloque en un consultorio dado de baja. Reactiva primero "
                        + "el consultorio y después crea o mueve el bloque");
    }
}
