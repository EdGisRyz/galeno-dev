package com.intoglobe.clinicas.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.intoglobe.clinicas.dto.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Único constructor del cuerpo de error de seguridad ({@code 401} y {@code 403}).
 *
 * <p>Existe porque el mismo {@code ErrorResponse} lo producen tres puntos distintos: el
 * filtro JWT cuando el token no verifica, el {@code AuthenticationEntryPoint} cuando la
 * petición llega sin credenciales y el {@code AccessDeniedHandler} cuando falta
 * autorización. Si cada uno armara su propio cuerpo, el contrato de error se rompería en
 * cuanto uno de los tres cambiara.</p>
 *
 * <p>Los mensajes son constantes públicas a propósito: las pruebas de contrato afirman
 * exactamente estas cadenas y un cambio accidental de redacción debe romperlas.</p>
 *
 * <p>Ninguno de estos mensajes incluye el token recibido, sus claims ni datos de negocio
 * (R-SEG-10): el {@code 401} no reexpone la credencial rechazada.</p>
 */
public final class SecurityErrorResponder {

    /** Mensaje del {@code 401} cuando la petición no trae credenciales Bearer. */
    public static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";

    /** Mensaje del {@code 401} cuando el token no verifica contra la clave pública. */
    public static final String MENSAJE_TOKEN_INVALIDO = "El token no es válido o está expirado";

    /** Mensaje del {@code 403} cuando el usuario está autenticado pero no autorizado. */
    public static final String MENSAJE_ACCESO_DENEGADO = "No tiene permisos para acceder a este recurso";

    private SecurityErrorResponder() {
    }

    /**
     * Construye el cuerpo de error de seguridad.
     *
     * @param estado  estado HTTP de la respuesta
     * @param mensaje mensaje seguro para el cliente
     * @param peticion petición que originó el error (de ella se toma la ruta real)
     * @return cuerpo listo para serializar
     */
    public static ErrorResponse cuerpo(HttpStatus estado, String mensaje, HttpServletRequest peticion) {
        return new ErrorResponse(
                OffsetDateTime.now(),
                estado.value(),
                estado.getReasonPhrase(),
                mensaje,
                peticion.getRequestURI());
    }

    /**
     * Escribe la respuesta de error de seguridad completa, con el mismo serializador que
     * usa el resto de la API.
     *
     * @param peticion   petición que originó el error
     * @param respuesta  respuesta a escribir
     * @param estado     estado HTTP de la respuesta
     * @param mensaje    mensaje seguro para el cliente
     * @param jsonMapper serializador configurado por la aplicación
     * @throws IOException si el cuerpo no puede escribirse
     */
    public static void escribir(HttpServletRequest peticion, HttpServletResponse respuesta,
            HttpStatus estado, String mensaje, JsonMapper jsonMapper) throws IOException {
        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getOutputStream().write(jsonMapper.writeValueAsBytes(cuerpo(estado, mensaje, peticion)));
    }
}
