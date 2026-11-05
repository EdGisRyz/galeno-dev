package com.intoglobe.clinicas.repository;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.HorarioMedico;

/**
 * Acceso a datos de {@link HorarioMedico}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL
 * explícito; está prohibido depender de la derivación de nombres de Spring Data.
 * Los métodos heredados de {@link JpaRepository} ({@code findById}, {@code save},
 * {@code delete}, ...) siguen siendo válidos.</p>
 */
public interface HorarioMedicoRepository extends JpaRepository<HorarioMedico, UUID> {

    /**
     * Lista los bloques de disponibilidad de un médico, ordenados por día de la
     * semana y hora de inicio.
     *
     * <p>Es la consulta que alimenta la agenda semanal: el orden es parte del
     * contrato porque el cliente pinta los bloques tal como llegan, y sin un orden
     * estable la rejilla del horario cambiaría entre peticiones. Filtra por
     * {@code h.medico.id}; V1 <strong>no</strong> declara índice sobre esa columna,
     * así que hoy la consulta recorre la tabla (pequeña por ahora) y el índice es un
     * pendiente declarado del V2.</p>
     *
     * @param medicoId médico dueño de la agenda
     * @return bloques de ese médico, ordenados por {@code diaSemana} y
     *         {@code horaInicio}
     */
    @Query("SELECT h FROM HorarioMedico h WHERE h.medico.id = :medicoId ORDER BY h.diaSemana, h.horaInicio")
    List<HorarioMedico> listarPorMedico(@Param("medicoId") UUID medicoId);

    /**
     * Responde a la pregunta «¿este bloque se solapa con algún bloque ya agendado de
     * este médico en este día?».
     *
     * <p>El predicado de solapamiento es el de intervalo semiabierto:
     * {@code inicio < otroFin AND fin > otroInicio}. Con él, dos bloques contiguos
     * (09:00-10:00 y 10:00-11:00) <em>no</em> se solapan, que es lo que la agenda
     * necesita: citas consecutivas sin hueco son válidas, y dos citas no pueden
     * empezar en el mismo instante en que la anterior termina solo si se usara un
     * intervalo cerrado.</p>
     *
     * <p><strong>Al crear</strong> hay que pasar el UUID todo ceros
     * ({@code new UUID(0L, 0L)}) como {@code idExcluido}: el {@code id} de la tabla
     * nunca es nulo (lo rellena {@code gen_random_uuid()}), así que comparar contra
     * un valor nulo excluiría todas las filas y la consulta devolvería siempre
     * {@code 0}, ocultando cualquier solapamiento. El UUID cero es un valor
     * documentado como «ningún registro» porque no puede colisionar con un
     * identificador generado. <strong>Al actualizar</strong> se pasa el
     * identificador del propio bloque, para que no cuente como solapado consigo
     * mismo.</p>
     *
     * @param medicoId     médico dueño de la agenda
     * @param diaSemana    día de la semana del bloque que se quiere guardar
     * @param horaInicio   hora de inicio del bloque que se quiere guardar
     * @param horaFin      hora de fin del bloque que se quiere guardar
     * @param idExcluido   identificador del bloque que se está editando, o el UUID
     *                     cero al crear
     * @return número de bloques del mismo médico y día que se solapan con el rango
     *         indicado, excluyendo {@code idExcluido}
     */
    @Query("SELECT COUNT(h) FROM HorarioMedico h "
            + "WHERE h.medico.id = :medicoId AND h.diaSemana = :diaSemana "
            + "AND h.horaInicio < :horaFin AND h.horaFin > :horaInicio "
            + "AND h.id <> :idExcluido")
    long contarSolapamientos(@Param("medicoId") UUID medicoId,
            @Param("diaSemana") short diaSemana,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFin") LocalTime horaFin,
            @Param("idExcluido") UUID idExcluido);
}
