package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.Medico;

/**
 * Acceso a datos de {@link Medico}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code existsById}, ...) siguen siendo válidos.</p>
 */
public interface MedicoRepository extends JpaRepository<Medico, UUID> {

    /**
     * Lista los médicos activos de una organización, ordenados alfabéticamente por
     * nombre.
     *
     * <p>Es la consulta que alimenta el listado público, que muestra solo lo
     * vigente; los médicos inactivos siguen siendo accesibles por su id. El orden es
     * parte del contrato: un listado sin orden estable obliga al cliente a ordenar
     * por su cuenta y hace irreproducible la respuesta. Filtra por
     * {@code m.organizacion.id} para apoyarse en el índice {@code idx_medico_org} de
     * V1; no existe un listado por consultorio porque V1 no indexa esa columna.</p>
     *
     * @param organizacionId organización dueña de los médicos
     * @return médicos con {@code activo = true} de esa organización, ordenados por
     *         nombre
     */
    @Query("SELECT m FROM Medico m WHERE m.organizacion.id = :orgId AND m.activo = true ORDER BY m.nombre")
    List<Medico> listarActivosPorOrganizacion(@Param("orgId") UUID organizacionId);
}
