package com.intoglobe.clinicas.controller;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.exception.CorreoDuplicadoEnOrganizacionException;
import com.intoglobe.clinicas.exception.GlobalExceptionHandler;
import com.intoglobe.clinicas.exception.MedicoRequeridoException;
import com.intoglobe.clinicas.exception.OperacionSobreLaPropiaCuentaException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.exception.UltimaAdministradoraException;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.security.AuthenticatedUser;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.UsuarioService;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
 * Pruebas del contrato HTTP de {@link UsuarioController}: las seis rutas con su código, el
 * {@code Location} del alta y la traducción de cada error a su cuerpo {@code ErrorResponse}.
 *
 * <p><strong>Por qué {@code standaloneSetup} y no la cadena de seguridad de producción.</strong> La
 * matriz de autorización de este módulo llega en la rebanada siguiente: mientras las seis rutas no
 * tengan sus celdas en {@code SecurityConfig}, el fallback deny-by-default las cierra para todos los
 * roles y ninguna petición llega al controlador. Para fijar el contrato del endpoint —que hoy no
 * sería alcanzable por HTTP— se monta el controlador con el {@link GlobalExceptionHandler} real y sin
 * la cadena de filtros; es el mismo criterio que
 * {@code OrganizacionControllerCrearTest} usa para el alta de organizaciones bloqueada. Que las seis
 * rutas respondan {@code 403} con la cadena real, y que el service no se invoque, lo fija
 * {@code UsuarioControllerRbacTest}. Lo que aquí se ejerce sigue siendo el despacho MVC de verdad:
 * enrutado, conversión de JSON, {@code @Valid} y el traductor de errores.</p>
 *
 * <p>El tenant y el actor se simulan en {@link TenantAccessGuard} porque en producción salen del
 * claim: lo que se verifica es que el controlador los propaga <em>tal como llegan</em> —nunca el
 * {@code organizacionId} del path en lugar del tenant, ni un dato del cuerpo en lugar del actor— y
 * que no decide nada por su cuenta.</p>
 */
class UsuarioControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID OTRA_ORGANIZACION_ID = UUID.fromString("7d2b1a9c-3e4f-4b6a-8c0d-1f2e3a4b5c6d");
    private static final UUID CUENTA_ID = UUID.fromString("b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13");
    private static final UUID MEDICO_ID = UUID.fromString("5b2e9c1a-7d34-4f80-9a2b-6c1d9e0f3a41");
    private static final String EMAIL = "laura.mendez@example.com";
    private static final String NOMBRE = "Administración";
    private static final String TELEFONO = "+52 55 5555 9876";
    private static final OffsetDateTime CREADA_EN = OffsetDateTime.parse("2025-01-15T10:30:00-06:00");

    private static final String MENSAJE_CUERPO_MAL_FORMADO =
            "El cuerpo de la petición no es válido o está mal formado";
    private static final String RESTRICCION_CORREO = "usuario_organizacion_id_email_key";

    private static final String CUERPO_VALIDO = """
            {"email":"laura.mendez@example.com","rol":"MEDICO","medicoId":"5b2e9c1a-7d34-4f80-9a2b-6c1d9e0f3a41","nombre":"Administración","telefono":"+52 55 5555 9876"}
            """;

    /** Cuerpo del alta con el nombre indicado y sin teléfono: teléfono es opcional (S1). */
    private static final String CUERPO_SIN_TELEFONO = """
            {"email":"laura.mendez@example.com","rol":"RECEPCIONISTA","nombre":"Administración"}
            """;

    /** Cuerpo sin nombre: el único dato que le falta al alta obligatorio desde S1. */
    private static final String CUERPO_SIN_NOMBRE = """
            {"email":"laura.mendez@example.com","rol":"RECEPCIONISTA"}
            """;

    /** Actor de la sesión: una cuenta distinta de la que se opera en la mayoría de los casos. */
    private static final AuthenticatedUser ACTOR =
            new AuthenticatedUser(UUID.fromString("9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"),
                    "admin@example.com", "ADMIN", TENANT_ID, null);

    private UsuarioService usuarioService;

    private TenantAccessGuard tenantAccessGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararElControlador() {
        usuarioService = mock(UsuarioService.class);
        tenantAccessGuard = mock(TenantAccessGuard.class);
        when(tenantAccessGuard.tenantActual()).thenReturn(TENANT_ID);
        when(tenantAccessGuard.usuarioActual()).thenReturn(ACTOR);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UsuarioController(usuarioService, tenantAccessGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------
    // Alta: 201 con Location
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST devuelve 201, Location relativo a la ruta plana y el cuerpo de la cuenta creada")
    void crear_shouldReturn201WithLocationAndBody() throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/usuarios/" + CUENTA_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(CUENTA_ID.toString()))
                .andExpect(jsonPath("$.organizacionId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.medicoId").value(MEDICO_ID.toString()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.rol").value("MEDICO"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.authUserId").doesNotExist());

        verify(usuarioService).crear(TENANT_ID, TENANT_ID, new UsuarioRequest(
                EMAIL, RolUsuario.MEDICO, MEDICO_ID, NOMBRE, TELEFONO));
    }

    @Test
    @DisplayName("POST propaga el tenant del claim y no el organizacionId de la ruta")
    void crear_shouldPropagateTheClaimTenant_whenThePathNamesAnotherOrganization() throws Exception {
        doThrow(new AccessDeniedException("El organizacionId de la ruta no corresponde al tenant"))
                .when(usuarioService).crear(eq(OTRA_ORGANIZACION_ID), eq(TENANT_ID), any());

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios",
                        OTRA_ORGANIZACION_ID), CUERPO_VALIDO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta")
                        .value("/api/v1/organizaciones/" + OTRA_ORGANIZACION_ID + "/usuarios"));

        verify(usuarioService).crear(OTRA_ORGANIZACION_ID, TENANT_ID, new UsuarioRequest(
                EMAIL, RolUsuario.MEDICO, MEDICO_ID, NOMBRE, TELEFONO));
    }

    @Test
    @DisplayName("POST con un rol fuera del conjunto cerrado devuelve 400 antes de llegar al service")
    void crear_shouldReturn400_whenTheRoleIsOutsideTheClosedSet() throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        """
                                {"email":"laura.mendez@example.com","rol":"GERENTE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CUERPO_MAL_FORMADO));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("POST sin rol devuelve 400 de validación y no llega al service")
    void crear_shouldReturn400_whenTheRoleIsMissing() throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        """
                                {"email":"laura.mendez@example.com","nombre":"Administración"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo rol es obligatorio"));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("POST de un MEDICO sin medicoId devuelve 400 con el motivo de la regla")
    void crear_shouldReturn400_whenTheMedicoIsMissingForARoleMedico() throws Exception {
        doThrow(new MedicoRequeridoException())
                .when(usuarioService).crear(eq(TENANT_ID), eq(TENANT_ID), any());

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("medicoId")));
    }

    @Test
    @DisplayName("POST con un médico inexistente devuelve 404")
    void crear_shouldReturn404_whenTheMedicoDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Medico", MEDICO_ID))
                .when(usuarioService).crear(eq(TENANT_ID), eq(TENANT_ID), any());

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.mensaje").value(
                        "No se encontró el recurso Medico con id: " + MEDICO_ID));
    }

    @Test
    @DisplayName("POST con correo ya ocupado devuelve 409 con el motivo y la ruta")
    void crear_shouldReturn409_whenTheEmailIsAlreadyUsed() throws Exception {
        doThrow(new CorreoDuplicadoEnOrganizacionException(EMAIL))
                .when(usuarioService).crear(eq(TENANT_ID), eq(TENANT_ID), any());

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString(EMAIL)))
                .andExpect(jsonPath("$.ruta")
                        .value("/api/v1/organizaciones/" + TENANT_ID + "/usuarios"));
    }

    // ------------------------------------------------------------------
    // Forma del correo de la cuenta (S1, S3)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "POST con el correo \"{0}\" responde 400 y no llega al service")
    @ValueSource(strings = {"juan@", "juan", "@dominio.com", "juan@dominio", "juan @dominio.com",
            "juan@@dominio.com", "juan@.com"})
    void crear_shouldReturn400_whenTheEmailHasNoMailShape(String email) throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConEmail(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("El campo email es inválido"))
                .andExpect(jsonPath("$.ruta")
                        .value("/api/v1/organizaciones/" + TENANT_ID + "/usuarios"));
        verifyNoInteractions(usuarioService);
    }

    @ParameterizedTest(name = "POST con el correo \"{0}\" responde 201")
    @ValueSource(strings = {"admin@dev-auth.local", "medico@dev-auth.local",
            "recepcion@dev-auth.local", "paciente@dev-auth.local", "ejemplo@gmail.com",
            "nombre+etiqueta@dominio.com.mx", "Laura.Mendez@Example.COM"})
    void crear_shouldReturn201_whenTheEmailHasMailShape(String email) throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConEmail(email)))
                .andExpect(status().isCreated());

        verify(usuarioService).crear(TENANT_ID, TENANT_ID,
                new UsuarioRequest(email, RolUsuario.RECEPCIONISTA, null, NOMBRE, null));
    }

    @ParameterizedTest(name = "PUT con el correo \"{0}\" responde 400 y no llega al service")
    @ValueSource(strings = {"juan@", "juan", "@dominio.com", "juan@dominio", "juan @dominio.com",
            "juan@@dominio.com", "juan@.com"})
    void actualizar_shouldReturn400_whenTheEmailHasNoMailShape(String email) throws Exception {
        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), cuerpoConEmail(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo email es inválido"));
        verifyNoInteractions(usuarioService);
    }

    @ParameterizedTest(name = "PUT con el correo \"{0}\" responde 200")
    @ValueSource(strings = {"admin@dev-auth.local", "ejemplo@gmail.com",
            "nombre+etiqueta@dominio.com.mx", "Laura.Mendez@Example.COM"})
    void actualizar_shouldReturn200_whenTheEmailHasMailShape(String email) throws Exception {
        when(usuarioService.actualizar(eq(ACTOR), eq(CUENTA_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), cuerpoConEmail(email)))
                .andExpect(status().isOk());

        verify(usuarioService).actualizar(ACTOR, CUENTA_ID,
                new UsuarioRequest(email, RolUsuario.RECEPCIONISTA, null, NOMBRE, null));
    }

    @ParameterizedTest(name = "POST con el correo \"{0}\" rodeado de espacios responde 201 y se recorta antes de validarlo")
    @ValueSource(strings = {"  juan@dominio.com  ", "\u00A0juan@dominio.com\u00A0"})
    void crear_shouldReturn201AndTrimTheEmail_whenItArrivesWithSurroundingSpaces(String crudo)
            throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConEmail(crudo)))
                .andExpect(status().isCreated());

        // El 201 ya demuestra que el patrón vio el valor recortado: el crudo no tiene forma de
        // correo. El captor fija además qué valor recibe el servicio. El segundo caso es el espacio
        // duro que deja un pegado desde un documento, que `String#strip` no recorta.
        var captor = ArgumentCaptor.forClass(UsuarioRequest.class);
        verify(usuarioService).crear(eq(TENANT_ID), eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().email())
                .as("el correo viaja recortado al servicio y a la base")
                .isEqualTo("juan@dominio.com");
    }

    @Test
    @DisplayName("PUT con espacios alrededor del correo responde 200 y recorta el valor antes de validarlo")
    void actualizar_shouldReturn200AndTrimTheEmail_whenItArrivesWithSurroundingSpaces() throws Exception {
        when(usuarioService.actualizar(eq(ACTOR), eq(CUENTA_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID),
                        cuerpoConEmail("  laura.mendez@example.com  ")))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UsuarioRequest.class);
        verify(usuarioService).actualizar(eq(ACTOR), eq(CUENTA_ID), captor.capture());
        assertThat(captor.getValue().email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("POST con un correo de 181 caracteres sigue devolviendo 400 de longitud: la regla de forma no relaja el máximo de la columna")
    void crear_shouldReturn400_whenTheEmailExceedsTheColumnLength() throws Exception {
        String emailLargo = "a".repeat(174) + "@dominio.com";

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConEmail(emailLargo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje")
                        .value("El campo email tiene una longitud fuera del rango permitido"));
        verifyNoInteractions(usuarioService);
    }

    // ------------------------------------------------------------------
    // Nombre obligatorio y teléfono opcional (S1, S3, S7)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "POST con el nombre \"{0}\" responde 400 y no llega al service")
    @ValueSource(strings = {"   "})
    void crear_shouldReturn400_whenTheNameIsBlank(String nombre) throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConNombre(nombre)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("POST sin el campo nombre responde 400: el alta no crea cuentas sin nombre")
    void crear_shouldReturn400_whenTheNameIsMissing() throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_SIN_NOMBRE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("POST con dígitos en el nombre responde 400: la regla es la de las personas")
    void crear_shouldReturn400_whenTheNameHasDigits() throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConNombre("Administrador 123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El campo nombre es inválido"));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("POST con un nombre de 121 caracteres responde 400: el máximo es el de la columna")
    void crear_shouldReturn400_whenTheNameExceedsTheColumnLength() throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConNombre("a".repeat(121))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje")
                        .value("El campo nombre tiene una longitud fuera del rango permitido"));
        verifyNoInteractions(usuarioService);
    }

    @ParameterizedTest(name = "POST con el teléfono \"{0}\" responde 400 y no llega al service")
    @ValueSource(strings = {"vvvvvuvu", "12345", "123456789012345678901", "555 555 5555x", "--"})
    void crear_shouldReturn400_whenThePhoneHasNoPhoneShape(String telefono) throws Exception {
        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConNombreYTelefono(NOMBRE, telefono)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo telefono es inválido"));
        verifyNoInteractions(usuarioService);
    }

    @ParameterizedTest(name = "POST con el teléfono \"{0}\" responde 201")
    @ValueSource(strings = {"+52 55 5555 9876", "(951) 111 1111", "951-501-2001"})
    void crear_shouldReturn201_whenThePhoneHasPhoneShape(String telefono) throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        cuerpoConNombreYTelefono(NOMBRE, telefono)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST sin teléfono responde 201: el teléfono es opcional")
    void crear_shouldReturn201_whenThePhoneIsMissing() throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_SIN_TELEFONO))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PUT sin nombre responde 400: editar una cuenta anterior obliga a completarle el nombre")
    void actualizar_shouldReturn400_whenTheNameIsMissing() throws Exception {
        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), CUERPO_SIN_NOMBRE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value(400))
                .andExpect(jsonPath("$.mensaje").value("El campo nombre no puede estar vacío"));
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("la respuesta del alta devuelve el nombre y el teléfono de la cuenta")
    void crear_shouldReturnTheNameAndPhoneOfTheAccount() throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value(NOMBRE))
                .andExpect(jsonPath("$.telefono").value(TELEFONO));
    }

    @Test
    @DisplayName("el GET por id devuelve el nombre y el teléfono de la cuenta")
    void buscarPorId_shouldReturnTheNameAndPhoneOfTheAccount() throws Exception {
        when(usuarioService.buscarPorId(CUENTA_ID, TENANT_ID)).thenReturn(respuesta(true));

        mockMvc.perform(get("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value(NOMBRE))
                .andExpect(jsonPath("$.telefono").value(TELEFONO));
    }

    // ------------------------------------------------------------------
    // Listado y consulta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET anidado devuelve 200 con las cuentas activas e inactivas en el orden del servicio")
    void listarPorOrganizacion_shouldReturn200WithActiveAndInactiveAccounts() throws Exception {
        when(usuarioService.listarPorOrganizacion(TENANT_ID, TENANT_ID))
                .thenReturn(List.of(respuesta(true), respuesta(false)));

        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value(EMAIL))
                .andExpect(jsonPath("$[0].activo").value(true))
                .andExpect(jsonPath("$[1].activo").value(false));
    }

    @Test
    @DisplayName("GET anidado devuelve 404 cuando la organización no existe")
    void listarPorOrganizacion_shouldReturn404_whenTheOrganizationDoesNotExist() throws Exception {
        when(usuarioService.listarPorOrganizacion(TENANT_ID, TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Organizacion", TENANT_ID));

        mockMvc.perform(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404));
    }

    @Test
    @DisplayName("GET por id devuelve 200 con la cuenta del tenant")
    void buscarPorId_shouldReturn200() throws Exception {
        when(usuarioService.buscarPorId(CUENTA_ID, TENANT_ID)).thenReturn(respuesta(true));

        mockMvc.perform(get("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CUENTA_ID.toString()))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    @DisplayName("GET por id devuelve 404 cuando la cuenta no está en el tenant del token")
    void buscarPorId_shouldReturn404_whenTheAccountIsNotInTheTenant() throws Exception {
        when(usuarioService.buscarPorId(CUENTA_ID, TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Usuario", CUENTA_ID));

        mockMvc.perform(get("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje")
                        .value("No se encontró el recurso Usuario con id: " + CUENTA_ID));
    }

    // ------------------------------------------------------------------
    // Actualización, baja y reactivación
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT devuelve 200 y entrega al service el actor del claim")
    void actualizar_shouldReturn200AndPassTheActorOfTheClaim() throws Exception {
        when(usuarioService.actualizar(eq(ACTOR), eq(CUENTA_ID), any())).thenReturn(respuesta(true));

        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), CUERPO_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CUENTA_ID.toString()));

        verify(usuarioService).actualizar(ACTOR, CUENTA_ID,
                new UsuarioRequest(EMAIL, RolUsuario.MEDICO, MEDICO_ID, NOMBRE, TELEFONO));
    }

    @Test
    @DisplayName("PUT de la propia cuenta cambiándose el rol devuelve 403 con el motivo de R11")
    void actualizar_shouldReturn403_whenTheActorChangesItsOwnRole() throws Exception {
        when(usuarioService.actualizar(eq(ACTOR), eq(CUENTA_ID), any()))
                .thenThrow(OperacionSobreLaPropiaCuentaException.alCambiarElRol(CUENTA_ID));

        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), CUERPO_VALIDO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("su propia cuenta")));
    }

    @Test
    @DisplayName("PUT que dejaría a la organización sin administración devuelve 409 con el motivo")
    void actualizar_shouldReturn409_whenItIsTheLastAdministrator() throws Exception {
        when(usuarioService.actualizar(eq(ACTOR), eq(CUENTA_ID), any()))
                .thenThrow(UltimaAdministradoraException.alCambiarElRol(CUENTA_ID));

        mockMvc.perform(conCuerpo(put("/api/v1/usuarios/{id}", CUENTA_ID), CUERPO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("última")));
    }

    @Test
    @DisplayName("DELETE devuelve 204 sin cuerpo y entrega al service el actor del claim")
    void desactivar_shouldReturn204AndPassTheActorOfTheClaim() throws Exception {
        mockMvc.perform(delete("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(usuarioService).desactivar(ACTOR, CUENTA_ID);
    }

    @Test
    @DisplayName("DELETE de la propia cuenta devuelve 403 con el motivo de R11")
    void desactivar_shouldReturn403_whenTheActorIsTheAccount() throws Exception {
        doThrow(OperacionSobreLaPropiaCuentaException.alDarDeBaja(CUENTA_ID))
                .when(usuarioService).desactivar(eq(ACTOR), eq(CUENTA_ID));

        mockMvc.perform(delete("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("propia cuenta")));
    }

    @Test
    @DisplayName("DELETE de la última administradora devuelve 409 con el motivo de R8")
    void desactivar_shouldReturn409_whenItIsTheLastAdministrator() throws Exception {
        doThrow(UltimaAdministradoraException.alDarDeBaja(CUENTA_ID))
                .when(usuarioService).desactivar(eq(ACTOR), eq(CUENTA_ID));

        mockMvc.perform(delete("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("última")));
    }

    @Test
    @DisplayName("DELETE de una cuenta inexistente devuelve 404")
    void desactivar_shouldReturn404_whenTheAccountDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Usuario", CUENTA_ID))
                .when(usuarioService).desactivar(eq(ACTOR), eq(CUENTA_ID));

        mockMvc.perform(delete("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404));
    }

    @Test
    @DisplayName("POST de activación devuelve 204 sin cuerpo")
    void activar_shouldReturn204() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios/{id}/activar", CUENTA_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(usuarioService).activar(CUENTA_ID, TENANT_ID);
    }

    @Test
    @DisplayName("POST de activación de una cuenta inexistente devuelve 404")
    void activar_shouldReturn404_whenTheAccountDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Usuario", CUENTA_ID))
                .when(usuarioService).activar(eq(CUENTA_ID), eq(TENANT_ID));

        mockMvc.perform(post("/api/v1/usuarios/{id}/activar", CUENTA_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404));
    }

    // ------------------------------------------------------------------
    // Contrato de integridad del correo (carrera del alta)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("si la carrera del alta choca contra el correo único, la respuesta es 409 y no 500")
    void crear_shouldReturn409_whenTheUniqueEmailConstraintWinsTheRace() throws Exception {
        doThrow(violacionDeIntegridad(RESTRICCION_CORREO))
                .when(usuarioService).crear(eq(TENANT_ID), eq(TENANT_ID), any());

        mockMvc.perform(conCuerpo(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        CUERPO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje")
                        .value(containsString("correo")));
    }

    @Test
    @DisplayName("cualquier otra violación de integridad no se convierte en 409: sigue su curso")
    void traductor_shouldNotTranslate_whenTheViolatedConstraintIsNotTheEmailOne() {
        var traductor = new GlobalExceptionHandler();
        var otraRestriccion = violacionDeIntegridad("usuario_auth_user_id_key");
        var peticion = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> traductor.manejarUnicidadDuplicadaEnCarrera(otraRestriccion, peticion))
                .as("traducir en bloque convertiría en conflicto de negocio cualquier fallo de "
                        + "integridad de cualquier módulo")
                .isSameAs(otraRestriccion);
    }

    @Test
    @DisplayName("la violación del correo único se reconoce en la cadena de causas del error")
    void traductor_shouldTranslate_whenTheConstraintAppearsDeepInTheCauseChain() {
        var traductor = new GlobalExceptionHandler();
        var peticion = mock(HttpServletRequest.class);

        var respuesta = traductor.manejarUnicidadDuplicadaEnCarrera(
                violacionDeIntegridad(RESTRICCION_CORREO), peticion);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Representación de salida que devolvería el service, con los nueve campos del contrato.
     *
     * @param activo estado lógico de la cuenta
     * @return la respuesta esperada
     */
    private static UsuarioResponse respuesta(boolean activo) {
        return new UsuarioResponse(CUENTA_ID, TENANT_ID, MEDICO_ID, NOMBRE, TELEFONO, EMAIL,
                RolUsuario.MEDICO, activo, CREADA_EN);
    }

    /**
     * Cuerpo mínimo del alta y la edición de una cuenta con el correo indicado.
     *
     * <p>El rol elegido, {@code RECEPCIONISTA}, no exige médico, así que el único dato que varía —y
     * el único que puede producir el 400 de forma— es el correo.</p>
     *
     * @param email correo tal como viaja en el cuerpo
     * @return el cuerpo JSON de la petición
     */
    private static String cuerpoConEmail(String email) {
        return """
                {"email":"%s","rol":"RECEPCIONISTA","nombre":"Administración"}
                """.formatted(email);
    }

    /**
     * Cuerpo del alta con el nombre indicado, sin teléfono: el teléfono es opcional y así la única
     * regla que puede rechazar el cuerpo es la del nombre.
     *
     * @param nombre nombre tal como viaja en el cuerpo
     * @return el cuerpo JSON de la petición
     */
    private static String cuerpoConNombre(String nombre) {
        return """
                {"email":"laura.mendez@example.com","rol":"RECEPCIONISTA","nombre":"%s"}
                """.formatted(nombre);
    }
    /**
     * Cuerpo del alta con el nombre y el teléfono indicados, para ejercer la regla del teléfono.
     *
     * @param nombre   nombre de la persona
     * @param telefono teléfono tal como viaja en el cuerpo
     * @return el cuerpo JSON de la petición
     */
    private static String cuerpoConNombreYTelefono(String nombre, String telefono) {
        return """
                {"email":"laura.mendez@example.com","rol":"RECEPCIONISTA","nombre":"%s","telefono":"%s"}
                """.formatted(nombre, telefono);
    }

    /**
     * Ejecuta la petición con cuerpo JSON.
     *
     * @param peticion petición a ejecutar
     * @param cuerpo   cuerpo JSON de la petición
     * @return la petición lista para MockMvc
     */
    private static MockHttpServletRequestBuilder conCuerpo(MockHttpServletRequestBuilder peticion,
            String cuerpo) {
        return peticion.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    /**
     * Construye el error de integridad que lanza el proveedor cuando una restricción rechaza la
     * escritura, con el nombre de la restricción dentro del mensaje tal como lo publican H2 y
     * PostgreSQL.
     *
     * @param restriccion nombre de la restricción violada
     * @return la excepción que recibe el traductor
     */
    private static DataIntegrityViolationException violacionDeIntegridad(String restriccion) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException("Unique index or primary key violation: \"public." + restriccion
                        + " INDEX public." + restriccion + "_INDEX_F\""));
    }
}
