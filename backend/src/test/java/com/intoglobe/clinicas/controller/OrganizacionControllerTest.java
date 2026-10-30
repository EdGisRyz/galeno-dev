package com.intoglobe.clinicas.controller;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.service.OrganizacionService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas MVC del contrato HTTP de {@link OrganizacionController}.
 *
 * <p>Rebanada web pura: no hay base de datos y el service se simula con
 * {@code @MockitoBean}. Se importa explícitamente {@link SecurityConfig} para ejercer el
 * contrato a través de la cadena de filtros real: desde T3 (R-SEG-03) todos los endpoints
 * exigen un token RS256 válido. Cada petición se autentica con un token Bearer de verdad,
 * firmado con el par RSA que {@link ClaveRsaDePrueba} genera en memoria y publicado a la
 * cadena con {@link DynamicPropertySource}: no se usan post-procesadores de seguridad ni
 * usuarios simulados, así que el filtro JWT, el entry point y el contrato de error siguen
 * siendo los reales.</p>
 */
@WebMvcTest(OrganizacionController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class OrganizacionControllerTest {

    private static final UUID ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final OffsetDateTime TRIAL_HASTA = OffsetDateTime.parse("2026-12-31T23:59:59-05:00");
    private static final OffsetDateTime CREADA_EN = OffsetDateTime.parse("2026-01-15T09:30:00-05:00");

    private static final String MENSAJE_NO_ENCONTRADO =
            "No se encontró el recurso Organizacion con id: " + ID;
    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'id' tiene un formato inválido";

    private static final String TOKEN = ClaveRsaDePrueba.token().compactar();
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizacionService organizacionService;

    @Test
    @DisplayName("POST devuelve 201, Location relativo versionado y cuerpo de la organización")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(organizacionService.crear(any(OrganizacionRequest.class)))
                .thenReturn(respuesta(ID, "Clínica San Rafael", PlanTipo.CLINICA, "sub-rafael", TRIAL_HASTA, true));

        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica San Rafael","plan":"CLINICA","subscriptionId":"sub-rafael","trialHasta":"2026-12-31T23:59:59-05:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/organizaciones/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Clínica San Rafael"))
                .andExpect(jsonPath("$.plan").value("CLINICA"))
                .andExpect(jsonPath("$.subscriptionId").value("sub-rafael"))
                .andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    @DisplayName("POST acepta subscriptionId opcional de longitud máxima 100")
    void crear_shouldReturn201_whenSubscriptionIdHasMaxLength() throws Exception {
        String subscriptionId = "s".repeat(100);
        when(organizacionService.crear(any(OrganizacionRequest.class)))
                .thenReturn(respuesta(ID, "Clínica Límite", PlanTipo.SOLO, subscriptionId, null, true));

        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Límite","plan":"SOLO","subscriptionId":"%s"}
                                """.formatted(subscriptionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscriptionId").value(subscriptionId));
    }

    @Test
    @DisplayName("POST deserializa el request y entrega los opcionales nulos cuando se omiten")
    void crear_shouldBindRequestWithNullOptionals_whenTheyAreOmitted() throws Exception {
        when(organizacionService.crear(any(OrganizacionRequest.class)))
                .thenReturn(respuesta(ID, "Consultorio Solo", PlanTipo.SOLO, null, null, true));
        var captor = org.mockito.ArgumentCaptor.forClass(OrganizacionRequest.class);

        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Solo","plan":"SOLO"}
                                """))
                .andExpect(status().isCreated());

        verify(organizacionService).crear(captor.capture());
        var enviado = captor.getValue();
        assertThat(enviado.nombre()).isEqualTo("Consultorio Solo");
        assertThat(enviado.plan()).isEqualTo(PlanTipo.SOLO);
        assertThat(enviado.subscriptionId()).isNull();
        assertThat(enviado.trialHasta()).isNull();
    }

    @Test
    @DisplayName("Las rutas solo existen bajo /api/v1/organizaciones")
    void rutas_shouldExposeOnlyTheVersionedV1Path() throws Exception {
        when(organizacionService.listarActivas()).thenReturn(java.util.List.of());

        perform(get("/api/organizaciones"))
                .andExpect(status().isNotFound());

        perform(get("/api/v1/organizaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /{id} devuelve 200 con la organización encontrada")
    void buscarPorId_shouldReturn200WithBody() throws Exception {
        when(organizacionService.buscarPorId(ID))
                .thenReturn(respuesta(ID, "Clínica Norte", PlanTipo.ENTERPRISE, "sub-norte", TRIAL_HASTA, true));

        perform(get("/api/v1/organizaciones/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Clínica Norte"))
                .andExpect(jsonPath("$.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("GET devuelve 200 con el arreglo de organizaciones activas")
    void listarActivas_shouldReturn200WithArray() throws Exception {
        when(organizacionService.listarActivas()).thenReturn(java.util.List.of(
                respuesta(UUID.randomUUID(), "Alfa Salud", PlanTipo.SOLO, null, null, true),
                respuesta(UUID.randomUUID(), "Beta Médica", PlanTipo.CLINICA, "sub-beta", TRIAL_HASTA, true)));

        perform(get("/api/v1/organizaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Alfa Salud"))
                .andExpect(jsonPath("$[1].nombre").value("Beta Médica"))
                .andExpect(jsonPath("$[*].activo", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    @DisplayName("PUT /{id} devuelve 200 con el request validado aplicado")
    void actualizar_shouldReturn200_whenRequestIsValid() throws Exception {
        when(organizacionService.actualizar(any(UUID.class), any(OrganizacionRequest.class)))
                .thenReturn(respuesta(ID, "Clínica Renombrada", PlanTipo.ENTERPRISE, null, null, true));

        perform(put("/api/v1/organizaciones/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Renombrada","plan":"ENTERPRISE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Clínica Renombrada"))
                .andExpect(jsonPath("$.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.subscriptionId").doesNotExist())
                .andExpect(jsonPath("$.id").value(ID.toString()));
        verify(organizacionService).actualizar(any(UUID.class), any(OrganizacionRequest.class));
    }

    @Test
    @DisplayName("DELETE /{id} devuelve 204 sin cuerpo y es idempotente al repetirse")
    void desactivar_shouldReturn204WithoutBody_andRemain204_whenRepeated() throws Exception {
        perform(delete("/api/v1/organizaciones/{id}", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        when(organizacionService.buscarPorId(ID))
                .thenReturn(respuesta(ID, "Clínica Baja", PlanTipo.SOLO, null, null, false));

        perform(delete("/api/v1/organizaciones/{id}", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(organizacionService, times(2)).desactivar(ID);
    }

    @Test
    @DisplayName("GET /{id} inexistente devuelve 404 con ErrorResponse completo")
    void buscarPorId_shouldReturn404WithErrorResponse_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Organizacion", ID))
                .when(organizacionService).buscarPorId(ID);

        perform(get("/api/v1/organizaciones/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ID))
                .andExpect(jsonPath("$.marcaTemporal").exists());
    }

    @Test
    @DisplayName("PUT /{id} inexistente devuelve 404 con ErrorResponse")
    void actualizar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Organizacion", ID))
                .when(organizacionService).actualizar(any(UUID.class), any(OrganizacionRequest.class));

        perform(put("/api/v1/organizaciones/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Fantasma","plan":"SOLO"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ID));
    }

    @Test
    @DisplayName("DELETE /{id} inexistente devuelve 404 con ErrorResponse")
    void desactivar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Organizacion", ID))
                .when(organizacionService).desactivar(ID);

        perform(delete("/api/v1/organizaciones/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ID));
    }

    @Test
    @DisplayName("POST con nombre en blanco devuelve 400 con mensaje de negocio en español")
    void crear_shouldReturn400_whenNombreIsBlank() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"   ","plan":"CLINICA"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
        verifyNoInteractions(organizacionService);
    }

    @Test
    @DisplayName("POST con nombre de más de 120 caracteres devuelve 400")
    void crear_shouldReturn400_whenNombreIsTooLong() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"%s","plan":"SOLO"}
                                """.formatted("n".repeat(121))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre tiene una longitud fuera del rango permitido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("POST con subscriptionId de más de 100 caracteres devuelve 400")
    void crear_shouldReturn400_whenSubscriptionIdIsTooLong() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Límite","plan":"SOLO","subscriptionId":"%s"}
                                """.formatted("s".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo subscriptionId tiene una longitud fuera del rango permitido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("POST con plan nulo devuelve 400")
    void crear_shouldReturn400_whenPlanIsNull() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Sin Plan"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo plan es obligatorio"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("POST con JSON mal formado devuelve 400 sin detalles internos del parser")
    void crear_shouldReturn400_whenJsonIsMalformed() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Clínica Rota\",\"plan\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("POST con enum de plan inválido devuelve 400 sin detalles internos")
    void crear_shouldReturn400_whenPlanEnumIsInvalid() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Premium","plan":"PREMIUM"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("POST con fecha inválida devuelve 400 sin detalles internos")
    void crear_shouldReturn400_whenTrialHastaIsInvalid() throws Exception {
        perform(post("/api/v1/organizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Fecha","plan":"CLINICA","trialHasta":"31-12-2026"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("GET /{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void buscarPorId_shouldReturn400_whenUuidIsInvalid() throws Exception {
        perform(get("/api/v1/organizaciones/{id}", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/no-es-un-uuid"));
        verifyNoInteractions(organizacionService);
    }

    @Test
    @DisplayName("PUT /{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void actualizar_shouldReturn400_whenUuidIsInvalid() throws Exception {
        perform(put("/api/v1/organizaciones/{id}", "no-es-un-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Clínica Inválida","plan":"SOLO"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/no-es-un-uuid"));
    }

    @Test
    @DisplayName("Anotaciones: ruta versionada, listado como arreglo y 204 sin contenido Swagger")
    void anotaciones_shouldDeclareVersionedRouteArrayListAndEmpty204() throws Exception {
        RequestMapping rutaClase = OrganizacionController.class.getAnnotation(RequestMapping.class);
        assertThat(rutaClase).isNotNull();
        assertThat(rutaClase.value()).containsExactly("/api/v1/organizaciones");

        Method listado = OrganizacionController.class.getMethod("listarActivas");
        ApiResponse okListado = buscarApiResponse(listado, "200");
        assertThat(okListado.content()).hasSize(1);
        assertThat(okListado.content()[0].array().schema().implementation())
                .isEqualTo(OrganizacionResponse.class);

        Method baja = OrganizacionController.class.getMethod("desactivar", UUID.class);
        ApiResponse sinCuerpo = buscarApiResponse(baja, "204");
        assertThat(sinCuerpo.content()).hasSize(1);
        assertThat(sinCuerpo.content()[0].schema().implementation()).isEqualTo(Void.class);
        assertThat(sinCuerpo.content()[0].array().schema().implementation()).isEqualTo(Void.class);

        Method alta = OrganizacionController.class.getMethod("crear", OrganizacionRequest.class);
        ApiResponse creado = buscarApiResponse(alta, "201");
        assertThat(creado.content()[0].schema().implementation()).isEqualTo(OrganizacionResponse.class);
        assertThat(buscarApiResponse(alta, "400").content()[0].schema().implementation())
                .isEqualTo(com.intoglobe.clinicas.dto.response.ErrorResponse.class);
    }

    private static ApiResponse buscarApiResponse(Method metodo, String codigo) {
        return Arrays.stream(metodo.getAnnotationsByType(ApiResponse.class))
                .filter(respuesta -> respuesta.responseCode().equals(codigo))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Falta @ApiResponse " + codigo + " en " + metodo.getName()));
    }

    private static OrganizacionResponse respuesta(UUID id, String nombre, PlanTipo plan,
            String subscriptionId, OffsetDateTime trialHasta, boolean activo) {
        return new OrganizacionResponse(id, nombre, plan, subscriptionId, trialHasta, activo, CREADA_EN);
    }

    /**
     * Publica la clave pública de prueba y los claims esperados para que el filtro JWT
     * acepte el token de {@link #TOKEN}. Funciona en rebanadas web porque
     * {@code @DynamicPropertySource} es parte del framework de pruebas de Spring, no del
     * arranque completo.
     *
     * @param registro registro de propiedades dinámicas del contexto de prueba
     */
    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    /**
     * Ejecuta la petición con la cabecera {@code Authorization} del token de prueba.
     *
     * @param peticion petición a ejecutar
     * @return resultado de la petición para encadenar aserciones
     * @throws Exception si MockMvc no puede ejecutarla
     */
    private ResultActions perform(MockHttpServletRequestBuilder peticion) throws Exception {
        return mockMvc.perform(peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
    }
}
