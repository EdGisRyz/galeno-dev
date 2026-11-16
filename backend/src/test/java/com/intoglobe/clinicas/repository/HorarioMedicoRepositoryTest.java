package com.intoglobe.clinicas.repository;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link HorarioMedicoRepository} y del mapeo de
 * {@link HorarioMedico} contra el schema real de V1, V6 y V7.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo
 * que estas pruebas validan la entidad y las tres consultas contra la tabla
 * realmente migrada: el arranque del contexto falla si Hibernate no puede validar
 * el mapeo (por ejemplo, si la entidad declarara un {@code createdAt} o un
 * {@code activo} que V1 no tiene en {@code horario_medico}, o si dejara sin mapear
 * las columnas de consultorio y organización que la V7 dejó obligatorias).</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio y sus métodos de
 * dominio: no se usa reflexión ni setters, porque la entidad no los expone por
 * diseño. Los horarios se crean sobre médicos reales persistidos con el
 * constructor de negocio de {@link Medico}, igual que en
 * {@link MedicoRepositoryTest}. Todo médico de estas pruebas queda con su
 * consultorio principal asignado, como lo deja el alta del médico: sin esa fila, la
 * clave {@code horario_medico_consultorio_asignado_fkey} de la V6 rechaza el bloque.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HorarioMedicoRepositoryTest {

    /** Lunes. Se usa {@code short} para espejar el tipo {@code SMALLINT} de V1. */
    private static final short LUNES = 1;
    /** Martes. */
    private static final short MARTES = 2;
    /** Jueves, para el caso del médico que un día atiende en otro consultorio. */
    private static final short JUEVES = 4;
    /** Duración de consulta permitida por el CHECK de V1. */
    private static final short TREINTA_MINUTOS = 30;
    /**
     * UUID todo ceros que los llamadores pasan como {@code idExcluido} al crear: la
     * columna {@code id} nunca es nula, así que el {@code <>} de la consulta no
     * excluye ninguna fila existente.
     */
    private static final UUID ID_CERO = new UUID(0L, 0L);

    @Autowired
    private HorarioMedicoRepository horarioMedicoRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndLoad_shouldPersistAllFields() {
        var medico = persistirMedico("Dr. Horario Completo");
        var horario = new HorarioMedico(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(13, 30),
                TREINTA_MINUTOS);
        entityManager.persist(horario);
        entityManager.flush();
        UUID id = horario.getId();
        entityManager.clear();

        var encontrado = horarioMedicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getMedico().getId()).isEqualTo(medico.getId());
        assertThat(encontrado.getConsultorio().getId())
                .as("un bloque siempre dice en qué consultorio se dicta (V7: columna obligatoria)")
                .isEqualTo(medico.getConsultorio().getId());
        assertThat(encontrado.getOrganizacion().getId())
                .as("la organización de la fila es la del médico, y es obligatoria desde la V7")
                .isEqualTo(medico.getOrganizacion().getId());
        assertThat(encontrado.getDiaSemana()).isEqualTo(LUNES);
        assertThat(encontrado.getHoraInicio()).isEqualTo(LocalTime.of(9, 0));
        assertThat(encontrado.getHoraFin()).isEqualTo(LocalTime.of(13, 30));
        assertThat(encontrado.getDuracionConsulta()).isEqualTo(TREINTA_MINUTOS);
    }

    @Test
    void listarPorMedico_shouldExcludeOtherMedicosAndSortByDiaThenHora() {
        var medico = persistirMedico("Dr. Listado Propio");
        var otroMedico = persistirMedico("Dra. Listado Ajeno");
        UUID medicoId = medico.getId();
        UUID organizacionId = medico.getOrganizacion().getId();
        // Se insertan a propósito en desorden: primero el más tardío del martes,
        // después el lunes por la tarde y por último el lunes por la mañana.
        persistirHorario(medico, MARTES, LocalTime.of(8, 0), LocalTime.of(9, 0));
        persistirHorario(medico, LUNES, LocalTime.of(15, 0), LocalTime.of(19, 0));
        persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        var ajeno = persistirHorario(otroMedico, LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0));
        UUID ajenoId = ajeno.getId();
        entityManager.clear();

        var horarios = horarioMedicoRepository.listarPorMedico(medicoId, organizacionId);

        assertThat(horarios)
                .extracting(HorarioMedico::getDiaSemana, HorarioMedico::getHoraInicio)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LUNES, LocalTime.of(9, 0)),
                        org.assertj.core.groups.Tuple.tuple(LUNES, LocalTime.of(15, 0)),
                        org.assertj.core.groups.Tuple.tuple(MARTES, LocalTime.of(8, 0)));
        assertThat(horarios)
                .extracting(HorarioMedico::getId)
                .doesNotContain(ajenoId);
    }

    /**
     * La agenda semanal se pinta con el nombre del consultorio de cada bloque, así que
     * {@code listarPorMedico} trae el consultorio en la misma consulta ({@code JOIN FETCH}). Sin
     * el fetch, Hibernate devolvería un proxy por bloque y el listado haría una consulta extra por
     * fila: el N+1 clásico. Esta prueba fija el fetch; si alguien lo quita, falla.
     */
    @Test
    @DisplayName("listarPorMedico trae el consultorio ya inicializado: sin una consulta extra por bloque")
    void listarPorMedico_shouldFetchTheConsultorioToAvoidNPlusOne() {
        var medico = persistirMedico("Dra. Agenda Sin N+1");
        UUID medicoId = medico.getId();
        UUID organizacionId = medico.getOrganizacion().getId();
        persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        persistirHorario(medico, MARTES, LocalTime.of(11, 0), LocalTime.of(12, 0));
        entityManager.clear();

        var horarios = horarioMedicoRepository.listarPorMedico(medicoId, organizacionId);

        assertThat(horarios).hasSize(2);
        assertThat(horarios)
                .allSatisfy(horario -> {
                    assertThat(horario.getConsultorio())
                            .as("cada bloque debe traer su consultorio asociado")
                            .isNotNull();
                    assertThat(Hibernate.isInitialized(horario.getConsultorio()))
                            .as("con JOIN FETCH el nombre del consultorio no cuesta una consulta por bloque")
                            .isTrue();
                    assertThat(horario.getConsultorio().getNombre()).isNotBlank();
                });
    }

    /**
     * Las dos consultas propias están acotadas por la organización del médico: con el
     * {@code organizacionId} de otra clínica no devuelven ni una fila ni un solapamiento,
     * aunque el {@code medicoId} sea correcto. Es el filtro que impide que una agenda ajena
     * se lea —o se sondee— desde otro tenant, y viaja en el {@code WHERE} para que valga
     * también si un llamador olvidara comprobar la pertenencia antes.
     */
    @Test
    @DisplayName("las dos consultas propias están acotadas por la organización del médico")
    void lasConsultasPropias_shouldBeScopedByOrganizacion() {
        var medico = persistirMedico("Dr. Agenda Acotada");
        var otroMedico = persistirMedico("Dra. Otra Clínica");
        UUID medicoId = medico.getId();
        UUID organizacionPropia = medico.getOrganizacion().getId();
        UUID organizacionAjena = otroMedico.getOrganizacion().getId();
        persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        var ajeno = persistirHorario(otroMedico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        UUID ajenoId = ajeno.getId();
        entityManager.clear();

        assertThat(organizacionAjena)
                .as("las dos fixtures deben vivir en organizaciones distintas para que la prueba muerda")
                .isNotEqualTo(organizacionPropia);
        assertThat(horarioMedicoRepository.listarPorMedico(medicoId, organizacionAjena))
                .as("el listado con el tenant de otra clínica debe quedar vacío aunque el médico exista")
                .isEmpty();
        assertThat(horarioMedicoRepository.contarSolapamientos(medicoId, organizacionAjena, LUNES,
                LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("el conteo de solapamientos no puede sondear la agenda de otra organización")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(medicoId, organizacionPropia, LUNES,
                LocalTime.of(9, 0), LocalTime.of(10, 0), ajenoId))
                .as("con el tenant correcto el mismo rango sí cuenta el bloque propio")
                .isEqualTo(1L);
    }

    /**
     * El CHECK {@code dia_semana >= 0 AND dia_semana <= 6} de V1 es la única defensa
     * del rango: la entidad no lo valida. Se prueban los dos extremos porque cada
     * uno cae en un lado distinto de la comparación.
     */
    @ParameterizedTest
    @ValueSource(shorts = {7, -1})
    @DisplayName("un dia_semana fuera de 0..6 viola el CHECK de V1")
    void save_shouldRejectDiaSemanaOutOfRange(short diaSemana) {
        var medico = persistirMedico("Dr. Día Inválido");
        var invalido = new HorarioMedico(medico, diaSemana, LocalTime.of(9, 0), LocalTime.of(10, 0),
                TREINTA_MINUTOS);

        assertThatThrownBy(() -> horarioMedicoRepository.saveAndFlush(invalido))
                .as("el CHECK de V1 debe impedir persistir un día de la semana fuera de 0..6")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * El CHECK {@code duracion_consulta IN (20, 30, 45, 60)} vive solo en el
     * esquema: 25 minutos es un valor razonable que el catálogo no admite.
     */
    @Test
    @DisplayName("una duracion_consulta fuera de {20,30,45,60} viola el CHECK de V1")
    void save_shouldRejectDuracionConsultaNotAllowed() {
        var medico = persistirMedico("Dr. Duración Inválida");
        var invalido = new HorarioMedico(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), (short) 25);

        assertThatThrownBy(() -> horarioMedicoRepository.saveAndFlush(invalido))
                .as("el CHECK de V1 solo admite duraciones de 20, 30, 45 y 60 minutos")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * La FK {@code horario_medico_medico_id_fkey} impone la integridad referencial: un horario no
     * puede apuntar a un médico inexistente.
     *
     * <p>Se escribe con SQL nativo porque la entidad ya no se puede construir sobre un proxy
     * vacío: el constructor deriva la organización del médico y leerla en un {@code getReference}
     * inexistente lanza {@code EntityNotFoundException} <em>antes</em> de llegar a la base. Aquí
     * lo que se quiere medir es la clave foránea, no el proxy.</p>
     */
    @Test
    @DisplayName("un horario huérfano viola la FK de medico")
    void save_shouldRejectHorarioWithInexistentMedico() {
        var existente = persistirMedico("Dra. Consultorio De La Fila Huérfana");
        UUID organizacionId = existente.getOrganizacion().getId();
        UUID consultorioId = existente.getConsultorio().getId();

        assertThatThrownBy(() -> entityManager.createNativeQuery("INSERT INTO horario_medico"
                        + " (medico_id, organizacion_id, consultorio_id, dia_semana, hora_inicio,"
                        + " hora_fin, duracion_consulta) VALUES (:medicoId, :organizacionId,"
                        + " :consultorioId, 1, '09:00:00', '10:00:00', 30)")
                .setParameter("medicoId", UUID.randomUUID())
                .setParameter("organizacionId", organizacionId)
                .setParameter("consultorioId", consultorioId)
                .executeUpdate())
                .as("la FK de V1 debe impedir persistir un horario sin médico real")
                .isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    /**
     * La baja de un médico es <strong>lógica</strong>, y el esquema lo confirma: {@code
     * horario_medico_medico_id_fkey} declara {@code ON DELETE CASCADE}, pero desde la V6 cada
     * bloque apunta además a la asignación {@code (medico_id, consultorio_id)} de
     * {@code medico_consultorio}, cuya clave no tiene cascada. Las dos cascadas compiten y el
     * borrado termina rechazado: la fila de la asignación desaparece antes que el bloque que la
     * referencia. Por eso la baja del médico es {@code activo = false} y no un {@code DELETE}.
     */
    @Test
    @DisplayName("un médico con bloques no se puede borrar físicamente: su baja es lógica")
    void removeMedico_shouldBeRejectedByTheSchema_whenItStillHasHorarios() {
        var medico = persistirMedico("Dr. Baja Física");
        var primero = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        var segundo = persistirHorario(medico, MARTES, LocalTime.of(11, 0), LocalTime.of(14, 0));
        UUID medicoId = medico.getId();
        UUID primeroId = primero.getId();
        UUID segundoId = segundo.getId();
        entityManager.clear();

        var medicoAdministrado = entityManager.find(Medico.class, medicoId);

        assertThatThrownBy(() -> {
            entityManager.remove(medicoAdministrado);
            entityManager.flush();
        })
                .as("horario_medico_consultorio_asignado_fkey impide el borrado en cascada del médico")
                .isInstanceOf(jakarta.persistence.PersistenceException.class);
        entityManager.clear();
        assertThat(horarioMedicoRepository.findById(primeroId))
                .as("nada se borró: el borrado físico del médico no es el camino de la baja")
                .isPresent();
        assertThat(horarioMedicoRepository.findById(segundoId)).isPresent();
    }

    @Test
    void identity_shouldBeHashStableAndProxyAwareForTheSameRow() {
        var medico = persistirMedico("Dr. Identidad Horario");
        var primera = new HorarioMedico(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0),
                TREINTA_MINUTOS);
        var segunda = new HorarioMedico(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0),
                TREINTA_MINUTOS);

        assertThat(primera.getId()).isNull();
        assertThat(segunda.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);

        Set<HorarioMedico> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        horarioMedicoRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);

        var proxy = entityManager.getReference(HorarioMedico.class, id);

        assertThat(proxy).isEqualTo(primera);
        assertThat(proxy.hashCode()).isEqualTo(primera.hashCode());
    }

    /**
     * El {@code toString()} de la entidad omite {@code medico} justo para no
     * inicializar la asociación; este test fija que el mapeo sea perezoso y que leer
     * el identificador del proxy no la dispare.
     */
    @Test
    void findById_shouldKeepMedicoAssociationLazy() {
        var medico = persistirMedico("Dr. Lazy Horario");
        var horario = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        UUID id = horario.getId();
        entityManager.clear();

        var encontrado = horarioMedicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getMedico())
                .as("el horario debe tener su médico asociado (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(encontrado.getMedico().getId()).isEqualTo(medico.getId());
        assertThat(Hibernate.isInitialized(encontrado.getMedico()))
                .as("la asociación Medico debe permanecer sin inicializar tras cargar el horario")
                .isFalse();
        assertThat(encontrado.toString())
                .as("el toString() no debe exponer la asociación perezosa")
                .contains("HorarioMedico{", "diaSemana=" + LUNES)
                .doesNotContain("medico=");
    }

    /**
     * V5 agregó `horario_medico_rango_check`, así que un bloque invertido ya no puede existir ni
     * escribiendo por fuera del servicio.
     *
     * Antes de esa migración esta prueba fijaba lo contrario: que la fila se guardaba tal cual y
     * que la validación pertenecía solo a la capa de aplicación. El motivo de fondo es que un rango
     * invertido no se superpone con ningún otro bloque y desactivaría la validación de
     * solapamientos, que es la regla siguiente en importancia.
     */
    @Test
    @DisplayName("la base rechaza un rango invertido: el CHECK vive en el esquema")
    void save_shouldRejectInvertedRangeBecauseSchemaValidatesIt() {
        var medico = persistirMedico("Dr. Rango Invertido");
        var invertido = new HorarioMedico(medico, LUNES, LocalTime.of(15, 0), LocalTime.of(9, 0),
                TREINTA_MINUTOS);

        entityManager.persist(invertido);

        assertThatThrownBy(() -> entityManager.flush())
                .as("horario_medico_rango_check impide un bloque invertido")
                .isInstanceOf(jakarta.persistence.PersistenceException.class)
                .hasMessageContaining("horario_medico_rango_check");
    }

    /**
     * {@code contarSolapamientos} usa el predicado de intervalo semiabierto
     * ({@code inicio < otroFin AND fin > otroInicio}), así que dos bloques
     * contiguos (09:00-10:00 y 10:00-11:00) no se solapan, que es justo lo que
     * necesita la agenda: citas consecutivas sin hueco son válidas.
     */
    @Test
    void contarSolapamientos_shouldUseHalfOpenIntervalsAndExcludeSelf() {
        var medico = persistirMedico("Dr. Solapamientos");
        var otroMedico = persistirMedico("Dra. Solapamientos Ajena");
        UUID medicoId = medico.getId();
        UUID organizacionId = medico.getOrganizacion().getId();
        UUID otroMedicoId = otroMedico.getId();
        UUID otraOrganizacionId = otroMedico.getOrganizacion().getId();
        var existente = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        UUID existenteId = existente.getId();
        persistirHorario(otroMedico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        entityManager.clear();

        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(10, 0), LocalTime.of(11, 0), ID_CERO))
                .as("un bloque que empieza justo cuando termina el existente es contiguo, no solapado")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0), ID_CERO))
                .as("un bloque que termina justo cuando empieza el existente tampoco se solapa")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(9, 59), LocalTime.of(10, 59), ID_CERO))
                .as("un minuto de solapamiento real debe contar")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(8, 30), LocalTime.of(10, 30), ID_CERO))
                .as("un bloque que contiene al existente debe contar")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, MARTES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("el mismo rango en otro día de la semana no se solapa")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                otroMedicoId, otraOrganizacionId, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("el conteo está acotado al médico: solo ve su propio bloque, no el del primero")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), existenteId))
                .as("al editar, el propio bloque debe excluirse del conteo")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medicoId, organizacionId, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("con el UUID cero al crear, solo cuenta el bloque de este médico: si el del otro"
                        + " médico entrara en la consulta, el valor sería 2")
                .isEqualTo(1L);
    }

    /**
     * {@code actualizarDatos} solo toca los cuatro campos editables: el identificador
     * y el médico dueño de la agenda son inmutables (mover un horario a otro médico
     * dejaría huérfana la agenda del primero sin decirlo).
     */
    @Test
    void actualizarDatos_shouldOnlyChangeTheFourEditableFields() {
        var medico = persistirMedico("Dr. Actualización");
        var otroMedico = persistirMedico("Dra. Actualización Ajena");
        var horario = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        UUID id = horario.getId();
        UUID medicoId = medico.getId();
        UUID otroMedicoId = otroMedico.getId();
        entityManager.clear();

        var administrado = horarioMedicoRepository.findById(id).orElseThrow();
        administrado.actualizarDatos(MARTES, LocalTime.of(16, 0), LocalTime.of(20, 0), (short) 45);
        horarioMedicoRepository.saveAndFlush(administrado);
        entityManager.clear();

        var encontrado = horarioMedicoRepository.findById(id).orElseThrow();
        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getMedico().getId())
                .as("actualizarDatos no debe reasignar el médico del horario")
                .isEqualTo(medicoId)
                .isNotEqualTo(otroMedicoId);
        assertThat(encontrado.getDiaSemana()).isEqualTo(MARTES);
        assertThat(encontrado.getHoraInicio()).isEqualTo(LocalTime.of(16, 0));
        assertThat(encontrado.getHoraFin()).isEqualTo(LocalTime.of(20, 0));
        assertThat(encontrado.getDuracionConsulta()).isEqualTo((short) 45);
    }

    /**
     * {@code listarBloquesEnConsultorio} es la consulta que el servicio usa antes de retirar un
     * consultorio, y desde el mapeo de las columnas es JPQL: mismo nombre, mismos parámetros y
     * mismo orden que cuando era nativa. El orden importa porque el {@code 409} enumera los
     * bloques tal como llegan.
     */
    @Test
    @DisplayName("listarBloquesEnConsultorio filtra por consultorio del médico y ordena por día y hora")
    void listarBloquesEnConsultorio_shouldFilterByConsultorioAndSort() {
        var organizacion = new Organizacion("Clínica Bloques Por Consultorio", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        var principal = persistirConsultorio(organizacion, "Consultorio Principal");
        var secundario = persistirConsultorio(organizacion, "Consultorio Norte");
        var medico = persistirMedico(organizacion, principal, "Dr. Bloques Por Consultorio");
        var otroMedico = persistirMedico(organizacion, principal, "Dra. Bloques Ajenos");
        UUID medicoId = medico.getId();
        asignar(medico, secundario);
        asignar(otroMedico, secundario);
        // En desorden a propósito: jueves por la tarde antes que lunes por la mañana.
        var juevesEnElNorte = persistirHorario(medico, secundario, JUEVES,
                LocalTime.of(15, 0), LocalTime.of(19, 0));
        var lunesEnElNorte = persistirHorario(medico, secundario, LUNES,
                LocalTime.of(9, 0), LocalTime.of(13, 0));
        var lunesEnElPrincipal = persistirHorario(medico, principal, LUNES,
                LocalTime.of(13, 0), LocalTime.of(15, 0));
        var delOtroMedico = persistirHorario(otroMedico, secundario, LUNES,
                LocalTime.of(9, 0), LocalTime.of(13, 0));
        UUID principalId = lunesEnElPrincipal.getId();
        UUID ajenoId = delOtroMedico.getId();
        UUID secundarioId = secundario.getId();
        entityManager.clear();

        var bloques = horarioMedicoRepository.listarBloquesEnConsultorio(medicoId, secundarioId);

        assertThat(bloques)
                .extracting(HorarioMedico::getId)
                .containsExactly(lunesEnElNorte.getId(), juevesEnElNorte.getId());
        assertThat(bloques)
                .extracting(HorarioMedico::getConsultorio)
                .allSatisfy(consultorio -> assertThat(consultorio.getId()).isEqualTo(secundarioId));
        assertThat(bloques)
                .extracting(HorarioMedico::getId)
                .as("los del consultorio principal y los de otro médico no entran")
                .doesNotContain(principalId, ajenoId);
    }

    /**
     * La clave {@code horario_medico_consultorio_asignado_fkey} de la V6 es la última línea de
     * defensa de la regla «primero asignar, después agendar»: el servicio responde 409 antes,
     * pero la base tampoco acepta un bloque en un consultorio que el médico no tiene asignado.
     */
    @Test
    @DisplayName("la base rechaza un bloque en un consultorio no asignado al médico")
    void save_shouldRejectConsultorioNotAssignedToTheMedico() {
        var medico = persistirMedico("Dr. Consultorio No Asignado");
        var sinAsignar = persistirConsultorio(medico.getOrganizacion(), "Consultorio Sin Asignar");
        var bloque = new HorarioMedico(medico, sinAsignar, LUNES, LocalTime.of(9, 0),
                LocalTime.of(10, 0), TREINTA_MINUTOS);

        assertThatThrownBy(() -> horarioMedicoRepository.saveAndFlush(bloque))
                .as("el par (medico_id, consultorio_id) tiene que existir en medico_consultorio")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Persiste y hace flush de un médico con su organización y consultorio, para que
     * tenga identidad antes de asociarle horarios.
     */
    private Medico persistirMedico(String nombre) {
        var organizacion = new Organizacion("Clínica Horarios", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return persistirMedico(organizacion, persistirConsultorio(organizacion, "Consultorio Horarios"),
                nombre);
    }

    /**
     * Persiste un médico de la organización indicada, con ese consultorio como principal, y deja
     * escrita la asignación que el alta del médico crea: sin ella, la clave de la V6 rechaza
     * cualquier bloque en el consultorio principal.
     *
     * @param organizacion organización dueña
     * @param consultorio  consultorio principal del médico
     * @param nombre       nombre del médico
     * @return el médico persistido
     */
    private Medico persistirMedico(Organizacion organizacion, Consultorio consultorio, String nombre) {
        var medico = new Medico(organizacion, consultorio, nombre, null, null, null, new BigDecimal("500.00"));
        entityManager.persist(medico);
        entityManager.flush();
        asignar(medico, consultorio);
        return medico;
    }

    /**
     * Persiste y hace flush de un consultorio de la organización indicada.
     *
     * @param organizacion organización dueña
     * @param nombre       nombre del consultorio
     * @return el consultorio persistido
     */
    private Consultorio persistirConsultorio(Organizacion organizacion, String nombre) {
        var consultorio = new Consultorio(organizacion, nombre, null, null);
        entityManager.persist(consultorio);
        entityManager.flush();
        return consultorio;
    }

    /**
     * Persiste la asignación del consultorio al médico, con la organización del médico.
     *
     * @param medico      médico al que se asigna
     * @param consultorio consultorio asignado
     * @return la asignación persistida
     */
    private MedicoConsultorio asignar(Medico medico, Consultorio consultorio) {
        var asignacion = new MedicoConsultorio(medico, consultorio, medico.getOrganizacion());
        entityManager.persist(asignacion);
        entityManager.flush();
        return asignacion;
    }

    /**
     * Persiste y hace flush de un horario en el consultorio principal del médico, de modo que
     * tenga identidad y pueda usarse en las consultas del repositorio.
     */
    private HorarioMedico persistirHorario(Medico medico, short diaSemana, LocalTime horaInicio,
            LocalTime horaFin) {
        return persistirHorario(medico, medico.getConsultorio(), diaSemana, horaInicio, horaFin);
    }

    /**
     * Persiste y hace flush de un horario en el consultorio indicado, que tiene que estar
     * asignado al médico.
     */
    private HorarioMedico persistirHorario(Medico medico, Consultorio consultorio, short diaSemana,
            LocalTime horaInicio, LocalTime horaFin) {
        var horario = new HorarioMedico(medico, consultorio, diaSemana, horaInicio, horaFin,
                TREINTA_MINUTOS);
        entityManager.persist(horario);
        entityManager.flush();
        return horario;
    }
}
