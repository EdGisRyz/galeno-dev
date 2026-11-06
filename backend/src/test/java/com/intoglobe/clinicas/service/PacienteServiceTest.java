package com.intoglobe.clinicas.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.dto.response.PacienteResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Paciente;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.PacienteRepository;

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
 * Pruebas unitarias de {@link PacienteService} y del mapeo que orquesta.
 *
 * <p>No levantan contexto de Spring: los dos repositorios se simulan con Mockito. La
 * identidad ({@code id}) y {@code createdAt} los asigna Hibernate al persistir y la
 * entidad no expone setters, así que las fixtures con aspecto de registro persistido
 * las aportan espías de Mockito ({@code spy} más {@code doReturn}). Poblar la entidad
 * por reflexión está prohibido en este módulo: no se usa {@code ReflectionTestUtils}
 * ni reflexión propia.</p>
 *
 * <p>El acotamiento por tenant se prueba en las dos direcciones: un id inexistente y un
 * id de otra organización deben producir el mismo {@link ResourceNotFoundException}
 * —404, nunca 403— y ninguno debe persistir nada ni consultar el listado.</p>
 *
 * <p>Las validaciones Jakarta ({@code @NotBlank}, {@code @Size}, {@code @Pattern}) no se
 * prueban aquí: son del DTO y se ejercitan en los tests MVC de S3.</p>
 */
@ExtendWith(MockitoExtension.class)
class PacienteServiceTest {

    @Mock
    private PacienteRepository pacienteRepository;

    @Mock
    private OrganizacionRepository organizacionRepository;

    @InjectMocks
    private PacienteService pacienteService;

    @Test
    @DisplayName("crear resuelve la organización, guarda el paciente y devuelve su representación")
    void crear_shouldSavePacienteAndReturnResponse_whenOrganizationExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = pacienteService.crear(organizacionId, organizacionId, requestCompleto());

        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.nombre()).isEqualTo("María López Ramírez");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 5555 4321");
        assertThat(respuesta.fechaNacimiento()).isEqualTo(LocalDate.of(1985, 3, 14));
        assertThat(respuesta.sexo()).isEqualTo("F");
        assertThat(respuesta.email()).isEqualTo("maria.lopez@example.com");
        assertThat(respuesta.notas()).isEqualTo("Alergia a la penicilina");
        assertThat(respuesta.activo()).isTrue();
        verify(pacienteRepository).save(any(Paciente.class));
    }

    @Test
    @DisplayName("crear lanza ResourceNotFoundException y no persiste nada cuando la organización no existe")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.crear(organizacionId, organizacionId, requestCompleto()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(pacienteRepository, never()).save(any(Paciente.class));
    }

    @Test
    @DisplayName("crear con la organización de otro tenant responde 404 y no consulta ni persiste nada")
    void crear_shouldThrowResourceNotFoundExceptionAndNotSave_whenOrganizationBelongsToAnotherTenant() {
        var organizacionAjena = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> pacienteService.crear(organizacionAjena, tenantId, requestCompleto()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionAjena.toString());
        verify(organizacionRepository, never()).findById(any(UUID.class));
        verify(pacienteRepository, never()).save(any(Paciente.class));
    }

    @Test
    @DisplayName("buscarPorId devuelve los diez campos del paciente cuando existe en el tenant")
    void buscarPorId_shouldReturnAllTenFields_whenPacienteExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var paciente = pacientePersistido(organizacion);
        var createdAt = OffsetDateTime.parse("2025-01-15T10:30:00-06:00");
        doReturn(createdAt).when(paciente).getCreatedAt();
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));

        var respuesta = pacienteService.buscarPorId(paciente.getId(), organizacionId);

        assertThat(respuesta.id()).isEqualTo(paciente.getId());
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);
        assertThat(respuesta.nombre()).isEqualTo("María López Ramírez");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 5555 4321");
        assertThat(respuesta.fechaNacimiento()).isEqualTo(LocalDate.of(1985, 3, 14));
        assertThat(respuesta.sexo()).isEqualTo("F");
        assertThat(respuesta.email()).isEqualTo("maria.lopez@example.com");
        assertThat(respuesta.notas()).isEqualTo("Alergia a la penicilina");
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("buscarPorId lanza ResourceNotFoundException cuando el paciente no existe")
    void buscarPorId_shouldThrowResourceNotFoundException_whenPacienteMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(pacienteRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.buscarPorId(id, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("buscarPorId de un paciente de otro tenant lanza ResourceNotFoundException (404, no 403)")
    void buscarPorId_shouldThrowResourceNotFound_whenPacienteBelongsToAnotherTenant() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var paciente = pacientePersistido(organizacion);
        var otroTenant = UUID.randomUUID();
        // La fila existe, pero la comparación en memoria de la organización con el tenant
        // la descarta: el cliente recibe el mismo 404 que un id inexistente.
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));

        assertThatThrownBy(() -> pacienteService.buscarPorId(paciente.getId(), otroTenant))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente")
                .hasMessageContaining(paciente.getId().toString());
        verify(pacienteRepository).findById(paciente.getId());
    }

    @Test
    @DisplayName("listarPorOrganizacion devuelve los pacientes activos mapeados cuando la organización existe")
    void listarPorOrganizacion_shouldReturnMappedPacientes_whenOrganizationExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var primero = pacientePersistidoConNombre(organizacion, "Ana Alfa");
        var segundo = pacientePersistidoConNombre(organizacion, "Beto Beta");
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.of(organizacion));
        when(pacienteRepository.listarActivosPorOrganizacion(organizacionId))
                .thenReturn(List.of(primero, segundo));

        var respuestas = pacienteService.listarPorOrganizacion(organizacionId, organizacionId);

        assertThat(respuestas)
                .extracting(PacienteResponse::nombre)
                .containsExactly("Ana Alfa", "Beto Beta");
        assertThat(respuestas)
                .allMatch(PacienteResponse::activo)
                .allMatch(respuesta -> respuesta.organizacionId().equals(organizacionId));
        verify(pacienteRepository).listarActivosPorOrganizacion(organizacionId);
    }

    @Test
    @DisplayName("listarPorOrganizacion lanza ResourceNotFoundException y no consulta la lista cuando la organización no existe")
    void listarPorOrganizacion_shouldThrowResourceNotFoundExceptionAndNotQuery_whenOrganizationMissing() {
        var organizacionId = UUID.randomUUID();
        when(organizacionRepository.findById(organizacionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.listarPorOrganizacion(organizacionId, organizacionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionId.toString());
        verify(pacienteRepository, never()).listarActivosPorOrganizacion(any(UUID.class));
    }

    @Test
    @DisplayName("listarPorOrganizacion de otro tenant responde 404 sin consultar el listado")
    void listarPorOrganizacion_shouldThrowResourceNotFoundExceptionAndNotQuery_whenAnotherTenant() {
        var organizacionAjena = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> pacienteService.listarPorOrganizacion(organizacionAjena, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(organizacionAjena.toString());
        verify(organizacionRepository, never()).findById(any(UUID.class));
        verify(pacienteRepository, never()).listarActivosPorOrganizacion(any(UUID.class));
    }

    @Test
    @DisplayName("actualizar reemplaza los seis campos editables y conserva id, activo, createdAt y organización")
    void actualizar_shouldReplaceEditableFieldsAndPreserveImmutableOnes() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var paciente = pacientePersistido(organizacion);
        var createdAt = OffsetDateTime.parse("2025-01-15T10:30:00-06:00");
        doReturn(createdAt).when(paciente).getCreatedAt();
        var idOriginal = paciente.getId();
        when(pacienteRepository.findById(idOriginal)).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new PacienteRequest("María López Ruiz", "+52 55 9999 8888",
                LocalDate.of(1990, 7, 2), "M", "maria.ruiz@example.com", "Sin notas previas");

        var respuesta = pacienteService.actualizar(idOriginal, organizacionId, request);

        assertThat(respuesta.nombre()).isEqualTo("María López Ruiz");
        assertThat(respuesta.telefono()).isEqualTo("+52 55 9999 8888");
        assertThat(respuesta.fechaNacimiento()).isEqualTo(LocalDate.of(1990, 7, 2));
        assertThat(respuesta.sexo()).isEqualTo("M");
        assertThat(respuesta.email()).isEqualTo("maria.ruiz@example.com");
        assertThat(respuesta.notas()).isEqualTo("Sin notas previas");
        assertThat(respuesta.id()).isEqualTo(idOriginal);
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.createdAt()).isEqualTo(createdAt);
        assertThat(respuesta.organizacionId()).isEqualTo(organizacionId);

        var captor = ArgumentCaptor.forClass(Paciente.class);
        verify(pacienteRepository).save(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getId()).isEqualTo(idOriginal);
        assertThat(guardado.isActivo()).isTrue();
        assertThat(guardado.getCreatedAt()).isEqualTo(createdAt);
        assertThat(guardado.getOrganizacion()).isSameAs(organizacion);
    }

    @Test
    @DisplayName("actualizar lanza ResourceNotFoundException cuando el paciente no existe")
    void actualizar_shouldThrowResourceNotFoundException_whenPacienteMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(pacienteRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.actualizar(id, tenantId, requestCompleto()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente")
                .hasMessageContaining(id.toString());
        verify(pacienteRepository, never()).save(any(Paciente.class));
    }

    @Test
    @DisplayName("actualizar un paciente de otro tenant no persiste nada (404, no 403)")
    void actualizar_shouldNotSave_whenPacienteBelongsToAnotherTenant() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var paciente = pacientePersistido(organizacion);
        var otroTenant = UUID.randomUUID();
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));

        assertThatThrownBy(() -> pacienteService.actualizar(paciente.getId(), otroTenant, requestCompleto()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente");
        verify(pacienteRepository, never()).save(any(Paciente.class));
        assertThat(paciente.getNombre()).isEqualTo("María López Ramírez");
    }

    @Test
    @DisplayName("desactivar aplica la baja lógica y solo cambia activo")
    void desactivar_shouldDeactivate_whenPacienteExists() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var paciente = pacientePersistido(organizacion);
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        pacienteService.desactivar(paciente.getId(), organizacionId);

        assertThat(paciente.isActivo()).isFalse();
        assertThat(paciente.getOrganizacion()).isSameAs(organizacion);
        verify(pacienteRepository).save(paciente);
    }

    @Test
    @DisplayName("desactivar es idempotente: dos llamadas no lanzan excepción")
    void desactivar_shouldRemainIdempotent_whenCalledTwice() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var paciente = pacientePersistido(organizacion);
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        assertThatCode(() -> {
            pacienteService.desactivar(paciente.getId(), organizacion.getId());
            pacienteService.desactivar(paciente.getId(), organizacion.getId());
        }).doesNotThrowAnyException();

        assertThat(paciente.isActivo()).isFalse();
        verify(pacienteRepository, times(2)).save(paciente);
    }

    @Test
    @DisplayName("desactivar lanza ResourceNotFoundException cuando el paciente no existe")
    void desactivar_shouldThrowResourceNotFoundException_whenPacienteMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(pacienteRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.desactivar(id, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente")
                .hasMessageContaining(id.toString());
        verify(pacienteRepository, never()).save(any(Paciente.class));
    }

    @Test
    @DisplayName("activar reactiva un paciente dado de baja y solo cambia activo")
    void activar_shouldReactivate_whenPacienteIsInactive() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var paciente = pacientePersistido(organizacion);
        paciente.desactivar();
        assertThat(paciente.isActivo()).isFalse();
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        pacienteService.activar(paciente.getId(), organizacionId);

        assertThat(paciente.isActivo()).isTrue();
        assertThat(paciente.getOrganizacion()).isSameAs(organizacion);
        verify(pacienteRepository).save(paciente);
    }

    @Test
    @DisplayName("activar es idempotente sobre un paciente ya activo: no lanza y lo deja activo")
    void activar_shouldRemainIdempotent_whenPacienteIsAlreadyActive() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var paciente = pacientePersistido(organizacion);
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        assertThatCode(() -> {
            pacienteService.activar(paciente.getId(), organizacion.getId());
            pacienteService.activar(paciente.getId(), organizacion.getId());
        }).doesNotThrowAnyException();

        assertThat(paciente.isActivo()).isTrue();
        verify(pacienteRepository, times(2)).save(paciente);
    }

    @Test
    @DisplayName("activar lanza ResourceNotFoundException cuando el paciente no existe")
    void activar_shouldThrowResourceNotFoundException_whenPacienteMissing() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(pacienteRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pacienteService.activar(id, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente")
                .hasMessageContaining(id.toString());
        verify(pacienteRepository, never()).save(any(Paciente.class));
    }

    @Test
    @DisplayName("activar un paciente de otro tenant no lo reactiva (404, no 403)")
    void activar_shouldNotReactivate_whenPacienteBelongsToAnotherTenant() {
        var organizacion = organizacionConId(UUID.randomUUID());
        var paciente = pacientePersistido(organizacion);
        paciente.desactivar();
        var otroTenant = UUID.randomUUID();
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));

        assertThatThrownBy(() -> pacienteService.activar(paciente.getId(), otroTenant))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Paciente");
        verify(pacienteRepository, never()).save(any(Paciente.class));
        assertThat(paciente.isActivo()).isFalse();
    }

    @Test
    @DisplayName("actualizar limpia teléfono, fecha de nacimiento, sexo, correo y notas cuando el request los envía en null")
    void actualizar_shouldClearOptionalFields_whenRequestSendsNull() {
        var organizacionId = UUID.randomUUID();
        var organizacion = organizacionConId(organizacionId);
        var paciente = pacientePersistido(organizacion);
        when(pacienteRepository.findById(paciente.getId())).thenReturn(Optional.of(paciente));
        when(pacienteRepository.save(any(Paciente.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new PacienteRequest("María López Ramírez", null, null, null, null, null);

        var respuesta = pacienteService.actualizar(paciente.getId(), organizacionId, request);

        assertThat(respuesta.nombre()).isEqualTo("María López Ramírez");
        assertThat(respuesta.telefono()).isNull();
        assertThat(respuesta.fechaNacimiento()).isNull();
        assertThat(respuesta.sexo()).isNull();
        assertThat(respuesta.email()).isNull();
        assertThat(respuesta.notas()).isNull();
        assertThat(paciente.getTelefono()).isNull();
        assertThat(paciente.getFechaNacimiento()).isNull();
        assertThat(paciente.getSexo()).isNull();
        assertThat(paciente.getEmail()).isNull();
        assertThat(paciente.getNotas()).isNull();
    }

    /**
     * Request completo y válido con los seis campos editables poblados.
     */
    private static PacienteRequest requestCompleto() {
        return new PacienteRequest("María López Ramírez", "+52 55 5555 4321",
                LocalDate.of(1985, 3, 14), "F", "maria.lopez@example.com", "Alergia a la penicilina");
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
     * Paciente transitorio con todos los campos poblables asignados.
     */
    private static Paciente pacienteTransitorio(Organizacion organizacion, String nombre) {
        return new Paciente(organizacion, nombre, "+52 55 5555 4321", LocalDate.of(1985, 3, 14), "F",
                "maria.lopez@example.com", "Alergia a la penicilina");
    }

    /**
     * Paciente con aspecto de registro persistido: id simulado con un espía que conserva
     * el comportamiento real del dominio.
     */
    private static Paciente pacientePersistido(Organizacion organizacion) {
        var paciente = spy(pacienteTransitorio(organizacion, "María López Ramírez"));
        doReturn(UUID.randomUUID()).when(paciente).getId();
        return paciente;
    }

    /**
     * Paciente persistido con nombre explícito, usado en los listados ordenados.
     */
    private static Paciente pacientePersistidoConNombre(Organizacion organizacion, String nombre) {
        var paciente = spy(pacienteTransitorio(organizacion, nombre));
        doReturn(UUID.randomUUID()).when(paciente).getId();
        return paciente;
    }
}
