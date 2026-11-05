package com.intoglobe.clinicas.model;

import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Bloque de disponibilidad semanal de un médico. Espejo columna por columna de la
 * tabla {@code horario_medico} definida en la migración V1 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>Un horario pertenece a un médico mediante un {@code @ManyToOne} perezoso y
 *       sin {@code unique}: un médico tiene muchos bloques en la semana. La
 *       asociación es unidireccional: {@link Medico} no se modifica para navegar la
 *       relación inversa, y cargar la colección desde el médico produciría un N+1
 *       en cualquier listado.</li>
 *   <li>V1 <strong>no</strong> declara ningún índice sobre {@code horario_medico}:
 *       el listado por médico filtra por {@code medico_id} sin índice y hoy lo hace
 *       sobre una tabla pequeña. Agregarlo es un pendiente declarado del V2; este
 *       modelo no puede resolverlo.</li>
 *   <li>{@link #diaSemana} es {@code short} porque la columna es {@code SMALLINT} y
 *       los días son un rango cerrado, no una entidad: no hay tabla de días ni
 *       enum. Sigue la convención de JavaScript {@code Date.getDay()}, que el
 *       frontend ya asume: {@code 0=Domingo, 1=Lunes, 2=Martes, 3=Miércoles,
 *       4=Jueves, 5=Viernes, 6=Sábado}. El rango lo garantiza el CHECK de V1, no
 *       esta clase.</li>
 *   <li>{@link #horaInicio} y {@link #horaFin} son {@link LocalTime} porque las
 *       columnas son {@code TIME} sin zona: una hora de agenda es local al
 *       consultorio y desplazarla a UTC la pondría en el día equivocado.</li>
 *   <li>{@link #duracionConsulta} es {@code short} porque la columna es
 *       {@code SMALLINT} en minutos. El CHECK de V1 lo limita a
 *       {@code {20, 30, 45, 60}}: son los tamaños de cita que el catálogo admite, y
 *       un valor fuera de esa lista lo rechaza la base, no esta entidad.</li>
 *   <li>V1 <em>no</em> valida que {@code horaInicio < horaFin} ni que dos bloques
 *       del mismo médico no se solapen. Ambas son reglas de aplicación y viven en el
 *       servicio: el solapamiento se comprueba con
 *       {@code HorarioMedicoRepository#contarSolapamientos} usando intervalos
 *       semiabiertos, para que dos citas consecutivas (09:00-10:00 y 10:00-11:00)
 *       sigan siendo válidas.</li>
 *   <li>No existen {@code createdAt} ni {@code activo}: V1 no los declara en
 *       {@code horario_medico}. Declararlos aquí rompería
 *       {@code ddl-auto=validate} y, con él, el arranque de toda la suite. Un
 *       horario se borra, no se desactiva.</li>
 * </ul>
 */
@Entity
@Table(name = "horario_medico")
public class HorarioMedico {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medico_id", nullable = false)
    private Medico medico;

    /**
     * Día de la semana con la convención de JavaScript {@code Date.getDay()}:
     * {@code 0=Domingo, 1=Lunes, 2=Martes, 3=Miércoles, 4=Jueves, 5=Viernes,
     * 6=Sábado}. El rango {@code 0..6} lo impone el CHECK de V1.
     */
    @Column(name = "dia_semana", nullable = false)
    private short diaSemana;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    /**
     * Duración de cada cita en minutos. El CHECK de V1 la limita a
     * {@code {20, 30, 45, 60}}.
     */
    @Column(name = "duracion_consulta", nullable = false)
    private short duracionConsulta;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio; para
     * crear un horario utilice
     * {@link #HorarioMedico(Medico, short, LocalTime, LocalTime, short)}.
     */
    protected HorarioMedico() {
    }

    /**
     * Crea un bloque de disponibilidad para un médico.
     *
     * <p>No valida el rango del día ni la duración permitida: esos límites son
     * CHECK de V1 y se manifiestan como {@code DataIntegrityViolationException} al
     * persistir. Tampoco comprueba solapamientos; esa comprobación es del servicio,
     * que debe consultar {@code contarSolapamientos} antes de guardar.</p>
     *
     * @param medico           médico dueño del bloque (obligatorio)
     * @param diaSemana        día de la semana, {@code 0=Domingo .. 6=Sábado}
     * @param horaInicio       hora local de inicio del bloque (obligatoria)
     * @param horaFin          hora local de fin del bloque (obligatoria)
     * @param duracionConsulta duración de cada cita en minutos, uno de
     *                         {@code {20, 30, 45, 60}}
     */
    public HorarioMedico(Medico medico, short diaSemana, LocalTime horaInicio, LocalTime horaFin,
            short duracionConsulta) {
        this.medico = medico;
        this.diaSemana = diaSemana;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
        this.duracionConsulta = duracionConsulta;
    }

    /**
     * Actualiza exclusivamente los cuatro campos editables del bloque.
     *
     * <p>Nunca modifica {@code id} ni {@code medico}: mover un horario a otro médico
     * dejaría la agenda del primero sin ese bloque sin avisar, y ese cambio de
     * dueño no forma parte del contrato del {@code PUT}. El solapamiento resultante
     * tampoco se valida aquí, por la misma razón que en el constructor.</p>
     *
     * @param diaSemana        nuevo día de la semana, {@code 0=Domingo .. 6=Sábado}
     * @param horaInicio       nueva hora local de inicio
     * @param horaFin          nueva hora local de fin
     * @param duracionConsulta nueva duración de cita en minutos
     */
    public void actualizarDatos(short diaSemana, LocalTime horaInicio, LocalTime horaFin,
            short duracionConsulta) {
        this.diaSemana = diaSemana;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
        this.duracionConsulta = duracionConsulta;
    }

    public UUID getId() {
        return id;
    }

    public Medico getMedico() {
        return medico;
    }

    public short getDiaSemana() {
        return diaSemana;
    }

    public LocalTime getHoraInicio() {
        return horaInicio;
    }

    public LocalTime getHoraFin() {
        return horaFin;
    }

    public short getDuracionConsulta() {
        return duracionConsulta;
    }

    /**
     * Igualdad basada SOLO en el identificador persistente. Dos instancias
     * transitorias distintas (sin {@code id}) nunca son iguales, aunque compartan el
     * resto de sus datos, y dos referencias al mismo registro lo son aunque una de
     * ellas sea un proxy de Hibernate, porque se compara leyendo el {@code id}
     * mediante el getter.
     */
    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof HorarioMedico otro)) {
            return false;
        }
        return getId() != null && getId().equals(otro.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida de la entidad. Se calcula a partir
     * de la clase y no de {@code id} ni de campos mutables de negocio: así el valor
     * no cambia cuando Hibernate asigna el UUID al persistir, ni cuando
     * {@link #actualizarDatos(short, LocalTime, LocalTime, short)} cambia el bloque,
     * y la entidad sigue siendo localizable en colecciones basadas en hash.
     */
    @Override
    public final int hashCode() {
        return HorarioMedico.class.hashCode();
    }

    /**
     * Representación legible que omite deliberadamente el médico: leerlo forzaría la
     * inicialización de la asociación perezosa y podría lanzar
     * {@code LazyInitializationException} al registrar la entidad fuera de una
     * transacción.
     */
    @Override
    public String toString() {
        return "HorarioMedico{id=" + id
                + ", diaSemana=" + diaSemana
                + ", horaInicio=" + horaInicio
                + ", horaFin=" + horaFin
                + ", duracionConsulta=" + duracionConsulta
                + '}';
    }
}
