package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.Consultorio;

/**
 * Acceso a datos de {@link Consultorio}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code existsById}, ...) siguen siendo válidos.</p>
 */
public interface ConsultorioRepository extends JpaRepository<Consultorio, UUID> {

    /**
     * Lista los consultorios activos de una organización, ordenados
     * alfabéticamente por nombre.
     *
     * <p>Es la consulta que alimenta el listado público, que muestra solo lo
     * vigente; los consultorios inactivos siguen siendo accesibles por su id. El
     * orden es parte del contrato: un listado sin orden estable obliga al cliente a
     * ordenar por su cuenta y hace irreproducible la respuesta.</p>
     *
     * @param organizacionId organización dueña de los consultorios
     * @return consultorios con {@code activo = true} de esa organización, ordenados
     *         por nombre
     */
    @Query("SELECT c FROM Consultorio c WHERE c.organizacion.id = :orgId AND c.activo = true ORDER BY c.nombre")
    List<Consultorio> listarActivosPorOrganizacion(@Param("orgId") UUID organizacionId);
}
