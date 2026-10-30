package com.intoglobe.clinicas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.Organizacion;

/**
 * Acceso a datos de {@link Organizacion}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code existsById}, ...) siguen siendo válidos.</p>
 *
 * <p>No existe una consulta que liste todas las organizaciones: el listado de la API se
 * resuelve por la organización del token (R8.3(a), R-SEG-06), así que la capacidad de
 * enumerar tenants ajenos no se ofrece ni siquiera en la capa de datos.</p>
 */
public interface OrganizacionRepository extends JpaRepository<Organizacion, UUID> {

    /**
     * Recupera la organización activa con el identificador indicado.
     *
     * <p>Es el único listado que alimenta {@code GET /api/v1/organizaciones}: el
     * identificador es el {@code tenantId} del token, nunca uno recibido del cliente como
     * fuente de verdad. Una organización inactiva no se devuelve porque el contrato del
     * endpoint es listar lo vigente.</p>
     *
     * @param organizacionId organización del token de la petición
     * @return la organización activa, o vacío si no existe o está inactiva
     */
    @Query("SELECT o FROM Organizacion o WHERE o.id = :orgId AND o.activo = true")
    Optional<Organizacion> buscarActivaPorId(@Param("orgId") UUID organizacionId);
}
