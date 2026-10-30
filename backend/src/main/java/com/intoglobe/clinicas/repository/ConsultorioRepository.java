package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.Optional;
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

    /**
     * Recupera un consultorio solo si pertenece a la organización indicada.
     *
     * <p>Es la consulta de las tres operaciones por id ({@code GET}, {@code PUT} y
     * {@code DELETE /consultorios/{id}}): el filtro por organización viaja en el
     * {@code WHERE}, así que un consultorio de otro tenant no se carga nunca y la respuesta
     * es el mismo {@code 404} que el de un identificador inexistente (R-SEG-04, D5.6).
     * Comparar en memoria después de un {@code findById} también funcionaría, pero cargaría
     * datos de otra organización para descartarlos.</p>
     *
     * @param id             identificador del consultorio solicitado
     * @param organizacionId organización dueña según el token de la petición
     * @return el consultorio si existe <em>y</em> pertenece a esa organización; vacío en
     *         cualquier otro caso
     */
    @Query("SELECT c FROM Consultorio c WHERE c.id = :id AND c.organizacion.id = :orgId")
    Optional<Consultorio> buscarPorIdYOrganizacion(
            @Param("id") UUID id, @Param("orgId") UUID organizacionId);
}
