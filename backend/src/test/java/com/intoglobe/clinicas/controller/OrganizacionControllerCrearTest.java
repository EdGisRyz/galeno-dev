package com.intoglobe.clinicas.controller;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.OrganizacionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas de método de {@link OrganizacionController#crear(OrganizacionRequest)}: la parte del
 * alta que hoy no es alcanzable por HTTP.
 *
 * <p><strong>Por qué existe esta clase.</strong> Desde la decisión de producto del 2026-10-29 el
 * alta de organizaciones está bloqueada en la cadena de filtros y responde {@code 403} para los
 * tres roles (ver la nota de {@code SecurityConfig}); el método del controlador y su lógica se
 * conservan para el flujo de onboarding dedicado (Sprint 2). Sin esta prueba, el armado del
 * {@code 201} y de la cabecera {@code Location} se quedaría sin ninguna cobertura hasta entonces y
 * podría romperse en silencio: {@link OrganizacionControllerTest} ya no puede ejercerlo porque
 * ninguna petición HTTP llega al método.</p>
 *
 * <p>Se invoca el método directamente, sin {@code MockMvc} y sin cadena de seguridad, con el
 * service simulado: es la forma de fijar el contrato interno —estado, {@code Location} relativa
 * construida desde el id y cuerpo— sin depender de una autorización que hoy lo bloquea. La prueba
 * HTTP complementaria ({@code OrganizacionControllerTest#crear_shouldReturn403WithTheErrorContract_andNeverReachTheService})
 * sigue fijando que el endpoint real responde {@code 403} y que el service no se invoca.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrganizacionControllerCrearTest {

    private static final UUID ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final OffsetDateTime TRIAL_HASTA = OffsetDateTime.parse("2026-12-31T23:59:59-05:00");
    private static final OffsetDateTime CREADA_EN = OffsetDateTime.parse("2026-01-15T09:30:00-05:00");

    @Mock
    private OrganizacionService organizacionService;

    @Test
    @DisplayName("crear devuelve 201, Location relativa hacia el recurso y el cuerpo de la organización")
    void crear_shouldReturn201WithRelativeLocationAndBody() {
        var request = new OrganizacionRequest("Clínica San Rafael", PlanTipo.CLINICA, "sub-rafael", TRIAL_HASTA);
        var esperada = new OrganizacionResponse(ID, "Clínica San Rafael", PlanTipo.CLINICA, "sub-rafael",
                TRIAL_HASTA, true, CREADA_EN);
        when(organizacionService.crear(request)).thenReturn(esperada);
        var controller = new OrganizacionController(organizacionService, new TenantAccessGuard());

        var respuesta = controller.crear(request);

        assertThat(respuesta.getStatusCode().value())
                .as("el alta crea el recurso")
                .isEqualTo(HttpStatus.CREATED.value());
        assertThat(respuesta.getHeaders().getLocation())
                .as("el Location apunta a la ruta plana del recurso recién creado")
                .isEqualTo(URI.create("/api/v1/organizaciones/" + ID));
        assertThat(respuesta.getHeaders().getLocation().toString())
                .as("el Location se emite relativo, para que sea correcto detrás de un proxy reverso")
                .doesNotContain("http");
        assertThat(respuesta.getBody()).isEqualTo(esperada);
        verify(organizacionService).crear(request);
    }

    @Test
    @DisplayName("crear emite un Location construido con el id que devuelve el service, no con un valor fijo")
    void crear_shouldBuildTheLocationFromTheCreatedId() {
        var otroId = UUID.randomUUID();
        var request = new OrganizacionRequest("Clínica Nueva", PlanTipo.SOLO, null, null);
        var esperada = new OrganizacionResponse(otroId, "Clínica Nueva", PlanTipo.SOLO, null, null, true, CREADA_EN);
        when(organizacionService.crear(request)).thenReturn(esperada);
        var controller = new OrganizacionController(organizacionService, new TenantAccessGuard());

        var respuesta = controller.crear(request);

        assertThat(respuesta.getHeaders().getLocation())
                .isEqualTo(URI.create("/api/v1/organizaciones/" + otroId));
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().id()).isEqualTo(otroId);
    }
}
