package com.intoglobe.clinicas.exception;

import java.util.UUID;

/**
 * Excepción de negocio para la operación que dejaría a la organización sin ninguna cuenta
 * {@code ADMIN} activa.
 *
 * <p>Materializa <b>R8</b>: no se puede dar de baja —ni retirarle el rol— a la última cuenta
 * administradora activa de la organización. Sin ella nadie podría administrar cuentas desde la
 * interfaz y la organización quedaría inmanejable, con la única salida de tocar la base a mano.
 * La regla protege a la organización, no a la persona: cualquier administradora puede irse en
 * cuanto haya otra activa.</p>
 *
 * <p>El código es <b>409</b> y no 400 porque la petición está bien formada: lo que choca es el
 * estado actual de la organización. El mensaje explica el motivo —que se trata de la última
 * administradora y qué hacer antes— porque un 409 genérico dejaría al administrador sin saber
 * por qué su operación no se aplicó ni cómo desbloquearse.</p>
 *
 * <p>Hay dos formas de llegar aquí y el mensaje las distingue, porque la acción que las
 * desbloquea es distinta: {@link #alDarDeBaja(UUID)} y {@link #alCambiarElRol(UUID)}.</p>
 */
public class UltimaAdministradoraException extends RuntimeException {

    private static final String MOTIVO = "es la última cuenta con rol ADMIN activa de la "
            + "organización. Asigná el rol ADMIN a otra cuenta antes de continuar: sin ninguna "
            + "administradora activa nadie podría gestionar las cuentas de la organización";

    private UltimaAdministradoraException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de baja lógica de la última administradora.
     *
     * @param cuentaId identificador de la cuenta que se intentó dar de baja
     * @return la excepción lista para lanzar
     */
    public static UltimaAdministradoraException alDarDeBaja(UUID cuentaId) {
        return new UltimaAdministradoraException(
                "No se puede dar de baja la cuenta " + cuentaId + ": " + MOTIVO);
    }

    /**
     * Construye la excepción para el intento de retirarle el rol {@code ADMIN} a la última
     * administradora activa.
     *
     * @param cuentaId identificador de la cuenta a la que se intentó cambiar el rol
     * @return la excepción lista para lanzar
     */
    public static UltimaAdministradoraException alCambiarElRol(UUID cuentaId) {
        return new UltimaAdministradoraException(
                "No se puede cambiar el rol de la cuenta " + cuentaId + ": " + MOTIVO);
    }
}
