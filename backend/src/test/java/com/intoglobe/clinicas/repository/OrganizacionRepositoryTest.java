package com.intoglobe.clinicas.repository;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración de {@link OrganizacionRepository} contra el schema real
 * de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, por
 * lo que estas pruebas validan la entidad contra la tabla real migrada
 * (el arranque del contexto falla si Hibernate no puede validar el mapeo).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizacionRepositoryTest {

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    void hashCode_shouldRemainStableAcrossUuidAssignment() {
        var organizacion = new Organizacion("Clínica Centro", PlanTipo.CLINICA, null);
        Set<Organizacion> conjunto = new HashSet<>();
        conjunto.add(organizacion);
        int hashAntesDePersistir = organizacion.hashCode();

        entityManager.persist(organizacion);
        entityManager.flush();

        assertThat(organizacion.getId()).isNotNull();
        assertThat(organizacion.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto)
                .as("la organización debe seguir localizable en un HashSet tras persistir")
                .contains(organizacion);
    }

    @Test
    void equals_shouldConsiderTwoTransientOrganizationsAsDifferent() {
        var primera = new Organizacion("Clínica Espejo", PlanTipo.SOLO, "sub-1");
        var segunda = new Organizacion("Clínica Espejo", PlanTipo.SOLO, "sub-1");

        assertThat(primera.getId()).isNull();
        assertThat(segunda.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);
    }

    @Test
    void equals_shouldBeProxyAwareForTheSamePersistedOrganization() {
        var organizacion = new Organizacion("Clínica Proxy", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();
        entityManager.clear();

        var proxy = entityManager.getReference(Organizacion.class, id);

        assertThat(proxy).isEqualTo(organizacion);
        assertThat(proxy.hashCode()).isEqualTo(organizacion.hashCode());
    }

    @Test
    void saveAndLoad_shouldPersistNullableOptionalsWithTimestamps() {
        var organizacion = new Organizacion("Consultorio Sur", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();
        entityManager.clear();

        var encontrada = organizacionRepository.findById(id).orElseThrow();

        assertThat(encontrada.getNombre()).isEqualTo("Consultorio Sur");
        assertThat(encontrada.getPlan()).isEqualTo(PlanTipo.SOLO);
        assertThat(encontrada.getSubscriptionId()).isNull();
        assertThat(encontrada.getTrialHasta()).isNull();
        assertThat(encontrada.isActivo()).isTrue();
        assertThat(encontrada.getCreatedAt()).isNotNull();
    }

    @Test
    void saveAndLoad_shouldPersistValuesAndTrialTimestamp() {
        var trialHasta = OffsetDateTime.now()
                .plusDays(30)
                .truncatedTo(ChronoUnit.MICROS);
        var organizacion = new Organizacion("Clínica Norte", PlanTipo.ENTERPRISE, "sub-norte-001");
        organizacion.actualizarDatos("Clínica Norte", PlanTipo.ENTERPRISE, "sub-norte-001", trialHasta);
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();
        entityManager.clear();

        var encontrada = organizacionRepository.findById(id).orElseThrow();

        assertThat(encontrada.getSubscriptionId()).isEqualTo("sub-norte-001");
        assertThat(encontrada.getTrialHasta()).isNotNull();
        assertThat(encontrada.getTrialHasta().toInstant()).isEqualTo(trialHasta.toInstant());
        assertThat(encontrada.getPlan()).isEqualTo(PlanTipo.ENTERPRISE);
    }

    @Test
    void listarActivas_shouldExcludeInactiveAndSortByName() {
        var zeta = new Organizacion("Zeta Clínica", PlanTipo.CLINICA, null);
        var alfa = new Organizacion("Alfa Salud", PlanTipo.SOLO, null);
        var beta = new Organizacion("Beta Medica", PlanTipo.ENTERPRISE, null);
        var inactiva = new Organizacion("Omega Inactiva", PlanTipo.SOLO, null);
        inactiva.desactivar();
        entityManager.persist(zeta);
        entityManager.persist(alfa);
        entityManager.persist(beta);
        entityManager.persist(inactiva);
        entityManager.flush();
        entityManager.clear();

        var activas = organizacionRepository.listarActivas();

        assertThat(activas)
                .extracting(Organizacion::getNombre)
                .containsExactly("Alfa Salud", "Beta Medica", "Zeta Clínica");
        assertThat(activas).allMatch(Organizacion::isActivo);
    }

    @Test
    void save_shouldAllowDuplicateNames() {
        entityManager.persist(new Organizacion("Clínica Repetida", PlanTipo.SOLO, null));
        entityManager.persist(new Organizacion("Clínica Repetida", PlanTipo.CLINICA, null));
        entityManager.flush();

        var repetidas = organizacionRepository.listarActivas().stream()
                .filter(organizacion -> "Clínica Repetida".equals(organizacion.getNombre()))
                .toList();

        assertThat(repetidas).hasSize(2);
    }

    @Test
    void desactivar_shouldBeIdempotentAndRoundTripToInactive() {
        var organizacion = new Organizacion("Clínica Baja", PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();

        organizacion.desactivar();
        organizacion.desactivar();
        entityManager.flush();
        entityManager.clear();

        var encontrada = organizacionRepository.findById(id).orElseThrow();
        assertThat(encontrada.isActivo()).isFalse();
        assertThat(organizacionRepository.listarActivas())
                .extracting(Organizacion::getId)
                .doesNotContain(id);
    }

    @Test
    void actualizarDatos_shouldPreserveImmutableFieldsAndClearOptionals() {
        var organizacion = new Organizacion("Clínica Original", PlanTipo.SOLO, "sub-original");
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();
        OffsetDateTime createdAt = organizacion.getCreatedAt();
        organizacion.desactivar();

        organizacion.actualizarDatos("Clínica Renombrada", PlanTipo.ENTERPRISE, null, null);
        entityManager.flush();
        entityManager.clear();

        var encontrada = organizacionRepository.findById(id).orElseThrow();
        assertThat(encontrada.getId()).isEqualTo(id);
        assertThat(encontrada.getCreatedAt().toInstant()).isEqualTo(createdAt.toInstant());
        assertThat(encontrada.isActivo())
                .as("actualizar una organización inactiva no debe reactivarla")
                .isFalse();
        assertThat(encontrada.getNombre()).isEqualTo("Clínica Renombrada");
        assertThat(encontrada.getPlan()).isEqualTo(PlanTipo.ENTERPRISE);
        assertThat(encontrada.getSubscriptionId()).isNull();
    }

    @Test
    void effectiveConfiguration_shouldKeepFlywayEnabledAndHibernateValidate() {
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getActiveProfiles()).contains("test");

        Map<String, Object> propiedadesJpa = entityManagerFactory.getProperties();
        assertThat(propiedadesJpa)
                .as("la configuración JPA efectiva debe validar el schema, no crear ni borrar tablas")
                .satisfiesAnyOf(
                        propiedades -> assertThat(propiedades.get("hibernate.hbm2ddl.auto")).isEqualTo("validate"),
                        propiedades -> assertThat(propiedades
                                .get("jakarta.persistence.schema-generation.database.action"))
                                .isEqualTo("validate"));
    }
}
