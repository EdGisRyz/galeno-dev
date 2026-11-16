package com.intoglobe.clinicas.model;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Cuenta de usuario de una organización. Espejo columna por columna de la tabla
 * {@code usuario} tal como quedó tras las migraciones V2 y V3 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>{@link #organizacion} es un {@code @ManyToOne} perezoso y obligatorio: la cuenta
 *       pertenece a una organización y a ninguna otra. La asociación es unidireccional,
 *       igual que en {@link Paciente} y {@link Medico}.</li>
 *   <li>{@link #medico} es un {@code @ManyToOne} perezoso y <em>opcional</em>: un
 *       {@code MEDICO} siempre tiene su fila de médico —lo exige el CHECK
 *       {@code usuario_rol_medico_coherente_check} de V3—, mientras que los demás roles
 *       pueden tenerla o no. El caso legítimo es el de la persona que administra la clínica
 *       y además atiende. La FK es compuesta en la base
 *       ({@code (medico_id, organizacion_id)} → {@code medico (id, organizacion_id)}), de
 *       modo que apuntar a un médico de otra organización es imposible incluso por SQL
 *       directo; Hibernate solo mapea {@code medico_id} porque {@code organizacion_id} ya
 *       pertenece a la otra asociación.</li>
 *   <li>{@link #email} es la identidad de negocio y se guarda <strong>normalizado a
 *       minúsculas</strong> por {@link #normalizarEmail(String)}. Esa normalización ocurre
 *       en el constructor y en {@link #actualizarDatos}, es decir en la única vía de
 *       escritura que tiene la entidad: no existe setter ni ruta alternativa, así que
 *       {@code Juan@x.com} y {@code juan@x.com} son la misma cuenta dentro de la
 *       organización. La unicidad la impone la base
 *       ({@code usuario_organizacion_id_email_key} sobre la pareja organización-correo), no
 *       esta clase: el mismo correo puede existir en dos clínicas como dos cuentas
 *       independientes.</li>
 *   <li>{@link #rol} es un {@link RolUsuario}, espejo del CHECK de roles.</li>
 *   <li>{@link #authUserId} es el sujeto del servicio de identidad, no un dato de negocio:
 *       lo aporta el servicio al crear la cuenta y es inmutable ({@code updatable = false}),
 *       porque cambiarlo partiría la auditoría de la persona en dos identidades. Es único
 *       por organización, no global.</li>
 *   <li>{@link #activo} materializa la baja lógica mediante {@link #desactivar()} y la
 *       reactivación mediante {@link #activar()}, ambas idempotentes. La fila nunca se borra:
 *       {@code adjunto.subido_por} la referencia y la cuenta conserva su historial.</li>
 *   <li>{@link #createdAt} es {@link OffsetDateTime} porque la columna es
 *       {@code TIMESTAMP WITH TIME ZONE}; lo asigna Hibernate al insertar y no es
 *       actualizable, así que el cliente nunca lo envía ni lo sobrescribe.</li>
 *   <li>La validación de entrada (formato del correo, rol obligatorio) vive en el DTO de
 *       petición, nunca en esta entidad.</li>
 * </ul>
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

    /**
     * Médico asociado, opcional. Es {@code null} en las cuentas que no atienden y en las de
     * un {@code MEDICO} que fue cambiado a otro rol, momento en que la agenda queda sin dueño
     * (la fila del médico y sus bloques de horario no se tocan).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medico_id")
    private Medico medico;

    @Column(name = "email", length = 180, nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", length = 20, nullable = false)
    private RolUsuario rol;

    /**
     * Sujeto del servicio de identidad ({@code sub} del token). Obligatorio e inmutable: lo
     * genera el servicio al crear la cuenta y ninguna actualización lo reescribe.
     */
    @Column(name = "auth_user_id", nullable = false, updatable = false)
    private UUID authUserId;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio; para crear una
     * cuenta utilice
     * {@link #Usuario(Organizacion, Medico, String, RolUsuario, UUID)}.
     */
    protected Usuario() {
    }

    /**
     * Crea una cuenta nueva con {@code activo = true} y el correo ya normalizado.
     *
     * <p>{@code authUserId} sí es un parámetro —a diferencia de {@code id} o
     * {@code createdAt}— porque no lo inventa la base: lo aporta el servicio, que en local
     * genera un UUID v4 y contra la plataforma real recibe el {@code sub} del token.</p>
     *
     * @param organizacion organización dueña de la cuenta (obligatoria)
     * @param medico       médico asociado, o {@code null} si la cuenta no atiende
     *                     (obligatorio cuando {@code rol} es {@link RolUsuario#MEDICO})
     * @param email        correo de la cuenta (obligatorio, 1..180 caracteres); se guarda en
     *                     minúsculas
     * @param rol          rol de la cuenta (obligatorio, uno de {@link RolUsuario})
     * @param authUserId   identidad de la cuenta en el servicio de autenticación (obligatoria)
     */
    public Usuario(Organizacion organizacion, Medico medico, String email, RolUsuario rol,
            UUID authUserId) {
        this.organizacion = organizacion;
        this.medico = medico;
        this.email = normalizarEmail(email);
        this.rol = rol;
        this.authUserId = authUserId;
    }

    /**
     * Normaliza un correo para almacenarlo y para buscarlo.
     *
     * <p>Es la única definición de «el mismo correo» del módulo, y por eso es estática y
     * pública: la usan el constructor, {@link #actualizarDatos} y el servicio cuando consulta
     * si un correo ya está ocupado. Sin un único lugar, guardar en minúsculas y buscar en
     * mayúsculas daría dos respuestas distintas a la misma pregunta.</p>
     *
     * <p>Solo cambia la caja, que es lo que la decisión de producto fijó: dos correos que
     * difieren únicamente en mayúsculas son la misma persona. Un {@code null} se conserva
     * como {@code null} para que la validación de obligatoriedad siga siendo del DTO.</p>
     *
     * @param email correo tal como llegó del cliente, o {@code null}
     * @return el correo en minúsculas, o {@code null} si la entrada era nula
     */
    public static String normalizarEmail(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    /**
     * Actualiza exclusivamente los tres campos editables de la cuenta: correo, rol y médico.
     *
     * <p>Un valor {@code null} en {@code medico} borra la asociación, y eso es exactamente lo
     * que ocurre al cambiar de {@code MEDICO} a otro rol: la cuenta deja de ser dueña de una
     * agenda. Nunca modifica {@code id}, {@code organizacion}, {@code authUserId},
     * {@code activo} ni {@code createdAt}: actualizar una cuenta dada de baja no la reactiva
     * —para eso está la reactivación—, la identidad de autenticación no cambia y mover la
     * cuenta de organización dejaría su historial en otra clínica.</p>
     *
     * @param medico nuevo médico asociado, o {@code null} para liberar la asociación
     * @param email  nuevo correo (obligatorio, 1..180 caracteres); se guarda en minúsculas
     * @param rol    nuevo rol (obligatorio, uno de {@link RolUsuario})
     */
    public void actualizarDatos(Medico medico, String email, RolUsuario rol) {
        this.medico = medico;
        this.email = normalizarEmail(email);
        this.rol = rol;
    }

    /**
     * Baja lógica e idempotente: desactivar una cuenta que ya está inactiva no produce error.
     * Solo cambia {@code activo}. La fila se conserva porque la cuenta es la que da sentido a
     * {@code adjunto.subido_por} y al historial de la organización.
     */
    public void desactivar() {
        this.activo = false;
    }

    /**
     * Reactivación lógica e idempotente: activar una cuenta que ya está activa no produce
     * error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p>Existe porque el correo de una cuenta dada de baja sigue ocupado por la restricción
     * de unicidad: crear otra para la misma persona obligaría a inventarle un correo distinto
     * y a partir la auditoría en dos identidades.</p>
     */
    public void activar() {
        this.activo = true;
    }

    public UUID getId() {
        return id;
    }

    public Organizacion getOrganizacion() {
        return organizacion;
    }

    public Medico getMedico() {
        return medico;
    }

    public String getEmail() {
        return email;
    }

    public RolUsuario getRol() {
        return rol;
    }

    /** Sujeto del servicio de identidad. Deliberadamente fuera de la respuesta HTTP. */
    public UUID getAuthUserId() {
        return authUserId;
    }

    /** Getter JavaBeans para el primitivo {@code activo}. */
    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Igualdad basada SOLO en el identificador persistente. Dos instancias transitorias
     * distintas (sin {@code id}) nunca son iguales, y dos referencias al mismo registro lo son
     * aunque una sea un proxy de Hibernate, porque se compara leyendo el {@code id} mediante el
     * getter.
     */
    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Usuario otro)) {
            return false;
        }
        return getId() != null && getId().equals(otro.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida: se calcula a partir de la clase y no de
     * {@code id} ni de campos mutables, así no cambia cuando Hibernate asigna el UUID al
     * persistir ni cuando {@link #actualizarDatos} cambia el correo o el rol.
     */
    @Override
    public final int hashCode() {
        return Usuario.class.hashCode();
    }

    /**
     * Representación legible que omite deliberadamente la organización, el médico y el correo:
     * leer las asociaciones forzaría su inicialización perezosa —y podría lanzar
     * {@code LazyInitializationException} al registrar la entidad fuera de una transacción—, y
     * el correo es el identificador de acceso de una persona, que no debe acabar en un log. El
     * {@code authUserId} tampoco se expone.
     */
    @Override
    public String toString() {
        return "Usuario{id=" + id
                + ", rol=" + rol
                + ", activo=" + activo
                + ", createdAt=" + createdAt
                + '}';
    }
}
