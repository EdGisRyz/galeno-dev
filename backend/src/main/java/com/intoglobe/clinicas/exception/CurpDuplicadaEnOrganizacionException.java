package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para una CURP ya ocupada dentro de la organización.
 *
 * <p>Materializa una <b>regla de negocio</b>: la CURP es opcional y, cuando existe, única dentro de
 * la organización. La restricción vive en la base ({@code paciente_organizacion_id_curp_key} sobre
 * la pareja organización-CURP, migración V8), pero el servicio la comprueba antes de guardar para
 * que el cliente reciba un 409 con un mensaje entendible en lugar del error de integridad de la
 * base, que llegaría como 500. La comprobación previa no sustituye a la restricción: dos altas
 * simultáneas pueden pasar las dos la comprobación y el esquema sigue siendo la última palabra.</p>
 *
 * <p>El mismo paciente puede estar registrado en otra organización: son dos registros
 * independientes, con historiales independientes. Por eso la consulta previa filtra siempre por
 * organización, y el mensaje lo dice para que el mostrador no intente «liberar» la CURP.</p>
 *
 * <p><strong>El mensaje nombra la salida.</strong> La baja de un paciente es lógica justamente para
 * que el registro se conserve, así que el caso más frecuente de este 409 es que la CURP pertenezca a
 * un paciente que ya estaba en la organización y fue dado de baja. Decir solo que la CURP existe
 * deja dos caminos aparentes —inventar otra CURP o dar de alta un duplicado— y ninguno es el
 * correcto: el mensaje pide reactivar a ese paciente.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de HTTP
 * y el {@code GlobalExceptionHandler} la traduce a un 409. El mensaje solo repite la CURP que el
 * propio cliente acaba de enviar; no revela datos del paciente que ocupa ese lugar.</p>
 */
public class CurpDuplicadaEnOrganizacionException extends RuntimeException {

    /**
     * Construye la excepción indicando la CURP rechazada.
     *
     * @param curp CURP canónica que ya está en uso en la organización
     */
    public CurpDuplicadaEnOrganizacionException(String curp) {
        super("Ya existe un paciente con la CURP " + curp + " en esta organización. "
                + "La CURP es única dentro de la organización, aunque el mismo paciente pueda "
                + "estar registrado en otras organizaciones. Si el paciente con esa CURP fue "
                + "dado de baja, hay que reactivarlo en lugar de registrarlo de nuevo");
    }
}
