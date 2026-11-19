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
import com.intoglobe.clinicas.exception.ConsultorioPrincipalConMedicoActivoException;
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

    /**
     * Token {@code ADMIN} cuyo {@code tenantId} es exactamente el {@code organizacionId} que
     * usan las rutas anidadas de estas pruebas: el path coincide con el claim y lo que se
     * ejerce es el contrato del endpoint, no la guardia de tenant.
     */
    private static final String TOKEN = ClaveRsaDePrueba.tokenPara("ADMIN", ORG_ID);
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
        when(consultorioService.buscarPorId(ID, ORG_ID))
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
        when(consultorioService.listarPorOrganizacion(ORG_ID, false)).thenReturn(List.of(
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
    @DisplayName("GET anidado con incluirInactivos=true propaga el parámetro al servicio (S8)")
    void listarPorOrganizacion_shouldPassIncluirInactivos_whenRequested() throws Exception {
        when(consultorioService.listarPorOrganizacion(ORG_ID, true)).thenReturn(List.of(
                respuesta(UUID.randomUUID(), ORG_ID, "Beta En Refacción", null, null, false)));

        perform(get("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .param("incluirInactivos", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activo").value(false));
        verify(consultorioService).listarPorOrganizacion(ORG_ID, true);
    }

    @Test
    @DisplayName("GET anidado con organización inexistente devuelve 404 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn404WithErrorResponse_whenOrganizationIsMissing() throws Exception {
        when(consultorioService.listarPorOrganizacion(ORG_ID, false))
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
        when(consultorioService.actualizar(eq(ID), eq(ORG_ID), any(ConsultorioRequest.class)))
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
        verify(consultorioService).actualizar(eq(ID), eq(ORG_ID), any(ConsultorioRequest.class));
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

        verify(consultorioService, times(2)).desactivar(ID, ORG_ID);
    }

    @Test
    @DisplayName("POST /{id}/activar devuelve 204 sin cuerpo y es idempotente al repetirse")
    void activar_shouldReturn204WithoutBody_andRemain204_whenRepeated() throws Exception {
        perform(post("/api/v1/consultorios/{id}/activar", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        perform(post("/api/v1/consultorios/{id}/activar", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(consultorioService, times(2)).activar(ID, ORG_ID);
    }

    @Test
    @DisplayName("DELETE /{id} del principal de un médico activo devuelve 409 con ErrorResponse (S6)")
    void desactivar_shouldReturn409_whenItIsThePrincipalOfAnActiveMedico() throws Exception {
        var mensaje = ConsultorioPrincipalConMedicoActivoException
                .porSerElPrincipalDeUnMedicoActivo().getMessage();
        doThrow(ConsultorioPrincipalConMedicoActivoException.porSerElPrincipalDeUnMedicoActivo())
                .when(consultorioService).desactivar(ID, ORG_ID);

        perform(delete("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.mensaje").value(mensaje))
                .andExpect(jsonPath("$.mensaje",
                        org.hamcrest.Matchers.containsString("Da de baja primero al médico")))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
    }

    @Test
    @DisplayName("GET /{id} inexistente devuelve 404 con ErrorResponse")
    void buscarPorId_shouldReturn404WithErrorResponse_whenMissing() throws Exception {
        when(consultorioService.buscarPorId(ID, ORG_ID))
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
        when(consultorioService.actualizar(eq(ID), eq(ORG_ID), any(ConsultorioRequest.class)))
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
                .when(consultorioService).desactivar(ID, ORG_ID);

        perform(delete("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
    }

    @Test
    @DisplayName("POST /{id}/activar inexistente devuelve 404 con ErrorResponse")
    void activar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Consultorio", ID))
                .when(consultorioService).activar(ID, ORG_ID);

        perform(post("/api/v1/consultorios/{id}/activar", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID + "/activar"));
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
                // El rechazo pasó de @Size a @Pattern: el formato del teléfono es una sola regla
                // compartida con médico y paciente, y el máximo de 20 vive dentro de ella.
                .andExpect(jsonPath("$.mensaje").value("El campo telefono es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con nombre numérico devuelve 400 (@Pattern) sin llamar al service")
    void crear_shouldReturn400_whenNombreHasDigits() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"454545"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con teléfono con letras devuelve 400 (@Pattern) sin llamar al service")
    void crear_shouldReturn400_whenTelefonoHasLetters() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio de prueba","telefono":"btsbtbrb4554545"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo telefono es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("PUT con nombre numérico devuelve 400 (@Valid también en el PUT)")
    void actualizar_shouldReturn400_whenNombreHasDigits() throws Exception {
        perform(put("/api/v1/consultorios/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"454545"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/consultorios/" + ID));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con dirección con números y letras devuelve 201 (la dirección es libre)")
    void crear_shouldReturn201_whenDireccionMixesDigitsAndLetters() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio de prueba", "Calle 5 de Mayo 12-B",
                        "9511111111", true));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio de prueba","direccion":"Calle 5 de Mayo 12-B","telefono":"9511111111"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direccion").value("Calle 5 de Mayo 12-B"));
        verify(consultorioService).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("POST con dirección de solo dígitos devuelve 201: la regla es deuda declarada")
    void crear_shouldReturn201_whenDireccionIsOnlyDigits() throws Exception {
        // `454545` no es una dirección, pero distinguirlo exige un servicio de direcciones y no una
        // expresión regular. El alcance lo deja fuera a propósito: el dato entra y la deuda queda
        // declarada en lugar de fingir que un patrón la resuelve. El nombre y el teléfono de la
        // misma captura sí se rechazan.
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio de prueba", "454545", null, true));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio de prueba","direccion":"454545"}
                                """))
                .andExpect(status().isCreated());
        verify(consultorioService).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("POST con los opcionales en cadena vacía devuelve 201 (@Pattern los deja pasar)")
    void crear_shouldReturn201_whenOptionalFieldsAreEmptyStrings() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio de prueba", "", "", true));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio de prueba","direccion":"","telefono":""}
                                """))
                .andExpect(status().isCreated());
        verify(consultorioService).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("POST con nombre con números y letras devuelve 201 (Consultorio 2 es un nombre real)")
    void crear_shouldReturn201_whenNombreHasDigitsAndLetters() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio 2", null, null, true));

        for (String nombre : List.of("Consultorio 2", "Sala 3", "Consultorio Central", "Alfa Salud")) {
            perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"%s"}
                                    """.formatted(nombre)))
                    .andExpect(status().isCreated());
        }
        verify(consultorioService, times(4)).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("POST con nombre de solo dígitos devuelve 400 (@Pattern: exige al menos una letra)")
    void crear_shouldReturn400_whenNombreIsOnlyDigits() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/consultorios"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con nombre sin ninguna letra devuelve 400 (@Pattern)")
    void crear_shouldReturn400_whenNombreHasNoLetters() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"---"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es inválido"));
        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("POST con teléfono entre paréntesis devuelve 201 (así se escribe un fijo)")
    void crear_shouldReturn201_whenTelefonoUsesParentheses() throws Exception {
        when(consultorioService.crear(any(UUID.class), any(ConsultorioRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio de prueba", null, "(951) 111 1111", true));

        perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio de prueba","telefono":"(951) 111 1111"}
                                """))
                .andExpect(status().isCreated());
        verify(consultorioService).crear(eq(ORG_ID), any(ConsultorioRequest.class));
    }

    @Test
    @DisplayName("POST con teléfono de solo paréntesis o de 2 caracteres devuelve 400 (@Pattern)")
    void crear_shouldReturn400_whenTelefonoHasNoDigits() throws Exception {
        // El mínimo de 7 caracteres no basta por sí solo: sin un dígito no hay teléfono.
        for (String telefono : List.of("()", "(      )", "-------")) {
            perform(post("/api/v1/organizaciones/{organizacionId}/consultorios", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"Consultorio de prueba","telefono":"%s"}
                                    """.formatted(telefono)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value(400))
                    .andExpect(jsonPath("$.mensaje").value("El campo telefono es inválido"));
        }
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

    @Test
    @DisplayName("Las rutas anidadas con el organizacionId de otro tenant responden 403 sin llamar al service")
    void rutasAnidadas_shouldReturn403_whenThePathTenantDiffersFromTheClaim() throws Exception {
        UUID organizacionAjena = UUID.randomUUID();

        afirmarAccesoDenegado(perform(get("/api/v1/organizaciones/{organizacionId}/consultorios",
                        organizacionAjena)),
                "/api/v1/organizaciones/" + organizacionAjena + "/consultorios");
        afirmarAccesoDenegado(perform(post("/api/v1/organizaciones/{organizacionId}/consultorios",
                        organizacionAjena)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Consultorio Ajeno"}
                                """)),
                "/api/v1/organizaciones/" + organizacionAjena + "/consultorios");

        verifyNoInteractions(consultorioService);
    }

    @Test
    @DisplayName("Las rutas planas por id entregan al service el tenant del claim, no el del path")
    void rutasPorId_shouldPassTheClaimTenantToTheService() throws Exception {
        when(consultorioService.buscarPorId(ID, ORG_ID))
                .thenReturn(respuesta(ID, ORG_ID, "Consultorio Norte", null, null, true));

        perform(get("/api/v1/consultorios/{id}", ID))
                .andExpect(status().isOk());

        verify(consultorioService).buscarPorId(ID, ORG_ID);
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
     * Afirma el contrato del {@code 403} que produce una discrepancia entre el tenant del
     * path y el del claim.
     *
     * @param resultado resultado de la petición
     * @param ruta      ruta esperada en el campo {@code ruta}
     * @throws Exception si una aserción de MockMvc falla
     */
    private static void afirmarAccesoDenegado(ResultActions resultado, String ruta) throws Exception {
        resultado
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(
                        com.intoglobe.clinicas.security.SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(ruta));
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
