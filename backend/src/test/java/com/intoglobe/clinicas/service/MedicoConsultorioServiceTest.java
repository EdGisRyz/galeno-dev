package com.intoglobe.clinicas.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ConsultorioNoRetirableException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
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
import com.intoglobe.clinicas.security.AuthenticatedUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link MedicoConsultorioService}: las seis reglas de la asignación y la
 * idempotencia de las dos escrituras.
 *
 * <p>No levantan contexto de Spring: los cuatro repositorios se simulan con Mockito. La identidad
 * la asigna la base al persistir y las entidades no exponen setters, así que las fixtures con
 * aspecto de registro persistido las aportan espías de Mockito ({@code spy} más
 * {@code doReturn}). Poblar entidades por reflexión está prohibido en este módulo.</p>
 *
 * <p>Las fixtures se construyen siempre <em>antes</em> de la instrucción {@code when} que las
 * consume, y ninguna se crea dentro de los argumentos de una simulación: un espía invoca al mock
 * al construirse ({@code getId()}), y hacerlo en medio de otra simulación deja la primera sin
 * terminar.</p>
 *
 * <p>El orden de las comprobaciones es contractual, así que varias pruebas hacen fallar dos
 * reglas a la vez y afirman cuál gana:</p>
 * <ol>
 *   <li>médico del tenant ({@code 404}) antes que cualquier otra cosa,</li>
 *   <li>consultorio del tenant ({@code 404}, nunca {@code 403}: un {@code 403} confirmaría que
 *       existe en otra clínica),</li>
 *   <li>propiedad de los consultorios para un token {@code MEDICO} ({@code 403}),</li>
 *   <li>consultorio principal ({@code 409}),</li>
 *   <li>bloques de horario en ese consultorio ({@code 409}),</li>
 *   <li>persistir o borrar.</li>
 * </ol>
 *
 * <p>Las celdas de rol de la matriz —{@code ADMIN} escribe, {@code MEDICO} solo lee los
 * propios— se prueban en {@code AislamientoYMatrizRbacTest} y en
 * {@code MedicoConsultorioControllerTest}; aquí se prueba lo que el rol no puede expresar: el
 * acotamiento por organización, la propiedad y las dos reglas de conflicto.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicoConsultorioServiceTest {

    private static final short LUNES = 1;
    private static final short JUEVES = 4;
    private static final short DURACION_TREINTA = 30;
    private static final LocalTime NUEVE = LocalTime.of(9, 0);
    private static final LocalTime TRECE = LocalTime.of(13, 0);
    private static final LocalTime QUINCE = LocalTime.of(15, 0);
    private static final LocalTime DIECINUEVE = LocalTime.of(19, 0);

    private static final String ROL_ADMIN = "ADMIN";
    private static final String ROL_MEDICO = "MEDICO";
    private static final String CORREO = "ana.torres@clinicasanrafael.test";

    @Mock
    private MedicoRepository medicoRepository;

    @Mock
    private ConsultorioRepository consultorioRepository;

    @Mock
    private MedicoConsultorioRepository medicoConsultorioRepository;

    @Mock
    private HorarioMedicoRepository horarioMedicoRepository;

    @InjectMocks
    private MedicoConsultorioService medicoConsultorioService;

    // ------------------------------------------------------------------
    // GET: listado ordenado y acotado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listar devuelve los consultorios asignados que trae la consulta, en su orden")
    void listar_shouldReturnTheAssignedConsultoriosInTheOrderOfTheQuery() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Uno");
        var segundo = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Dos");
        var medico = medicoConId(medicoId, organizacion, principal);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(medicoConsultorioRepository.listarConsultoriosPorMedico(medicoId, tenantId))
                .thenReturn(List.of(segundo, principal));

        var respuesta = medicoConsultorioService.listar(adminDe(tenantId), medicoId);

        assertThat(respuesta)
                .as("el orden lo fija la consulta; el servicio no reordena")
                .extracting(ConsultorioResponse::nombre)
                .containsExactly("Consultorio Dos", "Consultorio Uno");
        assertThat(respuesta)
                .extracting(ConsultorioResponse::id)
                .containsExactly(segundo.getId(), principal.getId());
        verify(medicoConsultorioRepository).listarConsultoriosPorMedico(medicoId, tenantId);
    }

    @Test
    @DisplayName("listar de un médico inexistente responde 404 y no consulta la asignación")
    void listar_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoConsultorioService.listar(adminDe(tenantId), medicoId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(medicoId.toString());
        verify(medicoConsultorioRepository, never())
                .listarConsultoriosPorMedico(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("listar de un médico de otra organización responde 404 y no devuelve sus consultorios")
    void listar_shouldThrowResourceNotFoundException_whenMedicoBelongsToAnotherTenant() {
        var tenantDelToken = UUID.randomUUID();
        var tenantAjeno = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var organizacionAjena = organizacionConId(tenantAjeno);
        var consultorioAjeno = consultorioConId(UUID.randomUUID(), organizacionAjena, "Consultorio Ajeno");
        var medicoAjeno = medicoConId(medicoId, organizacionAjena, consultorioAjeno);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medicoAjeno));

        assertThatThrownBy(() -> medicoConsultorioService.listar(adminDe(tenantDelToken), medicoId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verify(medicoConsultorioRepository, never())
                .listarConsultoriosPorMedico(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("listar con token MEDICO de otro médico responde 403 sin consultar la asignación")
    void listar_shouldThrowAccessDeniedException_whenMedicoReadsAnotherMedicosConsultorios() {
        var tenantId = UUID.randomUUID();
        var medicoDelToken = UUID.randomUUID();
        var medicoPedido = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoPedido, organizacion, principal);
        var actor = medicoDe(tenantId, medicoDelToken);
        when(medicoRepository.findById(medicoPedido)).thenReturn(Optional.of(medico));

        assertThatThrownBy(() -> medicoConsultorioService.listar(actor, medicoPedido))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("su propia");
        verify(medicoConsultorioRepository, never())
                .listarConsultoriosPorMedico(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("listar con token MEDICO sin claim medicoId falla cerrado con 403")
    void listar_shouldThrowAccessDeniedException_whenMedicoTokenHasNoMedicoClaim() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var actor = medicoDe(tenantId, null);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));

        assertThatThrownBy(() -> medicoConsultorioService.listar(actor, medicoId))
                .isInstanceOf(AccessDeniedException.class);
        verify(medicoConsultorioRepository, never())
                .listarConsultoriosPorMedico(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("listar gana el 404 del médico ajeno antes que el 403 de propiedad: no confirma que exista")
    void listar_shouldThrowResourceNotFoundExceptionBeforeOwnership_whenBothFail() {
        var tenantDelToken = UUID.randomUUID();
        var tenantAjeno = UUID.randomUUID();
        var medicoPedido = UUID.randomUUID();
        var organizacionAjena = organizacionConId(tenantAjeno);
        var consultorioAjeno = consultorioConId(UUID.randomUUID(), organizacionAjena, "Consultorio Ajeno");
        var medicoAjeno = medicoConId(medicoPedido, organizacionAjena, consultorioAjeno);
        var actor = medicoDe(tenantDelToken, UUID.randomUUID());
        when(medicoRepository.findById(medicoPedido)).thenReturn(Optional.of(medicoAjeno));

        assertThatThrownBy(() -> medicoConsultorioService.listar(actor, medicoPedido))
                .isInstanceOf(ResourceNotFoundException.class)
                .as("un médico de otro tenant responde 404 aunque el token pida los de otro médico")
                .isNotInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    // POST: asignar, idempotente
    // ------------------------------------------------------------------

    @Test
    @DisplayName("asignar guarda el par médico-consultorio con la organización del médico")
    void asignar_shouldSaveTheAssignmentWithTheOrganizacionOfTheMedico() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var consultorio = consultorioConId(consultorioId, organizacion, "Consultorio Dos");
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.of(consultorio));
        when(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, tenantId))
                .thenReturn(Optional.empty());

        medicoConsultorioService.asignar(adminDe(tenantId), medicoId, consultorioId);

        var captor = ArgumentCaptor.forClass(MedicoConsultorio.class);
        verify(medicoConsultorioRepository).save(captor.capture());
        var guardada = captor.getValue();
        assertThat(guardada.getMedico()).isSameAs(medico);
        assertThat(guardada.getConsultorio()).isSameAs(consultorio);
        assertThat(guardada.getOrganizacion())
                .as("la organización de la fila es la del médico, nunca un dato del cliente")
                .isSameAs(organizacion);
    }

    @Test
    @DisplayName("asignar un consultorio ya asignado es idempotente: no guarda ni falla")
    void asignar_shouldNotSaveAgain_whenTheConsultorioIsAlreadyAssigned() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var consultorio = consultorioConId(consultorioId, organizacion, "Consultorio Dos");
        var asignacionExistente = new MedicoConsultorio(medico, consultorio, organizacion);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.of(consultorio));
        when(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, tenantId))
                .thenReturn(Optional.of(asignacionExistente));

        medicoConsultorioService.asignar(adminDe(tenantId), medicoId, consultorioId);

        verify(medicoConsultorioRepository, never()).save(any(MedicoConsultorio.class));
    }

    @Test
    @DisplayName("asignar a un médico inexistente responde 404 y no resuelve el consultorio")
    void asignar_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoConsultorioService.asignar(adminDe(tenantId), medicoId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verifyNoInteractions(consultorioRepository);
        verify(medicoConsultorioRepository, never()).save(any(MedicoConsultorio.class));
    }

    @Test
    @DisplayName("asignar a un médico de otra organización responde 404 y no toca la asignación")
    void asignar_shouldThrowResourceNotFoundException_whenMedicoBelongsToAnotherTenant() {
        var tenantDelToken = UUID.randomUUID();
        var tenantAjeno = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var organizacionAjena = organizacionConId(tenantAjeno);
        var consultorioAjeno = consultorioConId(UUID.randomUUID(), organizacionAjena, "Consultorio Ajeno");
        var medicoAjeno = medicoConId(medicoId, organizacionAjena, consultorioAjeno);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medicoAjeno));

        assertThatThrownBy(() -> medicoConsultorioService.asignar(adminDe(tenantDelToken), medicoId,
                UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verify(medicoConsultorioRepository, never())
                .buscarAsignacion(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(medicoConsultorioRepository, never()).save(any(MedicoConsultorio.class));
    }

    @Test
    @DisplayName("asignar un consultorio de otra organización responde 404, nunca 403")
    void asignar_shouldThrowResourceNotFoundException_whenConsultorioIsFromAnotherTenant() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoConsultorioService.asignar(adminDe(tenantId), medicoId, consultorioId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(consultorioId.toString())
                .as("un consultorio ajeno y uno inexistente dan el mismo 404; un 403 confirmaría que existe")
                .isNotInstanceOf(AccessDeniedException.class);
        verify(medicoConsultorioRepository, never()).save(any(MedicoConsultorio.class));
    }

    // ------------------------------------------------------------------
    // DELETE: quitar, idempotente, con las dos reglas de conflicto
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quitar una asignación sin bloques borra la fila")
    void quitar_shouldDeleteTheAssignment_whenTheConsultorioHasNoBlocks() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var consultorio = consultorioConId(consultorioId, organizacion, "Consultorio Dos");
        var asignacion = new MedicoConsultorio(medico, consultorio, organizacion);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.of(consultorio));
        when(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, tenantId))
                .thenReturn(Optional.of(asignacion));
        when(horarioMedicoRepository.listarBloquesEnConsultorio(medicoId, consultorioId))
                .thenReturn(List.of());

        medicoConsultorioService.quitar(adminDe(tenantId), medicoId, consultorioId);

        verify(medicoConsultorioRepository).delete(asignacion);
    }

    @Test
    @DisplayName("quitar una asignación que no existe es idempotente: 204 sin borrar ni fallar")
    void quitar_shouldDoNothing_whenTheConsultorioIsNotAssigned() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var consultorio = consultorioConId(consultorioId, organizacion, "Consultorio Dos");
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.of(consultorio));
        when(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, tenantId))
                .thenReturn(Optional.empty());

        medicoConsultorioService.quitar(adminDe(tenantId), medicoId, consultorioId);

        verify(medicoConsultorioRepository, never()).delete(any(MedicoConsultorio.class));
        verify(horarioMedicoRepository, never())
                .listarBloquesEnConsultorio(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("quitar a un médico inexistente responde 404 y no resuelve el consultorio")
    void quitar_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoConsultorioService.quitar(adminDe(tenantId), medicoId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verifyNoInteractions(consultorioRepository);
        verify(medicoConsultorioRepository, never()).delete(any(MedicoConsultorio.class));
    }

    @Test
    @DisplayName("quitar un consultorio de otra organización responde 404, nunca 403")
    void quitar_shouldThrowResourceNotFoundException_whenConsultorioIsFromAnotherTenant() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoConsultorioService.quitar(adminDe(tenantId), medicoId, consultorioId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .isNotInstanceOf(AccessDeniedException.class);
        verify(medicoConsultorioRepository, never())
                .buscarAsignacion(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(medicoConsultorioRepository, never()).delete(any(MedicoConsultorio.class));
    }

    @Test
    @DisplayName("quitar el consultorio principal responde 409 y no borra ni mira los bloques")
    void quitar_shouldThrowConflict_whenTheConsultorioIsThePrincipal() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        UUID principalId = principal.getId();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(principalId, tenantId))
                .thenReturn(Optional.of(principal));

        assertThatThrownBy(() -> medicoConsultorioService.quitar(adminDe(tenantId), medicoId, principalId))
                .isInstanceOf(ConsultorioNoRetirableException.class)
                .hasMessageContaining("consultorio principal")
                // El mensaje no nombra identificadores: quien lo lee está mirando la fila del
                // consultorio que acaba de intentar quitar, así que un UUID solo agrega ruido.
                .satisfies(excepcion -> assertThat(excepcion.getMessage())
                        .doesNotContain(principalId.toString())
                        .doesNotContain(medicoId.toString()));
        verify(medicoConsultorioRepository, never()).delete(any(MedicoConsultorio.class));
        verify(horarioMedicoRepository, never())
                .listarBloquesEnConsultorio(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("quitar un consultorio con bloques de horario responde 409, dice cuáles y no borra")
    void quitar_shouldThrowConflictWithTheBlocks_whenTheConsultorioHasHorarios() {
        var tenantId = UUID.randomUUID();
        var medicoId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(tenantId);
        var principal = consultorioConId(UUID.randomUUID(), organizacion, "Consultorio Principal");
        var medico = medicoConId(medicoId, organizacion, principal);
        var consultorio = consultorioConId(consultorioId, organizacion, "Consultorio Dos");
        var asignacion = new MedicoConsultorio(medico, consultorio, organizacion);
        var bloques = List.of(
                new HorarioMedico(medico, LUNES, NUEVE, TRECE, DURACION_TREINTA),
                new HorarioMedico(medico, JUEVES, QUINCE, DIECINUEVE, DURACION_TREINTA));
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorioId, tenantId))
                .thenReturn(Optional.of(consultorio));
        when(medicoConsultorioRepository.buscarAsignacion(medicoId, consultorioId, tenantId))
                .thenReturn(Optional.of(asignacion));
        when(horarioMedicoRepository.listarBloquesEnConsultorio(medicoId, consultorioId))
                .thenReturn(bloques);

        assertThatThrownBy(() -> medicoConsultorioService.quitar(adminDe(tenantId), medicoId, consultorioId))
                .isInstanceOf(ConsultorioNoRetirableException.class)
                .hasMessageContaining("bloques de horario")
                .hasMessageContaining("lunes de 09:00 a 13:00")
                .hasMessageContaining("jueves de 15:00 a 19:00");
        // La detección es previa: el error de integridad de la V6 no puede salir al cliente.
        verify(medicoConsultorioRepository, never()).delete(any(MedicoConsultorio.class));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static AuthenticatedUser adminDe(UUID tenantId) {
        return new AuthenticatedUser(UUID.randomUUID(), CORREO, ROL_ADMIN, tenantId, null);
    }

    /**
     * Actor {@code MEDICO} del tenant y con el médico indicados.
     *
     * @param tenantId organización del token
     * @param medicoId claim {@code medicoId}; {@code null} simula el token de un médico que no lo
     *                 trae
     * @return el principal que deja el filtro JWT para un médico
     */
    private static AuthenticatedUser medicoDe(UUID tenantId, UUID medicoId) {
        return new AuthenticatedUser(UUID.randomUUID(), CORREO, ROL_MEDICO, tenantId, medicoId);
    }

    /**
     * Organización con identidad simulada mediante un espía: es el tenant contra el que el
     * servicio compara la organización del médico.
     *
     * @param organizacionId identificador que devolverá {@code getId()}
     * @return la organización espía
     */
    private static Organizacion organizacionConId(UUID organizacionId) {
        var organizacion = spy(new Organizacion("Clínica Demo", PlanTipo.CLINICA, null));
        // lenient: no todas las pruebas leen la organización, y una simulación exigida sin uso
        // haría fallar la prueba por un motivo que no es el que se está midiendo.
        lenient().doReturn(organizacionId).when(organizacion).getId();
        return organizacion;
    }

    /**
     * Consultorio de la organización indicada, con identidad simulada mediante un espía.
     *
     * @param consultorioId identificador que devolverá {@code getId()}
     * @param organizacion  organización dueña
     * @param nombre        nombre del consultorio
     * @return el consultorio espía
     */
    private static Consultorio consultorioConId(UUID consultorioId, Organizacion organizacion, String nombre) {
        var consultorio = spy(new Consultorio(organizacion, nombre, null, null));
        // lenient: el identificador se lee solo en las pruebas que lo afirman.
        lenient().doReturn(consultorioId).when(consultorio).getId();
        return consultorio;
    }

    /**
     * Médico de la organización y consultorio principal indicados, con identidad simulada
     * mediante un espía.
     *
     * @param medicoId     identificador que devolverá {@code getId()}
     * @param organizacion organización dueña del médico
     * @param principal    consultorio principal del médico
     * @return el médico espía
     */
    private static Medico medicoConId(UUID medicoId, Organizacion organizacion, Consultorio principal) {
        var medico = spy(new Medico(organizacion, principal, "Dra. Ana Torres", "Cardiología", "1234567",
                "+52 55 1111 2222", new BigDecimal("500.00")));
        // lenient: el identificador del médico solo se lee en las pruebas que lo afirman.
        lenient().doReturn(medicoId).when(medico).getId();
        return medico;
    }
}
