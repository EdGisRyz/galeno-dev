package com.intoglobe.clinicas.exception;

/**
 * Excepción de negocio para una cuenta con rol {@code MEDICO} que no indica su médico.
 *
 * <p>Materializa <b>R4</b>: si el rol es {@code MEDICO}, {@code medicoId} es obligatorio. Un
 * profesional sin su fila de médico no tiene agenda ni puede aparecer en el directorio, y la
 * base lo impide con el CHECK {@code usuario_rol_medico_coherente_check}. El servicio lo
 * comprueba antes de guardar para responder 400 —la card lo trata como un campo faltante— en
 * lugar de dejar que el CHECK llegue al cliente con forma de 500.</p>
 *
 * <p>No se expresa con Bean Validation porque la obligación depende del valor de otro campo del
 * mismo cuerpo: {@code @NotNull} sobre {@code medicoId} rechazaría también a los roles que
 * legítimamente no atienden (R5). El proyecto ya resuelve así las reglas que dependen de más de
 * un campo, con una excepción de negocio propia.</p>
 *
 * <p>El caso del médico <em>ajeno o inexistente</em> no cae aquí: responde
 * {@link ResourceNotFoundException}, es decir 404, para no confirmar si ese médico existe en
 * otra organización.</p>
 */
public class MedicoRequeridoException extends RuntimeException {

    /** Construye la excepción para una cuenta {@code MEDICO} sin {@code medicoId}. */
    public MedicoRequeridoException() {
        super("El rol MEDICO exige un médico asociado: falta el campo medicoId. "
                + "Una cuenta de médico siempre tiene su fila de médico, porque sin ella no "
                + "tiene agenda ni aparece en el directorio");
    }
}
