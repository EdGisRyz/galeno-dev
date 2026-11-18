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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.exception.ConsultorioNoRetirableException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.security.AuthenticatedUser;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.service.MedicoConsultorioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas MVC del contrato HTTP de {@link MedicoConsultorioController}.
 *
 * <p>Rebanada web pura: no hay base de datos y el service se simula con {@code @MockitoBean}. Se
 * importa explícitamente {@link SecurityConfig} para ejercer el contrato a través de la cadena de
 * filtros real, igual que los tests MVC de los módulos anteriores: cada petición se autentica con
 * un token Bearer RS256 de verdad, firmado con el par que {@link ClaveRsaDePrueba} genera en
 * memoria. No hay post-procesadores de seguridad ni usuarios simulados, así que el filtro JWT, la
 * matriz de autorización, el entry point y el contrato de error siguen siendo los de
 * producción.</p>
 *
 * <p>Lo que se fija aquí es la mitad HTTP de cada regla: el {@code 204} de las dos escrituras
 * idempotentes, el {@code 404} del médico y del consultorio, el {@code 409} de las dos reglas de
 * conflicto con su cuerpo {@code ErrorResponse}, el {@code 403} de propiedad y las celdas de rol
 * de la cadena. El acotamiento por organización, la propiedad y la detección previa de los dos
 * conflictos viven en el service y se prueban en {@code MedicoConsultorioServiceTest}.</p>
 */
@WebMvcTest(MedicoConsultorioController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class MedicoConsultorioControllerTest {

    private static final UUID MEDICO_ID = UUID.fromString("b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID CONSULTORIO_ID = UUID.fromString("7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f");
    private static final UUID SEGUNDO_CONSULTORIO_ID =
            UUID.fromString("8d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f");
    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");

    private static final String RUTA_CONSULTORIOS = "/api/v1/medicos/" + MEDICO_ID + "/consultorios";
    private static final String RUTA_CONSULTORIO = RUTA_CONSULTORIOS + "/" + CONSULTORIO_ID;

    private static final String MENSAJE_MEDICO_NO_ENCONTRADO =
            "No se encontró el recurso Medico con id: " + MEDICO_ID;
    private static final String MENSAJE_CONSULTORIO_NO_ENCONTRADO =
            "No se encontró el recurso Consultorio con id: " + CONSULTORIO_ID;
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'medicoId' tiene un formato inválido";
    private static final String MENSAJE_CONSULTORIO_ID_INVALIDO =
            "El parámetro 'consultorioId' tiene un formato inválido";
    private static final String MENSAJE_PRINCIPAL =
            ConsultorioNoRetirableException.porSerElPrincipal().getMessage();
    private static final String MENSAJE_BLOQUES =
            ConsultorioNoRetirableException.porTenerBloquesDeHorario(
                    List.of("lunes de 09:00 a 13:00", "jueves de 15:00 a 19:00"))
                    .getMessage();

    private static final String TOKEN = ClaveRsaDePrueba.tokenPara("ADMIN", TENANT_ID);
    private static final String TOKEN_MEDICO = ClaveRsaDePrueba.tokenPara("MEDICO", TENANT_ID);
    private static final String TOKEN_PACIENTE = ClaveRsaDePrueba.tokenPara("PACIENTE", TENANT_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MedicoConsultorioService medicoConsultorioService;

    @Test
    @DisplayName("GET devuelve 200 con los consultorios asignados en el orden del service")
    void listar_shouldReturn200WithTheAssignedConsultoriosInOrder() throws Exception {
        when(medicoConsultorioService.listar(any(AuthenticatedUser.class), eq(MEDICO_ID)))
                .thenReturn(List.of(
                        consultorio(SEGUNDO_CONSULTORIO_ID, "Consultorio Dos"),
                        consultorio(CONSULTORIO_ID, "Consultorio Uno")));

        perform(get(RUTA_CONSULTORIOS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(SEGUNDO_CONSULTORIO_ID.toString()))
                .andExpect(jsonPath("$[0].nombre").value("Consultorio Dos"))
                .andExpect(jsonPath("$[0].organizacionId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$[1].id").value(CONSULTORIO_ID.toString()))
                .andExpect(jsonPath("$[1].nombre").value("Consultorio Uno"));
        verify(medicoConsultorioService).listar(any(AuthenticatedUser.class), eq(MEDICO_ID));
    }

    @Test
    @DisplayName("GET de un médico inexistente devuelve 404 con el contrato de error")
    void listar_shouldReturn404_whenMedicoDoesNotExist() throws Exception {
        when(medicoConsultorioService.listar(any(AuthenticatedUser.class), eq(MEDICO_ID)))
                .thenThrow(new ResourceNotFoundException("Medico", MEDICO_ID));

        perform(get(RUTA_CONSULTORIOS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_CONSULTORIOS));
    }

    @Test
    @DisplayName("GET de los consultorios de otro médico devuelve 403 con el contrato de error")
    void listar_shouldReturn403_whenMedicoReadsAnotherMedicosConsultorios() throws Exception {
        when(medicoConsultorioService.listar(any(AuthenticatedUser.class), eq(MEDICO_ID)))
                .thenThrow(new AccessDeniedException("Un médico solo puede operar sobre su propia agenda"));

        perform(get(RUTA_CONSULTORIOS))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_CONSULTORIOS));
    }

    @Test
    @DisplayName("GET con token MEDICO devuelve 200 y el actor del token llega al service")
    void listar_shouldReturn200_whenActorIsMedico() throws Exception {
        when(medicoConsultorioService.listar(any(AuthenticatedUser.class), eq(MEDICO_ID)))
                .thenReturn(List.of(consultorio(CONSULTORIO_ID, "Consultorio Uno")));

        mockMvc.perform(get(RUTA_CONSULTORIOS).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CONSULTORIO_ID.toString()));

        var captor = org.mockito.ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(medicoConsultorioService).listar(captor.capture(), eq(MEDICO_ID));
        assertThat(captor.getValue().role()).isEqualTo("MEDICO");
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("GET con medicoId inválido devuelve 400 sin llegar al service")
    void listar_shouldReturn400_whenMedicoIdIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/medicos/no-es-un-uuid/consultorios")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO));
        verifyNoInteractions(medicoConsultorioService);
    }

    @Test
    @DisplayName("POST devuelve 204 sin cuerpo y delega médico y consultorio del path y del cuerpo")
    void asignar_shouldReturn204WithoutBody() throws Exception {
        perform(post(RUTA_CONSULTORIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(medicoConsultorioService).asignar(any(AuthenticatedUser.class), eq(MEDICO_ID),
                eq(CONSULTORIO_ID));
    }

    @Test
    @DisplayName("POST repetido devuelve 204 las dos veces: la ruta es idempotente")
    void asignar_shouldReturn204Twice_whenTheAssignmentAlreadyExists() throws Exception {
        perform(post(RUTA_CONSULTORIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isNoContent());
        perform(post(RUTA_CONSULTORIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isNoContent());
        verify(medicoConsultorioService, org.mockito.Mockito.times(2))
                .asignar(any(AuthenticatedUser.class), eq(MEDICO_ID), eq(CONSULTORIO_ID));
    }

    @Test
    @DisplayName("POST sin consultorioId devuelve 400 sin llegar al service")
    void asignar_shouldReturn400_whenConsultorioIdIsMissing() throws Exception {
        perform(post(RUTA_CONSULTORIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje")
                        .value(org.hamcrest.Matchers.containsString("consultorioId")));
        verifyNoInteractions(medicoConsultorioService);
    }

    @Test
    @DisplayName("POST de un consultorio inexistente devuelve 404 con el contrato de error")
    void asignar_shouldReturn404_whenConsultorioDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Consultorio", CONSULTORIO_ID))
                .when(medicoConsultorioService).asignar(any(AuthenticatedUser.class), eq(MEDICO_ID),
                        eq(CONSULTORIO_ID));

        perform(post(RUTA_CONSULTORIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_CONSULTORIOS));
    }

    @Test
    @DisplayName("POST con token MEDICO devuelve 403 sin llegar al service: asignar es de ADMIN")
    void asignar_shouldReturn403_whenActorIsMedico() throws Exception {
        mockMvc.perform(post(RUTA_CONSULTORIOS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO));
        verifyNoInteractions(medicoConsultorioService);
    }

    @Test
    @DisplayName("POST con token PACIENTE devuelve 403 sin llegar al service")
    void asignar_shouldReturn403_whenActorIsPaciente() throws Exception {
        mockMvc.perform(post(RUTA_CONSULTORIOS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_PACIENTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(CONSULTORIO_ID)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(medicoConsultorioService);
    }

    @Test
    @DisplayName("DELETE devuelve 204 sin cuerpo")
    void quitar_shouldReturn204WithoutBody() throws Exception {
        perform(delete(RUTA_CONSULTORIO))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(medicoConsultorioService).quitar(any(AuthenticatedUser.class), eq(MEDICO_ID),
                eq(CONSULTORIO_ID));
    }

    @Test
    @DisplayName("DELETE repetido devuelve 204 las dos veces: la ruta es idempotente")
    void quitar_shouldReturn204Twice_whenTheAssignmentIsAlreadyGone() throws Exception {
        perform(delete(RUTA_CONSULTORIO)).andExpect(status().isNoContent());
        perform(delete(RUTA_CONSULTORIO)).andExpect(status().isNoContent());
        verify(medicoConsultorioService, org.mockito.Mockito.times(2))
                .quitar(any(AuthenticatedUser.class), eq(MEDICO_ID), eq(CONSULTORIO_ID));
    }

    @Test
    @DisplayName("DELETE del consultorio principal devuelve 409 y explica que es el principal")
    void quitar_shouldReturn409_whenTheConsultorioIsThePrincipal() throws Exception {
        doThrow(ConsultorioNoRetirableException.porSerElPrincipal())
                .when(medicoConsultorioService).quitar(any(AuthenticatedUser.class), eq(MEDICO_ID),
                        eq(CONSULTORIO_ID));

        perform(delete(RUTA_CONSULTORIO))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_PRINCIPAL))
                .andExpect(jsonPath("$.mensaje",
                        org.hamcrest.Matchers.containsString("consultorio principal")))
                .andExpect(jsonPath("$.ruta").value(RUTA_CONSULTORIO));
    }

    @Test
    @DisplayName("DELETE de un consultorio con bloques devuelve 409 y dice cuáles lo impiden")
    void quitar_shouldReturn409_whenTheConsultorioHasHorarios() throws Exception {
        doThrow(ConsultorioNoRetirableException.porTenerBloquesDeHorario(
                List.of("lunes de 09:00 a 13:00", "jueves de 15:00 a 19:00")))
                .when(medicoConsultorioService).quitar(any(AuthenticatedUser.class), eq(MEDICO_ID),
                        eq(CONSULTORIO_ID));

        perform(delete(RUTA_CONSULTORIO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_BLOQUES))
                .andExpect(jsonPath("$.mensaje",
                        org.hamcrest.Matchers.containsString("bloques de horario")))
                .andExpect(jsonPath("$.mensaje",
                        org.hamcrest.Matchers.containsString("lunes de 09:00 a 13:00")))
                .andExpect(jsonPath("$.mensaje",
                        org.hamcrest.Matchers.containsString("jueves de 15:00 a 19:00")))
                .andExpect(jsonPath("$.ruta").value(RUTA_CONSULTORIO));
    }

    @Test
    @DisplayName("DELETE con token MEDICO devuelve 403 sin llegar al service: retirar es de ADMIN")
    void quitar_shouldReturn403_whenActorIsMedico() throws Exception {
        mockMvc.perform(delete(RUTA_CONSULTORIO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403));
        verifyNoInteractions(medicoConsultorioService);
    }

    @Test
    @DisplayName("DELETE con consultorioId inválido devuelve 400 sin llegar al service")
    void quitar_shouldReturn400_whenConsultorioIdIsInvalid() throws Exception {
        mockMvc.perform(delete("/api/v1/medicos/{medicoId}/consultorios/no-es-un-uuid", MEDICO_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CONSULTORIO_ID_INVALIDO));
        verifyNoInteractions(medicoConsultorioService);
    }

    /**
     * Respuesta de consultorio usada por las fixtures.
     *
     * @param id     identificador del consultorio
     * @param nombre nombre del consultorio
     * @return la representación de salida con el resto de campos fijos
     */
    private static ConsultorioResponse consultorio(UUID id, String nombre) {
        return new ConsultorioResponse(id, TENANT_ID, nombre, null, null, true,
                OffsetDateTime.parse("2026-02-10T08:45:00-06:00"));
    }

    /**
     * Cuerpo de la asignación con el consultorio indicado.
     *
     * @param consultorioId consultorio a asignar
     * @return cuerpo JSON de la petición
     */
    private static String cuerpoAsignacion(UUID consultorioId) {
        return """
                {"consultorioId":"%s"}
                """.formatted(consultorioId);
    }

    /**
     * Publica la clave pública de prueba y los claims esperados para que el filtro JWT acepte los
     * tokens de la clase, igual que en las pruebas MVC de los módulos anteriores.
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
        return mockMvc.perform(peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
    }
}
