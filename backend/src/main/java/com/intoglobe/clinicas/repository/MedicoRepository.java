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
     * Lista todos los médicos de una organización —activos e inactivos—, con el mismo orden que
     * la consulta anterior.
     *
     * <p>Existe porque la baja es lógica: si el listado ocultara siempre a los inactivos, un
     * médico dado de baja desaparecería de la pantalla y la reactivación sería inalcanzable —no
     * quedaría fila donde pulsarla— (S8). La pantalla la pide con {@code incluirInactivos=true};
     * sin el parámetro se sigue usando la consulta de activos, y ese es el contrato por defecto.</p>
     *
     * @param organizacionId organización dueña de los médicos
     * @return todos los médicos de esa organización, ordenados por nombre
     */
    @Query("SELECT m FROM Medico m WHERE m.organizacion.id = :orgId ORDER BY m.nombre")
    List<Medico> listarPorOrganizacion(@Param("orgId") UUID organizacionId);

    /**
     * Recupera un médico solo si pertenece a la organización indicada.
     *
     * <p>Es la consulta de las cuatro operaciones por id ({@code GET}, {@code PUT}, {@code DELETE} y
     * {@code POST /activar}): el filtro por organización viaja en el {@code WHERE},
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

    /**
     * Cuenta los médicos <strong>activos</strong> de la organización que tienen el consultorio
     * indicado como principal ({@code medico.consultorio_id}).
     *
     * <p>Es la consulta que sostiene la invariante «un médico activo tiene un consultorio
     * principal activo» (S6): antes de dar de baja un consultorio se cuenta cuántos médicos
     * activos lo tienen como principal, y un resultado mayor que cero responde {@code 409}. El
     * filtro por organización acompaña al de la baja del consultorio —que ya resolvió el recurso
     * dentro del tenant—, de modo que el conteo nunca puede incluir médicos de otra clínica. Los
     * médicos inactivos no cuentan: su principal puede darse de baja y la reactivación del médico
     * es la que exige el principal activo (S7).</p>
     *
     * <p>No se agregó un índice: {@code medico.consultorio_id} no lo tiene en V1, igual que el
     * listado por médico de {@code horario_medico}, y hoy la tabla es pequeña.</p>
     *
     * @param consultorioId consultorio que se quiere dar de baja
     * @param organizacionId organización dueña, según el token de la petición
     * @return cantidad de médicos activos que lo tienen como principal en esa organización
     */
    @Query("SELECT COUNT(m) FROM Medico m WHERE m.consultorio.id = :consultorioId "
            + "AND m.organizacion.id = :orgId AND m.activo = true")
    long contarActivosPorConsultorioPrincipal(@Param("consultorioId") UUID consultorioId,
            @Param("orgId") UUID organizacionId);
}
