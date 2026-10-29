package com.intoglobe.clinicas.security;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.intoglobe.clinicas.BackendApplication;
import com.intoglobe.clinicas.config.CorsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de R-SEG-13: CORS explícito con credenciales, orígenes tomados de configuración
 * y origen comodín imposible.
 *
 * <p>El preflight se prueba a través de la cadena de filtros real: el {@code CorsFilter}
 * de Spring Security corre antes de la autorización, así que un preflight desde el origen
 * permitido no necesita token, y uno desde un origen ajeno se corta antes de llegar a
 * ningún controlador. También se prueba el arranque: un valor con {@code *} no puede
 * levantar la aplicación, porque el comodín con {@code allowCredentials=true} permitiría a
 * cualquier sitio leer respuestas autenticadas.</p>
 */
@SpringBootTest(properties = "app.cors.allowed-origins=" + CorsDeSeguridadTest.ORIGEN_PERMITIDO)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsDeSeguridadTest {

    static final String ORIGEN_PERMITIDO = "http://localhost:5173";

    private static final String ORIGEN_AJENO = "https://sitio-ajeno.test";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("El preflight desde el origen permitido devuelve CORS con credenciales y sin token")
    void preflightDesdeElOrigenPermitidoDevuelveCorsConCredenciales() throws Exception {
        mockMvc.perform(options("/api/v1/organizaciones")
                        .header(HttpHeaders.ORIGIN, ORIGEN_PERMITIDO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEN_PERMITIDO))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        // Spring devuelve los nombres tal como los pidió el navegador.
                        org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
    }

    @Test
    @DisplayName("El preflight no exige token, ni siquiera para un método de escritura")
    void elPreflightNoExigeTokenNiParaMetodosDeEscritura() throws Exception {
        // El navegador manda el preflight antes de la petición real y sin cabecera
        // Authorization: si el preflight exigiera token, ninguna llamada autenticada con
        // credenciales podría salir del origen permitido.
        mockMvc.perform(options("/api/v1/medicos/1c7d9a11-4b2e-4c7d-9a11-77aa3e2d1a55")
                        .header(HttpHeaders.ORIGIN, ORIGEN_PERMITIDO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.DELETE.name()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEN_PERMITIDO))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("DELETE")));
    }

    @Test
    @DisplayName("El preflight desde un origen ajeno no obtiene Access-Control-Allow-Origin")
    void preflightDesdeUnOrigenAjenoNoObtieneAccessControlAllowOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/organizaciones")
                        .header(HttpHeaders.ORIGIN, ORIGEN_AJENO)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("Sin la propiedad configurada el origen de desarrollo por defecto es http://localhost:5173")
    void sinPropiedadElOrigenPorDefectoEsElDelFrontendDeDesarrollo() {
        try (var contexto = new AnnotationConfigApplicationContext()) {
            contexto.register(CorsConfig.class);
            contexto.refresh();

            CorsConfiguration configuracion = contexto.getBean(CorsConfigurationSource.class)
                    .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/organizaciones"));

            assertThat(configuracion).isNotNull();
            assertThat(configuracion.getAllowedOrigins()).containsExactly(ORIGEN_PERMITIDO);
            assertThat(configuracion.getAllowCredentials()).isTrue();
            assertThat(configuracion.getAllowedMethods())
                    .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
            assertThat(configuracion.getAllowedHeaders())
                    .containsExactlyInAnyOrder("Authorization", "Content-Type");
            assertThat(configuracion.getMaxAge()).isNotNull();
        }
    }

    @Test
    @DisplayName("Una lista separada por comas se acepta y se recorta")
    void unaListaSeparadaPorComasSeAceptaYSeRecorta() {
        try (var contexto = contextoConOrigenes(" http://localhost:5173 , https://app.intoglobe.test ")) {
            CorsConfiguration configuracion = contexto.getBean(CorsConfigurationSource.class)
                    .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/organizaciones"));

            assertThat(configuracion).isNotNull();
            assertThat(configuracion.getAllowedOrigins())
                    .containsExactly("http://localhost:5173", "https://app.intoglobe.test");
        }
    }

    @Test
    @DisplayName("Un origen comodín no arranca: es incompatible con credenciales")
    void unOrigenComodinNoArranca() {
        for (String comodin : List.of("*", "http://*.intoglobe.test")) {
            try (var contexto = new AnnotationConfigApplicationContext()) {
                contexto.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("prueba", java.util.Map.of("app.cors.allowed-origins", comodin)));
                contexto.register(CorsConfig.class);

                assertThatThrownBy(contexto::refresh)
                        .as("el origen comodín %s no puede levantar el contexto", comodin)
                        .hasRootCauseMessage(CorsConfig.MENSAJE_ORIGEN_COMODIN);
            }
        }
    }

    @Test
    @DisplayName("La aplicación real con app.cors.allowed-origins=* aborta el arranque")
    void laAplicacionRealConComodinAbortaElArranque() {
        assertThatThrownBy(() -> {
            try (var aplicacion = new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("test")
                    .run("--server.port=0", "--app.cors.allowed-origins=*")) {
                assertThat(aplicacion).isNotNull();
            }
        })
                .as("el comodín CORS es un error de seguridad, no un valor admisible")
                .hasRootCauseMessage(CorsConfig.MENSAJE_ORIGEN_COMODIN);
    }

    @Test
    @DisplayName("Una lista de orígenes vacía tampoco arranca: no hay nada que autorizar")
    void unaListaVaciaNoArranca() {
        assertThatThrownBy(() -> contextoConOrigenes(" , "))
                .hasRootCauseMessage(CorsConfig.MENSAJE_SIN_ORIGENES);
    }

    @Test
    @DisplayName("Los mensajes de CORS nombran la propiedad que hay que corregir")
    void losMensajesDeCorsNombranLaPropiedad() {
        assertThat(CorsConfig.MENSAJE_ORIGEN_COMODIN)
                .contains(CorsConfig.PROPIEDAD_ORIGENES)
                .contains("allowCredentials");
        assertThat(CorsConfig.MENSAJE_SIN_ORIGENES)
                .contains(CorsConfig.PROPIEDAD_ORIGENES);
        assertThat(CorsConfig.PROPIEDAD_ORIGENES).isEqualTo("app.cors.allowed-origins");
    }

    private static AnnotationConfigApplicationContext contextoConOrigenes(String origenes) {
        var contexto = new AnnotationConfigApplicationContext();
        contexto.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("prueba", java.util.Map.of("app.cors.allowed-origins", origenes)));
        contexto.register(CorsConfig.class);
        contexto.refresh();
        return contexto;
    }
}
