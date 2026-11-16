package com.intoglobe.clinicas.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Asignación de un consultorio a un médico. Espejo de la tabla {@code medico_consultorio} de la
 * migración V4, que hasta la operación de asignación no tenía escrituras en el código.
 *
 * <p>Qué representa: el conjunto de consultorios en los que un médico puede atender. El bloque
 * de horario elige uno de ellos ({@code horario_medico.consultorio_id}, migración V6) y por eso
 * la tabla deja de ser un dato histórico de la V4 y pasa a ser la fuente de esa elección.</p>
 *
 * <h2>Por qué la clave es el par y no un id propio</h2>
 *
 * <p>La tabla declara {@code PRIMARY KEY (medico_id, consultorio_id)}. La identidad del vínculo
 * es el par, de modo que asignar dos veces el mismo consultorio al mismo médico no es un
 * conflicto de negocio que haya que resolver con una consulta previa obligatoria: la base ya no
 * permite la segunda fila. El servicio, aun así, comprueba antes para responder {@code 204} de
 * forma idempotente en el camino normal.</p>
 *
 * <h2>Coherencia de organización</h2>
 *
 * <p>La tabla no guarda un {@code organizacion_id} redundante por comodidad: las dos claves
 * foráneas de V4 son compuestas y comparten esa columna
 * ({@code (medico_id, organizacion_id)} y {@code (consultorio_id, organizacion_id)}), así que un
 * vínculo entre un médico y un consultorio de clínicas distintas <strong>no puede
 * existir</strong>. La entidad mapea la columna para escribirla, y el servicio siempre la toma
 * del médico, nunca del cliente.</p>
 *
 * <h2>Sin baja lógica</h2>
 *
 * <p>La tabla no tiene columna {@code activo}: quitar una asignación es borrar la fila. La
 * migración V6 agrega una clave foránea desde {@code horario_medico} hacia este par, así que un
 * consultorio con bloques de horario del médico no puede retirarse mientras esos bloques
 * existan; el servicio lo detecta antes para responder {@code 409} en lugar de dejar salir un
 * error de integridad.</p>
 *
 * <p>Las tres asociaciones son perezosas: leer el par o el nombre del consultorio no necesita
 * el estado completo del médico ni de la organización, y cargarlos de forma anticipada
 * produciría un N+1 en los listados.</p>
 */
@Entity
@Table(name = "medico_consultorio")
public class MedicoConsultorio {

    @EmbeddedId
    private MedicoConsultorioId id;

    @MapsId("medicoId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medico_id", nullable = false, insertable = false, updatable = false)
    private Medico medico;

    @MapsId("consultorioId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultorio_id", nullable = false, insertable = false, updatable = false)
    private Consultorio consultorio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio; para crear una
     * asignación utilice {@link #MedicoConsultorio(Medico, Consultorio, Organizacion)}.
     */
    protected MedicoConsultorio() {
    }

    /**
     * Crea el vínculo entre un médico y uno de los consultorios de su organización.
     *
     * <p>La clave se construye aquí a partir de las dos asociaciones: JPA la deriva de ellas
     * ({@code @MapsId}), y dejarla nula obligaría a Hibernate a instanciarla para escribirla. Al
     * fijarla en el constructor, el par queda declarado por las entidades y no puede quedar
     * incoherente con ellas. La organización se recibe como parámetro porque la columna es
     * obligatoria y es la mitad de las dos claves foráneas compuestas: el llamador debe pasar la
     * del <strong>médico</strong>, nunca la del cliente.</p>
     *
     * @param medico       médico al que se le asigna el consultorio (obligatorio)
     * @param consultorio  consultorio asignado, de la misma organización (obligatorio)
     * @param organizacion organización dueña de los dos, la del médico
     */
    public MedicoConsultorio(Medico medico, Consultorio consultorio, Organizacion organizacion) {
        this.id = new MedicoConsultorioId(medico.getId(), consultorio.getId());
        this.medico = medico;
        this.consultorio = consultorio;
        this.organizacion = organizacion;
    }

    public MedicoConsultorioId getId() {
        return id;
    }

    public Medico getMedico() {
        return medico;
    }

    public Consultorio getConsultorio() {
        return consultorio;
    }

    public Organizacion getOrganizacion() {
        return organizacion;
    }

    /**
     * Igualdad basada SOLO en la clave compuesta. Dos vínculos del mismo par son el mismo
     * registro aunque uno de ellos sea un proxy de Hibernate.
     */
    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MedicoConsultorio otro)) {
            return false;
        }
        return getId() != null && getId().equals(otro.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida de la entidad. Se calcula a partir de la clase
     * y no de la clave: la clave se rellena al persistir, y un hash que cambiara dejaría la
     * entidad perdida en cualquier colección basada en hash.
     */
    @Override
    public final int hashCode() {
        return MedicoConsultorio.class.hashCode();
    }

    /**
     * Representación legible que omite las tres asociaciones: leerlas forzaría la carga
     * perezosa y podría lanzar {@code LazyInitializationException} al registrar la entidad
     * fuera de una transacción.
     */
    @Override
    public String toString() {
        return "MedicoConsultorio{id=" + id + '}';
    }
}
