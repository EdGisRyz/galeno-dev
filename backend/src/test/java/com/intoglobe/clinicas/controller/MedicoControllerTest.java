package com.intoglobe.clinicas.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.exception.CambioDeConsultorioNoPermitidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.service.MedicoService;

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
 * Pruebas MVC del contrato HTTP de {@link MedicoController}.
 *
 * <p>Rebanada web pura: no hay base de datos y el service se simula con
 * {@code @MockitoBean}. Se importa explícitamente {@link SecurityConfig} para
 * comprobar que la configuración abierta de la fase 0 deja pasar las peticiones sin
 * desactivar filtros ni omitir CSRF, igual que en los módulos 00 y 02. La forma de la
 * ruta es parte del contrato: colección anidada bajo la organización e ítem plano.</p>
 *
 * <p>Los errores 404 y el 400 de consultorio inmutable los produce el service y los
 * traduce el {@code GlobalExceptionHandler}: estos casos fijan ese acuerdo
 * extremo a extremo desde el punto de vista HTTP, y por eso afirman el cuerpo
 * {@code ErrorResponse}. Los 400 de validación, JSON mal formado y UUID de path
 * inválido ocurren antes de llegar al service, de modo que ahí se verifica
 * explícitamente que el service no fue invocado.</p>
 */
@WebMvcTest(MedicoController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class MedicoControllerTest {

    private static final UUID ID = UUID.fromString("b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID ORG_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID CONSULTORIO_ID = UUID.fromString("7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f");
    private static final UUID OTRO_CONSULTORIO_ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    private static final String MENSAJE_ORG_NO_ENCONTRADA =
            "No se encontró el recurso Organizacion con id: " + ORG_ID;
    private static final String MENSAJE_CONSULTORIO_NO_ENCONTRADO =
            "No se encontró el recurso Consultorio con id: " + CONSULTORIO_ID;
    private static final String MENSAJE_MEDICO_NO_ENCONTRADO =
            "No se encontró el recurso Medico con id: " + ID;
    private static final String MENSAJE_CAMBIO_DE_CONSULTORIO =
            "No se permite cambiar el consultorio del médico: el consultorio actual es "
                    + CONSULTORIO_ID + " y el solicitado es " + OTRO_CONSULTORIO_ID
                    + ". El consultorio de un médico no se modifica mediante la actualización";
    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'id' tiene un formato inválido";
    private static final String MENSAJE_ORG_ID_INVALIDO =
            "El parámetro 'organizacionId' tiene un formato inválido";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MedicoService medicoService;

    @Test
    @DisplayName("POST devuelve 201, Location relativo a la ruta plana y cuerpo del médico")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(medicoService.crear(any(UUID.class), any(MedicoRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, CONSULTORIO_ID, "Dra. Ana Torres", "Cardiología",
                        "12345678", "+52 55 5555 9876", "500.00", true));

        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres","especialidad":"Cardiología","cedula":"12345678","telefono":"+52 55 5555 9876","tarifaBase":"500.00"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/medicos/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.consultorioId").value(CONSULTORIO_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Dra. Ana Torres"))
                .andExpect(jsonPath("$.tarifaBase").value(500.00))
                .andExpect(jsonPath("$.activo").value(true));
        verify(medicoService).crear(eq(ORG_ID), any(MedicoRequest.class));
    }

    @Test
    @DisplayName("GET /{id} devuelve 200 con el médico encontrado")
    void buscarPorId_shouldReturn200WithBody() throws Exception {
        when(medicoService.buscarPorId(ID))
                .thenReturn(respuesta(ID, ORG_ID, CONSULTORIO_ID, "Dr. Luis Prado", "Dermatología",
                        null, null, null, true));

        mockMvc.perform(get("/api/v1/medicos/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.consultorioId").value(CONSULTORIO_ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Dr. Luis Prado"))
                .andExpect(jsonPath("$.especialidad").value("Dermatología"))
                .andExpect(jsonPath("$.activo").value(true));
        verify(medicoService).buscarPorId(ID);
    }

    @Test
    @DisplayName("GET anidado devuelve 200 con el arreglo de médicos activos")
    void listarPorOrganizacion_shouldReturn200WithArray() throws Exception {
        when(medicoService.listarPorOrganizacion(ORG_ID)).thenReturn(List.of(
                respuesta(UUID.randomUUID(), ORG_ID, CONSULTORIO_ID, "Dra. Ana Torres", null,
                        null, null, "500.00", true),
                respuesta(UUID.randomUUID(), ORG_ID, CONSULTORIO_ID, "Dr. Bruno Salas", null,
                        null, null, null, true)));

        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nombre").value("Dra. Ana Torres"))
                .andExpect(jsonPath("$[1].nombre").value("Dr. Bruno Salas"))
                .andExpect(jsonPath("$[*].organizacionId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(ORG_ID.toString()))));
        verify(medicoService).listarPorOrganizacion(ORG_ID);
    }

    @Test
    @DisplayName("GET anidado con organización inexistente devuelve 404 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn404WithErrorResponse_whenOrganizationIsMissing() throws Exception {
        when(medicoService.listarPorOrganizacion(ORG_ID))
                .thenThrow(new ResourceNotFoundException("Organizacion", ORG_ID));

        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_NO_ENCONTRADA))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
    }

    @Test
    @DisplayName("POST con organización inexistente devuelve 404 con ErrorResponse")
    void crear_shouldReturn404WithErrorResponse_whenOrganizationIsMissing() throws Exception {
        when(medicoService.crear(any(UUID.class), any(MedicoRequest.class)))
                .thenThrow(new ResourceNotFoundException("Organizacion", ORG_ID));

        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_NO_ENCONTRADA))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
    }

    @Test
    @DisplayName("POST con consultorio inexistente devuelve 404 con ErrorResponse")
    void crear_shouldReturn404WithErrorResponse_whenConsultorioIsMissing() throws Exception {
        when(medicoService.crear(any(UUID.class), any(MedicoRequest.class)))
                .thenThrow(new ResourceNotFoundException("Consultorio", CONSULTORIO_ID));

        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
    }

    @Test
    @DisplayName("PUT /{id} devuelve 200 con el request validado aplicado")
    void actualizar_shouldReturn200_whenRequestIsValid() throws Exception {
        when(medicoService.actualizar(eq(ID), any(MedicoRequest.class)))
                .thenReturn(respuesta(ID, ORG_ID, CONSULTORIO_ID, "Dra. Ana Torres Ruiz", "Cardiología",
                        "12345678", null, "650.50", true));

        mockMvc.perform(put("/api/v1/medicos/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres Ruiz","especialidad":"Cardiología","tarifaBase":"650.50"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.nombre").value("Dra. Ana Torres Ruiz"))
                .andExpect(jsonPath("$.tarifaBase").value(650.50))
                .andExpect(jsonPath("$.activo").value(true));
        verify(medicoService).actualizar(eq(ID), any(MedicoRequest.class));
    }

    @Test
    @DisplayName("PUT con consultorio distinto del actual devuelve 400 con ErrorResponse (R13)")
    void actualizar_shouldReturn400WithErrorResponse_whenConsultorioDiffers() throws Exception {
        when(medicoService.actualizar(eq(ID), any(MedicoRequest.class)))
                .thenThrow(new CambioDeConsultorioNoPermitidoException(CONSULTORIO_ID, OTRO_CONSULTORIO_ID));

        mockMvc.perform(put("/api/v1/medicos/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres"}
                                """.formatted(OTRO_CONSULTORIO_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CAMBIO_DE_CONSULTORIO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + ID));
        verify(medicoService).actualizar(eq(ID), any(MedicoRequest.class));
    }

    @Test
    @DisplayName("PUT /{id} con nombre en blanco devuelve 400 (@Valid también en el PUT)")
    void actualizar_shouldReturn400_whenNombreIsBlank() throws Exception {
        mockMvc.perform(put("/api/v1/medicos/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"   "}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + ID));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("DELETE /{id} devuelve 204 sin cuerpo")
    void desactivar_shouldReturn204WithoutBody() throws Exception {
        mockMvc.perform(delete("/api/v1/medicos/{id}", ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(medicoService).desactivar(ID);
    }

    @Test
    @DisplayName("GET /{id} inexistente devuelve 404 con ErrorResponse")
    void buscarPorId_shouldReturn404WithErrorResponse_whenMissing() throws Exception {
        when(medicoService.buscarPorId(ID))
                .thenThrow(new ResourceNotFoundException("Medico", ID));

        mockMvc.perform(get("/api/v1/medicos/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + ID));
    }

    @Test
    @DisplayName("PUT /{id} inexistente devuelve 404 con ErrorResponse")
    void actualizar_shouldReturn404_whenMissing() throws Exception {
        when(medicoService.actualizar(eq(ID), any(MedicoRequest.class)))
                .thenThrow(new ResourceNotFoundException("Medico", ID));

        mockMvc.perform(put("/api/v1/medicos/{id}", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + ID));
    }

    @Test
    @DisplayName("DELETE /{id} inexistente devuelve 404 con ErrorResponse")
    void desactivar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Medico", ID))
                .when(medicoService).desactivar(ID);

        mockMvc.perform(delete("/api/v1/medicos/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + ID));
    }

    @Test
    @DisplayName("POST con nombre en blanco devuelve 400 con mensaje de negocio en español")
    void crear_shouldReturn400_whenNombreIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"   "}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("POST con tarifaBase negativa devuelve 400 (@DecimalMin)")
    void crear_shouldReturn400_whenTarifaBaseIsNegative() throws Exception {
        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres","tarifaBase":"-10.00"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo tarifaBase es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("POST con tarifaBase de 3 decimales devuelve 400 (@Digits)")
    void crear_shouldReturn400_whenTarifaBaseHasThreeDecimals() throws Exception {
        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consultorioId":"%s","nombre":"Dra. Ana Torres","tarifaBase":"500.123"}
                                """.formatted(CONSULTORIO_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo tarifaBase es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("POST con JSON mal formado devuelve 400 sin detalles internos del parser")
    void crear_shouldReturn400_whenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/organizaciones/{organizacionId}/medicos", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Dra. Ana Torres\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/" + ORG_ID + "/medicos"));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("GET /{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void buscarPorId_shouldReturn400_whenUuidIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/medicos/{id}", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/no-es-un-uuid"));
        verifyNoInteractions(medicoService);
    }

    @Test
    @DisplayName("GET anidado con organizacionId inválido devuelve 400 con ErrorResponse")
    void listarPorOrganizacion_shouldReturn400_whenOrganizacionIdIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/medicos", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ORG_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones/no-es-un-uuid/medicos"));
        verifyNoInteractions(medicoService);
    }

    private static MedicoResponse respuesta(UUID id, UUID organizacionId, UUID consultorioId, String nombre,
            String especialidad, String cedula, String telefono, String tarifaBase, boolean activo) {
        return new MedicoResponse(id, organizacionId, consultorioId, nombre, especialidad, cedula,
                telefono, tarifaBase == null ? null : new BigDecimal(tarifaBase), activo);
    }
}
