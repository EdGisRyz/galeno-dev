package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para el intento de reactivar un médico cuyo consultorio principal está
 * dado de baja (S7).
 *
 * <p>Es la otra mitad de la invariante de S6: un médico activo tiene un consultorio principal
 * activo. Sin esta comprobación, el estado incoherente se colaría por el otro lado —se da de baja
 * el consultorio de un médico inactivo y después se reactiva al médico—, así que la reactivación
 * exige que su principal esté activo.</p>
 *
 * <p>El código es <b>409</b> y no 400: la petición está bien formada y el médico existe; lo que
 * choca es el estado del consultorio. El mensaje indica el camino —reactivar primero el
 * consultorio—, porque un conflicto genérico dejaría al administrador sin saber qué hacer antes de
 * reintentar. No repite identificadores ni nombres.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a {@code 409}.</p>
 */
public class MedicoConConsultorioPrincipalInactivoException extends RuntimeException {

    private MedicoConConsultorioPrincipalInactivoException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de reactivar un médico cuyo consultorio principal
     * está dado de baja.
     *
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static MedicoConConsultorioPrincipalInactivoException porTenerElPrincipalDadoDeBaja() {
        return new MedicoConConsultorioPrincipalInactivoException(
                "No se puede reactivar este médico: su consultorio principal está dado de baja. "
                        + "Reactiva primero el consultorio y después al médico");
    }
}
