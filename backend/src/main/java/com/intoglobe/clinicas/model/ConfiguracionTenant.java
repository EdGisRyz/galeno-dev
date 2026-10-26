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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Configuración de un tenant (organización): credenciales de integración y
 * estado de WhatsApp. Espejo columna por columna de la tabla
 * {@code configuracion_tenant} de la migración V1 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>La relación con {@link Organizacion} es 1-1, unidireccional y obligatoria
 *       porque {@code organizacion_id} es NOT NULL + UNIQUE en BD. La navegación
 *       inversa no existe todavía.</li>
 *   <li>Las cuatro API keys son datos sensibles: sus getters existen para el
 *       sprint que las consuma, pero {@link #toString()} nunca las expone.</li>
 *   <li>La tabla no tiene {@code activo} ni {@code updated_at}: no hay borrado
 *       lógico; la baja ocurre por cascada al eliminar la organización.</li>
 *   <li>No existen setters ni métodos de dominio de escritura: la entidad se
 *       puebla exclusivamente por sus dos constructores de negocio. Tampoco hay
 *       validaciones Jakarta: la validación, si existe, vivirá en el DTO del
 *       sprint que administre estos valores.</li>
 * </ul>
 *
 * <p>Existen dos constructores de negocio porque la tabla cubre dos momentos
 * distintos: {@link #ConfiguracionTenant(Organizacion)} representa el alta de un
 * tenant que todavía no tiene credenciales (onboarding), mientras que
 * {@link #ConfiguracionTenant(Organizacion, String, String, String, String, boolean, String)}
 * recibe el payload completo. El constructor de payload existe porque la entidad
 * es la única dueña de sus columnas y debe poder poblarlas sin exponer setters
 * mutables; así la escritura queda concentrada y auditable en la construcción.</p>
 */
@Entity
@Table(name = "configuracion_tenant")
public class ConfiguracionTenant {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false, unique = true)
    private Organizacion organizacion;

    @Column(name = "notif_api_key")
    private String notifApiKey;

    @Column(name = "storage_api_key")
    private String storageApiKey;

    @Column(name = "payment_api_key")
    private String paymentApiKey;

    @Column(name = "audit_api_key")
    private String auditApiKey;

    @Column(name = "whatsapp_conectado", nullable = false)
    private boolean whatsappConectado = false;

    @Column(name = "whatsapp_numero", length = 20)
    private String whatsappNumero;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio;
     * para crear una configuración utilice
     * {@link #ConfiguracionTenant(Organizacion)}.
     */
    protected ConfiguracionTenant() {
    }

    /**
     * Crea la configuración de una organización.
     *
     * <p>Deja WhatsApp desconectado y sin número, y las cuatro API keys en
     * {@code null}: sus valores se asignarán cuando exista el sprint que los
     * administre.</p>
     *
     * @param organizacion organización dueña de la configuración (obligatoria)
     */
    public ConfiguracionTenant(Organizacion organizacion) {
        this.organizacion = organizacion;
    }

    /**
     * Crea la configuración de una organización a partir del payload completo.
     *
     * <p>Es el único camino para poblar las columnas de credenciales y WhatsApp:
     * la entidad no expone setters, de modo que cada escritura nace de una
     * construcción explícita y el estado siempre refleja un payload conocido.</p>
     *
     * @param organizacion       organización dueña de la configuración (obligatoria)
     * @param notifApiKey        clave de la API de notificaciones (opcional)
     * @param storageApiKey      clave de la API de almacenamiento (opcional)
     * @param paymentApiKey      clave de la API de pagos (opcional)
     * @param auditApiKey        clave de la API de auditoría (opcional)
     * @param whatsappConectado  si el canal de WhatsApp está conectado
     * @param whatsappNumero     número de WhatsApp (opcional, máx. 20 caracteres)
     */
    public ConfiguracionTenant(Organizacion organizacion, String notifApiKey, String storageApiKey,
            String paymentApiKey, String auditApiKey, boolean whatsappConectado, String whatsappNumero) {
        this.organizacion = organizacion;
        this.notifApiKey = notifApiKey;
        this.storageApiKey = storageApiKey;
        this.paymentApiKey = paymentApiKey;
        this.auditApiKey = auditApiKey;
        this.whatsappConectado = whatsappConectado;
        this.whatsappNumero = whatsappNumero;
    }

    public UUID getId() {
        return id;
    }

    public Organizacion getOrganizacion() {
        return organizacion;
    }

    public String getNotifApiKey() {
        return notifApiKey;
    }

    public String getStorageApiKey() {
        return storageApiKey;
    }

    public String getPaymentApiKey() {
        return paymentApiKey;
    }

    public String getAuditApiKey() {
        return auditApiKey;
    }

    /** Getter JavaBeans para el primitivo {@code whatsappConectado}. */
    public boolean isWhatsappConectado() {
        return whatsappConectado;
    }

    public String getWhatsappNumero() {
        return whatsappNumero;
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
        if (!(obj instanceof ConfiguracionTenant otra)) {
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
        return ConfiguracionTenant.class.hashCode();
    }

    /**
     * Representación sin datos sensibles: omite deliberadamente las cuatro API keys
     * (ni total ni parcialmente), porque su filtración a logs sería un incidente de
     * seguridad.
     */
    @Override
    public String toString() {
        return "ConfiguracionTenant{id=" + id
                + ", whatsappConectado=" + whatsappConectado
                + ", whatsappNumero='" + whatsappNumero + '\''
                + ", createdAt=" + createdAt
                + '}';
    }
}
