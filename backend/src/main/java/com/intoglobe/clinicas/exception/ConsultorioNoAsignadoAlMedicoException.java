package com.intoglobe.clinicas.exception;

import java.util.UUID;

/**
 * Excepción de negocio para el intento de crear o mover un bloque de horario a un consultorio
 * que no está asignado al médico.
 *
 * <p>Materializa la regla del alcance A2: <strong>primero asignar, después agendar</strong>. El
 * administrador decide en qué consultorios puede atender un médico y el bloque elige uno de
 * ellos; elegir otro dejaría la agenda mintiendo sobre dónde se atiende, y la base lo rechazaría
 * con la clave {@code horario_medico_consultorio_asignado_fkey} de la migración V6.</p>
 *
 * <p>El código es <b>409</b> y no 400 porque la petición está bien formada: el consultorio puede
 * existir en la organización y el bloque puede ser válido; lo que choca es el estado actual de
 * la asignación del médico. Tampoco es un 404: el recurso existe, lo que falta es el vínculo. El
 * mensaje dice <em>qué</em> hacer —asignar ese consultorio al médico— porque un conflicto
 * genérico dejaría al usuario sin saber cómo continuar.</p>
 *
 * <p>Solo repite identificadores que el cliente ya conoce; no expone datos del médico, del
 * consultorio ni de sus pacientes. La excepción representa la regla, no el protocolo: el
 * servicio la lanza sin saber nada de HTTP y el {@code GlobalExceptionHandler} la traduce a
 * {@code 409}.</p>
 */
public class ConsultorioNoAsignadoAlMedicoException extends RuntimeException {

    private ConsultorioNoAsignadoAlMedicoException(String mensaje) {
        super(mensaje);
    }

    /**
     * Construye la excepción para el intento de usar en un bloque un consultorio que no está
     * asignado al médico.
     *
     * @param medicoId      médico al que se le quiere asignar el bloque
     * @param consultorioId consultorio que no está asignado a ese médico
     * @return la excepción lista para lanzar
     */
    public static ConsultorioNoAsignadoAlMedicoException porNoEstarAsignado(UUID medicoId,
            UUID consultorioId) {
        return new ConsultorioNoAsignadoAlMedicoException(
                "El consultorio " + consultorioId + " no está asignado al médico " + medicoId
                        + ": primero hay que asignarlo al médico y después crear o mover el bloque"
                        + " de horario a ese consultorio");
    }
}
