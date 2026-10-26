package com.intoglobe.clinicas.exception;

import java.util.UUID;

/**
 * Excepción de negocio para recursos inexistentes.
 *
 * <p>El {@code GlobalExceptionHandler} la traduce a una respuesta 404 con
 * {@code ErrorResponse}. El mensaje es en español y no expone detalles internos.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Construye la excepción indicando el recurso y el identificador buscado.
     *
     * @param recurso nombre del recurso no encontrado (por ejemplo, {@code "Organizacion"})
     * @param id      identificador que se intentó localizar
     */
    public ResourceNotFoundException(String recurso, UUID id) {
        super("No se encontró el recurso " + recurso + " con id: " + id);
    }
}
