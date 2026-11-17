package com.intoglobe.clinicas.repository;

import java.time.LocalDate;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.mapper.PacienteMapper;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Paciente;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * La ficha administrativa del paciente y la unicidad de su CURP contra el esquema real
 * (V8 y V9), no contra un repositorio simulado.
 *
 * <p>La prueba del servicio simula el repositorio, así que no puede demostrar dos cosas que aquí sí
 * se ejercen: que la unicidad la impone la base con el nombre de restricción que el traductor de
 * errores busca —{@code paciente_organizacion_id_curp_key}— y que la consulta de la comprobación
 * previa está acotada por organización. Ambas dependen del esquema migrado, y por eso esta prueba
 * corre sobre H2 en modo PostgreSQL con Flyway aplicado por el perfil {@code test}.</p>
 *
 * <p>Todo se comprueba por la interfaz pública del módulo: las operaciones de negocio de la entidad,
 * el mapper y el repositorio. Nunca por el estado interno de la sesión de persistencia.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PacienteCurpUnicidadTest {

    /** CURP canónica de la fixture: la que la base guarda y la que la búsqueda espera. */
    private static final String CURP = "SASC850101MDFRRN01";

    @Autowired
    private PacienteRepository pacienteRepository;

    @Autowired
    private EntityManager em;

    private static PacienteRequest request(String nombre, String curp) {
        return new PacienteRequest(nombre, "+52 55 5555 4321", LocalDate.of(1985, 3, 14), "F",
                "maria.lopez@example.com", "Alergia", curp, "Comerciante", "Av. Reforma 123",
                "Rosa Ramírez Silva", "+52 55 5555 8765", "Madre");
    }

    private Organizacion org(String nombre) {
        var organizacion = new Organizacion(nombre, PlanTipo.SOLO, null);
        em.persist(organizacion);
        return organizacion;
    }

    /** Relee la fila desde la base y comprueba que los seis campos de la ficha volvieron intactos. */
    private void releerIgual(UUID id, String curp) {
        em.flush();
        em.clear();
        var respuesta = PacienteMapper.aResponse(pacienteRepository.findById(id).orElseThrow());
        assertThat(respuesta.curp()).isEqualTo(curp);
        assertThat(respuesta.ocupacion()).isEqualTo("Comerciante");
        assertThat(respuesta.domicilio()).isEqualTo("Av. Reforma 123");
        assertThat(respuesta.nombreResponsable()).isEqualTo("Rosa Ramírez Silva");
        assertThat(respuesta.telefonoResponsable()).isEqualTo("+52 55 5555 8765");
        assertThat(respuesta.parentesco()).isEqualTo("Madre");
    }

    @Test
    @DisplayName("el alta guarda y devuelve los seis campos de la ficha (S8)")
    void elAltaGuardaYDevuelveLosSeisCamposDeLaFicha() {
        var organizacion = org("Clínica Alta");

        var paciente = pacienteRepository.saveAndFlush(
                PacienteMapper.aEntidad(organizacion, request("Ana", CURP)));

        releerIgual(paciente.getId(), CURP);
    }

    @Test
    @DisplayName("la edición reemplaza la ficha y no pierde ningún campo (S8)")
    void laEdicionReemplazaLaFichaYNoPierdeNingunCampo() {
        var organizacion = org("Clínica Edición");
        var paciente = pacienteRepository.saveAndFlush(
                PacienteMapper.aEntidad(organizacion, request("Ana", CURP)));

        PacienteMapper.actualizarEntidad(paciente, request("Ana Actualizada", "PEHJ751122HOCRN03"));
        pacienteRepository.saveAndFlush(paciente);

        releerIgual(paciente.getId(), "PEHJ751122HOCRN03");
    }

    @Test
    @DisplayName("la CURP se guarda canónica aunque la petición llegue en minúsculas (S3)")
    void laCurpSeGuardaCanonicaATravesDeLaEntidad() {
        var paciente = PacienteMapper.aEntidad(org("Clínica Canon"),
                request("Ana", "  sasc850101mdfrrn01  "));

        assertThat(paciente.getCurp()).isEqualTo(CURP);
        assertThat(pacienteRepository.saveAndFlush(paciente).getCurp()).isEqualTo(CURP);
    }

    @Test
    @DisplayName("sin CURP conviven y la misma CURP en otra organización entra (S2)")
    void sinCurpConvivenYLaMismaCurpEnOtraOrganizacionEntra() {
        var organizacion = org("Clínica Uno");
        var otra = org("Clínica Dos");

        assertThatCode(() -> {
            pacienteRepository.saveAndFlush(
                    PacienteMapper.aEntidad(organizacion, request("Sin curp uno", null)));
            pacienteRepository.saveAndFlush(
                    PacienteMapper.aEntidad(organizacion, request("Sin curp dos", "")));
            pacienteRepository.saveAndFlush(
                    PacienteMapper.aEntidad(organizacion, request("Con curp", CURP)));
            pacienteRepository.saveAndFlush(
                    PacienteMapper.aEntidad(otra, request("Otra clínica", CURP)));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el duplicado trae el nombre de la restricción que el handler busca (S5)")
    void elDuplicadoTraeElNombreDeLaRestriccionQueElHandlerBusca() {
        var organizacion = org("Clínica Dup");
        pacienteRepository.saveAndFlush(
                PacienteMapper.aEntidad(organizacion, request("Primero", CURP)));

        var excepcion = Assertions.catchThrowableOfType(DataIntegrityViolationException.class,
                () -> pacienteRepository.saveAndFlush(
                        PacienteMapper.aEntidad(organizacion, request("Segundo", CURP))));

        assertThat(excepcion).isNotNull();
        var cadena = new StringBuilder();
        for (Throwable actual = excepcion; actual != null; actual = actual.getCause()) {
            cadena.append(actual.getMessage()).append(" || ");
            if (actual.getCause() == actual) {
                break;
            }
        }
        assertThat(cadena.toString())
                .as("sin este nombre en el mensaje, el traductor no puede responder 409 en la carrera")
                .contains("paciente_organizacion_id_curp_key");
    }

    @Test
    @DisplayName("la consulta de unicidad acota por organización (S2)")
    void laConsultaDeUnicidadAcotaPorOrganizacion() {
        var organizacion = org("Clínica A");
        var otra = org("Clínica B");
        var enA = pacienteRepository.saveAndFlush(
                PacienteMapper.aEntidad(organizacion, request("En A", CURP)));
        var enB = pacienteRepository.saveAndFlush(
                PacienteMapper.aEntidad(otra, request("En B", CURP)));

        assertThat(pacienteRepository.buscarPorOrganizacionYCurp(organizacion.getId(), CURP)
                .orElseThrow().getId()).isEqualTo(enA.getId());
        assertThat(pacienteRepository.buscarPorOrganizacionYCurp(otra.getId(), CURP)
                .orElseThrow().getId()).isEqualTo(enB.getId());
        assertThat(pacienteRepository.buscarPorOrganizacionYCurp(organizacion.getId(),
                "XXXX000000XXXXXX00")).isEmpty();
    }
}
