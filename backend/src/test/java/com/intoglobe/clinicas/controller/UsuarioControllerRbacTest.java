package com.intoglobe.clinicas.controller;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.security.ClaveRsaDePrueba;
import com.intoglobe.clinicas.security.SecurityConfig;
import com.intoglobe.clinicas.security.SecurityErrorResponder;
import com.intoglobe.clinicas.service.UsuarioService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Estado de autorización de las seis rutas de usuario en la cadena de filtros real.
 *
 * <p><strong>Qué fija esta clase y por qué existe.</strong> El módulo entrega su controlador antes de
 * que la matriz de autorización tenga sus celdas: {@code SecurityConfig} termina en
 * {@code DenyByDefaultAuthorizationManager}, así que una ruta con controlador y sin regla explícita
 * queda cerrada para todos los roles. Esta clase es la evidencia de ese estado —las seis rutas
 * responden {@code 403} con el contrato de error habitual y el service no se invoca, con token
 * {@code ADMIN}, {@code MEDICO} o {@code PACIENTE}—, y por eso mismo es la que cambiará cuando la
 * rebanada siguiente agregue las seis filas a la matriz: en ese momento estas pruebas dejarán de
 * pasar y pedirán las celdas por rol.</p>
 *
 * <p>Se importa {@link SecurityConfig} explícitamente para ejercer la cadena de producción completa:
 * filtro JWT, entrada de autenticación, manejador de denegación y fallback. Las peticiones se
 * autentican con tokens RS256 de verdad, firmados con el par que {@link ClaveRsaDePrueba} genera en
 * memoria. No se usan usuarios simulados ni post-procesadores de seguridad: si la cadena dejara de
 * estar cerrada, la prueba fallaría con el código del endpoint en lugar de {@code 403}.</p>
 *
 * <p>El contrato de éxito de las seis operaciones —201 con {@code Location}, 200, 204 y cada cuerpo de
 * error— se fija en {@code UsuarioControllerTest} con el controlador montado sin la cadena, porque hoy
 * ninguna petición autenticada llega a los métodos. Es el mismo reparto que usa el módulo de
 * organizaciones para su alta bloqueada.</p>
 */
@WebMvcTest(UsuarioController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class UsuarioControllerRbacTest {

    private static final UUID TENANT_ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID CUENTA_ID = UUID.fromString("b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13");

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
    @DisplayName("ADMIN recibe 403 en las seis rutas mientras la matriz no tenga sus celdas")
    void rutasDeUsuario_shouldReturn403_whenActorIsAdminAndTheMatrixHasNoCellsYet() throws Exception {
        afirmar403(conToken(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_ADMIN),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID), TOKEN_ADMIN),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(get("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_ADMIN),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(put("/api/v1/usuarios/{id}", CUENTA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_ADMIN),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_ADMIN),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(post("/api/v1/usuarios/{id}/activar", CUENTA_ID), TOKEN_ADMIN),
                "/api/v1/usuarios/" + CUENTA_ID + "/activar");
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("MEDICO recibe 403 en las seis rutas de usuario")
    void rutasDeUsuario_shouldReturn403_whenActorIsMedico() throws Exception {
        afirmar403(conToken(get("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID), TOKEN_MEDICO),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(get("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_MEDICO),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(post("/api/v1/organizaciones/{organizacionId}/usuarios", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_MEDICO),
                "/api/v1/organizaciones/" + TENANT_ID + "/usuarios");
        afirmar403(conToken(put("/api/v1/usuarios/{id}", CUENTA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO), TOKEN_MEDICO),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_MEDICO),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(post("/api/v1/usuarios/{id}/activar", CUENTA_ID), TOKEN_MEDICO),
                "/api/v1/usuarios/" + CUENTA_ID + "/activar");
        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("PACIENTE recibe 403 en las seis rutas de usuario")
    void rutasDeUsuario_shouldReturn403_whenActorIsPaciente() throws Exception {
        afirmar403(conToken(get("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_PACIENTE),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(delete("/api/v1/usuarios/{id}", CUENTA_ID), TOKEN_PACIENTE),
                "/api/v1/usuarios/" + CUENTA_ID);
        afirmar403(conToken(post("/api/v1/usuarios/{id}/activar", CUENTA_ID), TOKEN_PACIENTE),
                "/api/v1/usuarios/" + CUENTA_ID + "/activar");
        verifyNoInteractions(usuarioService);
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
