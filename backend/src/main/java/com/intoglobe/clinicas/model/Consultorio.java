package com.intoglobe.clinicas.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Consultorio (sede física) de una organización. Espejo columna por columna de la
 * tabla {@code consultorio} definida en la migración V1 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>Una organización tiene muchos consultorios, por eso la asociación es
 *       {@code @ManyToOne} sin {@code unique}. Es unidireccional: la navegación
 *       inversa desde {@link Organizacion} no existe todavía.</li>
 *   <li>La organización de un consultorio es inmutable. Cambiarla dejaría
 *       huérfanos a los médicos y citas que apuntan al consultorio, porque esas
 *       FK no tienen {@code ON DELETE}: por eso la asociación no aparece en
 *       {@link #actualizarDatos(String, String, String)} ni en ningún otro método
 *       de escritura.</li>
 *   <li>La baja es lógica mediante {@link #desactivar()}, operación idempotente:
 *       el borrado físico rompería las FK de {@code medico} y {@code cita}, que no
 *       declaran cascada.</li>
 *   <li>La validación de entrada (nombre, teléfono) vive en el DTO de petición,
 *       nunca en esta entidad.</li>
 * </ul>
 */
@Entity
@Table(name = "consultorio")
public class Consultorio {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

    @Column(name = "nombre", length = 120, nullable = false)
    private String nombre;

    @Column(name = "direccion")
    private String direccion;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio;
     * para crear un consultorio utilice
     * {@link #Consultorio(Organizacion, String, String, String)}.
     */
    protected Consultorio() {
    }

    /**
     * Crea un consultorio nuevo con {@code activo = true}.
     *
     * @param organizacion organización dueña del consultorio (obligatoria)
     * @param nombre       nombre del consultorio (obligatorio, 1..120 caracteres)
     * @param direccion    dirección del consultorio (opcional, sin límite en BD)
     * @param telefono     teléfono del consultorio (opcional, máx. 20 caracteres)
     */
    public Consultorio(Organizacion organizacion, String nombre, String direccion, String telefono) {
        this.organizacion = organizacion;
        this.nombre = nombre;
        this.direccion = direccion;
        this.telefono = telefono;
    }

    /**
     * Actualiza exclusivamente los tres campos editables del consultorio.
     *
     * <p>Un valor {@code null} en {@code direccion} o {@code telefono} borra el
     * valor anterior (la actualización PUT es un reemplazo). Nunca modifica
     * {@code id}, {@code createdAt} ni {@code activo}: actualizar un consultorio
     * inactivo no lo reactiva. Tampoco reasigna la organización, porque mover el
     * consultorio a otra organización dejaría huérfanos a sus médicos y citas.</p>
     *
     * @param nombre    nuevo nombre (obligatorio, 1..120 caracteres)
     * @param direccion nueva dirección o {@code null} para borrarla
     * @param telefono  nuevo teléfono o {@code null} para borrarlo
     */
    public void actualizarDatos(String nombre, String direccion, String telefono) {
        this.nombre = nombre;
        this.direccion = direccion;
        this.telefono = telefono;
    }

    /**
     * Eliminación lógica e idempotente: desactivar un consultorio que ya está
     * inactivo no produce error (sigue siendo una operación válida).
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

    public String getNombre() {
        return nombre;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getTelefono() {
        return telefono;
    }

    /** Getter JavaBeans para el primitivo {@code activo}. */
    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Igualdad basada SOLO en el identificador persistente. Dos instancias
     * transitorias distintas (sin {@code id}) nunca son iguales, aunque compartan
     * el resto de sus datos, y dos referencias al mismo registro lo son aunque una
     * de ellas sea un proxy de Hibernate, porque se compara leyendo el {@code id}
     * mediante el getter.
     */
    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Consultorio otro)) {
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
        return Consultorio.class.hashCode();
    }

    /**
     * Representación legible que omite deliberadamente la organización: leerla
     * forzaría la inicialización de la asociación perezosa y podría lanzar
     * {@code LazyInitializationException} al registrar la entidad fuera de una
     * transacción.
     */
    @Override
    public String toString() {
        return "Consultorio{id=" + id
                + ", nombre='" + nombre + '\''
                + ", activo=" + activo
                + ", createdAt=" + createdAt
                + '}';
    }
}
