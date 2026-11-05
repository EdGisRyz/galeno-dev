package com.intoglobe.clinicas.exception;

import java.time.LocalTime;

/**
 * Excepción de negocio para un bloque de disponibilidad que se solapa con otro del mismo
 * médico y día.
 *
 * <p>Materializa una <b>regla de negocio</b> que depende del estado de la base —compara
 * el bloque enviado con los ya agendados—, así que no es expresable con anotaciones del
 * DTO. El schema de V1 <em>no</em> declara ninguna restricción de exclusión sobre
 * {@code horario_medico}, de modo que la comprobación es de aplicación y la hace el
 * service consultando {@code HorarioMedicoRepository#contarSolapamientos} antes de
 * guardar, con intervalo semiabierto: dos bloques contiguos (09:00-10:00 y 10:00-11:00)
 * <em>no</em> se solapan y son válidos.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el service la lanza sin saber
 * nada de HTTP, y el {@code GlobalExceptionHandler} la traduce a un 400. El mensaje es en
 * español y solo repite el día —con la convención {@code 0=Domingo .. 6=Sábado}— y las
 * horas enviadas por el cliente; nunca expone qué bloque existente produjo el conflicto.</p>
 */
public class SolapamientoDeHorarioException extends RuntimeException {

    /**
     * Construye la excepción indicando el bloque rechazado.
     *
     * @param diaSemana  día de la semana del bloque, {@code 0=Domingo .. 6=Sábado}
     * @param horaInicio hora de inicio del bloque
     * @param horaFin    hora de fin del bloque
     */
    public SolapamientoDeHorarioException(short diaSemana, LocalTime horaInicio, LocalTime horaFin) {
        super("El bloque de " + horaInicio + " a " + horaFin + " del día " + diaSemana
                + " (0=Domingo, 6=Sábado) se solapa con otro bloque ya agendado de este médico");
    }
}
