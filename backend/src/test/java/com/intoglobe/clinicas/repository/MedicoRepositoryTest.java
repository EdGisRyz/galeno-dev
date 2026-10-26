package com.intoglobe.clinicas.repository;

import java.math.BigDecimal;
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
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link MedicoRepository} y del mapeo de {@link Medico}
 * contra el schema real de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo
 * que estas pruebas validan la entidad y la consulta contra la tabla realmente
 * migrada: el arranque del contexto falla si Hibernate no puede validar el mapeo
 * (por ejemplo, si la entidad declarara un {@code createdAt} que V1 no tiene).</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio y sus métodos de
 * dominio: no se usa reflexión ni setters, porque la entidad no los expone por
 * diseño.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MedicoRepositoryTest {

    private static final String NOMBRE = "Dra. Ana López";
    private static final String ESPECIALIDAD = "Cardiología";
    private static final String CEDULA = "12345678";
    private static final String TELEFONO = "+525551234567";
    private static final BigDecimal TARIFA_BASE = new BigDecimal("850.00");

    @Autowired
    private MedicoRepository medicoRepository;

    @Autowired
    private ConsultorioRepository consultorioRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndLoad_shouldPersistAllFields() {
        var organizacion = persistirOrganizacion("Clínica Centro");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Centro");
        var medico = new Medico(organizacion, consultorio, NOMBRE, ESPECIALIDAD, CEDULA, TELEFONO, TARIFA_BASE);
        entityManager.persist(medico);
        entityManager.flush();
        UUID id = medico.getId();
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getConsultorio().getId()).isEqualTo(consultorio.getId());
        assertThat(encontrado.getNombre()).isEqualTo(NOMBRE);
        assertThat(encontrado.getEspecialidad()).isEqualTo(ESPECIALIDAD);
        assertThat(encontrado.getCedula()).isEqualTo(CEDULA);
        assertThat(encontrado.getTelefono()).isEqualTo(TELEFONO);
        assertThat(encontrado.getTarifaBase()).isEqualByComparingTo(TARIFA_BASE);
        assertThat(encontrado.isActivo()).isTrue();
    }

    @Test
    void saveAndLoad_shouldKeepNullOptionalFields() {
        var organizacion = persistirOrganizacion("Clínica Sur");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Sur");
        var medico = new Medico(organizacion, consultorio, "Dr. Sin Datos", null, null, null, null);
        entityManager.persist(medico);
        entityManager.flush();
        UUID id = medico.getId();
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getEspecialidad()).isNull();
        assertThat(encontrado.getCedula()).isNull();
        assertThat(encontrado.getTelefono()).isNull();
        assertThat(encontrado.getTarifaBase()).isNull();
    }

    @Test
    void saveAndLoad_shouldApplyActiveDefault() {
        var organizacion = persistirOrganizacion("Clínica Norte");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Norte");
        var medico = new Medico(organizacion, consultorio, NOMBRE, null, null, null, null);

        assertThat(medico.isActivo())
                .as("el inicializador debe dejar activo en true: el default del primitivo es false")
                .isTrue();

        medicoRepository.saveAndFlush(medico);

        assertThat(medico.isActivo())
                .as("el default TRUE de V1 no debe depender del valor del primitivo")
                .isTrue();
    }

    @Test
    void listarActivosPorOrganizacion_shouldExcludeInactiveAndSortByName() {
        var organizacion = persistirOrganizacion("Clínica Listado");
        var otraOrganizacion = persistirOrganizacion("Clínica Ajena");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Listado");
        var consultorioAjeno = persistirConsultorio(otraOrganizacion, "Consultorio Ajeno");
        var zeta = new Medico(organizacion, consultorio, "Zeta Médico", null, null, null, null);
        var alfa = new Medico(organizacion, consultorio, "Alfa Médico", null, null, null, null);
        var inactivo = new Medico(organizacion, consultorio, "Beta Médico", null, null, null, null);
        inactivo.desactivar();
        var ajeno = new Medico(otraOrganizacion, consultorioAjeno, "Médico Ajeno", null, null, null, null);
        entityManager.persist(zeta);
        entityManager.persist(alfa);
        entityManager.persist(inactivo);
        entityManager.persist(ajeno);
        entityManager.flush();
        entityManager.clear();

        var activos = medicoRepository.listarActivosPorOrganizacion(organizacion.getId());

        assertThat(activos)
                .extracting(Medico::getNombre)
                .containsExactly("Alfa Médico", "Zeta Médico");
        assertThat(activos)
                .extracting(Medico::getId)
                .doesNotContain(inactivo.getId(), ajeno.getId());
        assertThat(activos).allMatch(Medico::isActivo);
    }

    @Test
    void desactivar_shouldBeIdempotentAndRemoveFromActiveListing() {
        var organizacion = persistirOrganizacion("Clínica Baja");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Baja");
        var medico = new Medico(organizacion, consultorio, "Dr. Baja", null, null, null, null);
        entityManager.persist(medico);
        entityManager.flush();
        UUID id = medico.getId();

        medico.desactivar();
        medico.desactivar();
        entityManager.flush();
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();
        assertThat(encontrado.isActivo())
                .as("desactivar dos veces debe ser idempotente y no reactivar al médico")
                .isFalse();
        assertThat(medicoRepository.listarActivosPorOrganizacion(organizacion.getId()))
                .extracting(Medico::getId)
                .doesNotContain(id);
    }

    @Test
    void actualizarDatos_shouldPreserveImmutableFieldsAndClearOptionals() {
        var organizacion = persistirOrganizacion("Clínica Original");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Original");
        var medico = new Medico(organizacion, consultorio, NOMBRE, ESPECIALIDAD, CEDULA, TELEFONO, TARIFA_BASE);
        medicoRepository.saveAndFlush(medico);
        UUID id = medico.getId();
        var organizacionId = organizacion.getId();
        var consultorioId = consultorio.getId();
        medico.desactivar();

        medico.actualizarDatos("Dr. Renombrado", null, null, null, null);
        medicoRepository.saveAndFlush(medico);
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();
        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.isActivo())
                .as("actualizar un médico inactivo no debe reactivarlo")
                .isFalse();
        assertThat(encontrado.getOrganizacion().getId())
                .as("actualizarDatos no debe reasignar la organización")
                .isEqualTo(organizacionId);
        assertThat(encontrado.getConsultorio().getId())
                .as("actualizarDatos no debe reasignar el consultorio")
                .isEqualTo(consultorioId);
        assertThat(encontrado.getNombre()).isEqualTo("Dr. Renombrado");
        assertThat(encontrado.getEspecialidad()).isNull();
        assertThat(encontrado.getCedula()).isNull();
        assertThat(encontrado.getTelefono()).isNull();
        assertThat(encontrado.getTarifaBase()).isNull();
    }

    @Test
    void identity_shouldBeHashStableAndProxyAwareForTheSameRow() {
        var organizacion = persistirOrganizacion("Clínica Identidad");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Identidad");
        var primera = new Medico(organizacion, consultorio, NOMBRE, ESPECIALIDAD, CEDULA, TELEFONO, TARIFA_BASE);
        var segunda = new Medico(organizacion, consultorio, NOMBRE, ESPECIALIDAD, CEDULA, TELEFONO, TARIFA_BASE);

        assertThat(primera.getId()).isNull();
        assertThat(segunda.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);

        Set<Medico> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        medicoRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);

        var proxy = entityManager.getReference(Medico.class, id);

        assertThat(proxy).isEqualTo(primera);
        assertThat(proxy.hashCode()).isEqualTo(primera.hashCode());
    }

    /**
     * La FK {@code medico_consultorio_id_fkey} de V1 impone de verdad la integridad
     * referencial: un médico no puede apuntar a un consultorio inexistente. El test
     * prueba que la restricción vive en el esquema y que el repositorio traduce el
     * error de la base a la excepción de Spring.
     *
     * <p>Se usa {@code getReference} en lugar de cargar el consultorio porque
     * buscamos precisamente una referencia inválida: el proxy aporta el UUID para la
     * columna sin consultar la tabla, y así el INSERT llega a la base y es la FK
     * quien rechaza la fila.</p>
     */
    @Test
    @DisplayName("un médico huérfano viola la FK de consultorio y el repositorio traduce el error")
    void save_shouldRejectMedicoWithInexistentConsultorio() {
        var organizacion = persistirOrganizacion("Clínica Huérfana");
        var consultorioInexistente = entityManager.getReference(Consultorio.class, UUID.randomUUID());
        var huerfano = new Medico(organizacion, consultorioInexistente, "Dr. Huérfano", null, null, null, null);

        assertThatThrownBy(() -> medicoRepository.saveAndFlush(huerfano))
                .as("la FK de V1 debe impedir persistir un médico sin consultorio real")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * {@code medico_consultorio_id_fkey} no declara {@code ON DELETE}: por defecto
     * es {@code NO ACTION}, así que borrar un consultorio con médicos queda
     * bloqueado. Es la evidencia estructural que impide el borrado físico de
     * consultorios y obliga a la baja lógica.
     */
    @Test
    @DisplayName("un consultorio con médicos no se puede borrar (la FK es NO ACTION)")
    void delete_shouldRejectConsultorioWithMedicos() {
        var organizacion = persistirOrganizacion("Clínica Bloqueo");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Bloqueo");
        var medico = new Medico(organizacion, consultorio, NOMBRE, null, null, null, null);
        medicoRepository.saveAndFlush(medico);
        UUID consultorioId = consultorio.getId();
        // Se limpia el contexto para que el médico ya no esté administrado: si
        // siguiera gestionado, Hibernate rechazaría el flush por referenciar a un
        // consultorio eliminado y nunca llegaríamos a la FK de la base.
        entityManager.clear();

        var consultorioAdministrado = consultorioRepository.findById(consultorioId).orElseThrow();

        assertThatThrownBy(() -> {
            consultorioRepository.delete(consultorioAdministrado);
            consultorioRepository.flush();
        })
                .as("la FK sin ON DELETE debe impedir borrar un consultorio que todavía tiene médicos")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void removeOrganizacion_shouldCascadeDeleteMedicos() {
        var organizacion = persistirOrganizacion("Clínica Cascada");
        // El consultorio vive en OTRA organización a propósito: H2 aplica las
        // cascadas de una misma sentencia en un orden que violaría la FK
        // medico -> consultorio si ambos se borraran desde la misma organización
        // (PostgreSQL sí resuelve el borrado en conjunto, H2 no; comportamiento
        // verificado en PostgreSQL el 2026-10-26 con datos desechables). Con el
        // consultorio fuera del alcance del DELETE, el único efecto cascada que
        // queda por probar es el que nos importa: medico.organizacion_id.
        var otraOrganizacion = persistirOrganizacion("Clínica Cascada Ajena");
        var consultorio = persistirConsultorio(otraOrganizacion, "Consultorio Cascada");
        var primero = new Medico(organizacion, consultorio, "Dr. Uno", null, null, null, null);
        var segundo = new Medico(organizacion, consultorio, "Dr. Dos", null, null, null, null);
        entityManager.persist(primero);
        entityManager.persist(segundo);
        entityManager.flush();
        UUID primeroId = primero.getId();
        UUID segundoId = segundo.getId();
        UUID organizacionId = organizacion.getId();
        UUID consultorioAjenoId = consultorio.getId();
        entityManager.clear();

        var organizacionAdministrada = entityManager.find(Organizacion.class, organizacionId);
        entityManager.remove(organizacionAdministrada);
        entityManager.flush();
        entityManager.clear();

        assertThat(medicoRepository.findById(primeroId))
                .as("la FK ON DELETE CASCADE debe eliminar los médicos con la organización")
                .isEmpty();
        assertThat(medicoRepository.findById(segundoId)).isEmpty();
        assertThat(consultorioRepository.findById(consultorioAjenoId))
                .as("el consultorio de la otra organización no debe verse afectado por la cascada")
                .isPresent();
    }

    /**
     * {@code NUMERIC(10,2)} normaliza la escala a 2 al guardar, así que la
     * comparación se hace con {@code isEqualByComparingTo}: {@code equals} de
     * {@code BigDecimal} compara la escala y {@code "500.0"} no es igual a
     * {@code "500.00"}.
     */
    @Test
    void saveAndLoad_shouldNormalizeTarifaScale() {
        var organizacion = persistirOrganizacion("Clínica Tarifa");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Tarifa");
        var medico = new Medico(organizacion, consultorio, NOMBRE, null, null, null, new BigDecimal("500.0"));
        entityManager.persist(medico);
        entityManager.flush();
        UUID id = medico.getId();
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getTarifaBase()).isEqualByComparingTo("500.00");
    }

    /**
     * El mapper de la respuesta solo puede llamar {@code getId()} sobre las
     * asociaciones: si tocara otra propiedad, inicializaría el proxy y dispararía
     * un N+1 (o un {@code LazyInitializationException} fuera de la transacción).
     * Este test fija ese invariante para las dos asociaciones.
     */
    @Test
    void findById_shouldKeepBothAssociationsLazy() {
        var organizacion = persistirOrganizacion("Clínica Lazy");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Lazy");
        var medico = new Medico(organizacion, consultorio, NOMBRE, null, null, null, null);
        entityManager.persist(medico);
        entityManager.flush();
        UUID id = medico.getId();
        entityManager.clear();

        var encontrado = medicoRepository.findById(id).orElseThrow();

        assertThat(encontrado.getOrganizacion())
                .as("el médico debe tener su organización asociada (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(encontrado.getConsultorio())
                .as("el médico debe tener su consultorio asociado (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getConsultorio().getId()).isEqualTo(consultorio.getId());
        assertThat(Hibernate.isInitialized(encontrado.getOrganizacion()))
                .as("la asociación Organizacion debe permanecer sin inicializar tras cargar el médico")
                .isFalse();
        assertThat(Hibernate.isInitialized(encontrado.getConsultorio()))
                .as("la asociación Consultorio debe permanecer sin inicializar tras cargar el médico")
                .isFalse();
    }

    /**
     * Persiste y hace flush de una organización para que tenga identidad antes de
     * asociarle consultorios y médicos.
     */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }

    /**
     * Persiste y hace flush de un consultorio para que tenga identidad antes de
     * asociarle médicos.
     */
    private Consultorio persistirConsultorio(Organizacion organizacion, String nombre) {
        var consultorio = new Consultorio(organizacion, nombre, null, null);
        entityManager.persist(consultorio);
        entityManager.flush();
        return consultorio;
    }
}
