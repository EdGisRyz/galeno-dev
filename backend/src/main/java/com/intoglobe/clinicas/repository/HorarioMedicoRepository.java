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
     * <p><strong>Acotada por organización.</strong> El {@code WHERE} navega
     * {@code h.medico.organizacion.id} y exige la organización del token, de modo que un
     * {@code medicoId} de otro tenant no puede devolver filas ni siquiera si el llamador
     * olvidara comprobar la pertenencia antes: el aislamiento viaja en la consulta y un
     * listado ajeno es una lista vacía, no una fuga de la agenda de otra clínica
     * (R-SEG-04, R-SEG-06).</p>
     *
     * <p><strong>El {@code JOIN FETCH} es deliberado: no se quite.</strong> La respuesta de un
     * bloque incluye el identificador y el <em>nombre</em> de su consultorio, así que el mapper
     * lee {@code h.consultorio.getNombre()}. Sin el fetch, Hibernate resolvería un proxy por
     * cada bloque y la agenda semanal haría una consulta extra por fila: el N+1 clásico de un
     * listado. Con el fetch, el consultorio llega en la misma consulta; el orden y el filtro no
     * cambian. Si algún día el mapper deja de leer el consultorio, el fetch puede revisarse, pero
     * no al revés: quitarlo con el mapper actual solo agrega consultas.</p>
     *
     * @param medicoId       médico dueño de la agenda
     * @param organizacionId organización dueña, según el {@code tenantId} del token
     * @return bloques de ese médico en esa organización, ordenados por {@code diaSemana} y
     *         {@code horaInicio}
     */
    @Query("SELECT h FROM HorarioMedico h JOIN FETCH h.consultorio "
            + "WHERE h.medico.id = :medicoId "
            + "AND h.medico.organizacion.id = :organizacionId "
            + "ORDER BY h.diaSemana, h.horaInicio")
    List<HorarioMedico> listarPorMedico(@Param("medicoId") UUID medicoId,
            @Param("organizacionId") UUID organizacionId);

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
     * <p>El conteo está acotado igual que el listado: exige que el médico pertenezca a la
     * organización del token. Sin ese filtro, la comprobación de solapamiento sería una
     * sonda sobre la agenda de otra clínica —un {@code medicoId} ajeno devolvería cuántos
     * bloques chocan— y el alta podría rechazarse con datos que el llamador no puede
     * ver.</p>
     *
     * @param medicoId       médico dueño de la agenda
     * @param organizacionId organización dueña, según el {@code tenantId} del token
     * @param diaSemana      día de la semana del bloque que se quiere guardar
     * @param horaInicio     hora de inicio del bloque que se quiere guardar
     * @param horaFin        hora de fin del bloque que se quiere guardar
     * @param idExcluido     identificador del bloque que se está editando, o el UUID
     *                       cero al crear
     * @return número de bloques del mismo médico y día que se solapan con el rango
     *         indicado, excluyendo {@code idExcluido}
     */
    @Query("SELECT COUNT(h) FROM HorarioMedico h "
            + "WHERE h.medico.id = :medicoId AND h.medico.organizacion.id = :organizacionId "
            + "AND h.diaSemana = :diaSemana "
            + "AND h.horaInicio < :horaFin AND h.horaFin > :horaInicio "
            + "AND h.id <> :idExcluido")
    long contarSolapamientos(@Param("medicoId") UUID medicoId,
            @Param("organizacionId") UUID organizacionId,
            @Param("diaSemana") short diaSemana,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFin") LocalTime horaFin,
            @Param("idExcluido") UUID idExcluido);

    /**
     * Lista los bloques de un médico que se dictan en un consultorio concreto, ordenados por día
     * y hora de inicio.
     *
     * <p>Es la consulta de la regla que impide retirar de un médico un consultorio que todavía
     * tiene bloques de horario: la clave {@code horario_medico_consultorio_asignado_fkey} de la
     * migración V6 lo rechazaría con un error de integridad, así que el servicio lo detecta antes
     * y responde un {@code 409} que además dice cuáles son los bloques que lo impiden.</p>
     *
     * <p><strong>Es JPQL</strong>, igual que las otras dos consultas del repositorio. Cuando la V6
     * agregó {@code horario_medico.consultorio_id}, la entidad todavía no mapeaba la columna y la
     * consulta bajó a SQL nativo de forma temporal; con el mapeo de la V7 en su lugar, vuelve a
     * JPQL conservando la misma forma —mismo nombre, mismos parámetros, mismo orden— y sin tocar
     * al llamador. Se ordena por {@code diaSemana} y {@code horaInicio} porque el mensaje del
     * {@code 409} enumera los bloques en ese orden y el administrador los reconoce así.</p>
     *
     * <p>No se acota por organización porque no le hace falta: las dos claves foráneas de la V6
     * exigen que el consultorio y el médico compartan {@code organizacion_id} y que el par esté
     * asignado, de modo que un par de otra clínica no puede tener filas. El llamador, en cualquier
     * caso, ya resolvió el médico y el consultorio dentro del tenant.</p>
     *
     * @param medicoId      médico dueño de los bloques
     * @param consultorioId consultorio por el que se pregunta
     * @return bloques de ese médico en ese consultorio, ordenados por {@code diaSemana} y
     *         {@code horaInicio}; vacío si el consultorio no tiene ninguno
     */
    @Query("SELECT h FROM HorarioMedico h WHERE h.medico.id = :medicoId "
            + "AND h.consultorio.id = :consultorioId ORDER BY h.diaSemana, h.horaInicio")
    List<HorarioMedico> listarBloquesEnConsultorio(@Param("medicoId") UUID medicoId,
            @Param("consultorioId") UUID consultorioId);
}
