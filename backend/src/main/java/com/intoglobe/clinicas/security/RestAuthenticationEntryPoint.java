package com.intoglobe.clinicas.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Responde {@code 401} con el contrato de error de la API cuando una petición llega sin
 * credenciales válidas (R-SEG-03).
 *
 * <p>Reemplaza al {@code BasicAuthenticationEntryPoint} que Spring Security instala por
 * defecto. Ese entry point tiene dos problemas para esta API: publica el reto
 * {@code WWW-Authenticate: Basic} —un esquema que este backend no admite, porque no tiene
 * contraseñas ni {@code UserDetailsService}— y responde con un cuerpo vacío, fuera del
 * contrato {@code ErrorResponse}.</p>
 *
 * <p>El cuerpo y el mensaje se construyen con {@link SecurityErrorResponder}, el mismo
 * constructor que usa {@link JwtAuthenticationFilter} para su {@code 401}: ambos caminos
 * publican exactamente la misma forma.</p>
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger REGISTRO = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final JsonMapper jsonMapper;

    /**
     * Crea el entry point.
     *
     * @param jsonMapper serializador con el que se escribe el {@code ErrorResponse}
     */
    public RestAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest peticion, HttpServletResponse respuesta,
            AuthenticationException excepcion) throws IOException {
        // R-SEG-10: se registra el evento con método y ruta, nunca el token.
        REGISTRO.warn("Autenticación requerida en {} {}: {}",
                peticion.getMethod(), peticion.getRequestURI(), excepcion.getClass().getSimpleName());

        SecurityErrorResponder.escribir(peticion, respuesta, HttpStatus.UNAUTHORIZED,
                SecurityErrorResponder.MENSAJE_CREDENCIALES_BEARER, jsonMapper);
    }
}
