package com.intoglobe.clinicas.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.exception.ConstraintViolationException;
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
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link UsuarioRepository} y del mapeo de {@link Usuario} contra el
 * schema real (V1 más las migraciones V2 y V3).
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo que estas
 * pruebas validan la entidad y sus consultas contra la tabla realmente migrada: el arranque del
 * contexto falla si Hibernate no puede validar el mapeo. Las restricciones que se prueban aquí
 * —unicidad de correo e identidad por organización, CHECK de rol, coherencia rol-médico y clave
 * foránea compuesta— ya existen en el esquema: estas pruebas son la evidencia de que siguen
 * vigentes y de que el modelo las respeta, no una validación que la aplicación haga por su
 * cuenta.</p>
 *
 * <p>La entidad se puebla siempre con su constructor de negocio: no se usa reflexión ni setters,
 * porque la entidad no los expone por diseño.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UsuarioRepositoryTest {

    private static final String EMAIL = "laura.mendez@example.com";
    private static final String NOMBRE_MEDICO = "Laura Méndez";

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("persiste y relee todas las columnas de la cuenta")
    void saveAndLoad_shouldPersistAllFields() {
        var organizacion = persistirOrganizacion("Clínica Centro");
        var medico = persistirMedico(organizacion, NOMBRE_MEDICO);
        var authUserId = UUID.randomUUID();
        var usuario = new Usuario(organizacion, medico, EMAIL, RolUsuario.MEDICO, authUserId);
        entityManager.persist(usuario);
        entityManager.flush();
        UUID id = usuario.getId();
        entityManager.clear();

        var encontrado = usuarioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getId()).isEqualTo(id);
        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getMedico()).isNotNull();
        assertThat(encontrado.getMedico().getId()).isEqualTo(medico.getId());
        assertThat(encontrado.getEmail()).isEqualTo(EMAIL);
        assertThat(encontrado.getRol()).isEqualTo(RolUsuario.MEDICO);
        assertThat(encontrado.getAuthUserId())
                .as("auth_user_id es obligatorio en V2 y sobrevive a la relectura")
                .isEqualTo(authUserId);
        assertThat(encontrado.isActivo()).isTrue();
        assertThat(encontrado.getCreatedAt())
                .as("created_at es NOT NULL en la base: siempre trae valor al releer")
                .isNotNull();
    }

    @Test
    @DisplayName("la cuenta arranca activa y sin created_at hasta que la base inserta la fila")
    void save_shouldStartActivatedAndFillCreatedAt() {
        var organizacion = persistirOrganizacion("Clínica Sur");
        var usuario = new Usuario(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());

        assertThat(usuario.isActivo())
                .as("el inicializador debe dejar activo en true: el default del primitivo es false")
                .isTrue();
        assertThat(usuario.getCreatedAt())
                .as("createdAt lo asigna Hibernate al insertar, no el constructor")
                .isNull();

        usuarioRepository.saveAndFlush(usuario);
        UUID id = usuario.getId();
        entityManager.clear();

        assertThat(usuarioRepository.findById(id).orElseThrow().getCreatedAt()).isNotNull();
    }

    // ─────────────────────────────────────────
    // R2 · correo normalizado a minúsculas y único por organización
    // ─────────────────────────────────────────

    @Test
    @DisplayName("guarda el correo en minúsculas aunque llegue con mayúsculas")
    void save_shouldNormalizeEmailToLowerCase() {
        var organizacion = persistirOrganizacion("Clínica Normaliza");
        var usuario = new Usuario(organizacion, null, "Laura.Mendez@Example.COM", RolUsuario.ADMIN,
                UUID.randomUUID());

        assertThat(usuario.getEmail())
                .as("la normalización ocurre en la entidad, antes de que la fila llegue a la base")
                .isEqualTo(EMAIL);

        usuarioRepository.saveAndFlush(usuario);
        UUID id = usuario.getId();
        entityManager.clear();

        assertThat(usuarioRepository.findById(id).orElseThrow().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("rechaza el mismo correo dos veces en la misma organización, aunque cambie la caja")
    void save_shouldRejectDuplicateEmailInSameOrganization() {
        var organizacion = persistirOrganizacion("Clínica Única");
        usuarioRepository.saveAndFlush(
                new Usuario(organizacion, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID()));

        var repetido = new Usuario(organizacion, null, "LAURA.MENDEZ@example.com", RolUsuario.PACIENTE,
                UUID.randomUUID());

        assertThatThrownBy(() -> usuarioRepository.saveAndFlush(repetido))
                .as("usuario_organizacion_id_email_key impide dos cuentas con el mismo correo en la "
                        + "organización, y la normalización hace que la caja no importe")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("acepta el mismo correo en dos organizaciones distintas")
    void save_shouldAcceptSameEmailInAnotherOrganization() {
        var primera = persistirOrganizacion("Clínica Uno");
        var segunda = persistirOrganizacion("Clínica Dos");

        var cuentaUno = new Usuario(primera, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());
        var cuentaDos = new Usuario(segunda, null, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());

        assertThatCode(() -> usuarioRepository.saveAllAndFlush(List.of(cuentaUno, cuentaDos)))
                .as("son dos cuentas independientes: el correo es único por organización, no global")
                .doesNotThrowAnyException();
        assertThat(usuarioRepository.listarPorOrganizacion(primera.getId())).hasSize(1);
        assertThat(usuarioRepository.listarPorOrganizacion(segunda.getId())).hasSize(1);
    }

    @Test
    @DisplayName("rechaza el mismo auth_user_id dos veces en la misma organización")
    void save_shouldRejectDuplicateAuthUserIdInSameOrganization() {
        var organizacion = persistirOrganizacion("Clínica Identidad");
        var authUserId = UUID.randomUUID();
        usuarioRepository.saveAndFlush(
                new Usuario(organizacion, null, EMAIL, RolUsuario.ADMIN, authUserId));

        var repetida = new Usuario(organizacion, null, "otra@example.com", RolUsuario.ADMIN, authUserId);

        assertThatThrownBy(() -> usuarioRepository.saveAndFlush(repetida))
                .as("usuario_auth_user_id_key impide dos cuentas con la misma identidad en la organización")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("acepta el mismo auth_user_id en dos organizaciones distintas")
    void save_shouldAcceptSameAuthUserIdInAnotherOrganization() {
        var primera = persistirOrganizacion("Clínica Identidad Uno");
        var segunda = persistirOrganizacion("Clínica Identidad Dos");
        var authUserId = UUID.randomUUID();

        assertThatCode(() -> usuarioRepository.saveAllAndFlush(List.of(
                new Usuario(primera, null, EMAIL, RolUsuario.ADMIN, authUserId),
                new Usuario(segunda, null, "otra@example.com", RolUsuario.ADMIN, authUserId))))
                .as("la unicidad de la identidad también es por organización")
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────
    // R3 · rol admitido y rechazado
    // ─────────────────────────────────────────

    @Test
    @DisplayName("admite los cuatro roles del CHECK y deja el médico opcional salvo en MEDICO")
    void save_shouldAcceptEveryRoleOfTheCheck() {
        var organizacion = persistirOrganizacion("Clínica Roles");
        var medico = persistirMedico(organizacion, NOMBRE_MEDICO);

        var cuentas = List.of(
                new Usuario(organizacion, null, "admin@example.com", RolUsuario.ADMIN, UUID.randomUUID()),
                new Usuario(organizacion, medico, "medico@example.com", RolUsuario.MEDICO, UUID.randomUUID()),
                new Usuario(organizacion, null, "paciente@example.com", RolUsuario.PACIENTE, UUID.randomUUID()),
                new Usuario(organizacion, null, "recepcion@example.com", RolUsuario.RECEPCIONISTA,
                        UUID.randomUUID()));

        assertThatCode(() -> usuarioRepository.saveAllAndFlush(cuentas))
                .as("los cuatro roles del esquema son los cuatro valores del enum")
                .doesNotThrowAnyException();
        assertThat(usuarioRepository.listarPorOrganizacion(organizacion.getId()))
                .extracting(Usuario::getRol)
                .containsExactly(RolUsuario.ADMIN, RolUsuario.MEDICO, RolUsuario.PACIENTE,
                        RolUsuario.RECEPCIONISTA);
    }

    @Test
    @DisplayName("rechaza un rol fuera del CHECK, incluso con la entidad bien mapeada")
    void save_shouldRejectRolOutsideTheCheck() {
        var organizacion = persistirOrganizacion("Clínica Rol Inválido");

        assertThatThrownBy(() -> insertarRolCrudo(organizacion.getId(), "GERENTE", "gerente@example.com"))
                .as("usuario_rol_check sigue siendo la última palabra: el enum no relaja la base")
                .isInstanceOf(ConstraintViolationException.class);
    }

    // ─────────────────────────────────────────
    // R4 · coherencia rol-médico y médico de la misma organización
    // ─────────────────────────────────────────

    @Test
    @DisplayName("rechaza un MEDICO sin su fila de médico")
    void save_shouldRejectRolMedicoWithoutMedico() {
        var organizacion = persistirOrganizacion("Clínica Sin Médico");
        var sinMedico = new Usuario(organizacion, null, EMAIL, RolUsuario.MEDICO, UUID.randomUUID());

        assertThatThrownBy(() -> usuarioRepository.saveAndFlush(sinMedico))
                .as("usuario_rol_medico_coherente_check: un MEDICO siempre tiene su fila de médico")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("rechaza un médico de otra organización")
    void save_shouldRejectMedicoFromAnotherOrganization() {
        var organizacion = persistirOrganizacion("Clínica Propia");
        var otra = persistirOrganizacion("Clínica Ajena");
        var medicoAjeno = persistirMedico(otra, "Profesional Ajeno");
        var cuenta = new Usuario(organizacion, medicoAjeno, EMAIL, RolUsuario.ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> usuarioRepository.saveAndFlush(cuenta))
                .as("la clave foránea compuesta (medico_id, organizacion_id) hace imposible asignar "
                        + "un médico de otra organización, incluso por SQL directo")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────
    // Consultas propias
    // ─────────────────────────────────────────

    @Test
    @DisplayName("lista las cuentas de la organización —activas e inactivas— ordenadas por correo")
    void listarPorOrganizacion_shouldReturnActiveAndInactiveAccountsOrderedByEmail() {
        var organizacion = persistirOrganizacion("Clínica Listado");
        var otra = persistirOrganizacion("Clínica Ajena Listado");

        persistirCuenta(organizacion, "carla@example.com", RolUsuario.ADMIN);
        var segundo = persistirCuenta(organizacion, "beatriz@example.com", RolUsuario.PACIENTE);
        persistirCuenta(organizacion, "andrea@example.com", RolUsuario.RECEPCIONISTA);
        persistirCuenta(otra, "aa.primera@example.com", RolUsuario.ADMIN);

        segundo.desactivar();
        usuarioRepository.saveAndFlush(segundo);
        entityManager.clear();

        var cuentas = usuarioRepository.listarPorOrganizacion(organizacion.getId());

        assertThat(cuentas)
                .extracting(Usuario::getEmail)
                .as("el orden por correo es parte del contrato y no depende del orden de inserción")
                .containsExactly("andrea@example.com", "beatriz@example.com", "carla@example.com");
        assertThat(cuentas)
                .extracting(Usuario::isActivo)
                .as("el listado incluye las cuentas dadas de baja para poder reactivarlas")
                .containsExactly(true, false, true);
    }

    @Test
    @DisplayName("busca por correo normalizado y respeta la organización")
    void buscarPorOrganizacionYEmail_shouldFindTheEmailOnlyInItsOrganization() {
        var organizacion = persistirOrganizacion("Clínica Búsqueda");
        var otra = persistirOrganizacion("Clínica Ajena Búsqueda");
        persistirCuenta(organizacion, "Ana.Torres@Example.COM", RolUsuario.ADMIN);

        assertThat(usuarioRepository.buscarPorOrganizacionYEmail(organizacion.getId(), "ana.torres@example.com"))
                .as("el correo se guardó normalizado, así que la búsqueda con minúsculas lo encuentra")
                .isPresent()
                .get()
                .extracting(Usuario::getEmail)
                .isEqualTo("ana.torres@example.com");
        assertThat(usuarioRepository.buscarPorOrganizacionYEmail(otra.getId(), "ana.torres@example.com"))
                .as("el correo solo es único dentro de su organización: buscarlo desde otra no lo ve")
                .isEmpty();
        assertThat(usuarioRepository.buscarPorOrganizacionYEmail(organizacion.getId(),
                "Ana.Torres@Example.COM"))
                .as("la consulta compara exacto: quien llama debe normalizar antes con "
                        + "Usuario.normalizarEmail; sin normalizar no hay coincidencia")
                .isEmpty();
    }

    @Test
    @DisplayName("cuenta solo las cuentas activas con ese rol en esa organización")
    void contarActivosPorOrganizacionYRol_shouldCountOnlyActiveAccountsOfThatRole() {
        var organizacion = persistirOrganizacion("Clínica Conteo");
        var otra = persistirOrganizacion("Clínica Ajena Conteo");
        persistirCuenta(organizacion, "admin.uno@example.com", RolUsuario.ADMIN);
        var adminDos = persistirCuenta(organizacion, "admin.dos@example.com", RolUsuario.ADMIN);
        persistirCuenta(organizacion, "paciente@example.com", RolUsuario.PACIENTE);
        persistirCuenta(otra, "admin.ajena@example.com", RolUsuario.ADMIN);

        assertThat(usuarioRepository.contarActivosPorOrganizacionYRol(organizacion.getId(), RolUsuario.ADMIN))
                .as("cuenta las administradoras activas de esa organización, no las globales")
                .isEqualTo(2);

        adminDos.desactivar();
        usuarioRepository.saveAndFlush(adminDos);

        assertThat(usuarioRepository.contarActivosPorOrganizacionYRol(organizacion.getId(), RolUsuario.ADMIN))
                .as("una administradora dada de baja no puede administrar nada, así que no cuenta")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el mapper solo puede tocar el id de las asociaciones: ambas siguen perezosas")
    void findById_shouldKeepAssociationsLazy() {
        var organizacion = persistirOrganizacion("Clínica Pérezosa");
        var medico = persistirMedico(organizacion, NOMBRE_MEDICO);
        var usuario = new Usuario(organizacion, medico, EMAIL, RolUsuario.MEDICO, UUID.randomUUID());
        entityManager.persist(usuario);
        entityManager.flush();
        UUID id = usuario.getId();
        entityManager.clear();

        var encontrado = usuarioRepository.findById(id).orElseThrow();

        assertThat(encontrado.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrado.getMedico()).isNotNull();
        assertThat(encontrado.getMedico().getId()).isEqualTo(medico.getId());
        assertThat(Hibernate.isInitialized(encontrado.getOrganizacion()))
                .as("tocar el id no debe inicializar el proxy de la organización")
                .isFalse();
        assertThat(Hibernate.isInitialized(encontrado.getMedico()))
                .as("tocar el id no debe inicializar el proxy del médico")
                .isFalse();
    }

    @Test
    @DisplayName("la igualdad se apoya solo en el id y el hash no cambia al persistir")
    void equalsAndHashCode_shouldDependOnlyOnTheId() {
        var organizacion = persistirOrganizacion("Clínica Igualdad");
        var primera = new Usuario(organizacion, null, "uno@example.com", RolUsuario.ADMIN, UUID.randomUUID());
        var segunda = new Usuario(organizacion, null, "dos@example.com", RolUsuario.ADMIN, UUID.randomUUID());

        assertThat(primera.getId()).isNull();
        assertThat(primera).isNotEqualTo(segunda);
        Set<Usuario> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        usuarioRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);
        assertThat(entityManager.getReference(Usuario.class, id)).isEqualTo(primera);
    }

    /**
     * Inserta una cuenta por SQL nativo, sin pasar por el enum, para comprobar que el CHECK de la
     * base sigue rechazando un rol que el modelo no puede expresar. Los valores son UUID generados
     * aquí mismo, igual que en la prueba de la migración, porque es la forma más directa de saltarse
     * el mapeo tipado y llegar al constraint.
     */
    private void insertarRolCrudo(UUID organizacionId, String rol, String email) {
        entityManager.createNativeQuery("INSERT INTO usuario (id, organizacion_id, email, rol,"
                        + " auth_user_id, activo) VALUES ('" + UUID.randomUUID() + "', '"
                        + organizacionId + "', '" + email + "', '" + rol + "', '"
                        + UUID.randomUUID() + "', TRUE)")
                .executeUpdate();
        entityManager.flush();
    }

    /** Persiste una cuenta activa con el correo y rol indicados. */
    private Usuario persistirCuenta(Organizacion organizacion, String email, RolUsuario rol) {
        var usuario = new Usuario(organizacion, null, email, rol, UUID.randomUUID());
        entityManager.persist(usuario);
        entityManager.flush();
        return usuario;
    }

    /** Persiste y hace flush de una organización para que tenga identidad. */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }

    /** Persiste un médico de la organización, con su consultorio, para asociarle cuentas. */
    private Medico persistirMedico(Organizacion organizacion, String nombre) {
        var consultorio = new Consultorio(organizacion, "Consultorio de " + nombre, null, null);
        entityManager.persist(consultorio);
        var medico = new Medico(organizacion, consultorio, nombre, null, null, null, null);
        entityManager.persist(medico);
        entityManager.flush();
        return medico;
    }
}
