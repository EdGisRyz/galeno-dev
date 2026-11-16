package com.intoglobe.clinicas.repository;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.model.MedicoConsultorioId;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración de {@link MedicoConsultorioRepository} y del mapeo de
 * {@link MedicoConsultorio} contra el schema real de V4 y V6.
 *
 * <p>El perfil {@code test} mantiene H2 en modo PostgreSQL y Flyway activo, de modo que estas
 * pruebas validan la entidad y sus dos consultas contra la tabla realmente migrada: el arranque
 * del contexto falla si Hibernate no puede validar el mapeo, incluida la clave compuesta que JPA
 * deriva de las dos asociaciones.</p>
 *
 * <p>Además se fijan las dos restricciones del esquema de las que depende el servicio: un vínculo
 * entre un médico y un consultorio de clínicas distintas no se puede escribir (las claves
 * compuestas de V4 comparten {@code organizacion_id}), y un consultorio con bloques de horario del
 * médico no se puede retirar (la clave {@code horario_medico_consultorio_asignado_fkey} de V6). La
 * segunda es la razón por la que el servicio comprueba los bloques <em>antes</em> de borrar.</p>
 *
 * <p>Las entidades se pueblan siempre con sus constructores de negocio: no se usa reflexión ni
 * setters, porque las entidades no los exponen por diseño. El bloque se crea ya en su consultorio,
 * porque desde el mapeo de la V7 la columna es obligatoria y la consulta que los lista por
 * consultorio es JPQL.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MedicoConsultorioRepositoryTest {

    private static final short LUNES = 1;
    private static final short JUEVES = 4;
    private static final short DURACION_TREINTA = 30;
    private static final LocalTime NUEVE = LocalTime.of(9, 0);
    private static final LocalTime TRECE = LocalTime.of(13, 0);
    private static final LocalTime QUINCE = LocalTime.of(15, 0);
    private static final LocalTime DIECINUEVE = LocalTime.of(19, 0);

    @Autowired
    private MedicoConsultorioRepository medicoConsultorioRepository;

    @Autowired
    private HorarioMedicoRepository horarioMedicoRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("la asignación se persiste con el par derivado y su organización")
    void save_shouldPersistThePairWithItsOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Asignación");
        var principal = persistirConsultorio(organizacion, "Consultorio Principal");
        var segundo = persistirConsultorio(organizacion, "Consultorio Dos");
        var medico = persistirMedico(organizacion, principal, "Dra. Asignación");
        UUID medicoId = medico.getId();
        UUID segundoId = segundo.getId();
        UUID organizacionId = organizacion.getId();
        entityManager.clear();

        var gestionado = entityManager.find(Medico.class, medicoId);
        var consultorioGestionado = entityManager.find(Consultorio.class, segundoId);
        var asignacion = medicoConsultorioRepository.saveAndFlush(
                new MedicoConsultorio(gestionado, consultorioGestionado, gestionado.getOrganizacion()));
        entityManager.clear();

        var encontrada = medicoConsultorioRepository
                .findById(new MedicoConsultorioId(medicoId, segundoId))
                .orElseThrow();
        assertThat(asignacion.getId().getMedicoId()).isEqualTo(medicoId);
        assertThat(asignacion.getId().getConsultorioId()).isEqualTo(segundoId);
        assertThat(encontrada.getMedico().getId()).isEqualTo(medicoId);
        assertThat(encontrada.getConsultorio().getId()).isEqualTo(segundoId);
        assertThat(encontrada.getOrganizacion().getId()).isEqualTo(organizacionId);
    }

    @Test
    @DisplayName("el listado devuelve solo los consultorios del médico, ordenados por nombre")
    void listarConsultoriosPorMedico_shouldSortByNameAndExcludeOtherMedicos() {
        var organizacion = persistirOrganizacion("Clínica Listado");
        var principal = persistirConsultorio(organizacion, "Consultorio Base");
        var primero = persistirConsultorio(organizacion, "Consultorio A");
        var segundo = persistirConsultorio(organizacion, "Consultorio B");
        var ajeno = persistirConsultorio(organizacion, "Consultorio C");
        var medico = persistirMedico(organizacion, principal, "Dr. Listado Propio");
        var otroMedico = persistirMedico(organizacion, ajeno, "Dra. Listado Ajeno");
        UUID medicoId = medico.getId();
        UUID organizacionId = organizacion.getId();
        UUID primeroId = primero.getId();
        UUID segundoId = segundo.getId();
        UUID ajenoId = ajeno.getId();
        // Se asignan en desorden a propósito: primero el B, después el A.
        asignar(medico, segundo);
        asignar(medico, primero);
        asignar(otroMedico, ajeno);
        entityManager.clear();

        var consultorios = medicoConsultorioRepository.listarConsultoriosPorMedico(medicoId, organizacionId);

        assertThat(consultorios)
                .extracting(Consultorio::getId)
                .containsExactly(primeroId, segundoId);
        assertThat(consultorios)
                .as("el listado es del médico: los consultorios de otro médico de la misma clínica no entran")
                .extracting(Consultorio::getId)
                .doesNotContain(ajenoId);
    }

    @Test
    @DisplayName("el listado con el tenant de otra organización queda vacío")
    void listarConsultoriosPorMedico_shouldBeScopedByOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Del Token");
        var otraOrganizacion = persistirOrganizacion("Clínica Ajena");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Del Token");
        var medico = persistirMedico(organizacion, consultorio, "Dr. Del Token");
        UUID medicoId = medico.getId();
        UUID organizacionId = organizacion.getId();
        UUID ajenaId = otraOrganizacion.getId();
        asignar(medico, consultorio);
        entityManager.clear();

        assertThat(ajenaId)
                .as("las dos fixtures deben vivir en organizaciones distintas para que la prueba muerda")
                .isNotEqualTo(organizacionId);
        assertThat(medicoConsultorioRepository.listarConsultoriosPorMedico(medicoId, ajenaId))
                .as("con el tenant de otra clínica el listado no puede devolver filas")
                .isEmpty();
        assertThat(medicoConsultorioRepository.listarConsultoriosPorMedico(medicoId, organizacionId))
                .hasSize(1);
    }

    @Test
    @DisplayName("buscarAsignacion solo encuentra el par asignado dentro de la organización")
    void buscarAsignacion_shouldReturnOnlyTheAssignedPairInTheOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Búsqueda");
        var otraOrganizacion = persistirOrganizacion("Clínica Búsqueda Ajena");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Asignado");
        var consultorioSinAsignar = persistirConsultorio(organizacion, "Consultorio Suelto");
        var medico = persistirMedico(organizacion, consultorio, "Dr. Búsqueda");
        var otroMedico = persistirMedico(organizacion, consultorio, "Dra. Búsqueda");
        UUID medicoId = medico.getId();
        UUID otroMedicoId = otroMedico.getId();
        UUID consultorioId = consultorio.getId();
        UUID sinAsignarId = consultorioSinAsignar.getId();
        UUID organizacionId = organizacion.getId();
        UUID ajenaId = otraOrganizacion.getId();
        asignar(medico, consultorio);
        entityManager.clear();

        assertThat(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, organizacionId))
                .as("el par asignado dentro del tenant se encuentra")
                .isPresent();
        assertThat(medicoConsultorioRepository.buscarAsignacion(medicoId, sinAsignarId, organizacionId))
                .as("un consultorio de la organización que no está asignado no tiene fila")
                .isEmpty();
        assertThat(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, ajenaId))
                .as("con el tenant de otra clínica no se encuentra la asignación")
                .isEmpty();
        assertThat(medicoConsultorioRepository.buscarAsignacion(otroMedicoId, consultorioId, organizacionId))
                .as("la asignación es del par: el consultorio de un médico no es del otro")
                .isEmpty();
    }

    @Test
    @DisplayName("borrar la asignación elimina la fila del par")
    void delete_shouldRemoveTheAssignment() {
        var organizacion = persistirOrganizacion("Clínica Baja");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Baja");
        var medico = persistirMedico(organizacion, consultorio, "Dr. Baja");
        UUID medicoId = medico.getId();
        UUID consultorioId = consultorio.getId();
        var asignacion = asignar(medico, consultorio);
        entityManager.clear();

        medicoConsultorioRepository.delete(entityManager.find(MedicoConsultorio.class,
                new MedicoConsultorioId(medicoId, consultorioId)));
        medicoConsultorioRepository.flush();
        entityManager.clear();

        assertThat(asignacion.getId()).isNotNull();
        assertThat(medicoConsultorioRepository.findById(new MedicoConsultorioId(medicoId, consultorioId)))
                .as("quitar un consultorio es borrar la fila: la tabla no tiene baja lógica")
                .isEmpty();
    }

    @Test
    @DisplayName("la clave compuesta de V4 impide asignar un consultorio de otra organización")
    void save_shouldRejectAnAssignmentWhoseConsultorioBelongsToAnotherOrganizacion() {
        var organizacion = persistirOrganizacion("Clínica Cruzada");
        var otraOrganizacion = persistirOrganizacion("Clínica Cruzada Ajena");
        var consultorio = persistirConsultorio(organizacion, "Consultorio Propio");
        var consultorioAjeno = persistirConsultorio(otraOrganizacion, "Consultorio Ajeno");
        var medico = persistirMedico(organizacion, consultorio, "Dr. Cruzado");
        UUID consultorioAjenoId = consultorioAjeno.getId();
        var organizacionDelMedico = medico.getOrganizacion();
        entityManager.clear();

        var medicoGestionado = entityManager.find(Medico.class, medico.getId());
        var consultorioAjenoGestionado = entityManager.find(Consultorio.class, consultorioAjenoId);

        assertThatThrownBy(() -> medicoConsultorioRepository.saveAndFlush(new MedicoConsultorio(
                medicoGestionado, consultorioAjenoGestionado, organizacionDelMedico)))
                .as("las dos claves foráneas compuestas comparten organizacion_id: el cruce no existe")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("el listado por consultorio devuelve solo los bloques de ese médico en ese consultorio")
    void listarBloquesEnConsultorio_shouldReturnOnlyThatMedicoAndConsultorio() {
        var organizacion = persistirOrganizacion("Clínica Bloques");
        var principal = persistirConsultorio(organizacion, "Consultorio Principal");
        var segundo = persistirConsultorio(organizacion, "Consultorio Dos");
        var medico = persistirMedico(organizacion, principal, "Dra. Bloques Propia");
        var otroMedico = persistirMedico(organizacion, principal, "Dr. Bloques Ajeno");
        UUID medicoId = medico.getId();
        UUID segundoId = segundo.getId();
        asignar(medico, principal);
        asignar(medico, segundo);
        asignar(otroMedico, segundo);
        // Se insertan en desorden: primero el jueves por la tarde, después el lunes por la mañana.
        persistirBloque(medico, segundo, JUEVES, QUINCE, DIECINUEVE);
        persistirBloque(medico, segundo, LUNES, NUEVE, TRECE);
        var enElPrincipal = persistirBloque(medico, principal, LUNES, TRECE, QUINCE);
        var delOtroMedico = persistirBloque(otroMedico, segundo, LUNES, NUEVE, TRECE);
        UUID enElPrincipalId = enElPrincipal.getId();
        UUID delOtroMedicoId = delOtroMedico.getId();
        entityManager.clear();

        var bloques = horarioMedicoRepository.listarBloquesEnConsultorio(medicoId, segundoId);

        assertThat(bloques)
                .extracting(HorarioMedico::getDiaSemana, HorarioMedico::getHoraInicio)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LUNES, NUEVE),
                        org.assertj.core.groups.Tuple.tuple(JUEVES, QUINCE));
        assertThat(bloques)
                .extracting(HorarioMedico::getId)
                .as("los bloques del consultorio principal y los de otro médico no entran")
                .doesNotContain(enElPrincipalId, delOtroMedicoId);
        assertThat(bloques.getFirst().getMedico().getId()).isEqualTo(medicoId);
    }

    @Test
    @DisplayName("el esquema impide retirar un consultorio que todavía tiene bloques del médico")
    void delete_shouldBeRejectedByTheSchema_whenTheConsultorioStillHasBloques() {
        var organizacion = persistirOrganizacion("Clínica Conflicto");
        var principal = persistirConsultorio(organizacion, "Consultorio Principal");
        var segundo = persistirConsultorio(organizacion, "Consultorio Con Bloques");
        var medico = persistirMedico(organizacion, principal, "Dr. Conflicto");
        UUID medicoId = medico.getId();
        UUID segundoId = segundo.getId();
        var asignacion = asignar(medico, segundo);
        persistirBloque(medico, segundo, LUNES, NUEVE, TRECE);
        entityManager.clear();

        var gestionada = entityManager.find(MedicoConsultorio.class,
                new MedicoConsultorioId(medicoId, segundoId));

        assertThat(asignacion.getId().getConsultorioId()).isEqualTo(segundoId);
        assertThatThrownBy(() -> {
            medicoConsultorioRepository.delete(gestionada);
            medicoConsultorioRepository.flush();
        })
                .as("horario_medico_consultorio_asignado_fkey es la razón por la que el servicio"
                        + " comprueba los bloques antes de borrar la asignación")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Persiste una organización activa con el nombre indicado.
     *
     * @param nombre nombre de la organización
     * @return la organización persistida
     */
    private Organizacion persistirOrganizacion(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.CLINICA, null);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion;
    }

    /**
     * Persiste un consultorio de la organización indicada.
     *
     * @param organizacion organización dueña
     * @param nombre       nombre del consultorio
     * @return el consultorio persistido
     */
    private Consultorio persistirConsultorio(Organizacion organizacion, String nombre) {
        var consultorio = new Consultorio(organizacion, nombre, null, null);
        entityManager.persist(consultorio);
        entityManager.flush();
        return consultorio;
    }

    /**
     * Persiste un médico con su consultorio principal.
     *
     * @param organizacion organización dueña
     * @param consultorio  consultorio principal del médico
     * @param nombre       nombre del médico
     * @return el médico persistido
     */
    private Medico persistirMedico(Organizacion organizacion, Consultorio consultorio, String nombre) {
        var medico = new Medico(organizacion, consultorio, nombre, null, null, null, new BigDecimal("500.00"));
        entityManager.persist(medico);
        entityManager.flush();
        return medico;
    }

    /**
     * Persiste la asignación del consultorio al médico, con la organización del médico.
     *
     * @param medico      médico al que se asigna
     * @param consultorio consultorio asignado
     * @return la asignación persistida
     */
    private MedicoConsultorio asignar(Medico medico, Consultorio consultorio) {
        var asignacion = new MedicoConsultorio(medico, consultorio, medico.getOrganizacion());
        entityManager.persist(asignacion);
        entityManager.flush();
        return asignacion;
    }

    /**
     * Persiste un bloque de disponibilidad del médico en el consultorio indicado, que tiene que
     * estar asignado a ese médico.
     *
     * @param medico      médico dueño del bloque
     * @param consultorio consultorio donde se dicta el bloque
     * @param diaSemana   día de la semana
     * @param horaInicio  hora de inicio
     * @param horaFin     hora de fin
     * @return el bloque persistido
     */
    private HorarioMedico persistirBloque(Medico medico, Consultorio consultorio, short diaSemana,
            LocalTime horaInicio, LocalTime horaFin) {
        var bloque = new HorarioMedico(medico, consultorio, diaSemana, horaInicio, horaFin,
                DURACION_TREINTA);
        entityManager.persist(bloque);
        entityManager.flush();
        return bloque;
    }

}
