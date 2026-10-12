package com.intoglobe.clinicas.exception;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.intoglobe.clinicas.dto.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Traduce las excepciones conocidas de la API a {@link ErrorResponse}.
 *
 * <p>Es el único lugar donde se construye el cuerpo de error de la plataforma.
 * Los mensajes son resúmenes de negocio en español y nunca incluyen stacktraces
 * ni detalles internos del parser de JSON, para no filtrar información de
 * implementación al cliente.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<String, String> MENSAJES_POR_CODIGO = Map.of(
            "NotBlank", "no puede estar vacío",
            "NotNull", "es obligatorio",
            "Size", "tiene una longitud fuera del rango permitido");

    private static final String MENSAJE_CUERPO_INVALIDO =
            "El cuerpo de la petición no es válido o está mal formado";

    /**
     * Responde 404 cuando la organización solicitada no existe.
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 404 con {@link ErrorResponse}
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> manejarNoEncontrado(
            ResourceNotFoundException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.NOT_FOUND, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 400 con un resumen en español de los campos inválidos.
     *
     * @param excepcion excepción con el resultado del Bean Validation
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> manejarValidacion(
            MethodArgumentNotValidException excepcion, HttpServletRequest peticion) {
        String resumen = excepcion.getBindingResult().getFieldErrors().stream()
                .map(error -> "El campo " + error.getField() + " "
                        + MENSAJES_POR_CODIGO.getOrDefault(error.getCode(), "es inválido"))
                .distinct()
                .collect(Collectors.joining("; "));
        if (resumen.isBlank()) {
            resumen = "La petición contiene datos inválidos";
        }
        return construir(HttpStatus.BAD_REQUEST, resumen, peticion);
    }

    /**
     * Responde 400 cuando el cuerpo no puede leerse: JSON mal formado, enum o
     * fecha con formato inválido.
     *
     * @param excepcion excepción de lectura del cuerpo (no se expone su detalle)
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> manejarCuerpoIlegible(
            HttpMessageNotReadableException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, MENSAJE_CUERPO_INVALIDO, peticion);
    }

    /**
     * Responde 400 cuando un parámetro de path no se puede convertir al tipo
     * esperado, por ejemplo un UUID inválido.
     *
     * @param excepcion excepción con el nombre del parámetro inválido
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> manejarTipoInvalido(
            MethodArgumentTypeMismatchException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST,
                "El parámetro '" + excepcion.getName() + "' tiene un formato inválido", peticion);
    }

    /**
     * Construye la respuesta de error uniforme a partir del estado HTTP.
     *
     * @param estado  estado HTTP de la respuesta
     * @param mensaje mensaje seguro para el cliente
     * @param peticion petición que originó el error (de ella se toma la ruta real)
     * @return respuesta con el cuerpo {@link ErrorResponse}
     */
    private static ResponseEntity<ErrorResponse> construir(
            HttpStatus estado, String mensaje, HttpServletRequest peticion) {
        var cuerpo = new ErrorResponse(
                OffsetDateTime.now(),
                estado.value(),
                estado.getReasonPhrase(),
                mensaje,
                peticion.getRequestURI());
        return ResponseEntity.status(estado).body(cuerpo);
    }
}
