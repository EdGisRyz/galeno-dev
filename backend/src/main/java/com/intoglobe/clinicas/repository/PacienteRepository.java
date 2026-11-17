package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.Optional;
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

    /**
     * Lista todos los pacientes de una organización —activos e inactivos—, con el mismo orden que
     * la consulta anterior.
     *
     * <p>Existe porque la baja es lógica: si el listado ocultara siempre a los inactivos, un
     * paciente dado de baja desaparecería de la pantalla y la única acción visible sería volver a
     * darlo de alta, que es exactamente cómo se duplicaba un historial. Con esta consulta la
     * pantalla puede ofrecer la reactivación.</p>
     *
     * @param organizacionId organización dueña de los pacientes
     * @return todos los pacientes de esa organización, ordenados por nombre
     */
    @Query("SELECT p FROM Paciente p WHERE p.organizacion.id = :orgId ORDER BY p.nombre")
    List<Paciente> listarPorOrganizacion(@Param("orgId") UUID organizacionId);

    /**
     * Busca un paciente por su CURP dentro de una organización.
     *
     * <p>Es la comprobación previa de la unicidad de la CURP: el servicio la usa para responder un
     * 409 con un mensaje entendible en lugar del error de integridad de la base, que llegaría como
     * 500. No la sustituye —{@code paciente_organizacion_id_curp_key}, migración V8, sigue siendo
     * la última palabra si dos altas simultáneas pasan las dos la comprobación—.</p>
     *
     * <p>Filtra siempre por organización: la misma CURP en otra clínica es otro registro, no un
     * duplicado. La CURP debe venir ya canónica —recortada y en mayúsculas—, con la misma
     * definición que usa la entidad al guardar ({@code Paciente.normalizarCurp}), de modo que
     * {@code sasc850101mdfrrn01} y {@code SASC850101MDFRRN01} compitan por el mismo hueco.</p>
     *
     * @param organizacionId organización en cuyo espacio de CURP se busca
     * @param curp           CURP canónica
     * @return el paciente con esa CURP en esa organización, o vacío si no existe allí
     */
    @Query("SELECT p FROM Paciente p WHERE p.organizacion.id = :orgId AND p.curp = :curp")
    Optional<Paciente> buscarPorOrganizacionYCurp(
            @Param("orgId") UUID organizacionId, @Param("curp") String curp);
}
