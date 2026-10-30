package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.Optional;
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

    /**
     * Recupera un médico solo si pertenece a la organización indicada.
     *
     * <p>Es la consulta de las tres operaciones por id ({@code GET}, {@code PUT} y
     * {@code DELETE /medicos/{id}}): el filtro por organización viaja en el {@code WHERE},
     * así que un médico de otro tenant no se carga nunca y la respuesta es el mismo
     * {@code 404} que el de un identificador inexistente (R-SEG-04, D5.6). Es la consulta
     * que cierra el IDOR que este módulo tenía abierto: antes se buscaba por id sin mirar la
     * organización.</p>
     *
     * @param id             identificador del médico solicitado
     * @param organizacionId organización dueña según el token de la petición
     * @return el médico si existe <em>y</em> pertenece a esa organización; vacío en cualquier
     *         otro caso
     */
    @Query("SELECT m FROM Medico m WHERE m.id = :id AND m.organizacion.id = :orgId")
    Optional<Medico> buscarPorIdYOrganizacion(
            @Param("id") UUID id, @Param("orgId") UUID organizacionId);
}
