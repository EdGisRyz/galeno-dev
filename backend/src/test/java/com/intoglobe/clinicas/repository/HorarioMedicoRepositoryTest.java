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
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link HorarioMedicoRepository} y del mapeo de
 * {@link HorarioMedico} contra el schema real de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo
 * que estas pruebas validan la entidad y las dos consultas contra la tabla
 * realmente migrada: el arranque del contexto falla si Hibernate no puede validar
 * el mapeo (por ejemplo, si la entidad declarara un {@code createdAt} o un
 * {@code activo} que V1 no tiene en {@code horario_medico}).</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio y sus métodos de
 * dominio: no se usa reflexión ni setters, porque la entidad no los expone por
 * diseño. Los horarios se crean sobre médicos reales persistidos con el
 * constructor de negocio de {@link Medico}, igual que en
 * {@link MedicoRepositoryTest}.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HorarioMedicoRepositoryTest {

    /** Lunes. Se usa {@code short} para espejar el tipo {@code SMALLINT} de V1. */
    private static final short LUNES = 1;
    /** Martes. */
    private static final short MARTES = 2;
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
        assertThat(encontrado.getDiaSemana()).isEqualTo(LUNES);
        assertThat(encontrado.getHoraInicio()).isEqualTo(LocalTime.of(9, 0));
        assertThat(encontrado.getHoraFin()).isEqualTo(LocalTime.of(13, 30));
        assertThat(encontrado.getDuracionConsulta()).isEqualTo(TREINTA_MINUTOS);
    }

    @Test
    void listarPorMedico_shouldExcludeOtherMedicosAndSortByDiaThenHora() {
        var medico = persistirMedico("Dr. Listado Propio");
        var otroMedico = persistirMedico("Dra. Listado Ajeno");
        // Se insertan a propósito en desorden: primero el más tardío del martes,
        // después el lunes por la tarde y por último el lunes por la mañana.
        persistirHorario(medico, MARTES, LocalTime.of(8, 0), LocalTime.of(9, 0));
        persistirHorario(medico, LUNES, LocalTime.of(15, 0), LocalTime.of(19, 0));
        persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        var ajeno = persistirHorario(otroMedico, LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0));
        entityManager.clear();

        var horarios = horarioMedicoRepository.listarPorMedico(medico.getId());

        assertThat(horarios)
                .extracting(HorarioMedico::getDiaSemana, HorarioMedico::getHoraInicio)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LUNES, LocalTime.of(9, 0)),
                        org.assertj.core.groups.Tuple.tuple(LUNES, LocalTime.of(15, 0)),
                        org.assertj.core.groups.Tuple.tuple(MARTES, LocalTime.of(8, 0)));
        assertThat(horarios)
                .extracting(HorarioMedico::getId)
                .doesNotContain(ajeno.getId());
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
     * La FK {@code horario_medico_medico_id_fkey} impone la integridad referencial:
     * un horario no puede apuntar a un médico inexistente. Se usa
     * {@code getReference} con un UUID aleatorio para que el proxy aporte el
     * identificador sin consultar la tabla y sea la base quien rechace el INSERT.
     */
    @Test
    @DisplayName("un horario huérfano viola la FK de medico y el repositorio traduce el error")
    void save_shouldRejectHorarioWithInexistentMedico() {
        var medicoInexistente = entityManager.getReference(Medico.class, UUID.randomUUID());
        var huerfano = new HorarioMedico(medicoInexistente, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0),
                TREINTA_MINUTOS);

        assertThatThrownBy(() -> horarioMedicoRepository.saveAndFlush(huerfano))
                .as("la FK de V1 debe impedir persistir un horario sin médico real")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code horario_medico_medico_id_fkey} declara {@code ON DELETE CASCADE}: los
     * horarios no sobreviven a su médico.
     */
    @Test
    void removeMedico_shouldCascadeDeleteHorarios() {
        var medico = persistirMedico("Dr. Cascada");
        var primero = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        var segundo = persistirHorario(medico, MARTES, LocalTime.of(11, 0), LocalTime.of(14, 0));
        UUID medicoId = medico.getId();
        entityManager.clear();

        var medicoAdministrado = entityManager.find(Medico.class, medicoId);
        entityManager.remove(medicoAdministrado);
        entityManager.flush();
        entityManager.clear();

        assertThat(horarioMedicoRepository.findById(primero.getId()))
                .as("la FK ON DELETE CASCADE debe eliminar los horarios con el médico")
                .isEmpty();
        assertThat(horarioMedicoRepository.findById(segundo.getId())).isEmpty();
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
     * V1 <em>no</em> valida que {@code hora_inicio < hora_fin}: el único CHECK del
     * rango horario es la duración y el día. El test documenta que un rango
     * invertido se guarda tal cual, y que por tanto la validación pertenece a la
     * capa de aplicación (servicio/DTO) y no al esquema.
     */
    @Test
    @DisplayName("la base acepta un rango invertido: la validación es de aplicación, no de esquema")
    void save_shouldStoreInvertedRangeBecauseSchemaDoesNotValidateIt() {
        var medico = persistirMedico("Dr. Rango Invertido");
        var invertido = new HorarioMedico(medico, LUNES, LocalTime.of(15, 0), LocalTime.of(9, 0),
                TREINTA_MINUTOS);

        entityManager.persist(invertido);
        entityManager.flush();
        UUID id = invertido.getId();
        entityManager.clear();

        var encontrado = horarioMedicoRepository.findById(id);

        assertThat(encontrado)
                .as("sin CHECK de rango horario en V1, la fila invertida debe persistirse")
                .isPresent();
        assertThat(encontrado.orElseThrow().getHoraInicio()).isEqualTo(LocalTime.of(15, 0));
        assertThat(encontrado.orElseThrow().getHoraFin()).isEqualTo(LocalTime.of(9, 0));
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
        var existente = persistirHorario(medico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        persistirHorario(otroMedico, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0));
        entityManager.clear();

        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(10, 0), LocalTime.of(11, 0), ID_CERO))
                .as("un bloque que empieza justo cuando termina el existente es contiguo, no solapado")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0), ID_CERO))
                .as("un bloque que termina justo cuando empieza el existente tampoco se solapa")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(9, 59), LocalTime.of(10, 59), ID_CERO))
                .as("un minuto de solapamiento real debe contar")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(8, 30), LocalTime.of(10, 30), ID_CERO))
                .as("un bloque que contiene al existente debe contar")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), MARTES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("el mismo rango en otro día de la semana no se solapa")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                otroMedico.getId(), LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
                .as("el conteo está acotado al médico: solo ve su propio bloque, no el del primero")
                .isEqualTo(1L);
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), existente.getId()))
                .as("al editar, el propio bloque debe excluirse del conteo")
                .isZero();
        assertThat(horarioMedicoRepository.contarSolapamientos(
                medico.getId(), LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0), ID_CERO))
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
     * Persiste y hace flush de un médico con su organización y consultorio, para que
     * tenga identidad antes de asociarle horarios.
     */
    private Medico persistirMedico(String nombre) {
        var organizacion = new Organizacion("Clínica Horarios", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        var consultorio = new Consultorio(organizacion, "Consultorio Horarios", null, null);
        entityManager.persist(consultorio);
        entityManager.flush();
        var medico = new Medico(organizacion, consultorio, nombre, null, null, null, new BigDecimal("500.00"));
        entityManager.persist(medico);
        entityManager.flush();
        return medico;
    }

    /**
     * Persiste y hace flush de un horario, de modo que tenga identidad y pueda usarse
     * en las consultas del repositorio.
     */
    private HorarioMedico persistirHorario(Medico medico, short diaSemana, LocalTime horaInicio,
            LocalTime horaFin) {
        var horario = new HorarioMedico(medico, diaSemana, horaInicio, horaFin, TREINTA_MINUTOS);
        entityManager.persist(horario);
        entityManager.flush();
        return horario;
    }
}
