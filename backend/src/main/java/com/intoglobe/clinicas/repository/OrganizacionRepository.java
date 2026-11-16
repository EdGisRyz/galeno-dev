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

    /**
     * Resuelve la organización a partir de su slug.
     *
     * <p>Es la traducción del claim {@code tenantId} cuando el emisor de tokens manda el
     * slug en lugar del UUID (S3, S11). La aplicación sigue trabajando con UUID; el texto
     * solo se usa como clave de búsqueda, una vez por petición y únicamente cuando el claim
     * no es ya un UUID.</p>
     *
     * <p>La columna {@code slug} es <strong>nulable</strong>, y una organización sin slug no
     * coincide con ningún texto: la comparación de igualdad de JPQL nunca es cierta contra un
     * valor nulo, así que no hace falta una condición adicional.</p>
     *
     * <p>No se filtra por {@code activo} a propósito: aquí se traduce una identidad, y qué se
     * puede hacer con ella lo decide la autorización, no el filtro. El camino que trae el UUID
     * tampoco comprueba que la organización exista: las dos formas son asimétricas por diseño.
     * </p>
     *
     * @param slug valor del claim {@code tenantId} cuando no es un UUID
     * @return la organización con ese slug, o vacío si no existe
     */
    @Query("SELECT o FROM Organizacion o WHERE o.slug = :slug")
    Optional<Organizacion> buscarPorSlug(@Param("slug") String slug);
}
