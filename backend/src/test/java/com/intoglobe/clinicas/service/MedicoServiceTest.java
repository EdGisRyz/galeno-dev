package com.intoglobe.clinicas.service;

import java.math.BigDecimal;
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

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.exception.CambioDeConsultorioNoPermitidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link MedicoService} y del mapeo que orquesta.
 *
 * <p>No levantan contexto de Spring: los tres repositorios se simulan con
 * Mockito. La identidad ({@code id}) la asigna Hibernate al persistir y la entidad
 * no expone setters, así que las fixtures con aspecto de registro persistido las
 * aportan espías de Mockito ({@code spy} más {@code doReturn}). Poblar la entidad
 * por reflexión está prohibido en este módulo: no se usa
 * {@code ReflectionTestUtils} ni reflexión propia.</p>
 *
 * <p>Toda aserción sobre {@link BigDecimal} se escribe con
 * {@code isEqualByComparingTo}, nunca con {@code isEqualTo}: {@code equals}
 * compara la escala y {@code 500.0} no es igual a {@code 500.00}.</p>
 *
 * <p>Las validaciones Jakarta ({@code @NotNull}, {@code @NotBlank},
 * {@code @Size}, {@code @Digits}, {@code @DecimalMin}) no se prueban aquí: son del
 * DTO y se ejercitan en los tests MVC de S3.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicoServiceTest {

    @Mock
    private MedicoRepository medicoRepository;

    @Mock
    private OrganizacionRepository organizacionRepository;

    @Mock
    private ConsultorioRepository consultorioRepository;

    @InjectMocks
    private MedicoService medicoService;

    @Test
    @DisplayName("crear resuelve organización y consultorio, guarda el médico y devuelve su representación")
    void crear_shouldSaveMedicoAndReturnResponse_whenOrganizationAndConsultorioExist() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var consultorioId = UUID.randomUUID();
        var consultorio = consultorioConId(organizacion, consultorioId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(consultorioRepository.findById(consultorioId)).thenReturn(Optional.of(consultorio));
        when(medicoRepository.save(any(Medico.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = requestCompleto(consultorioId);

        var respuesta = medicoService.crear(organizacionId, request);

        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.consultorioId()).isEqualTo(consultorioId);
        assertThat(respuesta.nombre()).isEqualTo("Dra. Ana Torres");
        assertThat(respuesta.especialidad()).isEqualTo("Cardiología");
        assertThat(respuesta.cedula()).isEqualTo("1234567");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 1111 2222");
        assertThat(respuesta.tarifaBase()).isEqualByComparingTo("500.00");
        assertThat(respuesta.activo()).isTrue();
        verify(medicoRepository).save(any(Medico.class));
    }

    @Test
    @DisplayName("crear lanza ResourceNotFoundException y no persiste nada cuando la organización no existe")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.empty());
        var request = requestCompleto(UUID.randomUUID());

        assertThatThrownBy(() -> medicoService.crear(organizacionId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(medicoRepository, never()).save(any(Medico.class));
        verify(consultorioRepository, never()).findById(any(UUID.class));
    }

    @Test
    @DisplayName("crear lanza ResourceNotFoundException y no persiste nada cuando el consultorio no existe")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenConsultorioMissing() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionTransitoria();
        var consultorioId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(consultorioRepository.findById(consultorioId)).thenReturn(Optional.empty());
        var request = requestCompleto(consultorioId);

        assertThatThrownBy(() -> medicoService.crear(organizacionId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(consultorioId.toString());
        verify(medicoRepository, never()).save(any(Medico.class));
    }

    @Test
    @DisplayName("buscarPorId devuelve los nueve campos del médico cuando existe")
    void buscarPorId_shouldReturnAllNineFields_whenMedicoExists() {
        var organizacionId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var consultorio = consultorioConId(organizacion, consultorioId);
        var medico = medicoPersistido(organizacion, consultorio);
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));

        var respuesta = medicoService.buscarPorId(medico.getId());

        assertThat(respuesta.id()).isEqualTo(medico.getId());
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.consultorioId()).isEqualTo(consultorioId);
        assertThat(respuesta.nombre()).isEqualTo("Dra. Ana Torres");
        assertThat(respuesta.especialidad()).isEqualTo("Cardiología");
        assertThat(respuesta.cedula()).isEqualTo("1234567");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 1111 2222");
        assertThat(respuesta.tarifaBase()).isEqualByComparingTo("500.00");
        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    @DisplayName("buscarPorId lanza ResourceNotFoundException cuando el médico no existe")
    void buscarPorId_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var id = UUID.randomUUID();
        when(medicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoService.buscarPorId(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("listarPorOrganizacion devuelve los médicos activos mapeados cuando la organización existe")
    void listarPorOrganizacion_shouldReturnMappedMedicos_whenOrganizationExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var consultorio = consultorioConId(organizacion, UUID.randomUUID());
        var primero = medicoPersistidoConNombre(organizacion, consultorio, "Dra. Alfa Salud");
        var segundo = medicoPersistidoConNombre(organizacion, consultorio, "Dr. Beta Médica");
        when(organizacionRepository.existsById(organizacionId)).thenReturn(true);
        when(medicoRepository.listarActivosPorOrganizacion(organizacionId))
                .thenReturn(List.of(primero, segundo));

        var respuestas = medicoService.listarPorOrganizacion(organizacionId);

        assertThat(respuestas)
                .extracting(MedicoResponse::nombre)
                .containsExactly("Dra. Alfa Salud", "Dr. Beta Médica");
        assertThat(respuestas)
                .allMatch(MedicoResponse::activo)
                .allMatch(respuesta -> respuesta.organizacionId().equals(organizacionId));
        verify(medicoRepository).listarActivosPorOrganizacion(organizacionId);
    }

    @Test
    @DisplayName("listarPorOrganizacion lanza ResourceNotFoundException y no consulta la lista cuando la organización no existe")
    void listarPorOrganizacion_shouldThrowResourceNotFoundExceptionAndNotQuery_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.existsById(organizacionId)).thenReturn(false);

        assertThatThrownBy(() -> medicoService.listarPorOrganizacion(organizacionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(medicoRepository, never()).listarActivosPorOrganizacion(any(UUID.class));
    }

    @Test
    @DisplayName("listarPorOrganizacion devuelve lista vacía cuando la organización existe sin médicos")
    void listarPorOrganizacion_shouldReturnEmptyList_whenOrganizationHasNoMedicos() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.existsById(organizacionId)).thenReturn(true);
        when(medicoRepository.listarActivosPorOrganizacion(organizacionId)).thenReturn(List.of());

        var respuestas = medicoService.listarPorOrganizacion(organizacionId);

        assertThat(respuestas).isEmpty();
        verify(medicoRepository).listarActivosPorOrganizacion(organizacionId);
    }

    @Test
    @DisplayName("actualizar reemplaza los cinco campos editables y conserva id, activo, organización y consultorio")
    void actualizar_shouldReplaceEditableFieldsAndPreserveImmutableOnes() {
        var organizacionId = UUID.randomUUID();
        var consultorioId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var consultorio = consultorioConId(organizacion, consultorioId);
        var medico = medicoPersistido(organizacion, consultorio);
        var idOriginal = medico.getId();
        when(medicoRepository.findById(idOriginal)).thenReturn(Optional.of(medico));
        when(medicoRepository.save(any(Medico.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new MedicoRequest(consultorioId, "Dra. Ana Torres Ruiz", "Dermatología", "7654321",
                "+52 55 9999 8888", new BigDecimal("750.50"));

        var respuesta = medicoService.actualizar(idOriginal, request);

        assertThat(respuesta.nombre()).isEqualTo("Dra. Ana Torres Ruiz");
        assertThat(respuesta.especialidad()).isEqualTo("Dermatología");
        assertThat(respuesta.cedula()).isEqualTo("7654321");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 9999 8888");
        assertThat(respuesta.tarifaBase()).isEqualByComparingTo("750.50");
        assertThat(respuesta.id()).isEqualTo(idOriginal);
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.consultorioId()).isEqualTo(consultorioId);

        var captor = ArgumentCaptor.forClass(Medico.class);
        verify(medicoRepository).save(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getId()).isEqualTo(idOriginal);
        assertThat(guardado.isActivo()).isTrue();
        assertThat(guardado.getOrganizacion()).isSameAs(organizacion);
        assertThat(guardado.getConsultorio()).isSameAs(consultorio);
    }

    @Test
    @DisplayName("actualizar lanza CambioDeConsultorioNoPermitidoException y no guarda cuando el consultorio difiere")
    void actualizar_shouldThrowCambioDeConsultorioNoPermitidoExceptionAndNotSave_whenConsultorioDiffers() {
        var organizacion = organizacionTransitoria();
        var consultorioActualId = UUID.randomUUID();
        var consultorioActual = consultorioConId(organizacion, consultorioActualId);
        var medico = medicoPersistido(organizacion, consultorioActual);
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        var otroConsultorioId = UUID.randomUUID();
        var request = new MedicoRequest(otroConsultorioId, "Dra. Otra Persona", "Cardiología", "1234567",
                "+52 55 1111 2222", new BigDecimal("500.00"));

        assertThatThrownBy(() -> medicoService.actualizar(medico.getId(), request))
                .isInstanceOf(CambioDeConsultorioNoPermitidoException.class)
                .hasMessageContaining("consultorio");
        verify(medicoRepository, never()).save(any(Medico.class));
        assertThat(medico.getConsultorio()).isSameAs(consultorioActual);
        assertThat(medico.getNombre()).isEqualTo("Dra. Ana Torres");
    }

    @Test
    @DisplayName("actualizar procede y guarda cuando el consultorio del request es el mismo que el actual")
    void actualizar_shouldProceedAndSave_whenConsultorioIsUnchanged() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var consultorioId = UUID.randomUUID();
        var consultorio = consultorioConId(organizacion, consultorioId);
        var medico = medicoPersistido(organizacion, consultorio);
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        when(medicoRepository.save(any(Medico.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = requestCompleto(consultorioId);

        assertThatCode(() -> medicoService.actualizar(medico.getId(), request))
                .doesNotThrowAnyException();

        verify(medicoRepository).save(medico);
        assertThat(medico.getConsultorio()).isSameAs(consultorio);
    }

    @Test
    @DisplayName("actualizar lanza ResourceNotFoundException cuando el médico no existe")
    void actualizar_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var id = UUID.randomUUID();
        when(medicoRepository.findById(id)).thenReturn(Optional.empty());
        var request = requestCompleto(UUID.randomUUID());

        assertThatThrownBy(() -> medicoService.actualizar(id, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(id.toString());
        verify(medicoRepository, never()).save(any(Medico.class));
    }

    @Test
    @DisplayName("desactivar aplica la baja lógica y es idempotente: dos llamadas no lanzan excepción")
    void desactivar_shouldDeactivateAndRemainIdempotent_whenCalledTwice() {
        var organizacion = organizacionTransitoria();
        var consultorio = consultorioTransitorio(organizacion);
        var medico = medicoPersistido(organizacion, consultorio);
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        when(medicoRepository.save(any(Medico.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        assertThatCode(() -> {
            medicoService.desactivar(medico.getId());
            medicoService.desactivar(medico.getId());
        }).doesNotThrowAnyException();

        assertThat(medico.isActivo()).isFalse();
        verify(medicoRepository, times(2)).findById(medico.getId());
        verify(medicoRepository, times(2)).save(medico);
    }

    @Test
    @DisplayName("desactivar lanza ResourceNotFoundException cuando el médico no existe")
    void desactivar_shouldThrowResourceNotFoundException_whenMedicoMissing() {
        var id = UUID.randomUUID();
        when(medicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> medicoService.desactivar(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Medico")
                .hasMessageContaining(id.toString());
        verify(medicoRepository, never()).save(any(Medico.class));
    }

    @Test
    @DisplayName("actualizar limpia especialidad, cédula, teléfono y tarifa cuando el request los envía en null")
    void actualizar_shouldClearOptionalFields_whenRequestSendsNull() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var consultorioId = UUID.randomUUID();
        var consultorio = consultorioConId(organizacion, consultorioId);
        var medico = medicoPersistido(organizacion, consultorio);
        when(medicoRepository.findById(medico.getId())).thenReturn(Optional.of(medico));
        when(medicoRepository.save(any(Medico.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new MedicoRequest(consultorioId, "Dra. Ana Torres", null, null, null, null);

        var respuesta = medicoService.actualizar(medico.getId(), request);

        assertThat(respuesta.nombre()).isEqualTo("Dra. Ana Torres");
        assertThat(respuesta.especialidad()).isNull();
        assertThat(respuesta.cedula()).isNull();
        assertThat(respuesta.telefono()).isNull();
        assertThat(respuesta.tarifaBase()).isNull();
        assertThat(medico.getEspecialidad()).isNull();
        assertThat(medico.getCedula()).isNull();
        assertThat(medico.getTelefono()).isNull();
        assertThat(medico.getTarifaBase()).isNull();
    }

    /**
     * Request completo y válido apuntando al consultorio indicado.
     */
    private static MedicoRequest requestCompleto(UUID consultorioId) {
        return new MedicoRequest(consultorioId, "Dra. Ana Torres", "Cardiología", "1234567",
                "+52 55 1111 2222", new BigDecimal("500.00"));
    }

    /**
     * Organización transitoria sin identidad, suficiente cuando el mapeo no lee su id.
     */
    private static Organizacion organizacionTransitoria() {
        return new Organizacion("Clínica Demo", PlanTipo.CLINICA, null);
    }

    /**
     * Organización con identidad simulada. Se usa un espía en vez de reflexión para
     * respetar la prohibición de poblar entidades por reflexión.
     */
    private static Organizacion organizacionConId(UUID id) {
        var organizacion = spy(organizacionTransitoria());
        doReturn(id).when(organizacion).getId();
        return organizacion;
    }

    /**
     * Consultorio transitorio con datos de ejemplo.
     */
    private static Consultorio consultorioTransitorio(Organizacion organizacion) {
        return new Consultorio(organizacion, "Consultorio Centro", "Av. Reforma 100, CDMX", "+52 55 5555 1234");
    }

    /**
     * Consultorio con identidad simulada mediante un espía.
     */
    private static Consultorio consultorioConId(Organizacion organizacion, UUID id) {
        var consultorio = spy(consultorioTransitorio(organizacion));
        doReturn(id).when(consultorio).getId();
        return consultorio;
    }

    /**
     * Médico transitorio con todos los campos poblables asignados.
     */
    private static Medico medicoTransitorioConNombre(Organizacion organizacion, Consultorio consultorio,
            String nombre) {
        return new Medico(organizacion, consultorio, nombre, "Cardiología", "1234567", "+52 55 1111 2222",
                new BigDecimal("500.00"));
    }

    /**
     * Médico con aspecto de registro persistido: id simulado con un espía que
     * conserva el comportamiento real del dominio.
     */
    private static Medico medicoPersistido(Organizacion organizacion, Consultorio consultorio) {
        var medico = spy(medicoTransitorioConNombre(organizacion, consultorio, "Dra. Ana Torres"));
        doReturn(UUID.randomUUID()).when(medico).getId();
        return medico;
    }

    /**
     * Médico persistido con nombre explícito, usado en los listados ordenados.
     */
    private static Medico medicoPersistidoConNombre(Organizacion organizacion, Consultorio consultorio,
            String nombre) {
        var medico = spy(medicoTransitorioConNombre(organizacion, consultorio, nombre));
        doReturn(UUID.randomUUID()).when(medico).getId();
        return medico;
    }
}
