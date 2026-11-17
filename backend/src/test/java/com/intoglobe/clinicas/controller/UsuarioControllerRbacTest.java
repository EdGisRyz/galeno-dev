package com.intoglobe.clinicas.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.security.AuthenticatedUser;
import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.service.UsuarioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Celdas de la matriz de autorización para las seis rutas de usuario, ejercidas sobre la cadena de
 * filtros real.
 *
 * <p>Se importa {@link SecurityConfig} explícitamente para que la autorización que se ejerza sea la
 * de producción: filtro JWT, entrada de autenticación, manejador de denegación y reglas de la
 * matriz. Las peticiones se autentican con tokens RS256 de verdad, firmados con el par que
 * {@link ClaveRsaDePrueba} genera en memoria; no hay usuarios simulados ni post-procesadores de
 * seguridad.</p>
 *
 * <p><strong>Qué fija.</strong> {@code ADMIN} alcanza las seis operaciones con su propio tenant
 * {@code 201}, {@code 200} y {@code 204} según la operación, y el acotamiento por organización lo
 * aplica {@code UsuarioService} —aquí simulado—, cuya mitad está en {@code UsuarioServiceTest}.
 * {@code MEDICO} y {@code PACIENTE} reciben {@code 403} en las seis, y el service no se invoca.
 * {@code RECEPCIONISTA} está en el contrato de claims —el token se autentica— pero no tiene
 * ninguna celda de cuentas, así que recibe {@code 403} en las seis como los otros roles de
 * personal sin celda, y el service tampoco se invoca. Sin token, {@code 401}.</p>
 *
 * <p>El contrato de cada respuesta —cuerpos de éxito y de error, incluidos los {@code 409} de la
 * última administradora y del correo repetido y el {@code 403} de la operación sobre la propia
 * cuenta— se fija en {@code UsuarioControllerTest} con el controlador montado sin la cadena, y las
 * pruebas de aislamiento entre tenants viven en {@code AislamientoYMatrizRbacTest}.</p>
 */
@WebMvcTest(UsuarioController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class UsuarioControllerRbacTest {

    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID CUENTA_ID = UUID.fromString("b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13");
    private static final String EMAIL = "laura.mendez@example.com";

    private static final String CUERPO_VALIDO = """
            {"email":"laura.mendez@example.com","rol":"ADMIN","medicoId":null}
            """;

    private static final String TOKEN_ADMIN = ClaveRsaDePrueba.tokenPara("ADMIN", TENANT_ID);
    private static final String TOKEN_MEDICO = ClaveRsaDePrueba.tokenPara("MEDICO", TENANT_ID);
    private static final String TOKEN_PACIENTE = ClaveRsaDePrueba.tokenPara("PACIENTE", TENANT_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsuarioService usuarioService;

    @Test
    @DisplayName("ADMIN opera las seis rutas de usuario con su propio tenant")
    void adminOperaLasSeisRutasDeUsuario() throws Exception {
        when(usuarioService.crear(eq(TENANT_ID), eq(TENANT_ID), any())).thenReturn(respuesta(true));
        when(usuarioService.listarPorOrganizacion(eq(TENANT_ID), eq(TENANT_ID))).thenReturn(List.of(respuesta(true)));
        when(usuarioService.buscarPorId(eq(CUENTA_ID), eq(TENANT_ID))).thenReturn(respuesta(true));
        when(usuarioService.actualizar(any(AuthenticatedUser.class), eq(CUENTA_ID), any()))
                .thenReturn(respuesta(true));

        // POST de la colección anidada: 201 con Location hacia la ruta plana del recurso.
        mockMvc.perform(conToken(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_ADMIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/usuarios/" + CUENTA_ID))
                .andExpect(jsonPath("$.id").value(CUENTA_ID.toString()));

        // GET de la colección anidada: 200.
        mockMvc.perform(conToken(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID),
                        TOKEN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CUENTA_ID.toString()));

        // GET por id: 200.
        mockMvc.perform(conToken(get("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));

        // PUT: 200.
        mockMvc.perform(conToken(put("/api/v1/usuarios/{id}", CUENTA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        // DELETE: 204 sin cuerpo.
        mockMvc.perform(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // POST activar: 204 sin cuerpo.
        mockMvc.perform(conToken(post("/api/v1/usuarios/{id}/activar", CUENTA_ID), TOKEN_ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(usuarioService).crear(TENANT_ID, TENANT_ID, new UsuarioRequest(
                EMAIL, RolUsuario.ADMIN, null));
        verify(usuarioService).listarPorOrganizacion(TENANT_ID, TENANT_ID);
        verify(usuarioService).buscarPorId(CUENTA_ID, TENANT_ID);
        verify(usuarioService).activar(CUENTA_ID, TENANT_ID);
    }

    @Test
    @DisplayName("la baja y el cambio de rol reciben el actor del claim, no un dato del path ni del cuerpo")
    void adminOperaCuentas_shouldPassTheActorOfTheClaim() throws Exception {
        when(usuarioService.actualizar(any(AuthenticatedUser.class), eq(CUENTA_ID), any()))
                .thenReturn(respuesta(true));

        mockMvc.perform(conToken(put("/api/v1/usuarios/{id}", CUENTA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_ADMIN))
                .andExpect(status().isOk());
        mockMvc.perform(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_ADMIN))
                .andExpect(status().isNoContent());

        var actor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(usuarioService).actualizar(actor.capture(), eq(CUENTA_ID), any());
        verify(usuarioService).desactivar(actor.capture(), eq(CUENTA_ID));

        assertThat(actor.getAllValues()).hasSize(2);
        assertThat(actor.getAllValues()).allSatisfy(usado -> {
            assertThat(usado.id())
                    .as("el actor es el claim sub del token (auth_user_id), no la cuenta del path ni "
                            + "un dato del cuerpo: si el controlador propagara el id de la ruta, R11 "
                            + "compararía contra la columna equivocada")
                    .isEqualTo(UUID.fromString(ClaveRsaDePrueba.ID_USUARIO));
            assertThat(usado.tenantId())
                    .as("el tenant sale del claim")
                    .isEqualTo(TENANT_ID);
            assertThat(usado.role()).isEqualTo("ADMIN");
        });
    }

    @Test
    @DisplayName("MEDICO recibe 403 en las seis rutas de usuario sin llegar al service")
    void rutasDeUsuario_shouldReturn403_whenActorIsMedico() throws Exception {
        afirmarTodasLasRutasDenegadas(TOKEN_MEDICO);
    }

    @Test
    @DisplayName("PACIENTE recibe 403 en las seis rutas de usuario sin llegar al service")
    void rutasDeUsuario_shouldReturn403_whenActorIsPaciente() throws Exception {
        afirmarTodasLasRutasDenegadas(TOKEN_PACIENTE);
    }

    @Test
    @DisplayName("RECEPCIONISTA recibe 403 en las seis rutas de usuario sin llegar al service")
    void rutasDeUsuario_shouldReturn403_whenTheTokenClaimsReceptionist() throws Exception {
        // El rol está en el contrato de claims, así que el token es válido: el rechazo es de matriz
        // (403 con el contrato de error), no de token (401). Recepción no tiene ninguna celda de
        // cuentas del personal.
        var tokenRecepcionista = ClaveRsaDePrueba.tokenPara("RECEPCIONISTA", TENANT_ID);

        afirmarTodasLasRutasDenegadas(tokenRecepcionista);
    }

    @Test
    @DisplayName("sin token las rutas de usuario responden 401 y no llegan al service")
    void rutasDeUsuario_shouldReturn401_whenThereIsNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/{id}", CUENTA_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_CREDENCIALES_BEARER));
        verifyNoInteractions(usuarioService);
    }

    /**
     * Afirma las seis celdas denegadas de un rol: cada operación responde {@code 403} con el
     * contrato de error de la cadena y el service no se invoca.
     *
     * @param token token RS256 del rol que se quiere ejercer
     * @throws Exception si una aserción de MockMvc falla
     */
    private void afirmarTodasLasRutasDenegadas(String token) throws Exception {
        afirmar403(conToken(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), token),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID), token),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(get("/api/v1/usuarios/{id}", CUENTA_ID), token),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(put("/api/v1/usuarios/{id}", CUENTA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), token),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), token),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(post("/api/v1/usuarios/{id}/activar", CUENTA_ID), token),
                "/api/v1/usuarios/" + CUENTA_ID + "/activar");
        verifyNoInteractions(usuarioService);
    }

    /**
     * Afirma que la petición responde 403 con el contrato de error único de la API.
     *
     * @param peticion petición ya autenticada
     * @param ruta     ruta real de la petición, tal como debe aparecer en el cuerpo
     * @throws Exception si MockMvc no puede ejecutarla
     */
    private void afirmar403(MockHttpServletRequestBuilder peticion, String ruta) throws Exception {
        mockMvc.perform(peticion)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(ruta));
    }

    /**
     * Representación de salida que devolvería el service.
     *
     * @param activo estado lógico de la cuenta
     * @return la respuesta esperada
     */
    private static UsuarioResponse respuesta(boolean activo) {
        return new UsuarioResponse(CUENTA_ID, TENANT_ID, null, EMAIL, RolUsuario.ADMIN, activo,
                OffsetDateTime.parse("2025-01-15T10:30:00-06:00"));
    }

    /**
     * Añade la cabecera {@code Authorization} con el token indicado.
     *
     * @param peticion petición a ejecutar
     * @param token    token RS256 de prueba
     * @return la petición lista para MockMvc
     */
    private static MockHttpServletRequestBuilder conToken(MockHttpServletRequestBuilder peticion,
            String token) {
        return peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /**
     * Publica la clave pública de prueba y los claims esperados para que el filtro JWT acepte los
     * tokens de esta clase.
     *
     * @param registro registro de propiedades dinámicas del contexto de prueba
     */
    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }
}
