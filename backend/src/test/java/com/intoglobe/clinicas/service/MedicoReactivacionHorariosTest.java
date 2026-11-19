package com.intoglobe.clinicas.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.HorarioMedicoRepository;
import com.intoglobe.clinicas.repository.MedicoConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de integración de la reactivación del médico contra el schema real (S3).
 *
 * <p>La baja de un médico es lógica, así que sus bloques de disponibilidad no se tocan: el
 * dato que se perdería en silencio si alguna vez la baja «limpiara» los horarios es
 * exactamente este. La prueba siembra un médico con un bloque, lo desactiva y lo reactiva a
 * través de {@link MedicoService} —no llamando a la entidad— y después lee la agenda con
 * {@link HorarioMedicoRepository#listarPorMedico}, que es la consulta que alimenta el panel de
 * horarios. La sesión se limpia antes de leer para que la comprobación venga de la base y no
 * de la memoria de Hibernate.</p>
 *
 * <p>Se instancia el servicio a mano con los repositorios que provee {@code @DataJpaTest}:
 * así la transacción, las entidades administradas y la base son las reales sin levantar el
 * contexto web completo, que no aporta nada a esta regla.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MedicoReactivacionHorariosTest {

    /** Lunes, la convención de {@code Date.getDay()} que usa la columna {@code dia_semana}. */
    private static final short LUNES = 1;
    /** Duración de cita permitida por el CHECK de V1. */
    private static final short TREINTA_MINUTOS = 30;
    private static final LocalTime NUEVE = LocalTime.of(9, 0);
    private static final LocalTime ONCE = LocalTime.of(11, 0);

    @Autowired
    private MedicoRepository medicoRepository;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private ConsultorioRepository consultorioRepository;

    @Autowired
    private MedicoConsultorioRepository medicoConsultorioRepository;

    @Autowired
    private HorarioMedicoRepository horarioMedicoRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("los bloques de horario del médico vuelven con él tras desactivarlo y reactivarlo (S3)")
    void activar_shouldKeepTheHorariosOfTheMedico_whenItWasDeactivated() {
        var organizacion = new Organizacion("Clínica Reactivación", PlanTipo.CLINICA, null);
        entityManager.persist(organizacion);
        var consultorio = new Consultorio(organizacion, "Consultorio Reactivación", null, null);
        entityManager.persist(consultorio);
        var medico = new Medico(organizacion, consultorio, "Dr. Reactivado", "General", null, null,
                new BigDecimal("500.00"));
        entityManager.persist(medico);
        entityManager.flush();
        // La asignación que el alta del médico deja escrita: sin ella la V6 rechaza el bloque.
        entityManager.persist(new MedicoConsultorio(medico, consultorio, organizacion));
        var horario = new HorarioMedico(medico, consultorio, LUNES, NUEVE, ONCE, TREINTA_MINUTOS);
        entityManager.persist(horario);
        entityManager.flush();

        UUID medicoId = medico.getId();
        UUID organizacionId = organizacion.getId();
        UUID horarioId = horario.getId();
        var medicoService = new MedicoService(medicoRepository, organizacionRepository,
                consultorioRepository, medicoConsultorioRepository);

        medicoService.desactivar(medicoId, organizacionId);
        entityManager.flush();
        assertThat(horarioMedicoRepository.listarPorMedico(medicoId, organizacionId))
                .as("desactivar es una baja lógica: los bloques no se borran")
                .hasSize(1);

        medicoService.activar(medicoId, organizacionId);
        entityManager.flush();
        entityManager.clear();

        assertThat(medicoRepository.findById(medicoId).orElseThrow().isActivo()).isTrue();
        var agenda = horarioMedicoRepository.listarPorMedico(medicoId, organizacionId);
        assertThat(agenda)
                .as("los horarios del médico siguen ahí después de reactivarlo")
                .hasSize(1);
        assertThat(agenda.get(0).getId()).isEqualTo(horarioId);
        assertThat(agenda.get(0).getDiaSemana()).isEqualTo(LUNES);
        assertThat(agenda.get(0).getHoraInicio()).isEqualTo(NUEVE);
        assertThat(agenda.get(0).getHoraFin()).isEqualTo(ONCE);
    }
}
