package com.intoglobe.clinicas.exception;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.security.SecurityErrorResponder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Traduce las excepciones conocidas de la API a {@link ErrorResponse}.
 *
 * <p>Es el único lugar donde se construye el cuerpo de error de la plataforma.
 * Los mensajes son resúmenes de negocio en español y nunca incluyen stacktraces
 * ni detalles internos del parser de JSON, para no filtrar información de
 * implementación al cliente.</p>
 *
 * <p>También traduce las excepciones de autorización de Spring Security
 * ({@link AccessDeniedException} y {@link AuthorizationDeniedException}) a {@code 403} con
 * este mismo cuerpo (R-SEG-05, ADR-013). Son las que lanza una denegación producida
 * <em>dentro</em> de un controlador, que es el caso de {@code @PreAuthorize} en T4. Sin este
 * mapeo Spring respondería con su formato por defecto y rompería el contrato único de
 * error, mientras que la denegación producida en la cadena de filtros la responde
 * {@code RestAccessDeniedHandler} con el mismo cuerpo.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger REGISTRO = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, String> MENSAJES_POR_CODIGO = Map.of(
            "NotBlank", "no puede estar vacío",
            "NotNull", "es obligatorio",
            "Size", "tiene una longitud fuera del rango permitido");

    private static final String MENSAJE_CUERPO_INVALIDO =
            "El cuerpo de la petición no es válido o está mal formado";

    /**
     * Restricción de unicidad del correo dentro de la organización
     * ({@code usuario_organizacion_id_email_key}, migración V2). Su nombre es único en el esquema y
     * por eso sirve para saber si un error de integridad es el correo repetido o cualquier otra cosa.
     */
    private static final String RESTRICCION_CORREO_UNICO_POR_ORGANIZACION =
            "usuario_organizacion_id_email_key";

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
     * Responde 403 cuando una cuenta intenta darse de baja o cambiarse el rol a sí misma.
     *
     * <p>Es la regla R11. No se traduce como {@code AccessDeniedException} porque esa respuesta usa
     * el mensaje genérico de seguridad, y aquí el motivo debe llegar al cliente: es la única forma de
     * que entienda que la operación debe hacerla otra cuenta.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 403 con {@link ErrorResponse}
     */
    @ExceptionHandler(OperacionSobreLaPropiaCuentaException.class)
    public ResponseEntity<ErrorResponse> manejarOperacionSobreLaPropiaCuenta(
            OperacionSobreLaPropiaCuentaException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.FORBIDDEN, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 409 cuando el correo ya está ocupado dentro de la organización.
     *
     * <p>El conflicto no es de sintaxis: la petición está bien formada y lo que choca es el
     * estado actual de la organización. Por eso 409 y no 400. El mensaje viene de la excepción
     * porque explica <em>qué</em> correo está ocupado y por qué el mismo correo sí puede existir
     * en otra organización; se reusa el constructor de respuesta del resto del traductor.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse}
     */
    @ExceptionHandler(CorreoDuplicadoEnOrganizacionException.class)
    public ResponseEntity<ErrorResponse> manejarCorreoDuplicado(
            CorreoDuplicadoEnOrganizacionException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.CONFLICT, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 409 cuando la operación dejaría a la organización sin ninguna cuenta
     * administradora activa.
     *
     * <p>Es la regla R8. Igual que el correo repetido, es un conflicto con el estado actual y no
     * una petición mal formada, y el mensaje propio es parte de la regla: el administrador
     * necesita saber que se trata de la última administradora y que debe nombrar otra antes de
     * continuar.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse}
     */
    @ExceptionHandler(UltimaAdministradoraException.class)
    public ResponseEntity<ErrorResponse> manejarUltimaAdministradora(
            UltimaAdministradoraException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.CONFLICT, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 409 cuando la operación intenta retirar de un médico un consultorio que no se puede
     * retirar.
     *
     * <p>Cubre las dos reglas de la asignación de consultorios: el consultorio principal del médico
     * no se retira, y un consultorio que todavía tiene bloques de horario suyos tampoco —la clave
     * {@code horario_medico_consultorio_asignado_fkey} de la migración V6 lo impediría con un error
     * de integridad, que llegaría al cliente como 500—. Como en los demás conflictos, el mensaje es
     * parte de la regla: dice el motivo y, en el caso de los bloques, cuáles son.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse}
     */
    @ExceptionHandler(ConsultorioNoRetirableException.class)
    public ResponseEntity<ErrorResponse> manejarConsultorioNoRetirable(
            ConsultorioNoRetirableException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.CONFLICT, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 400 cuando la actualización intenta reasignar el consultorio de un
     * médico a otro distinto del actual.
     *
     * <p>Es una regla de negocio que depende del estado de la base —compara el
     * consultorio enviado con el persistido—, así que no es expresable con anotaciones
     * del DTO y necesita su propio caso. Reusa el mismo constructor de respuesta que el
     * resto del traductor para no abrir un camino paralelo.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(CambioDeConsultorioNoPermitidoException.class)
    public ResponseEntity<ErrorResponse> manejarCambioDeConsultorioNoPermitido(
            CambioDeConsultorioNoPermitidoException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 400 cuando el bloque de disponibilidad tiene el rango horario invertido o
     * vacío.
     *
     * <p>Es una regla de negocio que compara dos campos del cuerpo, así que no es
     * expresable con anotaciones del DTO y necesita su propio caso. Reusa el mismo
     * constructor de respuesta que el resto del traductor para no abrir un camino
     * paralelo.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(RangoHorarioInvalidoException.class)
    public ResponseEntity<ErrorResponse> manejarRangoHorarioInvalido(
            RangoHorarioInvalidoException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 400 cuando la duración de consulta no pertenece al conjunto permitido.
     *
     * <p>El schema declara el CHECK, pero un choque contra él llegaría al cliente como
     * error de integridad; este caso traduce la regla a un 400 con el mismo cuerpo que el
     * resto de errores. Reusa el constructor de respuesta existente.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(DuracionConsultaInvalidaException.class)
    public ResponseEntity<ErrorResponse> manejarDuracionConsultaInvalida(
            DuracionConsultaInvalidaException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 400 cuando una cuenta con rol {@code MEDICO} no indica su médico.
     *
     * <p>La obligación depende del valor de otro campo del cuerpo, así que no es expresable con
     * anotaciones del DTO: {@code @NotNull} sobre {@code medicoId} rechazaría también a los roles
     * que legítimamente no atienden. Reusa el constructor de respuesta del resto del traductor.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(MedicoRequeridoException.class)
    public ResponseEntity<ErrorResponse> manejarMedicoRequerido(
            MedicoRequeridoException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, excepcion.getMessage(), peticion);
    }

    /**
     * Responde 409 cuando la restricción de correo único por organización es la que rechazó la
     * escritura.
     *
     * <p>Es la red de seguridad de R2. El servicio comprueba el correo antes de guardar, pero dos
     * altas simultáneas pueden pasar las dos la comprobación: entonces la última palabra la tiene
     * {@code usuario_organizacion_id_email_key} y el cliente debe recibir el mismo 409 que en el
     * camino normal, no un 500 por error de integridad.</p>
     *
     * <p><strong>Esta rama es la única de todo el traductor que atiende {@code DataIntegrityViolationException},
     * y no cambia nada fuera de este módulo:</strong> si la restricción violada es cualquier otra
     * —una clave foránea, otro {@code UNIQUE}, un {@code CHECK}— la excepción se relanza intacta y
     * sigue el camino que ya seguía, que es el error del contenedor. Traducirla en bloque a 409 sería
     * peor: convertiría en conflicto de negocio cualquier fallo de integridad de cualquier módulo.</p>
     *
     * <p>El reconocimiento de la restricción se hace sobre el texto del error porque el nombre aparece
     * en el mensaje de las dos bases que usa el proyecto —PostgreSQL (
     * {@code duplicate key value violates unique constraint "usuario_organizacion_id_email_key"}) y
     * H2 en modo PostgreSQL— sin atar el traductor al proveedor de persistencia.</p>
     *
     * @param excepcion error de integridad lanzado al escribir
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse} si la restricción es la del correo
     * @throws DataIntegrityViolationException si la restricción violada es cualquier otra
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> manejarCorreoDuplicadoEnCarrera(
            DataIntegrityViolationException excepcion, HttpServletRequest peticion) {
        if (!violaLaUnicidadDelCorreo(excepcion)) {
            // Cualquier otra violación de integridad conserva su comportamiento: no se traduce aquí.
            throw excepcion;
        }
        return construir(HttpStatus.CONFLICT,
                "Ya existe una cuenta con ese correo en esta organización. El correo es único dentro "
                        + "de la organización, aunque el mismo correo pueda tener cuentas independientes "
                        + "en otras organizaciones",
                peticion);
    }

    /**
     * Responde 400 cuando el bloque de disponibilidad se solapa con otro del mismo médico
     * y día.
     *
     * <p>Es una regla que depende del estado de la base —compara el bloque enviado con los
     * ya agendados—, así que no es expresable con anotaciones del DTO. Reusa el mismo
     * constructor de respuesta que el resto del traductor.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(SolapamientoDeHorarioException.class)
    public ResponseEntity<ErrorResponse> manejarSolapamientoDeHorario(
            SolapamientoDeHorarioException excepcion, HttpServletRequest peticion) {
        return construir(HttpStatus.BAD_REQUEST, excepcion.getMessage(), peticion);
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
     * Responde 403 cuando el usuario está autenticado pero no autorizado.
     *
     * <p>Cubre tanto {@link AccessDeniedException} como su subtipo
     * {@link AuthorizationDeniedException}, que es el que envuelve una decisión negativa de
     * {@code @PreAuthorize}. El mensaje es una constante compartida con
     * {@code RestAccessDeniedHandler} para que el 403 de la cadena de filtros y el del
     * controlador sean indistinguibles para el cliente, y no reexpone el detalle de la
     * regla que falló.</p>
     *
     * @param excepcion excepción de autorización (no se expone su mensaje)
     * @param peticion  petición que originó el error
     * @return respuesta 403 con {@link ErrorResponse}
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> manejarAccesoDenegado(
            AccessDeniedException excepcion, HttpServletRequest peticion) {
        // R-SEG-10: se registra el evento con método y ruta, nunca el token ni sus claims.
        REGISTRO.warn("Acceso denegado en {} {}: {}",
                peticion.getMethod(), peticion.getRequestURI(), excepcion.getClass().getSimpleName());
        return construir(HttpStatus.FORBIDDEN, SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO, peticion);
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

    /**
     * Indica si el error de integridad proviene de la restricción de correo único por organización.
     *
     * <p>Recorre la cadena de causas porque el mensaje útil no está en la excepción de Spring, sino en
     * la de JDBC o en la del proveedor. El nombre de la restricción es único en el esquema, así que
     * encontrarlo en la cadena identifica la violación sin ambigüedad.</p>
     *
     * @param excepcion error de integridad lanzado al escribir
     * @return {@code true} si la restricción violada es {@code usuario_organizacion_id_email_key}
     */
    private static boolean violaLaUnicidadDelCorreo(Throwable excepcion) {
        for (var actual = excepcion; actual != null; actual = actual.getCause()) {
            if (actual.getMessage() != null
                    && actual.getMessage().contains(RESTRICCION_CORREO_UNICO_POR_ORGANIZACION)) {
                return true;
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return false;
    }
}
