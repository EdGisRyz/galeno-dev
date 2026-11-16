package com.intoglobe.clinicas.security;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de {@link ResolutorDeTenant} contra el esquema real.
 *
 * <p>Aquí se ejerce la mitad que no se puede simular: la traducción de un slug al
 * identificador de la organización a partir de la columna {@code slug} que agregó la
 * migración V2. La prueba del filtro usa un doble del resolutor, así que estas son las que
 * afirman que el doble se comporta como la implementación de verdad.</p>
 *
 * <p>La entidad mapea {@code slug}, así que los datos se cargan como cualquier otra columna
 * y la consulta del repositorio es JPQL. La transacción de {@code @DataJpaTest} revierte cada
 * caso.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResolutorDeTenantTest {

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Un slug conocido resuelve al UUID de su organización")
    void unSlugConocidoResuelveAlUuidDeSuOrganizacion() {
        UUID id = crearOrganizacionConSlug("acme");

        assertThat(resolutor().porSlug("acme"))
                .as("el resolutor es la traducción del claim al UUID que usa el resto de la aplicación")
                .contains(id);
    }

    @Test
    @DisplayName("Un slug desconocido no resuelve a ninguna organización")
    void unSlugDesconocidoNoResuelveANingunaOrganizacion() {
        crearOrganizacionConSlug("acme");

        assertThat(resolutor().porSlug("otra-clinica")).isEmpty();
    }

    @Test
    @DisplayName("Una organización con el slug nulo en la base no coincide con ningún texto")
    void unaOrganizacionConSlugNuloNoCoincideConNingunTexto() {
        UUID idSinSlug = crearOrganizacionConSlug(null);

        ResolutorDeTenant resolutor = resolutor();

        assertThat(resolutor.porSlug("acme")).isEmpty();
        assertThat(resolutor.porSlug("")).isEmpty();
        assertThat(resolutor.porSlug(null)).isEmpty();
        assertThat(organizacionRepository.findById(idSinSlug))
                .as("la organización sin slug sigue existiendo: lo que no hace es coincidir")
                .isPresent();
    }

    private ResolutorDeTenant resolutor() {
        return new ResolutorDeTenant(organizacionRepository);
    }

    /**
     * Crea una organización con el slug indicado (o sin él si se pasa {@code null}).
     *
     * @param slug slug a guardar, o {@code null} para dejarlo nulo
     * @return identificador de la organización creada
     */
    private UUID crearOrganizacionConSlug(String slug) {
        var organizacion = new Organizacion("Clínica de prueba", PlanTipo.CLINICA, null);
        organizacion.asignarSlug(slug);
        entityManager.persist(organizacion);
        entityManager.flush();
        UUID id = organizacion.getId();
        entityManager.clear();
        return id;
    }
}
