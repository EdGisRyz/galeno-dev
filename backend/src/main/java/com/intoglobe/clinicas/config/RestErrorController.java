package com.intoglobe.clinicas.config;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intoglobe.clinicas.dto.response.ErrorResponse;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Endpoint de error de la aplicación, dentro del contrato {@link ErrorResponse}
 * (R-SEG-09).
 *
 * <p>Reemplaza al {@code BasicErrorController} de Spring Boot, que publicaba un cuerpo con
 * otra forma ({@code timestamp}, {@code status}, {@code path}) y, cuando nadie había
 * reenviado un error, respondía {@code 500} a un simple {@code GET /error}. Ese {@code 500}
 * era observable con un token válido: una ruta pública del framework inventando un error
 * interno que nunca ocurrió. La corrección son dos líneas de estado y el cuerpo que ya usa
 * toda la API.</p>
 *
 * <h2>Qué estado responde</h2>
 *
 * <p>Cuando el contenedor reenvía una petición fallida, el estado real viaja en el atributo
 * {@code jakarta.servlet.error.status_code}: se publica ese, tal cual. Un {@code 404} de
 * ruta inexistente sigue siendo {@code 404}, un {@code 405} sigue siendo {@code 405} y un
 * {@code 500} real sigue siendo {@code 500}.</p>
 *
 * <p>Cuando <em>nadie</em> reenvió nada —alguien pide {@code /error} directamente— no hay
 * error que representar y el estado es {@code 404}: {@code /error} no es un recurso de la
 * API. Es la alternativa barata a inventar un {@code 500} o a devolver un cuerpo vacío.</p>
 *
 * <h2>Qué no publica</h2>
 *
 * <p>El mensaje es una constante del contrato y no depende de la excepción: nunca viajan
 * {@code message}, {@code exception}, {@code trace} ni la ruta de clases al cliente, y el
 * log tampoco escribe la excepción (R-SEG-10, ADR-014). Una consecuencia deliberada es que
 * el cuerpo es siempre JSON: este backend es una API y no tiene vistas HTML de error.</p>
 *
 * <p>Se implementa {@link ErrorController} a propósito: es la marca que hace que Spring Boot
 * no registre su {@code BasicErrorController} y evita dos manejadores compitiendo por la
 * misma ruta.</p>
 */
@RestController
public class RestErrorController implements ErrorController {

    /** Mensaje del {@code 404}: seguro y sin detalles internos. */
    public static final String MENSAJE_SIN_RECURSO = "No se encontró el recurso solicitado";

    /** Mensaje genérico para el resto de los estados: no reexpone la causa. */
    public static final String MENSAJE_ERROR = "Se produjo un error al procesar la petición";

    private static final Logger REGISTRO = LoggerFactory.getLogger(RestErrorController.class);

    /**
     * Atiende el reenvío de error del contenedor y el acceso directo a la ruta de error.
     *
     * @param peticion petición que originó el error, con los atributos del reenvío
     * @return la respuesta con el estado real y el cuerpo {@link ErrorResponse}
     */
    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest peticion) {
        HttpStatus estado = estadoDe(peticion);
        String ruta = rutaOriginal(peticion);

        if (estado.is5xxServerError()) {
            // R-SEG-10: se registra el evento con método y ruta, nunca la excepción ni datos
            // de negocio, y nada de esto llega al cliente.
            REGISTRO.warn("Error {} en {} {}", estado.value(), peticion.getMethod(), ruta);
        }

        return ResponseEntity.status(estado)
                .body(new ErrorResponse(
                        OffsetDateTime.now(),
                        estado.value(),
                        estado.getReasonPhrase(),
                        estado == HttpStatus.NOT_FOUND ? MENSAJE_SIN_RECURSO : MENSAJE_ERROR,
                        ruta));
    }

    /**
     * Resuelve el estado HTTP de la respuesta.
     *
     * @param peticion petición con los atributos del reenvío de error
     * @return el estado del error reenviado o {@code 404} si no hubo reenvío
     */
    private static HttpStatus estadoDe(HttpServletRequest peticion) {
        Object atributo = peticion.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (atributo == null) {
            // Acceso directo a /error: no hay error que representar y la ruta no es un
            // recurso, así que 404 y no el 500 que respondía el controlador por defecto.
            return HttpStatus.NOT_FOUND;
        }
        try {
            HttpStatus estado = HttpStatus.resolve(Integer.parseInt(atributo.toString()));
            return estado == null ? HttpStatus.INTERNAL_SERVER_ERROR : estado;
        } catch (NumberFormatException excepcion) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    /**
     * Devuelve la ruta que originó el error, no la ruta del reenvío.
     *
     * @param peticion petición con los atributos del reenvío de error
     * @return la ruta original de la petición
     */
    private static String rutaOriginal(HttpServletRequest peticion) {
        Object original = peticion.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return original != null ? original.toString() : peticion.getRequestURI();
    }
}
