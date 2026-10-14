package com.intoglobe.clinicas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.ConfiguracionTenant;

/**
 * Acceso a datos de {@link ConfiguracionTenant}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code saveAndFlush}, ...) siguen siendo válidos y son el camino que usa la
 * administración de la configuración.</p>
 */
public interface ConfiguracionTenantRepository extends JpaRepository<ConfiguracionTenant, UUID> {

    /**
     * Recupera la configuración de un tenant por el id de su organización.
     *
     * <p>La consulta filtra por la asociación y no por la columna: se navega
     * {@code c.organizacion.id} para no acoplar el repositorio al nombre físico de
     * {@code organizacion_id}.</p>
     *
     * @param organizacionId id de la organización dueña de la configuración
     * @return la configuración de esa organización, o {@link Optional#empty()} si
     *         no tiene ninguna
     */
    @Query("SELECT c FROM ConfiguracionTenant c WHERE c.organizacion.id = :organizacionId")
    Optional<ConfiguracionTenant> buscarPorOrganizacionId(@Param("organizacionId") UUID organizacionId);
}
