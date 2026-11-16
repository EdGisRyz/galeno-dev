package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para un correo ya ocupado dentro de la organización.
 *
 * <p>Materializa una <b>regla de negocio</b>: el correo es único dentro de la organización
 * (R2). La restricción vive en la base ({@code usuario_organizacion_id_email_key} sobre la
 * pareja organización-correo), pero el servicio la comprueba antes de guardar para que el
 * cliente reciba un 409 con un mensaje entendible en lugar del error de integridad de la base,
 * que llegaría como 500. La comprobación previa no sustituye al constraint: dos peticiones
 * simultáneas pueden pasar la comprobación y la restricción sigue siendo la última palabra.</p>
 *
 * <p>El mismo correo puede existir en otra organización: son dos cuentas independientes, con
 * contraseñas independientes en el servicio de identidad. Por eso la consulta previa filtra
 * siempre por organización.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el service la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a un 409. El mensaje es en español y solo
 * repite el correo que el propio cliente acaba de enviar; no revela datos de la cuenta que lo
 * ocupa.</p>
 */
public class CorreoDuplicadoEnOrganizacionException extends RuntimeException {

    /**
     * Construye la excepción indicando el correo rechazado.
     *
     * @param email correo normalizado a minúsculas que ya está en uso en la organización
     */
    public CorreoDuplicadoEnOrganizacionException(String email) {
        super("Ya existe una cuenta con el correo " + email + " en esta organización. "
                + "El correo es único dentro de la organización, aunque el mismo correo pueda "
                + "tener cuentas independientes en otras organizaciones");
    }
}
