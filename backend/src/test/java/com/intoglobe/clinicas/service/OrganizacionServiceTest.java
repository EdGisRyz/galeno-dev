package com.intoglobe.clinicas.service;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.OrganizacionMapper;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link OrganizacionService} y de {@link OrganizacionMapper}.
 *
 * <p>No levantan contexto de Spring: el repositorio se simula con Mockito. La
 * identidad ({@code id}) y la marca {@code createdAt} las genera Hibernate, por lo
 * que las fixtures de entidad persistida las asignan por reflexión solo en el test.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrganizacionServiceTest {

    @Mock
    private OrganizacionRepository organizacionRepository;

    @InjectMocks
    private OrganizacionService organizacionService;

    @Test
    @DisplayName("crear guarda la organización nueva y devuelve su representación")
    void crear_shouldSaveNewOrganizationAndReturnResponse() {
        var trialHasta = OffsetDateTime.parse("2026-12-31T23:59:59-05:00");
        var request = new OrganizacionRequest("Clínica San Rafael", PlanTipo.CLINICA, "sub-rafael", trialHasta);
        when(organizacionRepository.save(any(Organizacion.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = organizacionService.crear(request);

        assertThat(respuesta.nombre()).isEqualTo("Clínica San Rafael");
        assertThat(respuesta.plan()).isEqualTo(PlanTipo.CLINICA);
        assertThat(respuesta.subscriptionId()).isEqualTo("sub-rafael");
        assertThat(respuesta.trialHasta()).isEqualTo(trialHasta);
        assertThat(respuesta.activo()).isTrue();
        verify(organizacionRepository).save(any(Organizacion.class));
    }

    @Test
    @DisplayName("crear conserva null los opcionales y la organización activa")
    void crear_shouldKeepOptionalFieldsNull_whenRequestOmitsThem() {
        var request = new OrganizacionRequest("Consultorio Solo", PlanTipo.SOLO, null, null);
        when(organizacionRepository.save(any(Organizacion.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        var respuesta = organizacionService.crear(request);

        assertThat(respuesta.subscriptionId()).isNull();
        assertThat(respuesta.trialHasta()).isNull();
        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    @DisplayName("buscarPorId devuelve la organización mapeada cuando existe")
    void buscarPorId_shouldReturnMappedResponse_whenOrganizationExists() {
        var organizacion = organizacionPersistida("Clínica Norte", PlanTipo.ENTERPRISE, "sub-norte",
                OffsetDateTime.parse("2026-06-30T12:00:00Z"), true);
        when(organizacionRepository.findById(organizacion.getId())).thenReturn(Optional.of(organizacion));

        var respuesta = organizacionService.buscarPorId(organizacion.getId());

        assertThat(respuesta.id()).isEqualTo(organizacion.getId());
        assertThat(respuesta.nombre()).isEqualTo("Clínica Norte");
        assertThat(respuesta.plan()).isEqualTo(PlanTipo.ENTERPRISE);
        assertThat(respuesta.subscriptionId()).isEqualTo("sub-norte");
        assertThat(respuesta.trialHasta()).isEqualTo(organizacion.getTrialHasta());
        assertThat(respuesta.activo()).isTrue();
        assertThat(respuesta.createdAt()).isEqualTo(organizacion.getCreatedAt());
    }

    @Test
    @DisplayName("buscarPorId lanza ResourceNotFoundException cuando no existe")
    void buscarPorId_shouldThrowResourceNotFoundException_whenOrganizationMissing() {
        var id = UUID.randomUUID();
        when(organizacionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizacionService.buscarPorId(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("listarActivas mapea todas las organizaciones activas")
    void listarActivas_shouldMapAllActiveOrganizations() {
        var primera = organizacionPersistida("Alfa Salud", PlanTipo.SOLO, null, null, true);
        var segunda = organizacionPersistida("Beta Médica", PlanTipo.CLINICA, "sub-beta",
                OffsetDateTime.parse("2026-02-01T08:00:00Z"), true);
        when(organizacionRepository.listarActivas()).thenReturn(List.of(primera, segunda));

        var respuestas = organizacionService.listarActivas();

        assertThat(respuestas)
                .extracting(OrganizacionResponse::nombre)
                .containsExactly("Alfa Salud", "Beta Médica");
        assertThat(respuestas).allMatch(OrganizacionResponse::activo);
    }

    @Test
    @DisplayName("actualizar reemplaza los cuatro campos editables")
    void actualizar_shouldReplaceEditableFields() {
        var organizacion = organizacionPersistida("Clínica Original", PlanTipo.SOLO, "sub-vieja",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), true);
        when(organizacionRepository.findById(organizacion.getId())).thenReturn(Optional.of(organizacion));
        when(organizacionRepository.save(any(Organizacion.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var trialHasta = OffsetDateTime.parse("2026-09-30T18:00:00Z");
        var request = new OrganizacionRequest("Clínica Renombrada", PlanTipo.ENTERPRISE, "sub-nueva", trialHasta);

        var respuesta = organizacionService.actualizar(organizacion.getId(), request);

        assertThat(respuesta.nombre()).isEqualTo("Clínica Renombrada");
        assertThat(respuesta.plan()).isEqualTo(PlanTipo.ENTERPRISE);
        assertThat(respuesta.subscriptionId()).isEqualTo("sub-nueva");
        assertThat(respuesta.trialHasta()).isEqualTo(trialHasta);
        assertThat(respuesta.id()).isEqualTo(organizacion.getId());
        assertThat(respuesta.createdAt()).isEqualTo(organizacion.getCreatedAt());
    }

    @Test
    @DisplayName("actualizar limpia opcionales y nunca reactiva una organización inactiva")
    void actualizar_shouldClearOptionalsAndNeverReactivate_whenOrganizationIsInactive() {
        var organizacion = organizacionPersistida("Clínica Baja", PlanTipo.SOLO, "sub-original",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), false);
        when(organizacionRepository.findById(organizacion.getId())).thenReturn(Optional.of(organizacion));
        when(organizacionRepository.save(any(Organizacion.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));
        var request = new OrganizacionRequest("Clínica Baja", PlanTipo.SOLO, null, null);

        var respuesta = organizacionService.actualizar(organizacion.getId(), request);

        assertThat(respuesta.subscriptionId()).isNull();
        assertThat(respuesta.trialHasta()).isNull();
        assertThat(respuesta.activo()).isFalse();
    }

    @Test
    @DisplayName("actualizar lanza ResourceNotFoundException cuando no existe")
    void actualizar_shouldThrowResourceNotFoundException_whenOrganizationMissing() {
        var id = UUID.randomUUID();
        when(organizacionRepository.findById(id)).thenReturn(Optional.empty());
        var request = new OrganizacionRequest("Clínica Fantasma", PlanTipo.SOLO, null, null);

        assertThatThrownBy(() -> organizacionService.actualizar(id, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("desactivar es idempotente: dos llamadas dejan la organización inactiva")
    void desactivar_shouldDeactivateAndRemainIdempotent_whenCalledTwice() {
        var organizacion = organizacionPersistida("Clínica Baja", PlanTipo.CLINICA, null, null, true);
        when(organizacionRepository.findById(organizacion.getId())).thenReturn(Optional.of(organizacion));
        when(organizacionRepository.save(any(Organizacion.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        organizacionService.desactivar(organizacion.getId());
        organizacionService.desactivar(organizacion.getId());

        assertThat(organizacion.isActivo()).isFalse();
        verify(organizacionRepository, times(2)).findById(organizacion.getId());
        verify(organizacionRepository, times(2)).save(organizacion);
    }

    @Test
    @DisplayName("desactivar lanza ResourceNotFoundException cuando no existe")
    void desactivar_shouldThrowResourceNotFoundException_whenOrganizationMissing() {
        var id = UUID.randomUUID();
        when(organizacionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizacionService.desactivar(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organizacion")
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("actualizarEntidad preserva id, createdAt y activo")
    void actualizarEntidad_shouldPreserveIdentityTimestampAndActiveFlag() {
        var organizacion = organizacionPersistida("Clínica Estable", PlanTipo.SOLO, "sub-estable",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), false);
        var id = organizacion.getId();
        var createdAt = organizacion.getCreatedAt();
        var request = new OrganizacionRequest("Clínica Estable 2", PlanTipo.ENTERPRISE, null, null);

        OrganizacionMapper.actualizarEntidad(organizacion, request);

        assertThat(organizacion.getId()).isEqualTo(id);
        assertThat(organizacion.getCreatedAt()).isEqualTo(createdAt);
        assertThat(organizacion.isActivo()).isFalse();
        assertThat(organizacion.getNombre()).isEqualTo("Clínica Estable 2");
        assertThat(organizacion.getPlan()).isEqualTo(PlanTipo.ENTERPRISE);
        assertThat(organizacion.getSubscriptionId()).isNull();
        assertThat(organizacion.getTrialHasta()).isNull();
    }

    @Test
    @DisplayName("aEntidad construye una organización activa con los campos editables")
    void aEntidad_shouldBuildActiveOrganizationWithEditableFields() {
        var trialHasta = OffsetDateTime.parse("2026-03-15T10:15:30-05:00");
        var request = new OrganizacionRequest("Clínica Nueva", PlanTipo.CLINICA, "sub-nueva", trialHasta);

        var organizacion = OrganizacionMapper.aEntidad(request);

        assertThat(organizacion.getId()).isNull();
        assertThat(organizacion.getCreatedAt()).isNull();
        assertThat(organizacion.isActivo()).isTrue();
        assertThat(organizacion.getNombre()).isEqualTo("Clínica Nueva");
        assertThat(organizacion.getPlan()).isEqualTo(PlanTipo.CLINICA);
        assertThat(organizacion.getSubscriptionId()).isEqualTo("sub-nueva");
        assertThat(organizacion.getTrialHasta()).isEqualTo(trialHasta);
    }

    @Test
    @DisplayName("ErrorResponse expone los cinco componentes del contrato de error")
    void errorResponse_shouldExposeAllComponents() {
        var marcaTemporal = OffsetDateTime.parse("2026-01-15T09:30:00-05:00");

        var error = new ErrorResponse(marcaTemporal, 404, "Not Found",
                "No se encontró el recurso Organizacion", "/api/v1/organizaciones/1");

        assertThat(error.marcaTemporal()).isEqualTo(marcaTemporal);
        assertThat(error.codigo()).isEqualTo(404);
        assertThat(error.error()).isEqualTo("Not Found");
        assertThat(error.mensaje()).isEqualTo("No se encontró el recurso Organizacion");
        assertThat(error.ruta()).isEqualTo("/api/v1/organizaciones/1");
    }

    /**
     * Construye una entidad con identidad y marca temporal simuladas, como si
     * Hibernate ya la hubiera persistido. Solo el test usa reflexión aquí.
     */
    private static Organizacion organizacionPersistida(String nombre, PlanTipo plan, String subscriptionId,
            OffsetDateTime trialHasta, boolean activo) {
        var organizacion = new Organizacion(nombre, plan, subscriptionId);
        organizacion.actualizarDatos(nombre, plan, subscriptionId, trialHasta);
        if (!activo) {
            organizacion.desactivar();
        }
        asignarCampo(organizacion, "id", UUID.randomUUID());
        asignarCampo(organizacion, "createdAt", OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return organizacion;
    }

    private static void asignarCampo(Organizacion organizacion, String campo, Object valor) {
        try {
            Field field = Organizacion.class.getDeclaredField(campo);
            field.setAccessible(true);
            field.set(organizacion, valor);
        } catch (ReflectiveOperationException excepcion) {
            throw new IllegalStateException("No se pudo asignar el campo de prueba " + campo, excepcion);
        }
    }
}
