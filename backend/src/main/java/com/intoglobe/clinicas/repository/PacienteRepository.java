package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.Paciente;

/**
 * Acceso a datos de {@link Paciente}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code existsById}, ...) siguen siendo válidos.</p>
 */
public interface PacienteRepository extends JpaRepository<Paciente, UUID> {

    /**
     * Lista los pacientes activos de una organización, ordenados alfabéticamente
     * por nombre.
     *
     * <p>Es la consulta que alimenta el listado de pacientes, que muestra solo lo
     * vigente; los pacientes inactivos siguen siendo accesibles por su id. El orden
     * es parte del contrato: un listado sin orden estable obliga al cliente a
     * ordenar por su cuenta y hace irreproducible la respuesta. Filtra por
     * {@code p.organizacion.id} para apoyarse en el índice {@code idx_paciente_org}
     * de V1.</p>
     *
     * @param organizacionId organización dueña de los pacientes
     * @return pacientes con {@code activo = true} de esa organización, ordenados por
     *         nombre
     */
    @Query("SELECT p FROM Paciente p WHERE p.organizacion.id = :orgId AND p.activo = true ORDER BY p.nombre")
    List<Paciente> listarActivosPorOrganizacion(@Param("orgId") UUID organizacionId);
}
