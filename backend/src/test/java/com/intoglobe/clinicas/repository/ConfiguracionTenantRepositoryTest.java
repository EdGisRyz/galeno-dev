package com.intoglobe.clinicas.repository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.ConfiguracionTenant;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link ConfiguracionTenantRepository} y del mapeo de
 * {@link ConfiguracionTenant} contra el schema real de V1.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de
 * modo que estas pruebas validan la entidad y la consulta contra la tabla
 * realmente migrada: el arranque del contexto falla si Hibernate no puede validar
 * el mapeo.</p>
 *
 * <p>La entidad se puebla siempre por sus constructores de negocio, incluido el
 * constructor de payload completo: no se usa reflexión ni setters, porque la
 * entidad no los expone por diseño.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConfiguracionTenantRepositoryTest {

    private static final String NOTIF_API_KEY = "notif-clave-de-prueba";
    private static final String STORAGE_API_KEY = "storage-clave-de-prueba";
    private static final String PAYMENT_API_KEY = "payment-clave-de-prueba";
    private static final String AUDIT_API_KEY = "audit-clave-de-prueba";
    private static final String WHATSAPP_NUMERO = "+5491155550000";

    @Autowired
    private ConfiguracionTenantRepository configuracionTenantRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndLoad_shouldPersistAllFields() {
        var organizacion = persistirOrganizacion("Clínica Centro");
        var configuracion = new ConfiguracionTenant(organizacion, NOTIF_API_KEY, STORAGE_API_KEY,
                PAYMENT_API_KEY, AUDIT_API_KEY, true, WHATSAPP_NUMERO);

        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID id = configuracion.getId();
        entityManager.clear();

        var encontrada = configuracionTenantRepository.findById(id).orElseThrow();

        assertThat(encontrada.getId()).isEqualTo(id);
        assertThat(encontrada.getOrganizacion().getId()).isEqualTo(organizacion.getId());
        assertThat(encontrada.getNotifApiKey()).isEqualTo(NOTIF_API_KEY);
        assertThat(encontrada.getStorageApiKey()).isEqualTo(STORAGE_API_KEY);
        assertThat(encontrada.getPaymentApiKey()).isEqualTo(PAYMENT_API_KEY);
        assertThat(encontrada.getAuditApiKey()).isEqualTo(AUDIT_API_KEY);
        assertThat(encontrada.isWhatsappConectado()).isTrue();
        assertThat(encontrada.getWhatsappNumero()).isEqualTo(WHATSAPP_NUMERO);
        assertThat(encontrada.getCreatedAt()).isNotNull();
    }

    @Test
    void saveAndLoad_shouldKeepNullOptionalFields() {
        var organizacion = persistirOrganizacion("Consultorio Sur");
        var configuracion = new ConfiguracionTenant(organizacion);

        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID id = configuracion.getId();
        entityManager.clear();

        var encontrada = configuracionTenantRepository.findById(id).orElseThrow();

        assertThat(encontrada.getNotifApiKey()).isNull();
        assertThat(encontrada.getStorageApiKey()).isNull();
        assertThat(encontrada.getPaymentApiKey()).isNull();
        assertThat(encontrada.getAuditApiKey()).isNull();
        assertThat(encontrada.getWhatsappNumero()).isNull();
    }

    @Test
    void saveAndLoad_shouldApplyDefaultsAndKeepCreatedAtImmutable() {
        var organizacion = persistirOrganizacion("Clínica Norte");
        var configuracion = new ConfiguracionTenant(organizacion);

        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID id = configuracion.getId();

        assertThat(configuracion.getId()).isNotNull();
        assertThat(configuracion.getCreatedAt()).isNotNull();
        assertThat(configuracion.isWhatsappConectado())
                .as("el default FALSE debe materializarse sin tocar el campo")
                .isFalse();

        var createdAtAntes = configuracion.getCreatedAt().toInstant();
        entityManager.flush();

        assertThat(configuracion.getCreatedAt().toInstant())
                .as("createdAt no debe cambiar tras un flush posterior")
                .isEqualTo(createdAtAntes);

        entityManager.clear();
        var encontrada = configuracionTenantRepository.findById(id).orElseThrow();

        assertThat(encontrada.getCreatedAt().toInstant())
                .as("createdAt no debe cambiar tras releer la fila")
                .isEqualTo(createdAtAntes);
    }

    @Test
    void save_shouldRejectSecondConfigurationForTheSameOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Única");
        configuracionTenantRepository.saveAndFlush(new ConfiguracionTenant(organizacion));

        var duplicada = new ConfiguracionTenant(organizacion);

        assertThatThrownBy(() -> configuracionTenantRepository.saveAndFlush(duplicada))
                .as("el UNIQUE de organizacion_id debe impedir una segunda configuración para la misma organización")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void removeOrganizacion_shouldCascadeDeleteConfiguracionTenant() {
        var organizacion = persistirOrganizacion("Clínica Cascada");
        var configuracion = new ConfiguracionTenant(organizacion);
        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID configuracionId = configuracion.getId();
        UUID organizacionId = organizacion.getId();
        entityManager.clear();

        var organizacionAdministrada = entityManager.find(Organizacion.class, organizacionId);
        entityManager.remove(organizacionAdministrada);
        entityManager.flush();
        entityManager.clear();

        assertThat(configuracionTenantRepository.findById(configuracionId))
                .as("la FK ON DELETE CASCADE debe eliminar la configuración con la organización")
                .isEmpty();
    }

    @Test
    void buscarPorOrganizacionId_shouldReturnTheConfigurationOfEachOrganizacion() {
        var conConfiguracion = persistirOrganizacion("Clínica Con Configuración");
        var sinConfiguracion = persistirOrganizacion("Clínica Sin Configuración");
        var otraConConfiguracion = persistirOrganizacion("Consultorio Con Configuración");
        var primera = new ConfiguracionTenant(conConfiguracion);
        var otra = new ConfiguracionTenant(otraConConfiguracion);
        configuracionTenantRepository.saveAndFlush(primera);
        configuracionTenantRepository.saveAndFlush(otra);
        UUID primeraId = primera.getId();
        UUID otraId = otra.getId();
        entityManager.clear();

        assertThat(configuracionTenantRepository.buscarPorOrganizacionId(conConfiguracion.getId()))
                .get()
                .extracting(ConfiguracionTenant::getId)
                .isEqualTo(primeraId);
        assertThat(configuracionTenantRepository.buscarPorOrganizacionId(sinConfiguracion.getId()))
                .as("una organización sin configuración no debe devolver resultado")
                .isEmpty();
        assertThat(configuracionTenantRepository.buscarPorOrganizacionId(otraConConfiguracion.getId()))
                .as("cada organización distinta admite su propia configuración")
                .get()
                .extracting(ConfiguracionTenant::getId)
                .isEqualTo(otraId);
    }

    @Test
    void identity_shouldBeHashStableAndProxyAwareForTheSameRow() {
        var organizacion = persistirOrganizacion("Clínica Identidad");
        var primera = new ConfiguracionTenant(organizacion);
        var segunda = new ConfiguracionTenant(organizacion);

        assertThat(primera).isNotEqualTo(segunda);
        assertThat(primera).isNotEqualTo(null);

        Set<ConfiguracionTenant> conjunto = new HashSet<>();
        conjunto.add(primera);
        int hashAntesDePersistir = primera.hashCode();

        configuracionTenantRepository.saveAndFlush(primera);
        UUID id = primera.getId();
        entityManager.clear();

        assertThat(primera.hashCode())
                .as("el hash debe permanecer estable tras asignarse el UUID")
                .isEqualTo(hashAntesDePersistir);
        assertThat(conjunto).contains(primera);

        var proxy = entityManager.getReference(ConfiguracionTenant.class, id);

        assertThat(proxy).isEqualTo(primera);
        assertThat(proxy.hashCode()).isEqualTo(primera.hashCode());
    }

    @Test
    void findById_shouldKeepOrganizacionAssociationLazy() {
        var organizacion = persistirOrganizacion("Clínica Lazy");
        var configuracion = new ConfiguracionTenant(organizacion);
        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID id = configuracion.getId();
        entityManager.clear();

        var encontrada = configuracionTenantRepository.findById(id).orElseThrow();

        assertThat(encontrada.getOrganizacion())
                .as("la configuración debe tener su organización asociada (si no, isInitialized(null) daría un falso verde)")
                .isNotNull();
        assertThat(Hibernate.isInitialized(encontrada.getOrganizacion()))
                .as("la asociación Organizacion debe permanecer sin inicializar tras cargar la configuración")
                .isFalse();
    }

    @Test
    void removeConfiguracionTenant_shouldNotAffectOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Aislada");
        var configuracion = new ConfiguracionTenant(organizacion);
        configuracionTenantRepository.saveAndFlush(configuracion);
        UUID configuracionId = configuracion.getId();
        UUID organizacionId = organizacion.getId();
        entityManager.clear();

        var configuracionAdministrada = configuracionTenantRepository.findById(configuracionId).orElseThrow();
        entityManager.remove(configuracionAdministrada);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Organizacion.class, organizacionId))
                .as("eliminar la configuración no debe arrastrar la organización (sin cascade = REMOVE)")
                .isNotNull();
    }

    /**
     * Persiste y hace flush de una organización para que tenga identidad antes de
     * asociarle una configuración.
     */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }
}
