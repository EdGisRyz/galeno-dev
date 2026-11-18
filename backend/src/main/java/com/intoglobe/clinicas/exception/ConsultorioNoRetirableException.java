package com.intoglobe.clinicas.exception;

import java.util.List;
import java.util.UUID;

/**
 * Excepción de negocio para el intento de retirar de un médico un consultorio que no se puede
 * retirar.
 *
 * <p>Materializa las <b>dos reglas del módulo de asignación</b> que la base no puede explicar
 * por sí sola:</p>
 * <ol>
 *   <li>el <strong>consultorio principal</strong> del médico ({@code medico.consultorio_id}) no
 *       se retira: es la referencia por defecto y la regla que impide cambiar el consultorio de
 *       un médico sigue vigente,</li>
 *   <li>un consultorio que todavía tiene <strong>bloques de horario</strong> del médico tampoco:
 *       la clave {@code horario_medico_consultorio_asignado_fkey} de la migración V6 lo
 *       rechazaría con un error de integridad, y el servicio lo detecta antes para responder un
 *       conflicto con motivo en lugar de un {@code 500}.</li>
 * </ol>
 *
 * <p>El código es <b>409</b> y no 400 porque la petición está bien formada: lo que choca es el
 * estado actual de la agenda del médico. El mensaje explica el motivo —y, en el caso de los
 * bloques, cuáles son— porque un conflicto genérico dejaría al administrador sin saber qué
 * tiene que cambiar antes de reintentar. Solo repite identificadores que el cliente ya conoce y
 * días y horas de la agenda: no expone datos personales del médico ni de sus pacientes.</p>
 *
 * <p>La excepción representa la regla, no el protocolo: el servicio la lanza sin saber nada de
 * HTTP y el {@code GlobalExceptionHandler} la traduce a {@code 409}. Las dos formas de llegar
 * aquí se distinguen con {@link #porSerElPrincipal()} y
 * {@link #porTenerBloquesDeHorario(List)}, porque la acción que desbloquea la
 * operación es distinta.</p>
 */
public class ConsultorioNoRetirableException extends RuntimeException {

    private ConsultorioNoRetirableException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de retirar el consultorio principal del médico.
     *
     * @param medicoId      médico al que se le intentó retirar el consultorio
     * @param consultorioId consultorio que es el principal del médico
     * @return la excepción lista para lanzar
     */
    /**
     * Explica que el consultorio pedido es el principal del médico.
     *
     * <p>El mensaje no nombra identificadores: quien lo lee está mirando la fila del consultorio
     * que acaba de intentar quitar, así que un UUID no agrega nada y vuelve ilegible el texto.</p>
     *
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static ConsultorioNoRetirableException porSerElPrincipal() {
        return new ConsultorioNoRetirableException(
                "No se puede quitar este consultorio: es el consultorio principal del médico, la "
                        + "referencia por defecto de su agenda y de sus pacientes. El consultorio "
                        + "principal no se retira");
    }

    /**
     * Construye la excepción para el intento de retirar un consultorio que todavía tiene bloques
     * de horario del médico.
     *
     * @param medicoId      médico al que se le intentó retirar el consultorio
     * @param consultorioId consultorio que tiene bloques de horario
     * @param bloques       descripción legible de cada bloque que lo impide, por ejemplo
     *                      {@code "lunes de 09:00 a 13:00"}; se enumeran en el mensaje
     *                      para que el administrador sepa cuáles mover antes de reintentar
     * @return la excepción lista para lanzar
     */
    /**
     * Explica que el consultorio todavía tiene bloques de horario del médico.
     *
     * <p>Nombra los bloques —día y rango— porque son lo que hay que mover para desbloquear la
     * operación, y omite los identificadores por la misma razón que
     * {@link #porSerElPrincipal()}: quien lee el mensaje ya está en la fila del consultorio.</p>
     *
     * @param bloques descripciones legibles de los bloques que impiden la baja
     * @return la excepción de negocio, que el handler traduce a 409
     */
    public static ConsultorioNoRetirableException porTenerBloquesDeHorario(List<String> bloques) {
        return new ConsultorioNoRetirableException(
                "No se puede quitar este consultorio: todavía tiene " + bloques.size()
                        + (bloques.size() == 1 ? " bloque de horario" : " bloques de horario")
                        + " del médico (" + String.join("; ", bloques)
                        + "). Cambia esos bloques a otro consultorio asignado o elimínalos antes de "
                        + "retirar este");
    }
}
