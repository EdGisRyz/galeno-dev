package com.intoglobe.clinicas.exception;

import java.util.UUID;

/**
 * Excepción de negocio para el intento de reasignar el consultorio de un médico.
 *
 * <p>Materializa una <b>regla de negocio</b>: en el {@code PUT} de un médico, si el
 * cuerpo trae un {@code consultorioId} distinto del actual la operación se rechaza,
 * porque responder 200 sin aplicar lo que el cliente pidió mentiría sobre el
 * resultado. Mover a un médico de consultorio requeriría una operación de dominio
 * propia y su endpoint, fuera del alcance de este módulo.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el service la lanza sin
 * saber nada de HTTP, y el {@code GlobalExceptionHandler} la traduce a un 400. El
 * mensaje es en español y solo contiene identificadores ya públicos en la respuesta
 * del recurso: no expone datos personales del médico ni detalles internos.</p>
 */
public class CambioDeConsultorioNoPermitidoException extends RuntimeException {

    /**
     * Construye la excepción indicando el consultorio actual y el solicitado.
     *
     * @param consultorioActual    identificador del consultorio al que pertenece hoy
     *                             el médico
     * @param consultorioSolicitado identificador del consultorio enviado en el cuerpo
     */
    public CambioDeConsultorioNoPermitidoException(UUID consultorioActual, UUID consultorioSolicitado) {
        super("No se permite cambiar el consultorio del médico: el consultorio actual es "
                + consultorioActual + " y el solicitado es " + consultorioSolicitado
                + ". El consultorio de un médico no se modifica mediante la actualización");
    }
}
