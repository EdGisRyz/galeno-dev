package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intoglobe.clinicas.model.Organizacion;

/**
 * Acceso a datos de {@link Organizacion}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code existsById}, ...) siguen siendo válidos.</p>
 */
public interface OrganizacionRepository extends JpaRepository<Organizacion, UUID> {

    /**
     * Lista las organizaciones activas ordenadas alfabéticamente por nombre.
     *
     * @return organizaciones con {@code activo = true}, ordenadas por nombre
     */
    @Query("SELECT o FROM Organizacion o WHERE o.activo = true ORDER BY o.nombre")
    List<Organizacion> listarActivas();
}
