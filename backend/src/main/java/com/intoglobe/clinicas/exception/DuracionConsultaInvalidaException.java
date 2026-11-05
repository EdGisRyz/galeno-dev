package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para una duración de consulta fuera del conjunto permitido.
 *
 * <p>Materializa una <b>regla de negocio</b>: {@code duracionConsulta} solo admite
 * {@code {20, 30, 45, 60}} minutos, que son los tamaños de cita del catálogo. La tabla
 * {@code horario_medico} de V1 lo declara como CHECK
 * ({@code duracion_consulta IN (20, 30, 45, 60)}), pero el cliente debe recibir un 400 y
 * no un error de integridad con forma de 500, así que el service valida antes de
 * persistir.</p>
 *
 * <p>Existe porque Bean Validation no puede expresar un conjunto cerrado de valores sin
 * introducir nueva infraestructura de constraints personalizadas, que este slice no debe
 * crear: {@code @Min} y {@code @Max} describen un rango continuo, y un {@code @Pattern}
 * sobre un número obligaría a declarar el campo como texto.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el service la lanza sin saber
 * nada de HTTP, y el {@code GlobalExceptionHandler} la traduce a un 400. El mensaje es en
 * español y solo repite el número enviado por el cliente.</p>
 */
public class DuracionConsultaInvalidaException extends RuntimeException {

    /**
     * Construye la excepción indicando la duración rechazada.
     *
     * @param duracionConsulta duración en minutos enviada en el cuerpo
     */
    public DuracionConsultaInvalidaException(short duracionConsulta) {
        super("La duración de la consulta no es válida: " + duracionConsulta
                + " minutos. Las duraciones permitidas son 20, 30, 45 o 60 minutos");
    }
}
