package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de R-SEG-03: la cadena de filtros está cerrada y todo endpoint exige un token
 * RS256 válido.
 *
 * <p>Cubre las quince operaciones HTTP que hoy expone la API (organización, consultorio y
 * médico) sin token: todas deben responder {@code 401} con el contrato de error real
 * ({@code ErrorResponse}) y sin reto {@code WWW-Authenticate: Basic}, porque el mecanismo
 * de esta API es Bearer y ofrecer Basic invita a un cliente a probar otro esquema.</p>
 *
 * <p>Las únicas rutas públicas son las de documentación; se comprueba que no quedan
 * bloqueadas. Con un token válido la cadena deja pasar la petición, que es lo que
 * demuestran aquí y en las pruebas MVC de cada módulo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CadenaDeFiltrosCerradaTest {

    private static final UUID ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");

    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";
    private static final String MENSAJE_TOKEN_INVALIDO = "El token no es válido o está expirado";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    /**
     * Las quince operaciones de la API, con una ruta concreta para poder afirmar el campo
     * {@code ruta} del contrato de error.
     *
     * @return método y ruta de cada operación expuesta
     */
    static Stream<Arguments> operacionesExpuestas() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/organizaciones"),
                Arguments.of("GET", "/api/v1/organizaciones"),
                Arguments.of("GET", "/api/v1/organizaciones/" + ID),
                Arguments.of("PUT", "/api/v1/organizaciones/" + ID),
                Arguments.of("DELETE", "/api/v1/organizaciones/" + ID),
                Arguments.of("POST", "/api/v1/organizaciones/" + ID + "/consultorios"),
                Arguments.of("GET", "/api/v1/organizaciones/" + ID + "/consultorios"),
                Arguments.of("GET", "/api/v1/consultorios/" + ID),
                Arguments.of("PUT", "/api/v1/consultorios/" + ID),
                Arguments.of("DELETE", "/api/v1/consultorios/" + ID),
                Arguments.of("POST", "/api/v1/organizaciones/" + ID + "/medicos"),
                Arguments.of("GET", "/api/v1/organizaciones/" + ID + "/medicos"),
                Arguments.of("GET", "/api/v1/medicos/" + ID),
                Arguments.of("PUT", "/api/v1/medicos/" + ID),
                Arguments.of("DELETE", "/api/v1/medicos/" + ID));
    }

    @ParameterizedTest(name = "{0} {1} sin token responde 401 con ErrorResponse")
    @MethodSource("operacionesExpuestas")
    void sinTokenTodaOperacionResponde401ConElContrato(String metodo, String ruta) throws Exception {
        MvcResult resultado = mockMvc
                .perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(metodo), ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(ruta))
                .andExpect(jsonPath("$.marcaTemporal").exists())
                .andReturn();

        assertThat(RetosDeAutenticacion.ofreceRetoBasic(resultado.getResponse()))
                .as("un 401 de esta API no debe ofrecer el reto Basic")
                .isFalse();
    }

    @Test
    @DisplayName("Una ruta inexistente sin token responde 401, no 404: la autenticación va antes del enrutado")
    void unaRutaInexistenteSinTokenResponde401No404() throws Exception {
        // El cambio de comportamiento es deliberado: un anónimo no puede distinguir qué rutas
        // existen de las que no, porque la cadena corta antes del enrutado.
        String rutaInexistente = "/api/v1/esta-ruta-no-existe";

        mockMvc.perform(get(rutaInexistente))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(rutaInexistente))
                .andExpect(jsonPath("$.marcaTemporal").exists());

        // Con token válido sí se llega al enrutado y la ruta inexistente es un 404.
        mockMvc.perform(get(rutaInexistente)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ClaveRsaDePrueba.token().compactar()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Un token inválido tampoco autentica: 401 con el mensaje de token inválido")
    void tokenInvalidoResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().firmadoConOtraClavePrivada().compactar();

        mockMvc.perform(get("/api/v1/organizaciones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_TOKEN_INVALIDO))
                .andExpect(jsonPath("$.ruta").value("/api/v1/organizaciones"));
    }

    @Test
    @DisplayName("Con un token RS256 válido la cadena deja pasar la petición")
    void tokenValidoSuperaLaCadena() throws Exception {
        String token = ClaveRsaDePrueba.token().compactar();

        mockMvc.perform(get("/api/v1/organizaciones")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("La documentación de la API sigue siendo pública y no responde 401")
    void laDocumentacionSigueSiendoPublica() throws Exception {
        for (String ruta : List.of("/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html")) {
            int estado = mockMvc.perform(get(ruta)).andReturn().getResponse().getStatus();
            assertThat(estado)
                    .as("la ruta de documentación %s no puede quedar detrás de la autenticación", ruta)
                    .isNotIn(401, 403);
        }
    }
}
