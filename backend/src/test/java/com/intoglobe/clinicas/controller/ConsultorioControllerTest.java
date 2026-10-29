package com.intoglobe.clinicas.controller;

import java.time.OffsetDateTime;
import java.util.List;
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

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.service.ConsultorioService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Pruebas MVC del contrato HTTP de {@link ConsultorioController}.
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
@WebMvcTest(ConsultorioController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class ConsultorioControllerTest {

    private static final UUID ID = UUID.fromString("7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f");
    private static final UUID ORG_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final OffsetDateTime CREADO_EN = OffsetDateTime.parse("2026-02-10T08:45:00-06:00");

    private static final String MENSAJE_ORG_NO_ENCONTRADA =
            "No se encontró el recurso Organizacion con id: " + ORG_ID;
    private static final String MENSAJE_CONSULTORIO_NO_ENCONTRADO =
            "No se encontró el recurso Consultorio con id: " + ID;
    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'id' tiene un formato inválido";
    private static final String MENSAJE_ORG_ID_INVALIDO =
            "El parámetro 'organizacionId' tiene un formato inválido";

    private static final String TOKEN = ClaveRsaDePrueba.token().compactar();
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsultorioService consultorioService;

    @Test
    @DisplayName("POST devuelve 201, Location relativo a la ruta plana y cuerpo del consultorio")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio Centro", "Av. Insurgentes Sur 1234",
                        "+52 55 5555 1234", true));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Centro","direccion":"Av. Insurgentes Sur 1234","telefono":"+52 55 5555 1234"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/consultorios/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Consultorio Centro"))
                .andExpect(jsonPath("$.activo").value(true));
        verify(consultorioService).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("GET /{id} devuelve 200 con el consultorio encontrado")
    void buscarPorId_shouldReturn200WithBody() throws Exception {
        when(consultorioService.buscarPorId(ID))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio Norte", null, null, true));

        perform(get("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Consultorio Norte"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("GET anidado devuelve 200 con el arreglo de consultorios activos")
    void listarPorOrganizacion_shouldReturn200WithArray() throws Exception {
        when(consultorioService.listarPorOrganizacion(ORG_ID)).thenReturn(List.of(
                respuesta(UUID.randomUUID(), ORG_ID, "Alfa Salud", null, null, true),
                respuesta(UUID.randomUUID(), ORG_ID, "Beta Médica", null, null, true)));

        perform(get("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Alfa Salud"))
                .andExpect(jsonPath("$[1].nombre").value("Beta Médica"))
                .andExpect(jsonPath("$[*].organizacionId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(ORG_ID.toString()))));
    }

    @Test
    @DisplayName("GET anidado con organización inexistente devuelve 404 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn404WithErrorResponse_whenOrganizationIsMissing() throws Exception {
        when(consultorioService.listarPorOrganizacion(ORG_ID))
                .thenThrow(new ResourceNotFoundException("Organizacion", ORG_ID));

        perform(get("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_NO_ENCONTRADA))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
    }

    @Test
    @DisplayName("POST con organización inexistente devuelve 404 con ErrorResponse")
    void crear_shouldReturn404WithErrorResponse_whenOrganizationIsMissing() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenThrow(new ResourceNotFoundException("Organizacion", ORG_ID));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Fantasma"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_NO_ENCONTRADA))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
    }

    @Test
    @DisplayName("PUT /{id} devuelve 200 con el request validado aplicado")
    void actualizar_shouldReturn200_whenRequestIsValid() throws Exception {
        when(consultorioService.actualizar(eq(ID), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio Renombrado", "Calle Nueva 5", null, true));

        perform(put("/api/v1/consultorios/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Renombrado","direccion":"Calle Nueva 5"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Consultorio Renombrado"))
                .andExpect(jsonPath("$.direccion").value("Calle Nueva 5"));
        verify(consultorioService).actualizar(eq(ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("DELETE /{id} devuelve 204 sin cuerpo y es idempotente al repetirse")
    void desactivar_shouldReturn204WithoutBody_andRemain204_whenRepeated() throws Exception {
        perform(delete("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        perform(delete("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(consultorioService, times(2)).desactivar(ID);
    }

    @Test
    @DisplayName("GET /{id} inexistente devuelve 404 con ErrorResponse")
    void buscarPorId_shouldReturn404WithErrorResponse_whenMissing() throws Exception {
        when(consultorioService.buscarPorId(ID))
                .thenThrow(new ResourceNotFoundException("Consultorio", ID));

        perform(get("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
    }

    @Test
    @DisplayName("PUT /{id} inexistente devuelve 404 con ErrorResponse")
    void actualizar_shouldReturn404_whenMissing() throws Exception {
        when(consultorioService.actualizar(eq(ID), any(ConsultorioRequest.class)))
                .thenThrow(new ResourceNotFoundException("Consultorio", ID));

        perform(put("/api/v1/consultorios/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Renombrado"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
    }

    @Test
    @DisplayName("DELETE /{id} inexistente devuelve 404 con ErrorResponse")
    void desactivar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Consultorio", ID))
                .when(consultorioService).desactivar(ID);

        perform(delete("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
    }

    @Test
    @DisplayName("POST con nombre en blanco devuelve 400 con mensaje de negocio en español")
    void crear_shouldReturn400_whenNombreIsBlank() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con teléfono de más de 20 caracteres devuelve 400")
    void crear_shouldReturn400_whenTelefonoIsTooLong() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Límite","telefono":"%s"}
                                """.formatted("9".repeat(21))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo telefono tiene una longitud fuera del rango permitido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con JSON mal formado devuelve 400 sin detalles internos del parser")
    void crear_shouldReturn400_whenJsonIsMalformed() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Consultorio Roto\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("GET /{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void buscarPorId_shouldReturn400_whenUuidIsInvalid() throws Exception {
        perform(get("/api/v1/consultorios/{id}", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/no-es-un-uuid"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("GET anidado con organizacionId inválido devuelve 400 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn400_whenOrganizacionIdIsInvalid() throws Exception {
        perform(get("/api/v1/organizaciones/{organizacionId}/consultorios", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/no-es-un-uuid/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    private static ConsultorioResponse respuesta(UUID id, UUID organizacionId, String nombre,
            String direccion, String telefono, boolean activo) {
        return new ConsultorioResponse(id, organizacionId, nombre, direccion, telefono, activo, CREADO_EN);
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
