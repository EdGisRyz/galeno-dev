package com.intoglobe.clinicas.controller;

import java.time.LocalDate;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.dto.response.PacienteResponse;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.service.PacienteService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
 * Pruebas MVC del contrato HTTP de {@link PacienteController}.
 *
 * <p>Rebanada web pura: no hay base de datos y el service se simula con
 * {@code @MockitoBean}. Se importa explícitamente {@link SecurityConfig} para ejercer el
 * contrato a través de la cadena de filtros real, igual que los tests MVC de los módulos
 * 02, 03 y 04: cada petición se autentica con un token Bearer RS256 de verdad, firmado con
 * el par que {@link ClaveRsaDePrueba} genera en memoria y publicado a la cadena con
 * {@link DynamicPropertySource}. No se usan post-procesadores de seguridad,
 * {@code addFilters=false}, {@code permitAll} de prueba ni usuarios simulados, así que el
 * filtro JWT, la matriz de autorización, el entry point, el manejador de denegación y el
 * contrato de error siguen siendo los de producción.</p>
 *
 * <p>Los {@code 404} —organización inexistente y paciente inexistente o de otra
 * organización— los produce {@code PacienteService} y los traduce el
 * {@code GlobalExceptionHandler}; el {@code 403} de la organización del path distinta del
 * tenant del token también nace en el service, como {@link AccessDeniedException}, y el
 * traductor lo publica con el mismo cuerpo que el 403 de la cadena. Estas pruebas fijan ese
 * acuerdo extremo a extremo y por eso afirman el cuerpo {@code ErrorResponse}. El
 * acotamiento por tenant se prueba con la base en {@code PacienteServiceTest}; aquí se fija
 * la mitad que le toca al controller:
 * que propaga el {@code organizacionId} del path <em>y</em> el {@code tenantId} del claim
 * tal como llegan, sin sustituir el uno por el otro. Los {@code 400} de validación, JSON
 * mal formado y UUID de path inválido ocurren antes de llegar al service, así que ahí se
 * verifica explícitamente que el service no fue invocado.</p>
 *
 * <p>La cadena de filtros es la de producción, así que estas pruebas también fijan la
 * celda de rol de {@code SecurityConfig}: {@code ADMIN} alcanza las seis operaciones,
 * {@code MEDICO} solo las dos lecturas y {@code PACIENTE} ninguna —cae en las reglas
 * explícitas de {@code ADMIN} o en el fallback deny-by-default—. Si a la cadena le falta
 * una de esas reglas, los casos de rol fallan con {@code 403} y no con el código esperado:
 * es el síntoma exacto que dejó el módulo 04 mientras las celdas no existían.</p>
 */
@WebMvcTest(PacienteController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class PacienteControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID OTRA_ORGANIZACION_ID = UUID.fromString("7d2b1a9c-3e4f-4b6a-8c0d-1f2e3a4b5c6d");
    private static final UUID PACIENTE_ID = UUID.fromString("0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b");
    private static final UUID OTRO_PACIENTE_ID = UUID.fromString("1d0a9b2c-3e4f-4a5b-8c6d-7e8f9a0b1c2d");

    private static final String MENSAJE_ORGANIZACION_NO_ENCONTRADA =
            "No se encontró el recurso Organizacion con id: ";
    private static final String MENSAJE_PACIENTE_NO_ENCONTRADO =
            "No se encontró el recurso Paciente con id: " + PACIENTE_ID;
    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'id' tiene un formato inválido";
    private static final String MENSAJE_ORGANIZACION_ID_INVALIDO =
            "El parámetro 'organizacionId' tiene un formato inválido";

    private static final String CUERPO_PACIENTE_VALIDO = """
            {"nombre":"María López Ramírez","telefono":"+52 55 5555 4321","fechaNacimiento":"1985-03-14","sexo":"F","email":"maria.lopez@example.com","notas":"Alergia a la penicilina"}
            """;
    private static final String CUERPO_PACIENTE_ACTUALIZADO = """
            {"nombre":"María López R.","telefono":"+52 55 5555 0000","fechaNacimiento":"1985-03-14","sexo":"F","email":"maria.lopez@example.com","notas":null}
            """;

    /**
     * Token {@code ADMIN} cuyo {@code tenantId} es exactamente el {@code organizacionId}
     * de las rutas anidadas: lo que se ejerce es el contrato del endpoint y no la
     * comprobación de tenant.
     */
    private static final String TOKEN = ClaveRsaDePrueba.tokenPara("ADMIN", TENANT_ID);

    /**
     * Token {@code MEDICO} del mismo tenant. La matriz concede solo las dos lecturas a este
     * rol; las cuatro mutaciones responden {@code 403} en la cadena.
     */
    private static final String TOKEN_MEDICO = ClaveRsaDePrueba.tokenPara("MEDICO", TENANT_ID);

    /**
     * Token {@code PACIENTE} del mismo tenant: el rol existe en el schema pero no tiene
     * ninguna celda concedida en esta matriz.
     */
    private static final String TOKEN_PACIENTE = ClaveRsaDePrueba.tokenPara("PACIENTE", TENANT_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PacienteService pacienteService;

    // ------------------------------------------------------------------
    // Contrato de éxito con el rol ADMIN
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST devuelve 201, Location relativo a la ruta plana y cuerpo del paciente creado")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(pacienteService.crear(eq(TENANT_ID), eq(TENANT_ID), any(PacienteRequest.class)))
                .thenReturn(respuesta(PACIENTE_ID, "María López Ramírez", true));

        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pacientes/" + PACIENTE_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(PACIENTE_ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("María López Ramírez"))
                .andExpect(jsonPath("$.sexo").value("F"))
                .andExpect(jsonPath("$.activo").value(true));
        verify(pacienteService).crear(eq(TENANT_ID), eq(TENANT_ID), any(PacienteRequest.class));
    }

    @Test
    @DisplayName("GET /pacientes/{id} devuelve 200 con el paciente encontrado")
    void buscarPorId_shouldReturn200WithBody() throws Exception {
        when(pacienteService.buscarPorId(PACIENTE_ID, TENANT_ID))
                .thenReturn(respuesta(PACIENTE_ID, "María López Ramírez", true));

        perform(get("/api/v1/pacientes/{id}", PACIENTE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(PACIENTE_ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("María López Ramírez"))
                .andExpect(jsonPath("$.telefono").value("+52 55 5555 4321"))
                .andExpect(jsonPath("$.fechaNacimiento").value("1985-03-14"))
                .andExpect(jsonPath("$.email").value("maria.lopez@example.com"))
                .andExpect(jsonPath("$.notas").value("Alergia a la penicilina"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
        verify(pacienteService).buscarPorId(PACIENTE_ID, TENANT_ID);
    }

    @Test
    @DisplayName("GET anidado devuelve 200 con el arreglo de pacientes activos en el orden del contrato")
    void listarPorOrganizacion_shouldReturn200WithArray() throws Exception {
        when(pacienteService.listarPorOrganizacion(TENANT_ID, TENANT_ID)).thenReturn(List.of(
                respuesta(PACIENTE_ID, "Ana Beltrán", true),
                respuesta(OTRO_PACIENTE_ID, "María López Ramírez", true)));

        perform(get("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Ana Beltrán"))
                .andExpect(jsonPath("$[1].nombre").value("María López Ramírez"))
                .andExpect(jsonPath("$[*].activo",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
        verify(pacienteService).listarPorOrganizacion(TENANT_ID, TENANT_ID);
    }

    @Test
    @DisplayName("PUT /pacientes/{id} devuelve 200 con el request validado aplicado")
    void actualizar_shouldReturn200_whenRequestIsValid() throws Exception {
        when(pacienteService.actualizar(eq(PACIENTE_ID), eq(TENANT_ID), any(PacienteRequest.class)))
                .thenReturn(respuesta(PACIENTE_ID, "María López R.", true));

        perform(put("/api/v1/pacientes/{id}", PACIENTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_ACTUALIZADO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PACIENTE_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("María López R."));
        verify(pacienteService).actualizar(eq(PACIENTE_ID), eq(TENANT_ID), any(PacienteRequest.class));
    }

    @Test
    @DisplayName("DELETE /pacientes/{id} devuelve 204 sin cuerpo")
    void desactivar_shouldReturn204WithoutBody() throws Exception {
        perform(delete("/api/v1/pacientes/{id}", PACIENTE_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(pacienteService).desactivar(PACIENTE_ID, TENANT_ID);
    }

    @Test
    @DisplayName("POST /pacientes/{id}/activar devuelve 204 sin cuerpo")
    void activar_shouldReturn204WithoutBody() throws Exception {
        perform(post("/api/v1/pacientes/{id}/activar", PACIENTE_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(pacienteService).activar(PACIENTE_ID, TENANT_ID);
    }

    // ------------------------------------------------------------------
    // 404 de negocio: los produce el service y los traduce el handler
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET anidado con organización inexistente devuelve 404 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn404_whenOrganizacionIsMissing() throws Exception {
        when(pacienteService.listarPorOrganizacion(TENANT_ID, TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Organizacion", TENANT_ID));

        perform(get("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORGANIZACION_NO_ENCONTRADA + TENANT_ID))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
    }

    @Test
    @DisplayName("POST con organización inexistente devuelve 404 con ErrorResponse")
    void crear_shouldReturn404_whenOrganizacionIsMissing() throws Exception {
        when(pacienteService.crear(eq(TENANT_ID), eq(TENANT_ID), any(PacienteRequest.class)))
                .thenThrow(new ResourceNotFoundException("Organizacion", TENANT_ID));

        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORGANIZACION_NO_ENCONTRADA + TENANT_ID))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
    }

    @Test
    @DisplayName("GET /pacientes/{id} inexistente devuelve 404 con ErrorResponse")
    void buscarPorId_shouldReturn404_whenMissing() throws Exception {
        when(pacienteService.buscarPorId(PACIENTE_ID, TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Paciente", PACIENTE_ID));

        perform(get("/api/v1/pacientes/{id}", PACIENTE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_PACIENTE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/pacientes/" + PACIENTE_ID));
    }

    @Test
    @DisplayName("PUT /pacientes/{id} inexistente devuelve 404 con ErrorResponse")
    void actualizar_shouldReturn404_whenMissing() throws Exception {
        when(pacienteService.actualizar(eq(PACIENTE_ID), eq(TENANT_ID), any(PacienteRequest.class)))
                .thenThrow(new ResourceNotFoundException("Paciente", PACIENTE_ID));

        perform(put("/api/v1/pacientes/{id}", PACIENTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_ACTUALIZADO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_PACIENTE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/pacientes/" + PACIENTE_ID));
    }

    @Test
    @DisplayName("DELETE /pacientes/{id} inexistente devuelve 404 con ErrorResponse")
    void desactivar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Paciente", PACIENTE_ID))
                .when(pacienteService).desactivar(PACIENTE_ID, TENANT_ID);

        perform(delete("/api/v1/pacientes/{id}", PACIENTE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_PACIENTE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/pacientes/" + PACIENTE_ID));
    }

    @Test
    @DisplayName("POST /pacientes/{id}/activar con id inexistente devuelve 404 con ErrorResponse")
    void activar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Paciente", PACIENTE_ID))
                .when(pacienteService).activar(PACIENTE_ID, TENANT_ID);

        perform(post("/api/v1/pacientes/{id}/activar", PACIENTE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_PACIENTE_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/pacientes/" + PACIENTE_ID + "/activar"));
    }

    // ------------------------------------------------------------------
    // 403 del path ajeno: el service lo lanza y el handler lo publica
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST con el organizacionId del path distinto del tenant del token devuelve 403 y propaga ambos")
    void crear_shouldReturn403_whenPathOrganizacionIsNotTheTokenTenant() throws Exception {
        when(pacienteService.crear(eq(OTRA_ORGANIZACION_ID), eq(TENANT_ID), any(PacienteRequest.class)))
                .thenThrow(new AccessDeniedException(
                        "El organizacionId de la ruta no corresponde al tenant del token"));

        afirmar403(perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", OTRA_ORGANIZACION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_VALIDO)),
                "/api/v1/organizaciones/" + OTRA_ORGANIZACION_ID + "/pacientes");

        // El controller no sustituye el path por el claim ni al revés: entrega los dos y
        // deja que el service aplique el orden de comprobaciones que documenta.
        verify(pacienteService).crear(eq(OTRA_ORGANIZACION_ID), eq(TENANT_ID), any(PacienteRequest.class));
    }

    // ------------------------------------------------------------------
    // 400 de validación: ocurren antes del service
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST con nombre en blanco devuelve 400 (@NotBlank) sin llamar al service")
    void crear_shouldReturn400_whenNombreIsBlank() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"   ","sexo":"F"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("POST con sexo distinto de M, F u O devuelve 400 (@Pattern) sin llamar al service")
    void crear_shouldReturn400_whenSexoIsNotPermitted() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"María López Ramírez","sexo":"X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo sexo es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("POST con email de 181 caracteres devuelve 400 (@Size) sin llamar al service")
    void crear_shouldReturn400_whenEmailExceedsTheColumnLength() throws Exception {
        String emailLargo = "a".repeat(180) + "@";

        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"María López Ramírez","email":"%s"}
                                """.formatted(emailLargo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje")
                        .value("El campo email tiene una longitud fuera del rango permitido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("POST con JSON mal formado devuelve 400 sin detalles internos del parser")
    void crear_shouldReturn400_whenJsonIsMalformed() throws Exception {
        perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"María\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + TENANT_ID + "/pacientes"));
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("GET anidado con organizacionId inválido devuelve 400 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn400_whenOrganizacionIdIsInvalid() throws Exception {
        perform(get("/api/v1/organizaciones/{organizacionId}/pacientes", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORGANIZACION_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/no-es-un-uuid/pacientes"));
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("GET /pacientes/{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void buscarPorId_shouldReturn400_whenUuidIsInvalid() throws Exception {
        perform(get("/api/v1/pacientes/{id}", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/pacientes/no-es-un-uuid"));
        verifyNoInteractions(pacienteService);
    }

    // ------------------------------------------------------------------
    // Celdas de rol de la matriz: MEDICO solo lee, PACIENTE no entra
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MEDICO recibe 403 en las cuatro mutaciones sin llegar al service")
    void mutaciones_shouldReturn403_whenActorIsMedico() throws Exception {
        afirmar403(perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_VALIDO), TOKEN_MEDICO),
                "/api/v1/organizaciones/" + TENANT_ID + "/pacientes");
        afirmar403(perform(put("/api/v1/pacientes/{id}", PACIENTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_ACTUALIZADO), TOKEN_MEDICO),
                "/api/v1/pacientes/" + PACIENTE_ID);
        afirmar403(perform(delete("/api/v1/pacientes/{id}", PACIENTE_ID), TOKEN_MEDICO),
                "/api/v1/pacientes/" + PACIENTE_ID);
        afirmar403(perform(post("/api/v1/pacientes/{id}/activar", PACIENTE_ID), TOKEN_MEDICO),
                "/api/v1/pacientes/" + PACIENTE_ID + "/activar");
        verifyNoInteractions(pacienteService);
    }

    @Test
    @DisplayName("MEDICO recibe 200 en las dos lecturas y el tenant del claim llega al service")
    void lecturas_shouldReturn200_whenActorIsMedico() throws Exception {
        when(pacienteService.listarPorOrganizacion(TENANT_ID, TENANT_ID)).thenReturn(List.of(
                respuesta(PACIENTE_ID, "María López Ramírez", true)));
        when(pacienteService.buscarPorId(PACIENTE_ID, TENANT_ID))
                .thenReturn(respuesta(PACIENTE_ID, "María López Ramírez", true));

        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PACIENTE_ID.toString()));
        mockMvc.perform(get("/api/v1/pacientes/{id}", PACIENTE_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PACIENTE_ID.toString()));

        verify(pacienteService).listarPorOrganizacion(TENANT_ID, TENANT_ID);
        verify(pacienteService).buscarPorId(PACIENTE_ID, TENANT_ID);
    }

    @Test
    @DisplayName("PACIENTE recibe 403 en las seis operaciones sin llegar al service")
    void todasLasRutas_shouldReturn403_whenActorIsPaciente() throws Exception {
        afirmar403(perform(post("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_VALIDO), TOKEN_PACIENTE),
                "/api/v1/organizaciones/" + TENANT_ID + "/pacientes");
        afirmar403(perform(get("/api/v1/organizaciones/{organizacionId}/pacientes", TENANT_ID),
                        TOKEN_PACIENTE),
                "/api/v1/organizaciones/" + TENANT_ID + "/pacientes");
        afirmar403(perform(get("/api/v1/pacientes/{id}", PACIENTE_ID), TOKEN_PACIENTE),
                "/api/v1/pacientes/" + PACIENTE_ID);
        afirmar403(perform(put("/api/v1/pacientes/{id}", PACIENTE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_PACIENTE_ACTUALIZADO), TOKEN_PACIENTE),
                "/api/v1/pacientes/" + PACIENTE_ID);
        afirmar403(perform(delete("/api/v1/pacientes/{id}", PACIENTE_ID), TOKEN_PACIENTE),
                "/api/v1/pacientes/" + PACIENTE_ID);
        afirmar403(perform(post("/api/v1/pacientes/{id}/activar", PACIENTE_ID), TOKEN_PACIENTE),
                "/api/v1/pacientes/" + PACIENTE_ID + "/activar");
        verifyNoInteractions(pacienteService);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Construye la representación de salida que devolvería el service.
     *
     * @param id     identificador del paciente
     * @param nombre nombre del paciente
     * @param activo estado lógico del paciente
     * @return la respuesta con los diez campos del contrato
     */
    private static PacienteResponse respuesta(UUID id, String nombre, boolean activo) {
        return new PacienteResponse(id, TENANT_ID, nombre, "+52 55 5555 4321",
                LocalDate.parse("1985-03-14"), "F", "maria.lopez@example.com",
                "Alergia a la penicilina", activo,
                OffsetDateTime.parse("2025-01-15T10:30:00-06:00"));
    }

    /**
     * Publica la clave pública de prueba y los claims esperados para que el filtro JWT
     * acepte los tokens de esta clase, igual que en los tests MVC de los módulos 02-04.
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
     * Ejecuta la petición con la cabecera {@code Authorization} del token {@code ADMIN}.
     *
     * @param peticion petición a ejecutar
     * @return resultado de la petición para encadenar aserciones
     * @throws Exception si MockMvc no puede ejecutarla
     */
    private ResultActions perform(MockHttpServletRequestBuilder peticion) throws Exception {
        return perform(peticion, TOKEN);
    }

    /**
     * Ejecuta la petición con la cabecera {@code Authorization} del token indicado.
     *
     * @param peticion petición a ejecutar
     * @param token    token Bearer del rol que se quiere ejercer
     * @return resultado de la petición para encadenar aserciones
     * @throws Exception si MockMvc no puede ejecutarla
     */
    private ResultActions perform(MockHttpServletRequestBuilder peticion, String token) throws Exception {
        return mockMvc.perform(peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * Afirma el contrato completo del {@code 403} de la API.
     *
     * @param resultado resultado de la petición
     * @param ruta      ruta esperada en el campo {@code ruta} del cuerpo
     * @throws Exception si una aserción de MockMvc falla
     */
    private static void afirmar403(ResultActions resultado, String ruta) throws Exception {
        resultado
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(ruta));
    }
}
