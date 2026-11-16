package com.intoglobe.clinicas.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Clave compuesta de {@link MedicoConsultorio}: el par médico-consultorio.
 *
 * <p>La tabla {@code medico_consultorio} de la migración V4 declara
 * {@code PRIMARY KEY (medico_id, consultorio_id)}, así que la identidad de la fila es el par y
 * no un identificador propio. El par es además lo que garantiza la regla de negocio: un médico
 * no puede tener dos veces el mismo consultorio, porque no puede existir dos veces la misma
 * clave.</p>
 *
 * <p>Los dos valores son {@link UUID} y no entidades: la clave se compara por identificador, no
 * por el estado de las asociaciones. Por eso {@link #equals(Object)} y {@link #hashCode()} se
 * basan solo en las dos columnas y no en los objetos {@link Medico} y {@link Consultorio}, y la
 * clase es {@link Serializable} como exige JPA para una clave compuesta.</p>
 */
@Embeddable
public class MedicoConsultorioId implements Serializable {

    @Column(name = "medico_id", nullable = false, updatable = false)
    private UUID medicoId;

    @Column(name = "consultorio_id", nullable = false, updatable = false)
    private UUID consultorioId;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio.
     */
    protected MedicoConsultorioId() {
    }

    /**
     * Construye la clave del par médico-consultorio.
     *
     * @param medicoId      identificador del médico asignado
     * @param consultorioId identificador del consultorio asignado
     */
    public MedicoConsultorioId(UUID medicoId, UUID consultorioId) {
        this.medicoId = medicoId;
        this.consultorioId = consultorioId;
    }

    public UUID getMedicoId() {
        return medicoId;
    }

    public UUID getConsultorioId() {
        return consultorioId;
    }

    /**
     * Igualdad basada en las dos columnas de la clave compuesta.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MedicoConsultorioId otro)) {
            return false;
        }
        return Objects.equals(medicoId, otro.medicoId)
                && Objects.equals(consultorioId, otro.consultorioId);
    }

    /**
     * Hash coherente con {@link #equals(Object)}: solo las dos columnas de la clave.
     */
    @Override
    public int hashCode() {
        return Objects.hash(medicoId, consultorioId);
    }

    /**
     * Representación legible de la clave; no navega a las entidades.
     */
    @Override
    public String toString() {
        return "MedicoConsultorioId{medicoId=" + medicoId + ", consultorioId=" + consultorioId + '}';
    }
}
