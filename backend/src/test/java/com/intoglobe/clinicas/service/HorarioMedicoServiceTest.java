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

import com.intoglobe.clinicas.dto.request.HorarioMedicoRequest;
import com.intoglobe.clinicas.dto.response.HorarioMedicoResponse;
import com.intoglobe.clinicas.exception.DuracionConsultaInvalidaException;
import com.intoglobe.clinicas.exception.RangoHorarioInvalidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.SolapamientoDeHorarioException;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.HorarioMedico;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.HorarioMedicoRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link HorarioMedicoService} y del mapeo que orquesta.
 *
 * <p>No levantan contexto de Spring: los dos repositorios se simulan con Mockito. La
 * identidad ({@code id}) la asigna la base al persistir y las entidades no exponen
 * setters, así que las fixtures con aspecto de registro persistido las aportan espías de
 * Mockito ({@code spy} más {@code doReturn}). Poblar entidades por reflexión está
 * prohibido en este módulo: no se usa {@code ReflectionTestUtils} ni reflexión propia. Las
 * fixtures se construyen siempre <em>antes</em> de la instrucción {@code when} que las
 * consume, porque anidar una simulación dentro de otra deja la primera sin terminar.</p>
 *
 * <p>El orden de validación del servicio es contractual, así que varias pruebas hacen
 * fallar dos reglas a la vez y afirman cuál gana: médico inexistente antes que el rango,
 * el rango antes que la duración y la duración antes que el solapamiento. En todos los
 * rechazos se comprueba además que no se persiste nada.</p>
 *
 * <p>Las validaciones Jakarta del request ({@code @Min}, {@code @Max}, {@code @NotNull})
 * no se prueban aquí: son del DTO y se ejercitan en los tests MVC de una rebanada
 * posterior.</p>
 */
@ExtendWith(MockitoExtension.class)
class HorarioMedicoServiceTest {

    private static final short LUNES = 1;
    private static final short MARTES = 2;
    private static final short DURACION_TREINTA = 30;
    private static final LocalTime NUEVE = LocalTime.of(9, 0);
    private static final LocalTime DIEZ = LocalTime.of(10, 0);
    private static final LocalTime ONCE = LocalTime.of(11, 0);
    private static final LocalTime DOCE = LocalTime.NOON;

    /**
     * Valor de {@code idExcluido} que el servicio debe enviar al crear: el UUID todo
     * ceros, que nunca coincide con un {@code id} real.
     */
    private static final UUID NINGUN_ID = new UUID(0L, 0L);

    @Mock
    private HorarioMedicoRepository horarioMedicoRepository;

    @Mock
    private MedicoRepository medicoRepository;

    @InjectMocks
    private HorarioMedicoService horarioMedicoService;

    @Test
    @DisplayName("crear resuelve el médico, comprueba solapamientos y guarda el bloque mapeado")
    void crear_shouldSaveHorarioMappedFromRequest_whenMedicoExistsAndBlockIsFree() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(horarioMedicoRepository.contarSolapamientos(any(UUID.class), anyShort(), any(LocalTime.class),
                any(LocalTime.class), any(UUID.class))).thenReturn(0L);
        when(horarioMedicoRepository.save(any(HorarioMedico.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = request(LUNES, NUEVE, DIEZ, DURACION_TREINTA);

        var respuesta = horarioMedicoService.crear(medicoId, request);

        assertThat(respuesta.medicoId()).isEqualTo(medicoId);
        assertThat(respuesta.diaSemana()).isEqualTo(LUNES);
        assertThat(respuesta.horaInicio()).isEqualTo(NUEVE);
        assertThat(respuesta.horaFin()).isEqualTo(DIEZ);
        assertThat(respuesta.duracionConsulta()).isEqualTo(DURACION_TREINTA);
        var captor = ArgumentCaptor.forClass(HorarioMedico.class);
        verify(horarioMedicoRepository).save(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getMedico()).isSameAs(medico);
        assertThat(guardado.getDiaSemana()).isEqualTo(LUNES);
        assertThat(guardado.getHoraInicio()).isEqualTo(NUEVE);
        assertThat(guardado.getHoraFin()).isEqualTo(DIEZ);
        assertThat(guardado.getDuracionConsulta()).isEqualTo(DURACION_TREINTA);
    }

    @Test
    @DisplayName("crear lanza ResourceNotFoundException y no persiste nada cuando el médico no existe")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenMedicoMissing() {
        var medicoId = UUID.randomUUID();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());
        var request = request(LUNES, NUEVE, DIEZ, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(medicoId.toString());
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
        verify(horarioMedicoRepository, never()).contarSolapamientos(any(UUID.class), anyShort(),
                any(LocalTime.class), any(LocalTime.class), any(UUID.class));
    }

    @Test
    @DisplayName("crear gana el 404 del médico aunque el rango también sea inválido: resuelve primero al dueño")
    void crear_shouldThrowResourceNotFoundExceptionBeforeRangeRule_whenBothFail() {
        var medicoId = UUID.randomUUID();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.empty());
        var request = request(LUNES, DIEZ, NUEVE, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico");
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("crear con solapamiento consulta con el UUID todo ceros como idExcluido y no guarda")
    void crear_shouldThrowSolapamientoDeHorarioExceptionAndNotSave_whenBlockOverlaps() {
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(horarioMedicoRepository.contarSolapamientos(any(UUID.class), anyShort(), any(LocalTime.class),
                any(LocalTime.class), any(UUID.class))).thenReturn(1L);
        var request = request(LUNES, NUEVE, DIEZ, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(SolapamientoDeHorarioException.class)
                .hasMessageContaining(NUEVE.toString())
                .hasMessageContaining(DIEZ.toString());
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));

        var captor = ArgumentCaptor.forClass(UUID.class);
        verify(horarioMedicoRepository).contarSolapamientos(eq(medicoId), eq(LUNES), eq(NUEVE), eq(DIEZ),
                captor.capture());
        assertThat(captor.getValue())
                .as("al crear no hay bloque propio que excluir; se pasa el UUID cero documentado")
                .isEqualTo(NINGUN_ID);
    }

    @Test
    @DisplayName("crear con rango invertido lanza RangoHorarioInvalidoException y no guarda")
    void crear_shouldThrowRangoHorarioInvalidoExceptionAndNotSave_whenInicioIsAfterFin() {
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        var request = request(LUNES, DIEZ, NUEVE, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(RangoHorarioInvalidoException.class)
                .hasMessageContaining(DIEZ.toString())
                .hasMessageContaining(NUEVE.toString());
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
        verify(horarioMedicoRepository, never()).contarSolapamientos(any(UUID.class), anyShort(),
                any(LocalTime.class), any(LocalTime.class), any(UUID.class));
    }

    @Test
    @DisplayName("crear con un bloque de duración cero lanza RangoHorarioInvalidoException y no guarda")
    void crear_shouldThrowRangoHorarioInvalidoExceptionAndNotSave_whenBlockHasZeroLength() {
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        var request = request(LUNES, NUEVE, NUEVE, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(RangoHorarioInvalidoException.class);
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("crear con duración fuera del conjunto permitido (25) lanza DuracionConsultaInvalidaException y no guarda")
    void crear_shouldThrowDuracionConsultaInvalidaExceptionAndNotSave_whenDuracionIsNotAllowed() {
        short veinticinco = 25;
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        var request = request(LUNES, NUEVE, DIEZ, veinticinco);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(DuracionConsultaInvalidaException.class)
                .hasMessageContaining("25");
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
        verify(horarioMedicoRepository, never()).contarSolapamientos(any(UUID.class), anyShort(),
                any(LocalTime.class), any(LocalTime.class), any(UUID.class));
    }

    @Test
    @DisplayName("crear gana la regla de duración sobre el solapamiento: no llega a consultar solapamientos")
    void crear_shouldThrowDuracionConsultaInvalidaExceptionBeforeOverlapCheck_whenBothFail() {
        short cero = 0;
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        var request = request(LUNES, NUEVE, DIEZ, cero);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(DuracionConsultaInvalidaException.class)
                .hasMessageContaining("0");
        verify(horarioMedicoRepository, never()).contarSolapamientos(any(UUID.class), anyShort(),
                any(LocalTime.class), any(LocalTime.class), any(UUID.class));
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("crear gana la regla del rango sobre la duración cuando las dos fallan a la vez")
    void crear_shouldThrowRangoHorarioInvalidoExceptionBeforeDurationRule_whenBothFail() {
        short veinticinco = 25;
        var medicoId = UUID.randomUUID();
        var medico = medicoTransitorio();
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        var request = request(LUNES, DIEZ, NUEVE, veinticinco);

        assertThatThrownBy(() -> horarioMedicoService.crear(medicoId, request))
                .isInstanceOf(RangoHorarioInvalidoException.class);
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("crear acepta bloques adyacentes cuando la consulta de solapamientos devuelve 0 y guarda")
    void crear_shouldSave_whenNewBlockIsAdjacentToAnExistingOne() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        when(medicoRepository.findById(medicoId)).thenReturn(Optional.of(medico));
        when(horarioMedicoRepository.contarSolapamientos(medicoId, LUNES, DIEZ, ONCE, NINGUN_ID))
                .thenReturn(0L);
        when(horarioMedicoRepository.save(any(HorarioMedico.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = request(LUNES, DIEZ, ONCE, DURACION_TREINTA);

        assertThatCode(() -> horarioMedicoService.crear(medicoId, request)).doesNotThrowAnyException();

        verify(horarioMedicoRepository).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("buscarPorId devuelve los seis campos del horario cuando existe")
    void buscarPorId_shouldReturnAllSixFields_whenHorarioExists() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        var horario = horarioConId(medico, LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var id = horario.getId();
        when(horarioMedicoRepository.findById(id)).thenReturn(Optional.of(horario));

        var respuesta = horarioMedicoService.buscarPorId(id);

        assertThat(respuesta.id()).isEqualTo(id);
        assertThat(respuesta.medicoId()).isEqualTo(medicoId);
        assertThat(respuesta.diaSemana()).isEqualTo(LUNES);
        assertThat(respuesta.horaInicio()).isEqualTo(NUEVE);
        assertThat(respuesta.horaFin()).isEqualTo(DIEZ);
        assertThat(respuesta.duracionConsulta()).isEqualTo(DURACION_TREINTA);
    }

    @Test
    @DisplayName("buscarPorId lanza ResourceNotFoundException cuando el horario no existe")
    void buscarPorId_shouldThrowResourceNotFoundException_whenHorarioMissing() {
        var id = UUID.randomUUID();
        when(horarioMedicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> horarioMedicoService.buscarPorId(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Horario")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("listarPorMedico devuelve los bloques mapeados en el orden que entrega el repositorio")
    void listarPorMedico_shouldReturnMappedBlocks_whenMedicoExists() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        var lunes = horarioConId(medico, LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var martes = horarioConId(medico, MARTES, ONCE, DOCE, DURACION_TREINTA);
        when(medicoRepository.existsById(medicoId)).thenReturn(true);
        when(horarioMedicoRepository.listarPorMedico(medicoId)).thenReturn(List.of(lunes, martes));

        var respuestas = horarioMedicoService.listarPorMedico(medicoId);

        assertThat(respuestas)
                .extracting(HorarioMedicoResponse::diaSemana)
                .containsExactly(LUNES, MARTES);
        assertThat(respuestas)
                .allMatch(respuesta -> respuesta.medicoId().equals(medicoId));
        verify(horarioMedicoRepository).listarPorMedico(medicoId);
    }

    @Test
    @DisplayName("listarPorMedico lanza ResourceNotFoundException y no consulta la lista cuando el médico no existe")
    void listarPorMedico_shouldThrowResourceNotFoundExceptionAndNotQuery_whenMedicoMissing() {
        var medicoId = UUID.randomUUID();
        when(medicoRepository.existsById(medicoId)).thenReturn(false);

        assertThatThrownBy(() -> horarioMedicoService.listarPorMedico(medicoId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(medicoId.toString());
        verify(horarioMedicoRepository, never()).listarPorMedico(any(UUID.class));
    }

    @Test
    @DisplayName("actualizar reemplaza los cuatro campos editables y conserva id y médico")
    void actualizar_shouldReplaceEditableFieldsAndPreserveIdAndMedico() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        var horario = horarioConId(medico, LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var idOriginal = horario.getId();
        when(horarioMedicoRepository.findById(idOriginal)).thenReturn(Optional.of(horario));
        when(horarioMedicoRepository.contarSolapamientos(any(UUID.class), anyShort(), any(LocalTime.class),
                any(LocalTime.class), any(UUID.class))).thenReturn(0L);
        when(horarioMedicoRepository.save(any(HorarioMedico.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = request(MARTES, ONCE, DOCE, (short) 45);

        var respuesta = horarioMedicoService.actualizar(idOriginal, request);

        assertThat(respuesta.id()).isEqualTo(idOriginal);
        assertThat(respuesta.medicoId()).isEqualTo(medicoId);
        assertThat(respuesta.diaSemana()).isEqualTo(MARTES);
        assertThat(respuesta.horaInicio()).isEqualTo(ONCE);
        assertThat(respuesta.horaFin()).isEqualTo(DOCE);
        assertThat(respuesta.duracionConsulta()).isEqualTo((short) 45);

        var captor = ArgumentCaptor.forClass(HorarioMedico.class);
        verify(horarioMedicoRepository).save(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getId()).isEqualTo(idOriginal);
        assertThat(guardado.getMedico()).isSameAs(medico);
    }

    @Test
    @DisplayName("actualizar consulta el solapamiento excluyendo el id del propio bloque")
    void actualizar_shouldExcludeItsOwnIdWhenCheckingOverlap() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        var horario = horarioConId(medico, LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var idPropio = horario.getId();
        when(horarioMedicoRepository.findById(idPropio)).thenReturn(Optional.of(horario));
        when(horarioMedicoRepository.contarSolapamientos(any(UUID.class), anyShort(), any(LocalTime.class),
                any(LocalTime.class), any(UUID.class))).thenReturn(0L);
        when(horarioMedicoRepository.save(any(HorarioMedico.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = request(LUNES, NUEVE, ONCE, DURACION_TREINTA);

        horarioMedicoService.actualizar(idPropio, request);

        verify(horarioMedicoRepository).contarSolapamientos(eq(medicoId), eq(LUNES), eq(NUEVE), eq(ONCE),
                eq(idPropio));
        verify(horarioMedicoRepository).save(horario);
    }

    @Test
    @DisplayName("actualizar lanza ResourceNotFoundException y no guarda cuando el horario no existe")
    void actualizar_shouldThrowResourceNotFoundException_whenHorarioMissing() {
        var id = UUID.randomUUID();
        when(horarioMedicoRepository.findById(id)).thenReturn(Optional.empty());
        var request = request(LUNES, NUEVE, DIEZ, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.actualizar(id, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Horario")
                .hasMessageContaining(id.toString());
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
    }

    @Test
    @DisplayName("actualizar con solapamiento lanza SolapamientoDeHorarioException y no guarda")
    void actualizar_shouldThrowSolapamientoDeHorarioExceptionAndNotSave_whenBlockOverlaps() {
        var medicoId = UUID.randomUUID();
        var medico = medicoConId(medicoId);
        var horario = horarioConId(medico, LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var idOriginal = horario.getId();
        when(horarioMedicoRepository.findById(idOriginal)).thenReturn(Optional.of(horario));
        when(horarioMedicoRepository.contarSolapamientos(any(UUID.class), anyShort(), any(LocalTime.class),
                any(LocalTime.class), any(UUID.class))).thenReturn(1L);
        var request = request(LUNES, NUEVE, ONCE, DURACION_TREINTA);

        assertThatThrownBy(() -> horarioMedicoService.actualizar(idOriginal, request))
                .isInstanceOf(SolapamientoDeHorarioException.class);
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
        assertThat(horario.getHoraFin()).isEqualTo(DIEZ);
    }

    @Test
    @DisplayName("actualizar con duración inválida lanza DuracionConsultaInvalidaException y no guarda")
    void actualizar_shouldThrowDuracionConsultaInvalidaExceptionAndNotSave_whenDuracionIsNotAllowed() {
        var horario = horarioConId(medicoTransitorio(), LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var idOriginal = horario.getId();
        when(horarioMedicoRepository.findById(idOriginal)).thenReturn(Optional.of(horario));
        var request = request(LUNES, NUEVE, ONCE, (short) 25);

        assertThatThrownBy(() -> horarioMedicoService.actualizar(idOriginal, request))
                .isInstanceOf(DuracionConsultaInvalidaException.class)
                .hasMessageContaining("25");
        verify(horarioMedicoRepository, never()).save(any(HorarioMedico.class));
        verify(horarioMedicoRepository, never()).contarSolapamientos(any(UUID.class), anyShort(),
                any(LocalTime.class), any(LocalTime.class), any(UUID.class));
    }

    @Test
    @DisplayName("eliminar borra el bloque existente")
    void eliminar_shouldDeleteHorario_whenHorarioExists() {
        var horario = horarioConId(medicoTransitorio(), LUNES, NUEVE, DIEZ, DURACION_TREINTA);
        var id = horario.getId();
        when(horarioMedicoRepository.findById(id)).thenReturn(Optional.of(horario));

        horarioMedicoService.eliminar(id);

        verify(horarioMedicoRepository).delete(horario);
    }

    @Test
    @DisplayName("eliminar lanza ResourceNotFoundException y no borra nada cuando el horario no existe")
    void eliminar_shouldThrowResourceNotFoundException_whenHorarioMissing() {
        var id = UUID.randomUUID();
        when(horarioMedicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> horarioMedicoService.eliminar(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Horario")
                .hasMessageContaining(id.toString());
        verify(horarioMedicoRepository, never()).delete(any(HorarioMedico.class));
    }

    /**
     * Request válido con el bloque indicado.
     */
    private static HorarioMedicoRequest request(short diaSemana, LocalTime horaInicio, LocalTime horaFin,
            short duracionConsulta) {
        return new HorarioMedicoRequest(diaSemana, horaInicio, horaFin, duracionConsulta);
    }

    /**
     * Médico transitorio con todos los campos poblables asignados. Se usa cuando la prueba
     * no lee su identificador.
     */
    private static Medico medicoTransitorio() {
        var organizacion = new Organizacion("Clínica Demo", PlanTipo.CLINICA, null);
        var consultorio = new Consultorio(organizacion, "Consultorio Centro", "Av. Reforma 100, CDMX",
                "+52 55 5555 1234");
        return new Medico(organizacion, consultorio, "Dra. Ana Torres", "Cardiología", "1234567",
                "+52 55 1111 2222", new BigDecimal("500.00"));
    }

    /**
     * Médico con identidad simulada mediante un espía, para respetar la prohibición de
     * poblar entidades por reflexión.
     */
    private static Medico medicoConId(UUID id) {
        var medico = spy(medicoTransitorio());
        doReturn(id).when(medico).getId();
        return medico;
    }

    /**
     * Bloque de disponibilidad con identidad simulada mediante un espía.
     */
    private static HorarioMedico horarioConId(Medico medico, short diaSemana, LocalTime horaInicio,
            LocalTime horaFin, short duracionConsulta) {
        var horario = spy(new HorarioMedico(medico, diaSemana, horaInicio, horaFin, duracionConsulta));
        doReturn(UUID.randomUUID()).when(horario).getId();
        return horario;
    }
}
