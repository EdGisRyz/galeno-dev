package com.intoglobe.clinicas.model;

import java.math.BigDecimal;
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
 * Médico que atiende en un consultorio de una organización. Espejo columna por
 * columna de la tabla {@code medico} definida en la migración V1 (schema
 * congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>Un médico pertenece a una organización y trabaja en un consultorio, por eso
 *       hay dos {@code @ManyToOne} sin {@code unique}. Son asociaciones a entidades
 *       distintas y sin columnas compartidas, así que no compiten por el mismo
 *       mapeo. Ambas son unidireccionales: ni {@link Organizacion} ni
 *       {@link Consultorio} se modifican para navegar la relación inversa.</li>
 *   <li>Ambas asociaciones son perezosas ({@code LAZY}) porque el listado de médicos
 *       no necesita los datos de la organización ni del consultorio; cargarlos de
 *       forma anticipada produciría un N+1 en cada consulta.</li>
 *   <li>La baja es lógica mediante {@link #desactivar()}, operación idempotente. El
 *       borrado físico está descartado: la FK de {@code cita} lo bloquea y la de
 *       {@code horario_medico} borraría los horarios en silencio, sin avisar.</li>
 *   <li>La tarifa es {@link BigDecimal} porque la columna es {@code NUMERIC(10,2)}:
 *       usar {@code double} introduciría error de redondeo en un importe.</li>
 *   <li>La validación de entrada (nombre, longitudes, rango de la tarifa) vive en el
 *       DTO de petición, nunca en esta entidad.</li>
 *   <li>No existe {@code createdAt}: V1 no declara {@code created_at} en
 *       {@code medico}. Declararlo aquí rompería {@code ddl-auto=validate} y, con
 *       él, el arranque de toda la suite.</li>
 * </ul>
 */
@Entity
@Table(name = "medico")
public class Medico {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultorio_id", nullable = false)
    private Consultorio consultorio;

    @Column(name = "nombre", length = 120, nullable = false)
    private String nombre;

    @Column(name = "especialidad", length = 80)
    private String especialidad;

    @Column(name = "cedula", length = 30)
    private String cedula;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "tarifa_base", precision = 10, scale = 2)
    private BigDecimal tarifaBase;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio; para
     * crear un médico utilice
     * {@link #Medico(Organizacion, Consultorio, String, String, String, String, BigDecimal)}.
     */
    protected Medico() {
    }

    /**
     * Crea un médico nuevo con {@code activo = true}.
     *
     * @param organizacion organización a la que pertenece el médico (obligatoria)
     * @param consultorio  consultorio donde atiende (obligatorio)
     * @param nombre       nombre del médico (obligatorio, 1..120 caracteres)
     * @param especialidad especialidad (opcional, máx. 80 caracteres)
     * @param cedula       cédula profesional (opcional, máx. 30 caracteres)
     * @param telefono     teléfono de contacto (opcional, máx. 20 caracteres)
     * @param tarifaBase   tarifa base de la consulta (opcional, {@code NUMERIC(10,2)})
     */
    public Medico(Organizacion organizacion, Consultorio consultorio, String nombre, String especialidad,
            String cedula, String telefono, BigDecimal tarifaBase) {
        this.organizacion = organizacion;
        this.consultorio = consultorio;
        this.nombre = nombre;
        this.especialidad = especialidad;
        this.cedula = cedula;
        this.telefono = telefono;
        this.tarifaBase = tarifaBase;
    }

    /**
     * Actualiza exclusivamente los cinco campos editables del médico.
     *
     * <p>Un valor {@code null} en cualquier opcional borra el valor anterior (la
     * actualización PUT es un reemplazo). Nunca modifica {@code id}, {@code activo},
     * {@code organizacion} ni {@code consultorio}: actualizar un médico inactivo no
     * lo reactiva, y reasignar la organización dejaría al médico fuera de su tenant.
     * El cambio de consultorio no se contempla en este método porque el contrato del
     * {@code PUT} lo rechaza: un 200 que no aplica lo pedido mentiría.</p>
     *
     * @param nombre       nuevo nombre (obligatorio, 1..120 caracteres)
     * @param especialidad nueva especialidad o {@code null} para borrarla
     * @param cedula       nueva cédula o {@code null} para borrarla
     * @param telefono     nuevo teléfono o {@code null} para borrarlo
     * @param tarifaBase   nueva tarifa base o {@code null} para borrarla
     */
    public void actualizarDatos(String nombre, String especialidad, String cedula, String telefono,
            BigDecimal tarifaBase) {
        this.nombre = nombre;
        this.especialidad = especialidad;
        this.cedula = cedula;
        this.telefono = telefono;
        this.tarifaBase = tarifaBase;
    }

    /**
     * Eliminación lógica e idempotente: desactivar un médico que ya está inactivo no
     * produce error (sigue siendo una operación válida).
     */
    public void desactivar() {
        this.activo = false;
    }

    public UUID getId() {
        return id;
    }

    public Organizacion getOrganizacion() {
        return organizacion;
    }

    public Consultorio getConsultorio() {
        return consultorio;
    }

    public String getNombre() {
        return nombre;
    }

    public String getEspecialidad() {
        return especialidad;
    }

    public String getCedula() {
        return cedula;
    }

    public String getTelefono() {
        return telefono;
    }

    public BigDecimal getTarifaBase() {
        return tarifaBase;
    }

    /** Getter JavaBeans para el primitivo {@code activo}. */
    public boolean isActivo() {
        return activo;
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
        if (!(obj instanceof Medico otro)) {
            return false;
        }
        return getId() != null && getId().equals(otro.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida de la entidad. Se calcula a partir
     * de la clase y no de {@code id} ni de campos mutables de negocio: así el valor
     * no cambia cuando Hibernate asigna el UUID al persistir, y la entidad sigue
     * siendo localizable en colecciones basadas en hash.
     */
    @Override
    public final int hashCode() {
        return Medico.class.hashCode();
    }

    /**
     * Representación legible que omite deliberadamente la organización y el
     * consultorio: leerlas forzaría la inicialización de las asociaciones perezosas
     * y podría lanzar {@code LazyInitializationException} al registrar la entidad
     * fuera de una transacción. Tampoco expone la cédula, que es un dato
     * identificativo del profesional.
     */
    @Override
    public String toString() {
        return "Medico{id=" + id
                + ", nombre='" + nombre + '\''
                + ", especialidad='" + especialidad + '\''
                + ", activo=" + activo
                + ", tarifaBase=" + tarifaBase
                + '}';
    }
}
