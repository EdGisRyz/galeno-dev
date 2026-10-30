package com.intoglobe.clinicas.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

import com.intoglobe.clinicas.exception.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas directas del mapeo de denegaciones en {@code GlobalExceptionHandler} (R-SEG-05).
 *
 * <p>Estas excepciones son las que lanza una denegación producida <em>dentro</em> de un
 * controlador, que es el escenario de {@code @PreAuthorize} en T4. La prueba extrema a
 * extremo —petición HTTP real contra un controlador que la lanza— vive en
 * {@link ContratoDeErrorDeSeguridadTest}; esta fija la traducción en sí, sin contexto, para
 * que un cambio de estado o de mensaje se vea de inmediato y sin ruido de framework.</p>
 *
 * <p>El mensaje de la excepción nunca llega al cliente: se publica la constante compartida
 * con {@code RestAccessDeniedHandler}, así que un 403 del controlador y un 403 de la cadena
 * de filtros son indistinguibles.</p>
 */
class GlobalExceptionHandlerSeguridadTest {

    private static final String RUTA = "/api/v1/organizaciones/3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90";

    private final GlobalExceptionHandler manejador = new GlobalExceptionHandler();

    @Test
    @DisplayName("AccessDeniedException se traduce a 403 con ErrorResponse")
    void accessDeniedExceptionSeTraduceA403() {
        var peticion = new MockHttpServletRequest("PUT", RUTA);

        var respuesta = manejador.manejarAccesoDenegado(
                new AccessDeniedException("detalle interno que no debe publicarse"), peticion);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().codigo()).isEqualTo(403);
        assertThat(respuesta.getBody().error()).isEqualTo("Forbidden");
        assertThat(respuesta.getBody().mensaje()).isEqualTo(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO);
        assertThat(respuesta.getBody().ruta()).isEqualTo(RUTA);
        assertThat(respuesta.getBody().marcaTemporal()).isNotNull();
    }

    @Test
    @DisplayName("AuthorizationDeniedException se traduce al mismo 403 con el mismo cuerpo")
    void authorizationDeniedExceptionSeTraduceAlMismo403() {
        var peticion = new MockHttpServletRequest("DELETE", RUTA);

        var respuesta = manejador.manejarAccesoDenegado(
                new AuthorizationDeniedException("rol insuficiente"), peticion);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().codigo()).isEqualTo(403);
        assertThat(respuesta.getBody().error()).isEqualTo("Forbidden");
        assertThat(respuesta.getBody().mensaje()).isEqualTo(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO);
        assertThat(respuesta.getBody().ruta()).isEqualTo(RUTA);
    }
}
