package com.intoglobe.clinicas.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
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
 * Paciente atendido en una organización. Espejo columna por columna de la tabla
 * {@code paciente} definida en la migración V1 (schema congelado).
 *
 * <p>Reglas de negocio que materializa este modelo:</p>
 * <ul>
 *   <li>Un paciente pertenece a una organización mediante un {@code @ManyToOne}
 *       perezoso y sin {@code unique}: una organización tiene muchos pacientes, y
 *       V1 solo indexa {@code organizacion_id} con {@code idx_paciente_org}. La
 *       asociación es unidireccional: {@link Organizacion} no se modifica para
 *       navegar la relación inversa.</li>
 *   <li>La asociación es {@code LAZY} porque el listado de pacientes no necesita
 *       los datos de la organización; cargarlos de forma anticipada produciría un
 *       N+1 en cada consulta.</li>
 *   <li>La baja es lógica mediante {@link #desactivar()}, operación idempotente. El
 *       borrado físico está descartado porque {@code cita.paciente_id} y
 *       {@code adjunto.paciente_id} son {@code NO ACTION} en V1: la base lo
 *       bloquearía y, si no lo hiciera, se perdería el historial clínico.</li>
 *   <li>{@link #activar()} es idempotente y existe para el paciente que vuelve
 *       tras meses de ausencia: reactivar la misma fila conserva su historial,
 *       mientras que crearlo de nuevo lo partiría en dos identificadores.</li>
 *   <li>{@link #notas} se mapea como {@link String} sin {@code length} ni
 *       {@code columnDefinition}, igual que {@code Consultorio.direccion}: la
 *       columna es {@code TEXT} en V1 y no declara límite, así que fijar uno en la
 *       entidad sería inventar una restricción que la base no tiene.</li>
 *   <li>{@link #sexo} es {@link String} de 1 carácter y no un enum de Java: los
 *       valores válidos {@code 'M', 'F', 'O'} los impone el CHECK
 *       {@code paciente_sexo_check} de V1, no esta clase.</li>
 *   <li>{@link #fechaNacimiento} es {@link LocalDate} porque la columna es
 *       {@code DATE} sin hora ni zona: una fecha de nacimiento es un dato de
 *       calendario.</li>
 *   <li>{@link #createdAt} es {@link OffsetDateTime} porque la columna es
 *       {@code TIMESTAMP WITH TIME ZONE}; {@code LocalDateTime} descartaría el
 *       desplazamiento y cambiaría el instante al leer la fila. Lo asigna el
 *       constructor de negocio y la columna no es actualizable, así que el cliente
 *       nunca lo envía ni lo puede sobrescribir.</li>
 *   <li>La ficha administrativa —{@link #curp}, {@link #ocupacion}, {@link #domicilio} y los
 *       datos del responsable— se agrega con {@link #registrarFicha(String, String, String,
 *       String, String, String)}, no con el constructor: el constructor de negocio conserva su
 *       firma de siete parámetros y la ficha es un dato que puede faltar y cambiar, no parte de
 *       la identidad con la que el paciente se crea.</li>
 *   <li>{@link #curp} se guarda <strong>canónica</strong> por
 *       {@link #normalizarCurp(String)}: recortada y en mayúsculas. Esa normalización ocurre en
 *       {@link #registrarFicha(String, String, String, String, String, String)}, que es la única
 *       vía de escritura de la columna, de modo que {@code sasc850101mdfrrn01} y
 *       {@code SASC850101MDFRRN01} son el mismo paciente. La unicidad la impone la base
 *       ({@code paciente_organizacion_id_curp_key}, migración V8) y no esta clase. Un valor
 *       vacío se guarda como {@code null}: sin eso, dos pacientes «sin CURP» enviarían la cadena
 *       vacía y colisionarían entre sí dentro de la organización.</li>
 *   <li>La validación de entrada (nombre, longitudes, formato de correo y estructura de la CURP)
 *       vive en el DTO de petición, nunca en esta entidad.</li>
 * </ul>
 */
@Entity
@Table(name = "paciente")
public class Paciente {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizacion_id", nullable = false)
    private Organizacion organizacion;

    @Column(name = "nombre", length = 160, nullable = false)
    private String nombre;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    /**
     * Sexo registrado, un solo carácter. El CHECK {@code paciente_sexo_check} de V1
     * limita los valores a {@code 'M', 'F', 'O'}.
     */
    @Column(name = "sexo", length = 1)
    private String sexo;

    @Column(name = "email", length = 180)
    private String email;

    /** Notas libres. La columna es {@code TEXT}: sin límite declarado en V1. */
    @Column(name = "notas")
    private String notas;

    /**
     * CURP del paciente, opcional. La columna es {@code VARCHAR(18)} y la única
     * ({@code paciente_organizacion_id_curp_key}, V8) dentro de la organización. Se guarda
     * canónica mediante {@link #normalizarCurp(String)}.
     */
    @Column(name = "curp", length = 18)
    private String curp;

    @Column(name = "ocupacion", length = 120)
    private String ocupacion;

    @Column(name = "domicilio", length = 240)
    private String domicilio;

    @Column(name = "nombre_responsable", length = 160)
    private String nombreResponsable;

    @Column(name = "telefono_responsable", length = 20)
    private String telefonoResponsable;

    @Column(name = "parentesco", length = 40)
    private String parentesco;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    /**
     * Marca temporal de creación, asignada por Hibernate con {@link CreationTimestamp} al insertar — el
     * mismo patrón que {@code Consultorio} y {@code Organizacion}. La columna es
     * {@code TIMESTAMP WITH TIME ZONE} en V1, de ahí {@link OffsetDateTime}: con
     * {@link java.time.LocalDateTime} se perdería el desplazamiento horario y el instante dejaría de ser el
     * mismo. Es {@code updatable = false} para que ninguna actualización lo reescriba, y el cliente nunca lo
     * envía.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Constructor requerido por JPA. No debe usarse desde código de negocio; para
     * crear un paciente utilice
     * {@link #Paciente(Organizacion, String, String, LocalDate, String, String, String)}.
     */
    protected Paciente() {
    }

    /**
     * Crea un paciente nuevo con {@code activo = true} y {@code createdAt} tomado
     * del reloj en este momento.
     *
     * <p>{@code createdAt} no es un parámetro: es un dato que el cliente no puede
     * fijar, y por eso la firma no lo expone.</p>
     *
     * @param organizacion    organización a la que pertenece el paciente (obligatoria)
     * @param nombre          nombre del paciente (obligatorio, 1..160 caracteres)
     * @param telefono        teléfono de contacto (opcional, máx. 20 caracteres)
     * @param fechaNacimiento fecha de nacimiento (opcional, {@code DATE})
     * @param sexo            sexo registrado (opcional, uno de {@code 'M', 'F', 'O'})
     * @param email           correo electrónico (opcional, máx. 180 caracteres)
     * @param notas           notas libres (opcional, columna {@code TEXT})
     */
    public Paciente(Organizacion organizacion, String nombre, String telefono, LocalDate fechaNacimiento,
            String sexo, String email, String notas) {
        this.organizacion = organizacion;
        this.nombre = nombre;
        this.telefono = telefono;
        this.fechaNacimiento = fechaNacimiento;
        this.sexo = sexo;
        this.email = email;
        this.notas = notas;
    }

    /**
     * Actualiza exclusivamente los seis campos editables del paciente.
     *
     * <p>Un valor {@code null} en cualquier opcional borra el valor anterior (la
     * actualización PUT es un reemplazo). Nunca modifica {@code id}, {@code activo},
     * {@code createdAt} ni {@code organizacion}: actualizar un paciente inactivo no
     * lo reactiva, la fecha de alta no se reescribe, y mover al paciente de
     * organización dejaría su historial clínico en otra organización.</p>
     *
     * @param nombre          nuevo nombre (obligatorio, 1..160 caracteres)
     * @param telefono        nuevo teléfono o {@code null} para borrarlo
     * @param fechaNacimiento nueva fecha de nacimiento o {@code null} para borrarla
     * @param sexo            nuevo sexo o {@code null} para borrarlo
     * @param email           nuevo correo o {@code null} para borrarlo
     * @param notas           nuevas notas o {@code null} para borrarlas
     */
    public void actualizarDatos(String nombre, String telefono, LocalDate fechaNacimiento, String sexo,
            String email, String notas) {
        this.nombre = nombre;
        this.telefono = telefono;
        this.fechaNacimiento = fechaNacimiento;
        this.sexo = sexo;
        this.email = email;
        this.notas = notas;
    }

    /**
     * Registra la ficha administrativa del paciente: CURP, ocupación, domicilio y los datos del
     * responsable.
     *
     * <p>Es una operación aparte del constructor y no una ampliación de su firma: el paciente se
     * crea con los siete datos que lo identifican y la ficha se completa después —o nunca, porque
     * los seis campos son opcionales—. Un valor {@code null} en cualquiera borra el valor
     * anterior, igual que {@link #actualizarDatos(String, String, LocalDate, String, String,
     * String)}: la actualización PUT es un reemplazo.</p>
     *
     * <p>Nunca modifica {@code id}, {@code organizacion}, {@code activo} ni {@code createdAt}: la
     * ficha describe a la persona, no cambia de clínica, no revive una baja lógica y no reescribe
     * la fecha de alta.</p>
     *
     * @param curp               CURP del paciente, o {@code null} si no se registró; se guarda
     *                           canónica (recortada y en mayúsculas)
     * @param ocupacion          ocupación del paciente, o {@code null}
     * @param domicilio          domicilio del paciente, o {@code null}
     * @param nombreResponsable  nombre del responsable, o {@code null}
     * @param telefonoResponsable teléfono del responsable, o {@code null}
     * @param parentesco         parentesco del responsable con el paciente, o {@code null}
     */
    public void registrarFicha(String curp, String ocupacion, String domicilio, String nombreResponsable,
            String telefonoResponsable, String parentesco) {
        this.curp = normalizarCurp(curp);
        this.ocupacion = ocupacion;
        this.domicilio = domicilio;
        this.nombreResponsable = nombreResponsable;
        this.telefonoResponsable = telefonoResponsable;
        this.parentesco = parentesco;
    }

    /**
     * Normaliza una CURP para almacenarla y para buscarla.
     *
     * <p>Es la única definición de «la misma CURP» del módulo, y por eso es estática y pública: la
     * usan esta entidad al escribir, el DTO de petición —para que la validación estructural mire
     * el valor ya canónico— y el servicio cuando consulta si una CURP ya está ocupada. Sin un
     * único lugar, guardar en mayúsculas y buscar en minúsculas daría dos respuestas distintas a
     * la misma pregunta.</p>
     *
     * <p>Solo recorta y cambia la caja, que es lo que la decisión de producto fijó: dos CURP que
     * difieren únicamente en mayúsculas o en espacios son la misma persona. Una entrada vacía o en
     * blanco se convierte en {@code null} para que los pacientes sin CURP no colisionen entre sí en
     * la restricción única, y un {@code null} se conserva como {@code null}.</p>
     *
     * <p>Es idempotente: normalizar un valor ya canónico lo devuelve igual, así que las capas
     * pueden llamarla sin coordinarse.</p>
     *
     * @param curp CURP tal como llegó del cliente, o {@code null}
     * @return la CURP recortada y en mayúsculas, o {@code null} si la entrada era nula o vacía
     */
    public static String normalizarCurp(String curp) {
        if (curp == null) {
            return null;
        }
        var recortada = curp.strip();
        return recortada.isEmpty() ? null : recortada.toUpperCase(Locale.ROOT);
    }

    /**
     * Eliminación lógica e idempotente: desactivar un paciente que ya está inactivo
     * no produce error (sigue siendo una operación válida). Solo cambia
     * {@code activo}.
     */
    public void desactivar() {
        this.activo = false;
    }

    /**
     * Reactivación lógica e idempotente: activar un paciente que ya está activo no
     * produce error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p>Existe porque un paciente que vuelve a la clínica después de meses debe
     * seguir siendo el mismo registro: crearlo de nuevo partiría su historial
     * clínico entre dos identificadores, y las citas y adjuntos anteriores quedarían
     * colgados del viejo.</p>
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

    public String getNombre() {
        return nombre;
    }

    public String getTelefono() {
        return telefono;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public String getSexo() {
        return sexo;
    }

    public String getEmail() {
        return email;
    }

    public String getNotas() {
        return notas;
    }

    public String getCurp() {
        return curp;
    }

    public String getOcupacion() {
        return ocupacion;
    }

    public String getDomicilio() {
        return domicilio;
    }

    public String getNombreResponsable() {
        return nombreResponsable;
    }

    public String getTelefonoResponsable() {
        return telefonoResponsable;
    }

    public String getParentesco() {
        return parentesco;
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
        if (!(obj instanceof Paciente otro)) {
            return false;
        }
        return getId() != null && getId().equals(otro.getId());
    }

    /**
     * Hash estable durante todo el ciclo de vida de la entidad. Se calcula a partir
     * de la clase y no de {@code id} ni de campos mutables de negocio: así el valor
     * no cambia cuando Hibernate asigna el UUID al persistir, ni cuando
     * {@link #actualizarDatos(String, String, LocalDate, String, String, String)}
     * cambia los datos, y la entidad sigue siendo localizable en colecciones basadas
     * en hash.
     */
    @Override
    public final int hashCode() {
        return Paciente.class.hashCode();
    }

    /**
     * Representación legible que omite deliberadamente la organización, el correo y
     * las notas: leer la organización forzaría la inicialización de la asociación
     * perezosa y podría lanzar {@code LazyInitializationException} al registrar la
     * entidad fuera de una transacción, y el correo y las notas son datos de
     * contacto y clínicos que no deben acabar en un log.
     */
    @Override
    public String toString() {
        return "Paciente{id=" + id
                + ", nombre='" + nombre + '\''
                + ", activo=" + activo
                + ", createdAt=" + createdAt
                + '}';
    }
}
