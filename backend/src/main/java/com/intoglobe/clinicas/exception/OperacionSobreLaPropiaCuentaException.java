package com.intoglobe.clinicas.exception;

import java.util.UUID;

/**
 * Excepción de negocio para la operación de una cuenta sobre sí misma.
 *
 * <p>Materializa <b>R11</b>: nadie puede darse de baja a sí mismo ni cambiarse el rol a sí mismo.
 * La regla evita que una administradora se deje fuera del sistema por un clic y deja la operación
 * en manos de otra cuenta con permisos; es la contraparte individual de R8, que protege a la
 * organización entera.</p>
 *
 * <p>El código es <b>403</b>: la petición está bien formada y el recurso existe, lo que falta es la
 * autorización para ejecutarla sobre la propia cuenta. No se usa {@code AccessDeniedException}
 * porque el traductor responde esa excepción con un mensaje genérico —a propósito, para no revelar
 * qué regla falló en las denegaciones de seguridad— y aquí el motivo sí debe llegar al cliente: sin
 * él, la persona no entiende por qué el botón no funciona ni que debe pedirle a otra cuenta que lo
 * haga.</p>
 *
 * <p>Las dos formas de llegar aquí tienen mensajes distintos —{@link #alDarDeBaja(UUID)} y
 * {@link #alCambiarElRol(UUID)}— porque la acción que desbloquea la operación es distinta.</p>
 */
public class OperacionSobreLaPropiaCuentaException extends RuntimeException {

    private static final String MOTIVO = "una cuenta no puede operar sobre sí misma: la baja y el "
            + "cambio de rol tienen que hacerlos otra cuenta con permisos, para que nadie se deje "
            + "fuera del sistema por error";

    private OperacionSobreLaPropiaCuentaException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de darse de baja a sí mismo.
     *
     * @param cuentaId identificador de la cuenta del actor, que coincide con la del path
     * @return la excepción lista para lanzar
     */
    public static OperacionSobreLaPropiaCuentaException alDarDeBaja(UUID cuentaId) {
        return new OperacionSobreLaPropiaCuentaException(
                "No puede dar de baja su propia cuenta (" + cuentaId + "): " + MOTIVO);
    }

    /**
     * Construye la excepción para el intento de cambiarse el rol a sí mismo.
     *
     * @param cuentaId identificador de la cuenta del actor, que coincide con la del path
     * @return la excepción lista para lanzar
     */
    public static OperacionSobreLaPropiaCuentaException alCambiarElRol(UUID cuentaId) {
        return new OperacionSobreLaPropiaCuentaException(
                "No puede cambiar el rol de su propia cuenta (" + cuentaId + "): " + MOTIVO);
    }
}
