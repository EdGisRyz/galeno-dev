package com.intoglobe.clinicas.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Paciente;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link PacienteRepository} y del mapeo de
 * {@link Paciente} contra el schema real de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo
 * que estas pruebas validan la entidad y la consulta contra la tabla realmente
 * migrada: el arranque del contexto falla si Hibernate no puede validar el mapeo
 * (por ejemplo, si la entidad declarara una columna que V1 no tiene).</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio y sus métodos de
 * dominio: no se usa reflexión ni setters, porque la entidad no los expone por
 * diseño.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PacienteRepositoryTest {

    private static final String NOMBRE = "Ana María López";
    private static final String TELEFONO = "+525551234567";
    private static final LocalDate FECHA_NACIMIENTO = LocalDate.of(1985, 3, 17);
    private static final String SEXO = "F";
    private static final String EMAIL = "ana.lopez@example.com";
    private static final String NOTAS = "Alérgica a la penicilina. Prefiere citas por la mañana.";

    @Autowired
    private PacienteRepository pacienteRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndLoad_shouldPersistAllFields() {
        var organizacion = persistirOrganizacion("Clínica Centro");
        var paciente = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);
        entityManager.persist(paciente);
        entityManager.flush();
        UUID id = paciente.getId();
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();

        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getNombre()).isEqualTo(NOMBRE);
        assertThat(encontrado.getTelefono()).isEqualTo(TELEFONO);
        assertThat(encontrado.getFechaNacimiento()).isEqualTo(FECHA_NACIMIENTO);
        assertThat(encontrado.getSexo()).isEqualTo(SEXO);
        assertThat(encontrado.getEmail()).isEqualTo(EMAIL);
        assertThat(encontrado.getNotas()).isEqualTo(NOTAS);
    }

    @Test
    void saveAndLoad_shouldKeepNullOptionalFields() {
        var organizacion = persistirOrganizacion("Clínica Sur");
        var paciente = new Paciente(organizacion, "Paciente Sin Datos", null, null, null, null, null);
        entityManager.persist(paciente);
        entityManager.flush();
        UUID id = paciente.getId();
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();

        assertThat(encontrado.getNombre()).isEqualTo("Paciente Sin Datos");
        assertThat(encontrado.getTelefono()).isNull();
        assertThat(encontrado.getFechaNacimiento()).isNull();
        assertThat(encontrado.getSexo()).isNull();
        assertThat(encontrado.getEmail()).isNull();
        assertThat(encontrado.getNotas()).isNull();
    }

    @Test
    void saveAndLoad_shouldApplyDefaultsAndFillCreatedAtWithoutClientInput() {
        var organizacion = persistirOrganizacion("Clínica Norte");
        var paciente = new Paciente(organizacion, NOMBRE, null, null, null, null, null);

        assertThat(paciente.isActivo())
                .as("el inicializador debe dejar activo en true: el default del primitivo es false")
                .isTrue();
        assertThat(paciente.getCreatedAt())
                .as("createdAt lo asigna Hibernate al insertar, no el constructor: el cliente nunca lo envía")
                .isNull();

        pacienteRepository.saveAndFlush(paciente);
        UUID id = paciente.getId();
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();

        assertThat(encontrado.isActivo())
                .as("el default TRUE de V1 no debe depender del valor del primitivo")
                .isTrue();
        assertThat(encontrado.getCreatedAt())
                .as("created_at es NOT NULL en V1: Hibernate lo rellena al insertar y siempre trae valor al releer")
                .isNotNull();
    }

    @Test
    void listarActivosPorOrganizacion_shouldExcludeInactiveAndSortByName() {
        var organizacion = persistirOrganizacion("Clínica Listado");
        var otraOrganizacion = persistirOrganizacion("Clínica Ajena");
        var zeta = new Paciente(organizacion, "Zeta Paciente", null, null, null, null, null);
        var alfa = new Paciente(organizacion, "Alfa Paciente", null, null, null, null, null);
        var inactivo = new Paciente(organizacion, "Beta Paciente", null, null, null, null, null);
        inactivo.desactivar();
        var ajeno = new Paciente(otraOrganizacion, "Paciente Ajeno", null, null, null, null, null);
        entityManager.persist(zeta);
        entityManager.persist(alfa);
        entityManager.persist(inactivo);
        entityManager.persist(ajeno);
        entityManager.flush();
        entityManager.clear();

        var activos = pacienteRepository.listarActivosPorOrganizacion(organizacion.getId());

        assertThat(activos)
                .extracting(Paciente::getNombre)
                .containsExactly("Alfa Paciente", "Zeta Paciente");
        assertThat(activos)
                .extracting(Paciente::getId)
                .doesNotContain(inactivo.getId(), ajeno.getId());
        assertThat(activos).allMatch(Paciente::isActivo);
    }

    @Test
    void desactivar_shouldBeIdempotentAndRemoveFromActiveListing() {
        var organizacion = persistirOrganizacion("Clínica Baja");
        var paciente = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);
        entityManager.persist(paciente);
        entityManager.flush();
        UUID id = paciente.getId();

        paciente.desactivar();
        paciente.desactivar();
        entityManager.flush();
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();
        assertThat(encontrado.isActivo())
                .as("desactivar dos veces debe ser idempotente y no reactivar al paciente")
                .isFalse();
        assertThat(pacienteRepository.listarActivosPorOrganizacion(organizacion.getId()))
                .extracting(Paciente::getId)
                .doesNotContain(id);
    }

    @Test
    void activar_shouldBeIdempotentAndReturnToActiveListing() {
        var organizacion = persistirOrganizacion("Clínica Reingreso");
        var paciente = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);
        pacienteRepository.saveAndFlush(paciente);
        UUID id = paciente.getId();
        paciente.desactivar();
        pacienteRepository.saveAndFlush(paciente);
        entityManager.clear();

        var reactivado = pacienteRepository.findById(id).orElseThrow();
        reactivado.activar();
        reactivado.activar();
        pacienteRepository.saveAndFlush(reactivado);
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();
        assertThat(encontrado.isActivo())
                .as("activar dos veces debe ser idempotente y dejar al paciente activo")
                .isTrue();
        assertThat(pacienteRepository.listarActivosPorOrganizacion(organizacion.getId()))
                .extracting(Paciente::getId)
                .as("el paciente reactivado debe volver al listado y aparecer una sola vez")
                .containsExactly(id);
    }

    @Test
    void actualizarDatos_shouldPreserveImmutableFieldsAndClearOptionals() {
        var organizacion = persistirOrganizacion("Clínica Original");
        var paciente = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);
        pacienteRepository.saveAndFlush(paciente);
        UUID id = paciente.getId();
        var organizacionId = organizacion.getId();
        entityManager.clear();

        var administrado = pacienteRepository.findById(id).orElseThrow();
        OffsetDateTime createdAt = administrado.getCreatedAt();
        administrado.desactivar();

        administrado.actualizarDatos("Paciente Renombrado", null, null, null, null, null);
        pacienteRepository.saveAndFlush(administrado);
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();
        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getCreatedAt().toInstant())
                .as("actualizarDatos no debe reescribir la fecha de alta")
                .isEqualTo(createdAt.toInstant());
        assertThat(encontrado.isActivo())
                .as("actualizar un paciente inactivo no debe reactivarlo")
                .isFalse();
        assertThat(encontrado.getOrganizacion().getId())
                .as("actualizarDatos no debe reasignar la organización")
                .isEqualTo(organizacionId);
        assertThat(encontrado.getNombre()).isEqualTo("Paciente Renombrado");
        assertThat(encontrado.getTelefono()).isNull();
        assertThat(encontrado.getFechaNacimiento()).isNull();
        assertThat(encontrado.getSexo()).isNull();
        assertThat(encontrado.getEmail()).isNull();
        assertThat(encontrado.getNotas()).isNull();
    }

    @Test
    void identity_shouldBeHashStableAndProxyAwareForTheSameRow() {
        var organizacion = persistirOrganizacion("Clínica Identidad");
        var primera = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);
        var segunda = new Paciente(organizacion, NOMBRE, TELEFONO, FECHA_NACIMIENTO, SEXO, EMAIL, NOTAS);

        assertThat(primera.getId()).isNull();
        assertThat(segunda.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);

        Set<Paciente> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        pacienteRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);

        var proxy = entityManager.getReference(Paciente.class, id);

        assertThat(proxy).isEqualTo(primera);
        assertThat(proxy.hashCode()).isEqualTo(primera.hashCode());
    }

    /**
     * La FK {@code paciente_organizacion_id_fkey} de V1 impone de verdad la
     * integridad referencial: un paciente no puede apuntar a una organización
     * inexistente. El test prueba que la restricción vive en el esquema y que el
     * repositorio traduce el error de la base a la excepción de Spring.
     *
     * <p>Se usa {@code getReference} en lugar de cargar la organización porque
     * buscamos precisamente una referencia inválida: el proxy aporta el UUID para la
     * columna sin consultar la tabla, y así el INSERT llega a la base y es la FK
     * quien rechaza la fila.</p>
     */
    @Test
    @DisplayName("un paciente huérfano viola la FK de organizacion y el repositorio traduce el error")
    void save_shouldRejectPacienteWithInexistentOrganizacion() {
        var organizacionInexistente = entityManager.getReference(Organizacion.class, UUID.randomUUID());
        var huerfano = new Paciente(organizacionInexistente, "Paciente Huérfano", null, null, null, null, null);

        assertThatThrownBy(() -> pacienteRepository.saveAndFlush(huerfano))
                .as("la FK de V1 debe impedir persistir un paciente sin organización real")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code paciente_organizacion_id_fkey} declara {@code ON DELETE CASCADE}: dar
     * de baja una organización elimina sus pacientes en la base. Es la evidencia
     * estructural de que el borrado de pacientes no se hace uno por uno.
     */
    @Test
    void removeOrganizacion_shouldCascadeDeletePacientes() {
        var organizacion = persistirOrganizacion("Clínica Cascada");
        var primero = new Paciente(organizacion, "Paciente Uno", TELEFONO, null, null, EMAIL, NOTAS);
        var segundo = new Paciente(organizacion, "Paciente Dos", null, null, null, null, null);
        entityManager.persist(primero);
        entityManager.persist(segundo);
        entityManager.flush();
        UUID primeroId = primero.getId();
        UUID segundoId = segundo.getId();
        UUID organizacionId = organizacion.getId();
        entityManager.clear();

        var organizacionAdministrada = entityManager.find(Organizacion.class, organizacionId);
        entityManager.remove(organizacionAdministrada);
        entityManager.flush();
        entityManager.clear();

        assertThat(pacienteRepository.findById(primeroId))
                .as("la FK ON DELETE CASCADE debe eliminar los pacientes con la organización")
                .isEmpty();
        assertThat(pacienteRepository.findById(segundoId)).isEmpty();
    }

    /**
     * El CHECK {@code paciente_sexo_check} de V1 limita {@code sexo} a
     * {@code 'M', 'F', 'O'}. La entidad no valida ese dominio: lo impone la base. El
     * test incluye un valor válido para demostrar que la restricción rechaza el
     * valor ajeno y no toda la columna.
     */
    @Test
    @DisplayName("un sexo fuera del CHECK de V1 es rechazado por la base")
    void save_shouldRejectSexoOutsideTheCheck() {
        var organizacion = persistirOrganizacion("Clínica Sexo");
        var valido = new Paciente(organizacion, "Paciente Válido", null, null, "M", null, null);
        pacienteRepository.saveAndFlush(valido);

        var invalido = new Paciente(organizacion, "Paciente Inválido", null, null, "X", null, null);

        assertThatThrownBy(() -> pacienteRepository.saveAndFlush(invalido))
                .as("el CHECK de V1 debe impedir un sexo distinto de M, F u O")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * El mapper de la respuesta solo puede llamar {@code getId()} sobre la
     * asociación: si tocara otra propiedad, inicializaría el proxy y dispararía un
     * N+1 (o un {@code LazyInitializationException} fuera de la transacción). Este
     * test fija ese invariante.
     */
    @Test
    void findById_shouldKeepOrganizacionAssociationLazy() {
        var organizacion = persistirOrganizacion("Clínica Lazy");
        var paciente = new Paciente(organizacion, NOMBRE, null, null, null, null, null);
        entityManager.persist(paciente);
        entityManager.flush();
        UUID id = paciente.getId();
        entityManager.clear();

        var encontrado = pacienteRepository.findById(id).orElseThrow();

        assertThat(encontrado.getOrganizacion())
                .as("el paciente debe tener su organización asociada (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(Hibernate.isInitialized(encontrado.getOrganizacion()))
                .as("la asociación Organizacion debe permanecer sin inicializar tras cargar el paciente")
                .isFalse();
    }

    /**
     * Persiste y hace flush de una organización para que tenga identidad antes de
     * asociarle pacientes.
     */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }
}
