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
 *   <li>{@link #nombre} es el nombre de la persona, <strong>nulable</strong> desde la migración V10:
 *       las cuentas que ya existían no lo tienen y no hay de dónde derivarlo. El registro público lo
 *       exige —el alta de una clínica lo pide y lo valida—, así que la obligación vive en el DTO del
 *       registro y no en la tabla. Una fila sin nombre sigue siendo válida.</li>
 *   <li>{@link #telefono} es el teléfono de contacto del trabajador, <strong>nulable</strong> desde la
 *       V10 y con el mismo tipo que el de médico y el de paciente. El registro público
 *       <strong>no lo pide</strong> —su formulario no tiene ese campo—, así que las cuentas que crea
 *       el onboarding nacen sin teléfono; la columna queda lista para el alta administrativa.</li>
 *   <li>{@link #email} es la identidad de negocio y se guarda <strong>recortado y normalizado a
 *       minúsculas</strong> por {@link #normalizarEmail(String)}. Esa normalización ocurre
 *       en el constructor y en {@link #actualizarDatos}, es decir en la única vía de
 *       escritura que tiene la entidad: no existe setter ni ruta alternativa, así que
 *       {@code Juan@x.com}, {@code juan@x.com} y {@code "  juan@x.com  "} son la misma cuenta
 *       dentro de la organización. La unicidad la impone la base
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

    /**
     * Nombre de la persona. Nulable: las cuentas anteriores a la migración V10 no lo tienen. No es
     * el identificador de la cuenta —ése es {@link #email}— y por eso no participa en ninguna
     * unicidad.
     */
    @Column(name = "nombre", length = 120)
    private String nombre;

    /**
     * Teléfono de contacto del trabajador. Nulable: las cuentas anteriores a la migración V10 no lo
     * tienen y el registro público no lo pide. No identifica a la cuenta ni participa en ninguna
     * unicidad.
     */
    @Column(name = "telefono", length = 20)
    private String telefono;

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
     * @param email        correo de la cuenta (obligatorio, 1..180 caracteres); se guarda
     *                     recortado y en minúsculas
     * @param rol          rol de la cuenta (obligatorio, uno de {@link RolUsuario})
     * @param authUserId   identidad de la cuenta en el servicio de autenticación (obligatoria)
     */
    public Usuario(Organizacion organizacion, Medico medico, String email, RolUsuario rol,
            UUID authUserId) {
        this(organizacion, medico, null, email, rol, authUserId);
    }

    /**
     * Crea una cuenta nueva con el nombre de la persona.
     *
     * <p>Es el constructor del registro público, que sí recibe el nombre; el de las cuentas de la
     * administración delega aquí con {@code null} porque esa pantalla todavía no lo pide.</p>
     *
     * @param organizacion organización dueña de la cuenta (obligatoria)
     * @param medico       médico asociado, o {@code null} si la cuenta no atiende
     * @param nombre       nombre de la persona, o {@code null} si no se registró
     * @param email        correo de la cuenta (obligatorio, 1..180 caracteres); se guarda
     *                     recortado y en minúsculas
     * @param rol          rol de la cuenta (obligatorio, uno de {@link RolUsuario})
     * @param authUserId   identidad de la cuenta en el servicio de autenticación (obligatoria)
     */
    public Usuario(Organizacion organizacion, Medico medico, String nombre, String email,
            RolUsuario rol, UUID authUserId) {
        this(organizacion, medico, nombre, null, email, rol, authUserId);
    }

    /**
     * Crea una cuenta nueva con el nombre y el teléfono de la persona.
     *
     * <p>El registro público usa la sobrecarga anterior: su formulario no pide teléfono, así que la
     * cuenta nace sin él. Esta variante existe para el alta administrativa de cuentas, que todavía no
     * lo captura.</p>
     *
     * @param organizacion organización dueña de la cuenta (obligatoria)
     * @param medico       médico asociado, o {@code null} si la cuenta no atiende
     * @param nombre       nombre de la persona, o {@code null} si no se registró
     * @param telefono     teléfono de contacto, o {@code null} si no se registró
     * @param email        correo de la cuenta (obligatorio, 1..180 caracteres); se guarda
     *                     recortado y en minúsculas
     * @param rol          rol de la cuenta (obligatorio, uno de {@link RolUsuario})
     * @param authUserId   identidad de la cuenta en el servicio de autenticación (obligatoria)
     */
    public Usuario(Organizacion organizacion, Medico medico, String nombre, String telefono, String email,
            RolUsuario rol, UUID authUserId) {
        this.organizacion = organizacion;
        this.medico = medico;
        this.nombre = nombre;
        this.telefono = telefono;
        this.email = normalizarEmail(email);
        this.rol = rol;
        this.authUserId = authUserId;
    }

    /**
     * Normaliza un correo para almacenarlo y para buscarlo.
     *
     * <p>Es la única definición de «el mismo correo» del módulo, y por eso es estática y
     * pública: la usan el constructor, {@link #actualizarDatos}, el DTO al recibir el cuerpo y el
     * servicio cuando consulta si un correo ya está ocupado. Sin un único lugar, guardar en
     * minúsculas y buscar en mayúsculas daría dos respuestas distintas a la misma pregunta.</p>
     *
     * <p>Hace las dos cosas que fijan «el mismo correo»: recorta los espacios de los extremos
     * —ver {@link #recortarEmail(String)}— y pasa la caja a minúsculas. Dos correos que difieren
     * solo en la caja o en un espacio pegado son la misma persona y la misma fila. Un
     * {@code null} se conserva como {@code null} para que la validación de obligatoriedad siga
     * siendo del DTO.</p>
     *
     * @param email correo tal como llegó del cliente, o {@code null}
     * @return el correo recortado y en minúsculas, o {@code null} si la entrada era nula
     */
    public static String normalizarEmail(String email) {
        var recortado = recortarEmail(email);
        return recortado == null ? null : recortado.toLowerCase(Locale.ROOT);
    }

    /**
     * Recorta los espacios de los extremos de un correo sin tocar su contenido ni su caja.
     *
     * <p>Un correo pegado desde un documento llega muchas veces con un espacio invisible al final,
     * y ese espacio no forma parte del correo: el valor se guarda recortado y la unicidad lo trata
     * como el mismo. El recorte se separa de {@link #normalizarEmail(String)} porque
     * <strong>tiene que ocurrir antes de validar</strong>: el patrón de
     * {@code PatronesDeFormato.CORREO_CUENTA} rechaza los extremos en blanco y Bean Validation
     * corre sobre el DTO, antes de que exista una entidad. Por eso el DTO recorta con esta misma
     * función en su constructor compacto y la entidad vuelve a aplicarla al guardar.</p>
     *
     * <p>Se consideran espacios los de {@link Character#isWhitespace(char)} más el espacio duro
     * ({@code U+00A0}) y la marca de orden de byte ({@code U+FEFF}), que son los que aparecen al
     * pegar desde un documento o una página y que {@code String#strip()} no recorta. El espejo del
     * formulario —{@code String.prototype.trim} de JavaScript— cubre ese mismo conjunto y algunos
     * espacios Unicode más, así que el cliente puede recortar de más, nunca de menos.</p>
     *
     * @param email correo tal como llegó del cliente, o {@code null}
     * @return el correo sin espacios en los extremos, o {@code null} si la entrada era nula
     */
    public static String recortarEmail(String email) {
        if (email == null) {
            return null;
        }
        int inicio = 0;
        int fin = email.length();
        while (inicio < fin && esEspacioDeLosExtremos(email.charAt(inicio))) {
            inicio++;
        }
        while (fin > inicio && esEspacioDeLosExtremos(email.charAt(fin - 1))) {
            fin--;
        }
        return email.substring(inicio, fin);
    }

    /**
     * Caracteres que un pegado puede dejar en los extremos de un correo y que no forman parte de
     * él: el espacio duro y la marca de orden de byte se suman a los que ya reconoce Java.
     *
     * @param caracter carácter a clasificar
     * @return {@code true} si es un espacio de los extremos
     */
    private static boolean esEspacioDeLosExtremos(char caracter) {
        return Character.isWhitespace(caracter) || caracter == '\u00A0' || caracter == '\uFEFF';
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
     * @param email  nuevo correo (obligatorio, 1..180 caracteres); se guarda recortado y en
     *               minúsculas
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

    /**
     * Nombre de la persona.
     *
     * @return el nombre, o {@code null} si la cuenta no lo tiene registrado
     */
    public String getNombre() {
        return nombre;
    }

    /**
     * Teléfono de contacto del trabajador.
     *
     * @return el teléfono, o {@code null} si la cuenta no lo tiene registrado
     */
    public String getTelefono() {
        return telefono;
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
     * Representación legible que omite deliberadamente la organización, el médico, el nombre, el
     * teléfono y el correo: leer las asociaciones forzaría su inicialización perezosa —y podría
     * lanzar {@code LazyInitializationException} al registrar la entidad fuera de una
     * transacción—, y el nombre, el teléfono y el correo son datos de una persona que no deben
     * acabar en un log. El {@code authUserId} tampoco se expone.
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
