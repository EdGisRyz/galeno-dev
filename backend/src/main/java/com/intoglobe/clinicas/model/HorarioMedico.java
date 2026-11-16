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
 * tabla {@code horario_medico}: las columnas base vienen de la migración V1 y las de
 * consultorio y organización, de las migraciones V6 y V7.
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>Un horario pertenece a un médico mediante un {@code @ManyToOne} perezoso y
 *       sin {@code unique}: un médico tiene muchos bloques en la semana. La
 *       asociación es unidireccional: {@link Medico} no se modifica para navegar la
 *       relación inversa, y cargar la colección desde el médico produciría un N+1
 *       en cualquier listado.</li>
 *   <li>Un horario se dicta en <strong>un consultorio</strong>, elegido entre los
 *       asignados al médico ({@code medico_consultorio}). La columna
 *       {@code consultorio_id} la agregó la V6 y la V7 la cerró como obligatoria:
 *       desde entonces todo bloque dice dónde atiende el médico, y la agenda puede
 *       responder a qué consultorio va un paciente. La relación no tiene
 *       {@code ON DELETE}: un consultorio con bloques no se borra, se desactiva.</li>
 *   <li>{@link #organizacion} se repite en la fila aunque sea deducible del médico y
 *       del consultorio. No es redundancia por comodidad: las claves compuestas de la
 *       V6 ({@code (medico_id, organizacion_id)} y {@code (consultorio_id,
 *       organizacion_id)}) la necesitan para garantizar que médico y consultorio sean
 *       de la misma clínica. El valor se toma siempre del médico, nunca del cliente.</li>
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
     * Consultorio donde se dicta el bloque, uno de los asignados al médico. La clave
     * {@code horario_medico_consultorio_asignado_fkey} de la V6 lo exige en la base; el
     * servicio responde {@code 409} antes de que el error de integridad llegue al cliente.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultorio_id", nullable = false)
    private Consultorio consultorio;

    /**
     * Organización dueña del médico y del consultorio. Se duplica en la fila porque las
     * dos claves foráneas compuestas de la V6 la comparten: es lo que impide que un
     * bloque cruce dos clínicas. Se toma del médico y es inmutable.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

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
     * Crea un bloque de disponibilidad para un médico en su consultorio principal.
     *
     * <p>Es el atajo del caso más común: un bloque que no elige consultorio se dicta en el
     * consultorio principal del médico ({@code medico.consultorio_id}), que el alta del
     * médico deja asignado. Para un bloque en otro consultorio asignado, use
     * {@link #HorarioMedico(Medico, Consultorio, short, LocalTime, LocalTime, short)}.</p>
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
        this(medico, medico.getConsultorio(), diaSemana, horaInicio, horaFin, duracionConsulta);
    }

    /**
     * Crea un bloque de disponibilidad para un médico en un consultorio concreto.
     *
     * <p>La organización de la fila se toma del <strong>médico</strong>, no del consultorio
     * ni del cliente: es la mitad de las dos claves foráneas compuestas que garantizan que
     * médico y consultorio pertenezcan a la misma clínica. Que el consultorio esté asignado
     * al médico lo comprueba el servicio antes de llamar aquí y lo garantiza, además, la
     * clave {@code horario_medico_consultorio_asignado_fkey} de la V6.</p>
     *
     * <p>No valida el rango del día ni la duración permitida, por la misma razón que el
     * constructor del consultorio principal.</p>
     *
     * @param medico           médico dueño del bloque (obligatorio)
     * @param consultorio      consultorio donde se dicta, asignado a ese médico (obligatorio)
     * @param diaSemana        día de la semana, {@code 0=Domingo .. 6=Sábado}
     * @param horaInicio       hora local de inicio del bloque (obligatoria)
     * @param horaFin          hora local de fin del bloque (obligatoria)
     * @param duracionConsulta duración de cada cita en minutos, uno de
     *                         {@code {20, 30, 45, 60}}
     */
    public HorarioMedico(Medico medico, Consultorio consultorio, short diaSemana,
            LocalTime horaInicio, LocalTime horaFin, short duracionConsulta) {
        this.medico = medico;
        this.consultorio = consultorio;
        this.organizacion = medico.getOrganizacion();
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

    /**
     * Mueve el bloque a otro consultorio del mismo médico.
     *
     * <p>No cambia la organización ni el médico: el consultorio destino tiene que estar
     * asignado a ese médico y pertenecer a su clínica, condiciones que el servicio comprueba
     * antes y que las claves de la V6 vuelven a exigir en la base. No valida el solapamiento
     * resultante, por la misma razón que el constructor: esa regla es del servicio.</p>
     *
     * @param consultorio consultorio destino, asignado al médico del bloque
     */
    public void cambiarConsultorio(Consultorio consultorio) {
        this.consultorio = consultorio;
    }

    public UUID getId() {
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
     * Representación legible que omite deliberadamente las asociaciones —médico,
     * consultorio y organización—: leerlas forzaría la inicialización de la carga perezosa
     * y podría lanzar {@code LazyInitializationException} al registrar la entidad fuera de
     * una transacción.
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
