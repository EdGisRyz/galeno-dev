package com.intoglobe.clinicas.exception;

import java.time.LocalTime;

/**
 * Excepción de negocio para un bloque de disponibilidad con el rango horario invertido
 * o vacío.
 *
 * <p>Materializa una <b>regla de negocio</b>: {@code horaInicio} debe ser estrictamente
 * anterior a {@code horaFin}. El schema de V1 <em>no</em> declara esa comprobación —no
 * hay CHECK que compare las dos columnas— y Bean Validation tampoco puede expresar una
 * comparación entre dos campos del mismo cuerpo sin infraestructura adicional, así que la
 * regla vive en el service. Un bloque de duración cero ({@code horaInicio == horaFin})
 * cae aquí a propósito: no contiene ninguna cita posible.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el service la lanza sin saber
 * nada de HTTP, y el {@code GlobalExceptionHandler} la traduce a un 400. El mensaje es en
 * español y solo repite las dos horas enviadas por el cliente, que no son datos
 * sensibles.</p>
 */
public class RangoHorarioInvalidoException extends RuntimeException {

    /**
     * Construye la excepción indicando el rango rechazado.
     *
     * @param horaInicio hora de inicio enviada en el cuerpo
     * @param horaFin    hora de fin enviada en el cuerpo
     */
    public RangoHorarioInvalidoException(LocalTime horaInicio, LocalTime horaFin) {
        super("El rango horario no es válido: la hora de inicio (" + horaInicio
                + ") debe ser estrictamente anterior a la hora de fin (" + horaFin + ")");
    }
}
