package com.intoglobe.clinicas.security;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del contrato único de error para {@code 401} y {@code 403}.
 *
 * <p>Afirma tres cosas que el proyecto exige por separado:</p>
 * <ol>
 *   <li>El {@code 401} del {@code AuthenticationEntryPoint} (petición sin credenciales)
 *       tiene la misma forma y las mismas convenciones de mensaje que el {@code 401} del
 *       filtro JWT (token inválido): mismos cinco campos de {@code ErrorResponse}, mismos
 *       códigos y sin reto {@code Basic}.</li>
 *   <li>Una denegación lanzada <em>dentro</em> de un controlador —el escenario de
 *       {@code @PreAuthorize} de T4— sale como {@code 403} con el mismo cuerpo, gracias al
 *       mapeo de {@code AccessDeniedException} y {@code AuthorizationDeniedException} en
 *       {@code GlobalExceptionHandler}. Sin ese mapeo Spring responde 403 con el cuerpo
 *       por defecto del framework y rompe el contrato.</li>
 *   <li>La denegación no expone el token recibido.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ContratoDeErrorDeSeguridadTest.ConfiguracionSonda.class)
class ContratoDeErrorDeSeguridadTest {

    private static final String RUTA_DENEGADA = "/api/v1/sonda/denegado";
    private static final String RUTA_DENEGADA_POR_AUTORIZACION = "/api/v1/sonda/denegado-por-autorizacion";
    private static final String RUTA_PROTEGIDA = "/api/v1/organizaciones";

    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";
    private static final String MENSAJE_TOKEN_INVALIDO = "El token no es válido o está expirado";
    private static final String MENSAJE_ACCESO_DENEGADO = "No tiene permisos para acceder a este recurso";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @Test
    @DisplayName("Sin credenciales el entry point responde 401 con ErrorResponse y sin reto Basic")
    void sinCredencialesElEntryPointResponde401ConElContrato() throws Exception {
        MvcResult resultado = mockMvc.perform(get(RUTA_PROTEGIDA))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(RUTA_PROTEGIDA))
                .andExpect(jsonPath("$.marcaTemporal").exists())
                .andReturn();

        assertThat(RetosDeAutenticacion.ofreceRetoBasic(resultado.getResponse()))
                .as("un 401 de esta API no debe ofrecer el reto Basic")
                .isFalse();
    }

    @Test
    @DisplayName("El 401 del filtro y el del entry point comparten forma y convención de mensaje")
    void losDosOrigenesDel401CompartenFormaYMensaje() throws Exception {
        String token = ClaveRsaDePrueba.token().firmadoConOtraClavePrivada().compactar();

        MvcResult delEntryPoint = mockMvc.perform(get(RUTA_PROTEGIDA)).andReturn();
        MvcResult delFiltro = mockMvc.perform(get(RUTA_PROTEGIDA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(delEntryPoint.getResponse().getStatus()).isEqualTo(401);
        assertThat(delFiltro.getResponse().getStatus()).isEqualTo(401);

        String cuerpoEntryPoint = delEntryPoint.getResponse().getContentAsString();
        String cuerpoFiltro = delFiltro.getResponse().getContentAsString();

        assertThat(delEntryPoint.getResponse().getContentType())
                .isEqualTo(delFiltro.getResponse().getContentType());
        assertThat(nombresDeCampo(cuerpoEntryPoint))
                .as("los dos 401 publican exactamente los mismos campos de ErrorResponse")
                .containsExactlyElementsOf(nombresDeCampo(cuerpoFiltro));
        assertThat(cuerpoEntryPoint).contains("\"codigo\":401", "\"error\":\"Unauthorized\"");
        assertThat(cuerpoFiltro).contains("\"codigo\":401", "\"error\":\"Unauthorized\"");
        assertThat(cuerpoEntryPoint).contains(MENSAJE_CREDENCIALES_BEARER);
        assertThat(cuerpoFiltro).contains(MENSAJE_TOKEN_INVALIDO);
        assertThat(cuerpoFiltro).doesNotContain(token);
    }

    @Test
    @DisplayName("Una AccessDeniedException dentro de un controlador sale 403 con ErrorResponse")
    void accessDeniedDentroDeUnControladorSale403ConElContrato() throws Exception {
        mockMvc.perform(get(RUTA_DENEGADA).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_DENEGADA))
                .andExpect(jsonPath("$.marcaTemporal").exists());
    }

    @Test
    @DisplayName("Una AuthorizationDeniedException dentro de un controlador sale 403 con ErrorResponse")
    void authorizationDeniedDentroDeUnControladorSale403ConElContrato() throws Exception {
        mockMvc.perform(get(RUTA_DENEGADA_POR_AUTORIZACION)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_DENEGADA_POR_AUTORIZACION))
                .andExpect(jsonPath("$.marcaTemporal").exists());
    }

    private static String tokenValido() {
        return ClaveRsaDePrueba.token().compactar();
    }

    /**
     * Nombres de los campos del cuerpo JSON, en orden de aparición.
     *
     * @param cuerpo JSON de la respuesta
     * @return nombres de campo detectados
     */
    private static List<String> nombresDeCampo(String cuerpo) {
        return java.util.regex.Pattern.compile("\"([a-zA-Z]+)\"\\s*:")
                .matcher(cuerpo)
                .results()
                .map(coincidencia -> coincidencia.group(1))
                .toList();
    }

    /**
     * Sondas que lanzan las excepciones de autorización dentro de un controlador: es el
     * punto exacto donde T4 aplicará {@code @PreAuthorize}.
     */
    @TestConfiguration
    static class ConfiguracionSonda {

        @Bean
        ControladorSonda controladorSonda() {
            return new ControladorSonda();
        }
    }

    @RestController
    static class ControladorSonda {

        @GetMapping(RUTA_DENEGADA)
        String denegado() {
            throw new AccessDeniedException("denegado por prueba");
        }

        @GetMapping(RUTA_DENEGADA_POR_AUTORIZACION)
        String denegadoPorAutorizacion() {
            throw new AuthorizationDeniedException("denegado por prueba de autorización");
        }
    }
}
