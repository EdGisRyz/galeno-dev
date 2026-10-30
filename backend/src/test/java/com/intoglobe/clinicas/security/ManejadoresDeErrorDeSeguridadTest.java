package com.intoglobe.clinicas.security;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas directas del {@code 401} y del {@code 403} de la cadena de filtros.
 *
 * <p>Se invocan el entry point y el manejador de denegaciones con una petición y una
 * respuesta simuladas, sin arrancar el contexto: lo que se afirma es el contrato de
 * respuesta —estado, tipo de contenido, campos de {@code ErrorResponse}, ausencia de reto
 * {@code Basic} y ausencia del token en el cuerpo— que es exactamente lo que el cliente ve
 * cuando la cadena de filtros corta una petición.</p>
 */
class ManejadoresDeErrorDeSeguridadTest {

    private static final String RUTA = "/api/v1/medicos/1c7d9a11-4b2e-4c7d-9a11-77aa3e2d1a55";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("El entry point responde 401 con ErrorResponse, sin reto Basic y sin cuerpo vacío")
    void elEntryPointResponde401ConElContrato() throws Exception {
        var peticion = new MockHttpServletRequest("GET", RUTA);
        var respuesta = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(jsonMapper)
                .commence(peticion, respuesta, new InsufficientAuthenticationException("sin credenciales"));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(respuesta.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(respuesta.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("la API no admite Basic: no debe anunciarlo")
                .isNull();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(respuesta)).isFalse();
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":401")
                .contains("\"error\":\"Unauthorized\"")
                .contains(SecurityErrorResponder.MENSAJE_CREDENCIALES_BEARER)
                .contains("\"ruta\":\"" + RUTA + "\"")
                .contains("\"marcaTemporal\":");
    }

    @Test
    @DisplayName("El manejador de denegaciones responde 403 con ErrorResponse y sin el token")
    void elManejadorDeDenegacionesResponde403ConElContrato() throws Exception {
        var peticion = new MockHttpServletRequest("DELETE", RUTA);
        peticion.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token.que.no.debe.aparecer");
        var respuesta = new MockHttpServletResponse();

        new RestAccessDeniedHandler(jsonMapper)
                .handle(peticion, respuesta, new AccessDeniedException("rol insuficiente"));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(respuesta.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":403")
                .contains("\"error\":\"Forbidden\"")
                .contains(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO)
                .contains("\"ruta\":\"" + RUTA + "\"")
                .contains("\"marcaTemporal\":")
                .doesNotContain("token.que.no.debe.aparecer");
    }

    @Test
    @DisplayName("El detector de reto Basic reconoce un reto Basic real: la aserción no es vacía")
    void elDetectorDeRetoBasicReconoceUnRetoReal() throws Exception {
        // Control positivo con la implementación de Spring Security: si el detector no
        // marcara esto, la aserción "no ofrece Basic" de las pruebas de contrato no probaría
        // nada. Es la razón por la que no basta con afirmar que la cabecera no existe.
        var peticion = new MockHttpServletRequest("GET", RUTA);
        var respuesta = new MockHttpServletResponse();

        new BasicAuthenticationEntryPoint().commence(
                peticion, respuesta, new InsufficientAuthenticationException("reto Basic"));

        assertThat(respuesta.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNotNull();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(respuesta)).isTrue();
    }

    @Test
    @DisplayName("El detector no confunde un reto Bearer con Basic")
    void elDetectorNoConfundeBearerConBasic() {
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(List.of("Bearer realm=\"galeno\""))).isFalse();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(List.of("Bearer realm=\"Basic zone\""))).isFalse();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(List.<String>of())).isFalse();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic((List<String>) null)).isFalse();
        assertThat(RetosDeAutenticacion.ofreceRetoBasic(List.of("Bearer realm=\"x\", Basic realm=\"y\"")))
                .isTrue();
    }
}
