package com.intoglobe.clinicas.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Organización (tenant) dueña de los datos clínicos. Espejo columna por columna
 * de la tabla {@code organizacion} definida en la migración V1 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>El plan contratado es un {@link PlanTipo}, espejo del CHECK de V1.</li>
 *   <li>{@code subscriptionId} y {@code trialHasta} son opcionales (nullable en BD).</li>
 *   <li>La organización no se elimina físicamente: su baja es lógica mediante
 *       {@link #desactivar()}, operación idempotente.</li>
 *   <li>La validación de entrada (nombre, plan, longitudes) vive en el DTO de
 *       petición, nunca en esta entidad.</li>
 * </ul>
 */
@Entity
@Table(name = "organizacion")
public class Organizacion {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nombre", length = 120, nullable = false)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", length = 20, nullable = false)
    private PlanTipo plan;

    @Column(name = "subscription_id", length = 100)
    private String subscriptionId;

    @Column(name = "trial_hasta")
    private OffsetDateTime trialHasta;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio;
     * para crear una organización utilice
     * {@link #Organizacion(String, PlanTipo, String)}.
     */
    protected Organizacion() {
    }

    /**
     * Crea una organización nueva con {@code activo = true}.
     *
     * @param nombre         nombre de la organización (obligatorio, 1..120 caracteres)
     * @param plan           plan contratado (obligatorio)
     * @param subscriptionId identificador de suscripción (opcional, máx. 100 caracteres)
     */
    public Organizacion(String nombre, PlanTipo plan, String subscriptionId) {
        this.nombre = nombre;
        this.plan = plan;
        this.subscriptionId = subscriptionId;
    }

    /**
     * Actualiza exclusivamente los cuatro campos editables de la organización.
     *
     * <p>Un valor {@code null} en {@code subscriptionId} o {@code trialHasta} borra
     * el valor anterior (la actualización PUT es un reemplazo). Nunca modifica
     * {@code id}, {@code createdAt} ni {@code activo}: actualizar una organización
     * inactiva no la reactiva.</p>
     *
     * @param nombre         nuevo nombre (obligatorio, 1..120 caracteres)
     * @param plan           nuevo plan (obligatorio)
     * @param subscriptionId nueva suscripción o {@code null} para borrarla
     * @param trialHasta     nueva vigencia de prueba o {@code null} para borrarla
     */
    public void actualizarDatos(String nombre, PlanTipo plan, String subscriptionId, OffsetDateTime trialHasta) {
        this.nombre = nombre;
        this.plan = plan;
        this.subscriptionId = subscriptionId;
        this.trialHasta = trialHasta;
    }

    /**
     * Eliminación lógica e idempotente: desactivar una organización que ya está
     * inactiva no produce error (sigue siendo una operación válida).
     */
    public void desactivar() {
        this.activo = false;
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public PlanTipo getPlan() {
        return plan;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public OffsetDateTime getTrialHasta() {
        return trialHasta;
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
        if (!(obj instanceof Organizacion otra)) {
            return false;
        }
        return getId() != null && getId().equals(otra.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida de la entidad. Se calcula a partir
     * de la clase y no de {@code id} ni de campos mutables de negocio: así el valor
     * no cambia cuando Hibernate asigna el UUID al persistir, y la entidad sigue
     * siendo localizable en colecciones basadas en hash.
     */
    @Override
    public final int hashCode() {
        return Organizacion.class.hashCode();
    }

    /** Representación sin datos sensibles (no expone {@code subscriptionId}). */
    @Override
    public String toString() {
        return "Organizacion{id=" + id
                + ", nombre='" + nombre + '\''
                + ", plan=" + plan
                + ", activo=" + activo
                + ", createdAt=" + createdAt
                + '}';
    }
}
