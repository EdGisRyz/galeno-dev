package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.model.MedicoConsultorioId;

/**
 * Acceso a datos de {@link MedicoConsultorio}, la asignación de consultorios a un médico.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL explícito;
 * está prohibido depender de la derivación de nombres de Spring Data. Los métodos heredados de
 * {@link JpaRepository} ({@code save}, {@code delete}, {@code findById}, ...) siguen siendo
 * válidos.</p>
 *
 * <p><strong>Las dos consultas están acotadas por la organización del token.</strong> El
 * aislamiento viaja en el {@code WHERE}, no en una comparación posterior en memoria: un
 * {@code medicoId} o un {@code consultorioId} de otra clínica no devuelve filas ni siquiera si
 * el llamador olvidara comprobar la pertenencia antes, y una asignación ajena no puede
 * borrarse por error (R-SEG-04, R-SEG-06).</p>
 */
public interface MedicoConsultorioRepository
        extends JpaRepository<MedicoConsultorio, MedicoConsultorioId> {

    /**
     * Lista los consultorios asignados a un médico, ordenados alfabéticamente por nombre del
     * consultorio.
     *
     * <p>El orden es parte del contrato de la respuesta: el cliente lo pinta tal como llega y
     * sin un orden estable el listado cambiaría entre peticiones. Se ordena por el nombre del
     * consultorio y no por el de la asignación para que dos médicos con los mismos consultorios
     * vean la misma secuencia, y para que el orden sea el mismo que el del catálogo de la
     * organización.</p>
     *
     * <p>Devuelve los {@link Consultorio} y no las asignaciones a propósito: el contrato de la
     * ruta es la lista de consultorios del médico, y los datos del vínculo (su
     * {@code organizacion_id} y su {@code created_at}) no forman parte de esa representación.</p>
     *
     * @param medicoId       médico cuyos consultorios se listan
     * @param organizacionId organización dueña, según el {@code tenantId} del token
     * @return consultorios asignados a ese médico en esa organización, ordenados por nombre
     */
    @Query("SELECT mc.consultorio FROM MedicoConsultorio mc "
            + "WHERE mc.medico.id = :medicoId AND mc.organizacion.id = :organizacionId "
            + "ORDER BY mc.consultorio.nombre")
    List<Consultorio> listarConsultoriosPorMedico(@Param("medicoId") UUID medicoId,
            @Param("organizacionId") UUID organizacionId);

    /**
     * Recupera la asignación del par médico-consultorio dentro de la organización indicada.
     *
     * <p>Es la consulta de las dos operaciones de escritura: el alta la usa para no duplicar
     * —lo que hace la operación idempotente— y la baja para saber si hay algo que borrar antes
     * de comprobar las dos reglas de conflicto. Devuelve la entidad y no un booleano porque la
     * baja necesita el objeto gestionado para eliminarlo.</p>
     *
     * @param medicoId       médico de la asignación
     * @param consultorioId  consultorio de la asignación
     * @param organizacionId organización dueña, según el {@code tenantId} del token
     * @return la asignación si existe en esa organización; vacío en cualquier otro caso
     */
    @Query("SELECT mc FROM MedicoConsultorio mc WHERE mc.medico.id = :medicoId "
            + "AND mc.consultorio.id = :consultorioId AND mc.organizacion.id = :organizacionId")
    Optional<MedicoConsultorio> buscarAsignacion(@Param("medicoId") UUID medicoId,
            @Param("consultorioId") UUID consultorioId,
            @Param("organizacionId") UUID organizacionId);
}
