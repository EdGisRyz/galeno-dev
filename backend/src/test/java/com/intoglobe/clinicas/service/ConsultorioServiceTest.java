package com.intoglobe.clinicas.service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link ConsultorioService} y del mapeo que orquesta.
 *
 * <p>No levantan contexto de Spring: los repositorios se simulan con Mockito. La
 * identidad ({@code id}) y la marca {@code createdAt} las asigna Hibernate al
 * persistir y la entidad no expone setters, así que las fixtures con aspecto de
 * registro persistido las aportan espías de Mockito ({@code spy} más
 * {@code doReturn}). Poblar la entidad por reflexión está prohibido en este
 * módulo: no se usa {@code ReflectionTestUtils} ni reflexión propia.</p>
 *
 * <p>Las validaciones Jakarta ({@code @NotBlank}, {@code @Size}) no se prueban
 * aquí: son del DTO y se ejercitan en los tests MVC de S3.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConsultorioServiceTest {

    @Mock
    private ConsultorioRepository consultorioRepository;

    @Mock
    private OrganizacionRepository organizacionRepository;

    @InjectMocks
    private ConsultorioService consultorioService;

    @Test
    @DisplayName("crear guarda el consultorio de una organización existente y devuelve su representación")
    void crear_shouldSaveConsultorioAndReturnResponse_whenOrganizationExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(consultorioRepository.save(any(Consultorio.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new ConsultorioRequest("Consultorio Centro", "Av. Reforma 100, CDMX", "+52 55 5555 1234");

        var respuesta = consultorioService.crear(organizacionId, request);

        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.nombre()).isEqualTo("Consultorio Centro");
        assertThat(respuesta.direccion()).isEqualTo("Av. Reforma 100, CDMX");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 5555 1234");
        assertThat(respuesta.activo()).isTrue();
        verify(consultorioRepository).save(any(Consultorio.class));
    }

    @Test
    @DisplayName("crear lanza ResourceNotFoundException y no persiste nada cuando la organización no existe")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.empty());
        var request = new ConsultorioRequest("Consultorio Fantasma", null, null);

        assertThatThrownBy(() -> consultorioService.crear(organizacionId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(consultorioRepository, never()).save(any(Consultorio.class));
    }

    @Test
    @DisplayName("buscarPorId devuelve los siete campos del consultorio cuando existe en el tenant")
    void buscarPorId_shouldReturnAllSevenFields_whenConsultorioExists() {
        var organizacionId = UUID.randomUUID();
        var consultorio = consultorioPersistido(organizacionConId(organizacionId), "Consultorio Norte",
                "Calzada Vallejo 500, CDMX", "+52 55 1234 5678", true);
        when(consultorioRepository.buscarPorIdYOrganizacion(consultorio.getId(), organizacionId))
                .thenReturn(Optional.of(consultorio));

        var respuesta = consultorioService.buscarPorId(consultorio.getId(), organizacionId);

        assertThat(respuesta.id()).isEqualTo(consultorio.getId());
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.nombre()).isEqualTo("Consultorio Norte");
        assertThat(respuesta.direccion()).isEqualTo("Calzada Vallejo 500, CDMX");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 1234 5678");
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.createdAt()).isEqualTo(consultorio.getCreatedAt());
    }

    @Test
    @DisplayName("buscarPorId lanza ResourceNotFoundException cuando el consultorio no existe")
    void buscarPorId_shouldThrowResourceNotFoundException_whenConsultorioMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultorioService.buscarPorId(id, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("buscarPorId de un consultorio de otro tenant lanza ResourceNotFoundException (404, no 403)")
    void buscarPorId_shouldThrowResourceNotFound_whenConsultorioBelongsToAnotherTenant() {
        var id = UUID.randomUUID();
        var otroTenant = UUID.randomUUID();
        // La consulta está acotada por organización: para un tenant ajeno el repositorio no
        // devuelve la fila aunque exista, y el service responde 404 en vez de 403.
        when(consultorioRepository.buscarPorIdYOrganizacion(id, otroTenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultorioService.buscarPorId(id, otroTenant))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(id.toString());
        verify(consultorioRepository).buscarPorIdYOrganizacion(id, otroTenant);
    }

    @Test
    @DisplayName("listarPorOrganizacion mapea los consultorios activos cuando la organización existe")
    void listarPorOrganizacion_shouldReturnMappedConsultorios_whenOrganizationExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var primero = consultorioPersistido(organizacion, "Alfa Salud", "Av. Uno 1, CDMX", "555-0001", true);
        var segundo = consultorioPersistido(organizacion, "Beta Médica", null, null, true);
        when(organizacionRepository.existsById(organizacionId)).thenReturn(true);
        when(consultorioRepository.listarActivosPorOrganizacion(organizacionId))
                .thenReturn(List.of(primero, segundo));

        var respuestas = consultorioService.listarPorOrganizacion(organizacionId);

        assertThat(respuestas)
                .extracting(ConsultorioResponse::nombre)
                .containsExactly("Alfa Salud", "Beta Médica");
        assertThat(respuestas)
                .allMatch(ConsultorioResponse::activo)
                .allMatch(respuesta -> respuesta.organizacionId().equals(organizacionId));
        verify(consultorioRepository).listarActivosPorOrganizacion(organizacionId);
    }

    @Test
    @DisplayName("listarPorOrganizacion devuelve lista vacía cuando la organización existe sin consultorios")
    void listarPorOrganizacion_shouldReturnEmptyList_whenOrganizationHasNoConsultorios() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.existsById(organizacionId)).thenReturn(true);
        when(consultorioRepository.listarActivosPorOrganizacion(organizacionId)).thenReturn(List.of());

        var respuestas = consultorioService.listarPorOrganizacion(organizacionId);

        assertThat(respuestas).isEmpty();
        verify(consultorioRepository).listarActivosPorOrganizacion(organizacionId);
    }

    @Test
    @DisplayName("listarPorOrganizacion lanza ResourceNotFoundException y no consulta la lista cuando la organización no existe")
    void listarPorOrganizacion_shouldThrowResourceNotFoundExceptionAndNotQuery_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.existsById(organizacionId)).thenReturn(false);

        assertThatThrownBy(() -> consultorioService.listarPorOrganizacion(organizacionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(consultorioRepository, never()).listarActivosPorOrganizacion(any(UUID.class));
    }

    @Test
    @DisplayName("actualizar reemplaza los tres campos editables y conserva id, createdAt, activo y organización")
    void actualizar_shouldReplaceEditableFieldsAndPreserveImmutableOnes() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var consultorio = consultorioPersistido(organizacion, "Consultorio Original", "Calle Vieja 1", "555-0000",
                false);
        var idOriginal = consultorio.getId();
        var createdAtOriginal = consultorio.getCreatedAt();
        when(consultorioRepository.buscarPorIdYOrganizacion(idOriginal, organizacionId))
                .thenReturn(Optional.of(consultorio));
        when(consultorioRepository.save(any(Consultorio.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new ConsultorioRequest("Consultorio Renombrado", "Calle Nueva 2", "555-1111");

        var respuesta = consultorioService.actualizar(idOriginal, organizacionId, request);

        assertThat(respuesta.nombre()).isEqualTo("Consultorio Renombrado");
        assertThat(respuesta.direccion()).isEqualTo("Calle Nueva 2");
        assertThat(respuesta.telefono()).isEqualTo("555-1111");
        assertThat(respuesta.id()).isEqualTo(idOriginal);
        assertThat(respuesta.createdAt()).isEqualTo(createdAtOriginal);
        assertThat(respuesta.activo()).isFalse();
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);

        var captor = ArgumentCaptor.forClass(Consultorio.class);
        verify(consultorioRepository).save(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getId()).isEqualTo(idOriginal);
        assertThat(guardado.getCreatedAt()).isEqualTo(createdAtOriginal);
        assertThat(guardado.isActivo()).isFalse();
        assertThat(guardado.getOrganizacion()).isSameAs(organizacion);
    }

    @Test
    @DisplayName("actualizar limpia direccion y telefono cuando el request los envía en null")
    void actualizar_shouldClearDireccionAndTelefono_whenRequestSendsNull() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var consultorio = new Consultorio(organizacionTransitoria(), "Consultorio Con Datos", "Calle Vieja 1",
                "555-0000");
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.of(consultorio));
        when(consultorioRepository.save(any(Consultorio.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new ConsultorioRequest("Consultorio Sin Datos", null, null);

        var respuesta = consultorioService.actualizar(id, tenantId, request);

        assertThat(respuesta.nombre()).isEqualTo("Consultorio Sin Datos");
        assertThat(respuesta.direccion()).isNull();
        assertThat(respuesta.telefono()).isNull();
        assertThat(consultorio.getDireccion()).isNull();
        assertThat(consultorio.getTelefono()).isNull();
    }

    @Test
    @DisplayName("actualizar lanza ResourceNotFoundException cuando el consultorio no existe")
    void actualizar_shouldThrowResourceNotFoundException_whenConsultorioMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.empty());
        var request = new ConsultorioRequest("Consultorio Fantasma", "Calle Falsa 123", null);

        assertThatThrownBy(() -> consultorioService.actualizar(id, tenantId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(id.toString());
        verify(consultorioRepository, never()).save(any(Consultorio.class));
    }

    @Test
    @DisplayName("actualizar un consultorio de otro tenant no persiste nada (404, no 403)")
    void actualizar_shouldNotSave_whenConsultorioBelongsToAnotherTenant() {
        var id = UUID.randomUUID();
        var otroTenant = UUID.randomUUID();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, otroTenant)).thenReturn(Optional.empty());
        var request = new ConsultorioRequest("Consultorio Ajeno", null, null);

        assertThatThrownBy(() -> consultorioService.actualizar(id, otroTenant, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(consultorioRepository, never()).save(any(Consultorio.class));
    }

    @Test
    @DisplayName("desactivar deja inactivo un consultorio activo y lo persiste")
    void desactivar_shouldDeactivateConsultorioAndPersistIt() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var consultorio = new Consultorio(organizacionTransitoria(), "Consultorio Activo", null, null);
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.of(consultorio));
        when(consultorioRepository.save(any(Consultorio.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        consultorioService.desactivar(id, tenantId);

        assertThat(consultorio.isActivo()).isFalse();
        verify(consultorioRepository).save(consultorio);
    }

    @Test
    @DisplayName("desactivar es idempotente: dos llamadas sobre uno ya inactivo no lanzan excepción")
    void desactivar_shouldRemainIdempotent_whenConsultorioIsAlreadyInactive() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var consultorio = new Consultorio(organizacionTransitoria(), "Consultorio Ya Inactivo", null, null);
        consultorio.desactivar();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.of(consultorio));
        when(consultorioRepository.save(any(Consultorio.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        consultorioService.desactivar(id, tenantId);
        consultorioService.desactivar(id, tenantId);

        assertThat(consultorio.isActivo()).isFalse();
        verify(consultorioRepository, times(2)).buscarPorIdYOrganizacion(id, tenantId);
        verify(consultorioRepository, times(2)).save(consultorio);
    }

    @Test
    @DisplayName("desactivar lanza ResourceNotFoundException cuando el consultorio no existe")
    void desactivar_shouldThrowResourceNotFoundException_whenConsultorioMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultorioService.desactivar(id, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Consultorio")
                .hasMessageContaining(id.toString());
        verify(consultorioRepository, never()).save(any(Consultorio.class));
    }

    @Test
    @DisplayName("desactivar un consultorio de otro tenant no lo desactiva (404, no 403)")
    void desactivar_shouldNotDeactivate_whenConsultorioBelongsToAnotherTenant() {
        var id = UUID.randomUUID();
        var otroTenant = UUID.randomUUID();
        when(consultorioRepository.buscarPorIdYOrganizacion(id, otroTenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultorioService.desactivar(id, otroTenant))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(consultorioRepository, never()).save(any(Consultorio.class));
    }

    /**
     * Organización transitoria sin identidad, suficiente cuando el mapeo no lee su id.
     */
    private static Organizacion organizacionTransitoria() {
        return new Organizacion("Clínica Demo", PlanTipo.CLINICA, null);
    }

    /**
     * Organización con identidad simulada. Se usa un espía en vez de reflexión
     * para respetar la prohibición de poblar entidades por reflexión.
     */
    private static Organizacion organizacionConId(UUID id) {
        var organizacion = spy(organizacionTransitoria());
        doReturn(id).when(organizacion).getId();
        return organizacion;
    }

    /**
     * Consultorio con aspecto de registro persistido: id y createdAt simulados con
     * un espía que conserva el comportamiento real del dominio.
     */
    private static Consultorio consultorioPersistido(Organizacion organizacion, String nombre, String direccion,
            String telefono, boolean activo) {
        var consultorio = spy(new Consultorio(organizacion, nombre, direccion, telefono));
        doReturn(UUID.randomUUID()).when(consultorio).getId();
        doReturn(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)).when(consultorio).getCreatedAt();
        if (!activo) {
            consultorio.desactivar();
        }
        return consultorio;
    }
}
