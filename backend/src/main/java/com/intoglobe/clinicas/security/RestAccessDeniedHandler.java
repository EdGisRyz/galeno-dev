package com.intoglobe.clinicas.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Responde {@code 403} con el contrato de error de la API cuando el usuario está
 * autenticado pero no autorizado (R-SEG-03 y R-SEG-05).
 *
 * <p>Cubre la denegación que ocurre <em>fuera</em> del controlador, es decir dentro de la
 * cadena de filtros: por ejemplo, una regla de {@code authorizeHttpRequests} que exija un
 * rol. La denegación que se lanza dentro de un controlador —el caso de
 * {@code @PreAuthorize}, que llega en T4— la traduce
 * {@link com.intoglobe.clinicas.exception.GlobalExceptionHandler} al mismo cuerpo; sin ese
 * mapeo Spring respondería con el formato por defecto del framework y rompería el contrato
 * único de error (ADR-013).</p>
 *
 * <p>Registra el evento de autorización con actor, ruta y resultado, sin token ni datos
 * clínicos (R-SEG-10, ADR-014).</p>
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger REGISTRO = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final JsonMapper jsonMapper;

    /**
     * Crea el manejador de denegaciones.
     *
     * @param jsonMapper serializador con el que se escribe el {@code ErrorResponse}
     */
    public RestAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest peticion, HttpServletResponse respuesta,
            AccessDeniedException excepcion) throws IOException {
        REGISTRO.warn("Acceso denegado en {} {} para {}: {}",
                peticion.getMethod(), peticion.getRequestURI(), actor(),
                excepcion.getClass().getSimpleName());

        SecurityErrorResponder.escribir(peticion, respuesta, HttpStatus.FORBIDDEN,
                SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO, jsonMapper);
    }

    /**
     * Identifica al actor para el registro de auditoría sin exponer el token.
     *
     * @return identificador y rol del usuario autenticado, o {@code anónimo}
     */
    private static String actor() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion != null && autenticacion.getPrincipal() instanceof AuthenticatedUser usuario) {
            return usuario.id() + " (" + usuario.role() + ")";
        }
        return "anónimo";
    }
}
