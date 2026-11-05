package com.intoglobe.clinicas.controller;

import java.time.LocalTime;
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

import com.intoglobe.clinicas.dto.request.HorarioMedicoRequest;
import com.intoglobe.clinicas.dto.response.HorarioMedicoResponse;
import com.intoglobe.clinicas.exception.DuracionConsultaInvalidaException;
import com.intoglobe.clinicas.exception.RangoHorarioInvalidoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.SolapamientoDeHorarioException;
import com.intoglobe.clinicas.security.AuthenticatedUser;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.service.HorarioMedicoService;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas MVC del contrato HTTP de {@link HorarioMedicoController}.
 *
 * <p>Rebanada web pura: no hay base de datos y el service se simula con
 * {@code @MockitoBean}. Se importa explícitamente {@link SecurityConfig} para ejercer el
 * contrato a través de la cadena de filtros real, igual que {@code MedicoControllerTest}:
 * cada petición se autentica con un token Bearer RS256 de verdad, firmado con el par que
 * {@link ClaveRsaDePrueba} genera en memoria y publicado a la cadena con
 * {@link DynamicPropertySource}. No se usan post-procesadores de seguridad ni usuarios
 * simulados, así que el filtro JWT, la matriz de autorización, el entry point y el contrato
 * de error siguen siendo los de producción.</p>
 *
 * <p>Los {@code 404} y los {@code 400} de negocio —rango invertido, duración fuera del
 * catálogo y solapamiento— los produce el service y los traduce el
 * {@code GlobalExceptionHandler}: estos casos fijan ese acuerdo extremo a extremo desde el
 * punto de vista HTTP y por eso afirman el cuerpo {@code ErrorResponse}. Los {@code 400} de
 * validación, JSON mal formado y UUID de path inválido ocurren antes de llegar al service, de
 * modo que ahí se verifica explícitamente que el service no fue invocado.</p>
 *
 * <p>La cadena de filtros es la de producción, así que estas pruebas también fijan la mitad
 * de autorización que vive en {@code SecurityConfig}: {@code ADMIN} y {@code MEDICO} alcanzan
 * las cinco rutas y {@code PACIENTE} queda fuera por el fallback deny-by-default. El
 * acotamiento por organización y la propiedad de la agenda son del service y se prueban en
 * {@code HorarioMedicoServiceTest}; aquí solo se fija que el controller toma el principal del
 * token y lo pasa al service, y que el {@code 403} de negocio llega al cliente con el contrato
 * de error.</p>
 */
@WebMvcTest(HorarioMedicoController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class HorarioMedicoControllerTest {

    private static final UUID MEDICO_ID = UUID.fromString("b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID HORARIO_ID = UUID.fromString("c1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID OTRO_HORARIO_ID = UUID.fromString("d1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");

    private static final String MENSAJE_MEDICO_NO_ENCONTRADO =
            "No se encontró el recurso Medico con id: " + MEDICO_ID;
    private static final String MENSAJE_HORARIO_NO_ENCONTRADO =
            "No se encontró el recurso Horario con id: " + HORARIO_ID;
    private static final String MENSAJE_RANGO_INVALIDO =
            "El rango horario no es válido: la hora de inicio (13:00) debe ser estrictamente"
                    + " anterior a la hora de fin (11:00)";
    private static final String MENSAJE_DURACION_INVALIDA =
            "La duración de la consulta no es válida: 25 minutos."
                    + " Las duraciones permitidas son 20, 30, 45 o 60 minutos";
    private static final String MENSAJE_SOLAPAMIENTO =
            "El bloque de 09:00 a 11:00 del día 1 (0=Domingo, 6=Sábado)"
                    + " se solapa con otro bloque ya agendado de este médico";
    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String MENSAJE_ID_INVALIDO =
            "El parámetro 'id' tiene un formato inválido";
    private static final String MENSAJE_MEDICO_ID_INVALIDO =
            "El parámetro 'medicoId' tiene un formato inválido";

    /** Token {@code ADMIN} del tenant de prueba, igual que en los tests MVC del módulo 03. */
    private static final String TOKEN = ClaveRsaDePrueba.tokenPara("ADMIN", TENANT_ID);

    /**
     * Token {@code MEDICO} del mismo tenant. La matriz concede las cinco rutas a este rol y el
     * service es quien decide después si la agenda es la suya: en esta rebanada el service está
     * simulado, así que aquí solo se fija la mitad del rol y que el actor llegue intacto.
     */
    private static final String TOKEN_MEDICO = ClaveRsaDePrueba.tokenPara("MEDICO", TENANT_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HorarioMedicoService horarioMedicoService;

    @Test
    @DisplayName("POST devuelve 201, Location relativo a la ruta plana y cuerpo del bloque creado")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(horarioMedicoService.crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class)))
                .thenReturn(respuesta(HORARIO_ID, MEDICO_ID, (short) 1, "09:00", "13:00", (short) 30));

        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"09:00:00","horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/horarios/" + HORARIO_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(HORARIO_ID.toString()))
                .andExpect(jsonPath("$.medicoId").value(MEDICO_ID.toString()))
                .andExpect(jsonPath("$.diaSemana").value(1))
                .andExpect(jsonPath("$.horaInicio").value("09:00:00"))
                .andExpect(jsonPath("$.horaFin").value("13:00:00"))
                .andExpect(jsonPath("$.duracionConsulta").value(30));
        verify(horarioMedicoService).crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("POST acepta los límites del día: domingo (0) y sábado (6) con la duración máxima")
    void crear_shouldReturn201_whenDiaSemanaIsAtTheBoundary() throws Exception {
        when(horarioMedicoService.crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class)))
                .thenReturn(respuesta(HORARIO_ID, MEDICO_ID, (short) 6, "18:00", "20:00", (short) 60));

        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":6,"horaInicio":"18:00:00","horaFin":"20:00:00","duracionConsulta":60}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.diaSemana").value(6))
                .andExpect(jsonPath("$.duracionConsulta").value(60));
        verify(horarioMedicoService).crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("GET /horarios/{id} devuelve 200 con el bloque encontrado")
    void buscarPorId_shouldReturn200WithBody() throws Exception {
        when(horarioMedicoService.buscarPorId(any(AuthenticatedUser.class), eq(HORARIO_ID)))
                .thenReturn(respuesta(HORARIO_ID, MEDICO_ID, (short) 3, "08:30", "12:00", (short) 45));

        perform(get("/api/v1/horarios/{id}", HORARIO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(HORARIO_ID.toString()))
                .andExpect(jsonPath("$.medicoId").value(MEDICO_ID.toString()))
                .andExpect(jsonPath("$.diaSemana").value(3))
                .andExpect(jsonPath("$.horaInicio").value("08:30:00"))
                .andExpect(jsonPath("$.horaFin").value("12:00:00"))
                .andExpect(jsonPath("$.duracionConsulta").value(45));
        verify(horarioMedicoService).buscarPorId(any(AuthenticatedUser.class), eq(HORARIO_ID));
    }

    @Test
    @DisplayName("GET anidado devuelve 200 con el arreglo de bloques en el orden del contrato")
    void listarPorMedico_shouldReturn200WithArray() throws Exception {
        when(horarioMedicoService.listarPorMedico(any(AuthenticatedUser.class), eq(MEDICO_ID))).thenReturn(List.of(
                respuesta(HORARIO_ID, MEDICO_ID, (short) 1, "09:00", "13:00", (short) 30),
                respuesta(OTRO_HORARIO_ID, MEDICO_ID, (short) 1, "15:00", "18:00", (short) 20),
                respuesta(UUID.randomUUID(), MEDICO_ID, (short) 4, "09:00", "11:00", (short) 60)));

        perform(get("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].diaSemana").value(1))
                .andExpect(jsonPath("$[0].horaInicio").value("09:00:00"))
                .andExpect(jsonPath("$[1].diaSemana").value(1))
                .andExpect(jsonPath("$[1].horaInicio").value("15:00:00"))
                .andExpect(jsonPath("$[2].diaSemana").value(4))
                .andExpect(jsonPath("$[*].medicoId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(MEDICO_ID.toString()))));
        verify(horarioMedicoService).listarPorMedico(any(AuthenticatedUser.class), eq(MEDICO_ID));
    }

    @Test
    @DisplayName("GET anidado con médico inexistente devuelve 404 con ErrorResponse")
    void listarPorMedico_shouldReturn404WithErrorResponse_whenMedicoIsMissing() throws Exception {
        when(horarioMedicoService.listarPorMedico(any(AuthenticatedUser.class), eq(MEDICO_ID)))
                .thenThrow(new ResourceNotFoundException("Medico", MEDICO_ID));

        perform(get("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
    }

    @Test
    @DisplayName("POST con médico inexistente devuelve 404 con ErrorResponse")
    void crear_shouldReturn404WithErrorResponse_whenMedicoIsMissing() throws Exception {
        when(horarioMedicoService.crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class)))
                .thenThrow(new ResourceNotFoundException("Medico", MEDICO_ID));

        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"09:00:00","horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
    }

    @Test
    @DisplayName("PUT /horarios/{id} devuelve 200 con el request validado aplicado")
    void actualizar_shouldReturn200_whenRequestIsValid() throws Exception {
        when(horarioMedicoService.actualizar(any(AuthenticatedUser.class), eq(HORARIO_ID), any(HorarioMedicoRequest.class)))
                .thenReturn(respuesta(HORARIO_ID, MEDICO_ID, (short) 2, "10:00", "14:00", (short) 20));

        perform(put("/api/v1/horarios/{id}", HORARIO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":2,"horaInicio":"10:00:00","horaFin":"14:00:00","duracionConsulta":20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HORARIO_ID.toString()))
                .andExpect(jsonPath("$.medicoId").value(MEDICO_ID.toString()))
                .andExpect(jsonPath("$.diaSemana").value(2))
                .andExpect(jsonPath("$.horaInicio").value("10:00:00"))
                .andExpect(jsonPath("$.duracionConsulta").value(20));
        verify(horarioMedicoService).actualizar(any(AuthenticatedUser.class), eq(HORARIO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("DELETE /horarios/{id} devuelve 204 sin cuerpo")
    void eliminar_shouldReturn204WithoutBody() throws Exception {
        perform(delete("/api/v1/horarios/{id}", HORARIO_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(horarioMedicoService).eliminar(any(AuthenticatedUser.class), eq(HORARIO_ID));
    }

    @Test
    @DisplayName("GET /horarios/{id} inexistente devuelve 404 con ErrorResponse")
    void buscarPorId_shouldReturn404WithErrorResponse_whenMissing() throws Exception {
        when(horarioMedicoService.buscarPorId(any(AuthenticatedUser.class), eq(HORARIO_ID)))
                .thenThrow(new ResourceNotFoundException("Horario", HORARIO_ID));

        perform(get("/api/v1/horarios/{id}", HORARIO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_HORARIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/" + HORARIO_ID));
    }

    @Test
    @DisplayName("PUT /horarios/{id} inexistente devuelve 404 con ErrorResponse")
    void actualizar_shouldReturn404_whenMissing() throws Exception {
        when(horarioMedicoService.actualizar(any(AuthenticatedUser.class), eq(HORARIO_ID), any(HorarioMedicoRequest.class)))
                .thenThrow(new ResourceNotFoundException("Horario", HORARIO_ID));

        perform(put("/api/v1/horarios/{id}", HORARIO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"09:00:00","horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_HORARIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/" + HORARIO_ID));
    }

    @Test
    @DisplayName("DELETE /horarios/{id} inexistente devuelve 404 con ErrorResponse")
    void eliminar_shouldReturn404_whenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("Horario", HORARIO_ID))
                .when(horarioMedicoService).eliminar(any(AuthenticatedUser.class), eq(HORARIO_ID));

        perform(delete("/api/v1/horarios/{id}", HORARIO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_HORARIO_NO_ENCONTRADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/" + HORARIO_ID));
    }

    @Test
    @DisplayName("POST con diaSemana 7 devuelve 400 (@Max) sin llamar al service")
    void crear_shouldReturn400_whenDiaSemanaIsOutOfRange() throws Exception {
        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":7,"horaInicio":"09:00:00","horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo diaSemana es inválido"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("POST con horaInicio nula devuelve 400 (@NotNull) sin llamar al service")
    void crear_shouldReturn400_whenHoraInicioIsMissing() throws Exception {
        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":null,"horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo horaInicio es obligatorio"))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("POST con horaInicio con formato inválido devuelve 400 sin detalles del parser")
    void crear_shouldReturn400_whenHoraInicioHasInvalidFormat() throws Exception {
        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"nueve de la mañana","horaFin":"13:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("POST con duración de 25 minutos devuelve 400 con el mensaje del catálogo")
    void crear_shouldReturn400_whenDuracionConsultaIsNotPermitted() throws Exception {
        when(horarioMedicoService.crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class)))
                .thenThrow(new DuracionConsultaInvalidaException((short) 25));

        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"09:00:00","horaFin":"13:00:00","duracionConsulta":25}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_DURACION_INVALIDA))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verify(horarioMedicoService).crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("POST con un bloque que se solapa devuelve 400 con ErrorResponse")
    void crear_shouldReturn400_whenBlockOverlaps() throws Exception {
        when(horarioMedicoService.crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class)))
                .thenThrow(new SolapamientoDeHorarioException((short) 1,
                        LocalTime.of(9, 0), LocalTime.of(11, 0)));

        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"09:00:00","horaFin":"11:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_SOLAPAMIENTO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verify(horarioMedicoService).crear(any(AuthenticatedUser.class), eq(MEDICO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("PUT con el rango horario invertido devuelve 400 con ErrorResponse")
    void actualizar_shouldReturn400_whenRangeIsInverted() throws Exception {
        when(horarioMedicoService.actualizar(any(AuthenticatedUser.class), eq(HORARIO_ID), any(HorarioMedicoRequest.class)))
                .thenThrow(new RangoHorarioInvalidoException(LocalTime.of(13, 0), LocalTime.of(11, 0)));

        perform(put("/api/v1/horarios/{id}", HORARIO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diaSemana":1,"horaInicio":"13:00:00","horaFin":"11:00:00","duracionConsulta":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_RANGO_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/" + HORARIO_ID));
        verify(horarioMedicoService).actualizar(any(AuthenticatedUser.class), eq(HORARIO_ID), any(HorarioMedicoRequest.class));
    }

    @Test
    @DisplayName("POST con JSON mal formado devuelve 400 sin detalles internos del parser")
    void crear_shouldReturn400_whenJsonIsMalformed() throws Exception {
        perform(post("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diaSemana\":1,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/" + MEDICO_ID + "/horarios"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("GET anidado con medicoId inválido devuelve 400 con ErrorResponse")
    void listarPorMedico_shouldReturn400_whenMedicoIdIsInvalid() throws Exception {
        perform(get("/api/v1/medicos/{medicoId}/horarios", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_MEDICO_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/medicos/no-es-un-uuid/horarios"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("GET /horarios/{id} con UUID de path inválido devuelve 400 con ErrorResponse")
    void buscarPorId_shouldReturn400_whenUuidIsInvalid() throws Exception {
        perform(get("/api/v1/horarios/{id}", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ID_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/no-es-un-uuid"));
        verifyNoInteractions(horarioMedicoService);
    }

    @Test
    @DisplayName("GET anidado con token MEDICO devuelve 200 y el actor del token llega al service")
    void listarPorMedico_shouldReturn200_whenActorIsMedico() throws Exception {
        when(horarioMedicoService.listarPorMedico(any(AuthenticatedUser.class), eq(MEDICO_ID))).thenReturn(List.of(
                respuesta(HORARIO_ID, MEDICO_ID, (short) 1, "09:00", "13:00", (short) 30)));

        mockMvc.perform(get("/api/v1/medicos/{medicoId}/horarios", MEDICO_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].medicoId").value(MEDICO_ID.toString()));

        var captor = org.mockito.ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(horarioMedicoService).listarPorMedico(captor.capture(), eq(MEDICO_ID));
        assertThat(captor.getValue().role()).isEqualTo("MEDICO");
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("GET /horarios/{id} de otro médico devuelve 403 con el contrato de error")
    void buscarPorId_shouldReturn403_whenMedicoTouchesAnotherMedicosBlock() throws Exception {
        when(horarioMedicoService.buscarPorId(any(AuthenticatedUser.class), eq(HORARIO_ID)))
                .thenThrow(new org.springframework.security.access.AccessDeniedException(
                        "Un médico solo puede operar sobre su propia agenda"));

        mockMvc.perform(get("/api/v1/horarios/{id}", HORARIO_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_MEDICO))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/horarios/" + HORARIO_ID));
        verify(horarioMedicoService).buscarPorId(any(AuthenticatedUser.class), eq(HORARIO_ID));
    }

    private static HorarioMedicoResponse respuesta(UUID id, UUID medicoId, short diaSemana,
            String horaInicio, String horaFin, short duracionConsulta) {
        return new HorarioMedicoResponse(id, medicoId, diaSemana, LocalTime.parse(horaInicio),
                LocalTime.parse(horaFin), duracionConsulta);
    }

    /**
     * Publica la clave pública de prueba y los claims esperados para que el filtro JWT
     * acepte el token de {@link #TOKEN}, igual que en las pruebas MVC del módulo 03.
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
