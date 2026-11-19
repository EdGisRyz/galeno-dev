package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para el intento de dar de baja el consultorio principal de un médico
 * <strong>activo</strong> (S6).
 *
 * <p>Un médico activo tiene que tener un consultorio principal activo: es la referencia por
 * defecto de su agenda y de sus citas. Dar de baja ese consultorio sin tocar al médico deja el
 * estado incoherente sin que nadie lo note, porque la fila del médico sigue diciendo «activo»
 * mientras su agenda apunta a una sala cerrada. La única puerta que puede producir ese estado es
 * esta baja, así que se cierra aquí.</p>
 *
 * <p>El código es <b>409</b> y no 400: la petición está bien formada y el consultorio existe; lo
 * que choca es el estado actual del médico. El mensaje nombra el motivo y el camino —dar de baja
 * primero al médico—, porque un conflicto genérico dejaría al administrador sin saber qué hacer
 * antes de reintentar. No repite identificadores ni nombres: quien lo lee está en la fila del
 * consultorio que acaba de intentar dar de baja.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a {@code 409}.</p>
 */
public class ConsultorioPrincipalConMedicoActivoException extends RuntimeException {

    private ConsultorioPrincipalConMedicoActivoException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de dar de baja un consultorio que es el principal de
     * un médico activo.
     *
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static ConsultorioPrincipalConMedicoActivoException porSerElPrincipalDeUnMedicoActivo() {
        return new ConsultorioPrincipalConMedicoActivoException(
                "No se puede dar de baja este consultorio: es el consultorio principal de un "
                        + "médico activo, la referencia por defecto de su agenda. Da de baja "
                        + "primero al médico y después al consultorio");
    }
}
