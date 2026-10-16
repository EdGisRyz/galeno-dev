package com.intoglobe.clinicas.repository;

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

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link ConsultorioRepository} y del mapeo de
 * {@link Consultorio} contra el schema real de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de
 * modo que estas pruebas validan la entidad y la consulta contra la tabla
 * realmente migrada: el arranque del contexto falla si Hibernate no puede validar
 * el mapeo.</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio y sus métodos de
 * dominio: no se usa reflexión ni setters, porque la entidad no los expone por
 * diseño.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConsultorioRepositoryTest {

    private static final String NOMBRE = "Consultorio Centro";
    private static final String DIRECCION = "Av. Reforma 123, Ciudad de México";
    private static final String TELEFONO = "+525555555555";

    @Autowired
    private ConsultorioRepository consultorioRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndLoad_shouldPersistAllFields() {
        var organizacion = persistirOrganizacion("Clínica Centro");
        var consultorio = new Consultorio(organizacion, NOMBRE, DIRECCION, TELEFONO);
        entityManager.persist(consultorio);
        entityManager.flush();
        UUID id = consultorio.getId();
        entityManager.clear();

        var encontrado = consultorioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getNombre()).isEqualTo(NOMBRE);
        assertThat(encontrado.getDireccion()).isEqualTo(DIRECCION);
        assertThat(encontrado.getTelefono()).isEqualTo(TELEFONO);
        assertThat(encontrado.isActivo()).isTrue();
        assertThat(encontrado.getCreatedAt()).isNotNull();
    }

    @Test
    void saveAndLoad_shouldKeepNullOptionalFields() {
        var organizacion = persistirOrganizacion("Consultorio Sur");
        var consultorio = new Consultorio(organizacion, "Consultorio Sin Datos", null, null);
        entityManager.persist(consultorio);
        entityManager.flush();
        UUID id = consultorio.getId();
        entityManager.clear();

        var encontrado = consultorioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getDireccion()).isNull();
        assertThat(encontrado.getTelefono()).isNull();
    }

    @Test
    void saveAndLoad_shouldApplyDefaultsAndKeepCreatedAtImmutable() {
        var organizacion = persistirOrganizacion("Clínica Norte");
        var consultorio = new Consultorio(organizacion, "Consultorio Norte", DIRECCION, TELEFONO);
        consultorioRepository.saveAndFlush(consultorio);
        UUID id = consultorio.getId();

        assertThat(consultorio.getId()).isNotNull();
        assertThat(consultorio.getCreatedAt()).isNotNull();
        assertThat(consultorio.isActivo())
                .as("el default TRUE debe materializarse sin tocar el campo")
                .isTrue();

        var createdAtAntes = consultorio.getCreatedAt().toInstant();
        entityManager.flush();

        assertThat(consultorio.getCreatedAt().toInstant())
                .as("createdAt no debe cambiar tras un flush posterior")
                .isEqualTo(createdAtAntes);

        entityManager.clear();
        var encontrado = consultorioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getCreatedAt().toInstant())
                .as("createdAt no debe cambiar tras releer la fila")
                .isEqualTo(createdAtAntes);
    }

    @Test
    void listarActivosPorOrganizacion_shouldExcludeInactiveAndSortByName() {
        var organizacion = persistirOrganizacion("Clínica Listado");
        var otraOrganizacion = persistirOrganizacion("Clínica Ajena");
        var zeta = new Consultorio(organizacion, "Zeta Consultorio", DIRECCION, TELEFONO);
        var alfa = new Consultorio(organizacion, "Alfa Consultorio", DIRECCION, TELEFONO);
        var inactivo = new Consultorio(organizacion, "Beta Consultorio", DIRECCION, TELEFONO);
        inactivo.desactivar();
        var ajeno = new Consultorio(otraOrganizacion, "Consultorio Ajeno", DIRECCION, TELEFONO);
        entityManager.persist(zeta);
        entityManager.persist(alfa);
        entityManager.persist(inactivo);
        entityManager.persist(ajeno);
        entityManager.flush();
        entityManager.clear();

        var activos = consultorioRepository.listarActivosPorOrganizacion(organizacion.getId());

        assertThat(activos)
                .extracting(Consultorio::getNombre)
                .containsExactly("Alfa Consultorio", "Zeta Consultorio");
        assertThat(activos)
                .extracting(Consultorio::getId)
                .doesNotContain(inactivo.getId(), ajeno.getId());
        assertThat(activos).allMatch(Consultorio::isActivo);
    }

    @Test
    void desactivar_shouldBeIdempotentAndRemoveFromActiveListing() {
        var organizacion = persistirOrganizacion("Clínica Baja");
        var consultorio = new Consultorio(organizacion, "Consultorio Baja", DIRECCION, TELEFONO);
        entityManager.persist(consultorio);
        entityManager.flush();
        UUID id = consultorio.getId();

        consultorio.desactivar();
        consultorio.desactivar();
        entityManager.flush();
        entityManager.clear();

        var encontrado = consultorioRepository.findById(id).orElseThrow();
        assertThat(encontrado.isActivo()).isFalse();
        assertThat(consultorioRepository.listarActivosPorOrganizacion(organizacion.getId()))
                .extracting(Consultorio::getId)
                .doesNotContain(id);
    }

    @Test
    void actualizarDatos_shouldPreserveImmutableFieldsAndClearOptionals() {
        var organizacion = persistirOrganizacion("Clínica Original");
        var consultorio = new Consultorio(organizacion, "Consultorio Original", DIRECCION, TELEFONO);
        consultorioRepository.saveAndFlush(consultorio);
        UUID id = consultorio.getId();
        OffsetDateTime createdAt = consultorio.getCreatedAt();
        consultorio.desactivar();

        consultorio.actualizarDatos("Consultorio Renombrado", null, null);
        consultorioRepository.saveAndFlush(consultorio);
        entityManager.clear();

        var encontrado = consultorioRepository.findById(id).orElseThrow();
        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getCreatedAt().toInstant()).isEqualTo(createdAt.toInstant());
        assertThat(encontrado.isActivo())
                .as("actualizar un consultorio inactivo no debe reactivarlo")
                .isFalse();
        assertThat(encontrado.getOrganizacion().getId())
                .as("actualizarDatos no debe reasignar la organización")
                .isEqualTo(organizacion.getId());
        assertThat(encontrado.getNombre()).isEqualTo("Consultorio Renombrado");
        assertThat(encontrado.getDireccion()).isNull();
        assertThat(encontrado.getTelefono()).isNull();
    }

    @Test
    void identity_shouldBeHashStableAndProxyAwareForTheSameRow() {
        var organizacion = persistirOrganizacion("Clínica Identidad");
        var primera = new Consultorio(organizacion, NOMBRE, DIRECCION, TELEFONO);
        var segunda = new Consultorio(organizacion, NOMBRE, DIRECCION, TELEFONO);

        assertThat(primera.getId()).isNull();
        assertThat(segunda.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);

        Set<Consultorio> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        consultorioRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);

        var proxy = entityManager.getReference(Consultorio.class, id);

        assertThat(proxy).isEqualTo(primera);
        assertThat(proxy.hashCode()).isEqualTo(primera.hashCode());
    }

    @Test
    void save_shouldAllowDuplicateNamesInTheSameOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Duplicados");
        consultorioRepository.saveAndFlush(new Consultorio(organizacion, "Consultorio Repetido", DIRECCION, TELEFONO));
        consultorioRepository.saveAndFlush(new Consultorio(organizacion, "Consultorio Repetido", null, null));

        var repetidos = consultorioRepository.listarActivosPorOrganizacion(organizacion.getId()).stream()
                .filter(consultorio -> "Consultorio Repetido".equals(consultorio.getNombre()))
                .toList();

        assertThat(repetidos)
                .as("V1 no declara unicidad: dos consultorios de la misma organización pueden llamarse igual")
                .hasSize(2);
    }

    @Test
    void removeOrganizacion_shouldCascadeDeleteConsultorios() {
        var organizacion = persistirOrganizacion("Clínica Cascada");
        var primero = new Consultorio(organizacion, "Consultorio Uno", DIRECCION, TELEFONO);
        var segundo = new Consultorio(organizacion, "Consultorio Dos", null, null);
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

        assertThat(consultorioRepository.findById(primeroId))
                .as("la FK ON DELETE CASCADE debe eliminar los consultorios con la organización")
                .isEmpty();
        assertThat(consultorioRepository.findById(segundoId)).isEmpty();
    }

    @Test
    void findById_shouldKeepOrganizacionAssociationLazy() {
        var organizacion = persistirOrganizacion("Clínica Lazy");
        var consultorio = new Consultorio(organizacion, "Consultorio Lazy", DIRECCION, TELEFONO);
        entityManager.persist(consultorio);
        entityManager.flush();
        UUID id = consultorio.getId();
        entityManager.clear();

        var encontrado = consultorioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getOrganizacion())
                .as("el consultorio debe tener su organización asociada (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(Hibernate.isInitialized(encontrado.getOrganizacion()))
                .as("la asociación Organizacion debe permanecer sin inicializar tras cargar el consultorio")
                .isFalse();
    }

    /**
     * La FK {@code consultorio_organizacion_id_fkey} de V1 impone de verdad la
     * integridad referencial: un consultorio no puede apuntar a una organización
     * inexistente. El test existe para probar que la restricción vive en el
     * esquema y no solo en el código de negocio, y que el repositorio traduce el
     * error de la base a la excepción de Spring.
     *
     * <p>Se usa {@code getReference} en lugar de cargar la organización porque
     * buscamos precisamente una referencia inválida: el proxy aporta el UUID para
     * la columna sin consultar la tabla, y así el INSERT llega a la base y es la
     * FK quien rechaza la fila, sin que Hibernate falle antes por su cuenta.</p>
     */
    @Test
    @DisplayName("un consultorio huérfano viola la FK de organizacion y el repositorio traduce el error")
    void save_shouldRejectConsultorioWithInexistentOrganizacion() {
        var organizacionInexistente = entityManager.getReference(Organizacion.class, UUID.randomUUID());
        var huerfano = new Consultorio(organizacionInexistente, "Consultorio Huérfano", null, null);

        assertThatThrownBy(() -> consultorioRepository.saveAndFlush(huerfano))
                .as("la FK de V1 debe impedir persistir un consultorio sin organización real")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Persiste y hace flush de una organización para que tenga identidad antes de
     * asociarle consultorios.
     */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }
}
