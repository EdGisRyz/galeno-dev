package com.intoglobe.clinicas.exception;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.validation.FieldError;
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

    /** Nombre del atributo que toda restricción de Jakarta Validation declara. */
    private static final String ATRIBUTO_MENSAJE = "message";

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
     * Restricción de unicidad de la CURP dentro de la organización
     * ({@code paciente_organizacion_id_curp_key}, migración V8). Es la última frontera de S2: el
     * servicio comprueba la CURP antes de guardar, pero dos altas simultáneas pueden pasar las dos
     * la comprobación.
     */
    private static final String RESTRICCION_CURP_UNICA_POR_ORGANIZACION =
            "paciente_organizacion_id_curp_key";

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
     * Responde 409 cuando la CURP ya está ocupada dentro de la organización (S5).
     *
     * <p>Igual que el correo repetido, el conflicto no es de sintaxis: la petición está bien formada
     * —el formato de la CURP se validó en el DTO— y lo que choca es el estado actual de la
     * organización. Por eso 409 y no 400. El mensaje viene de la excepción porque explica
     * <em>qué</em> CURP está ocupada y por qué el mismo paciente sí puede estar registrado en otra
     * organización.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse}
     */
    @ExceptionHandler(CurpDuplicadaEnOrganizacionException.class)
    public ResponseEntity<ErrorResponse> manejarCurpDuplicada(
            CurpDuplicadaEnOrganizacionException excepcion, HttpServletRequest peticion) {
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
     * Responde 409 cuando el bloque de horario apunta a un consultorio que no está asignado al
     * médico.
     *
     * <p>Es la regla «primero asignar, después agendar»: la petición está bien formada y el
     * consultorio puede existir en la organización, pero el vínculo con ese médico todavía no
     * existe. La clave {@code horario_medico_consultorio_asignado_fkey} de la V6 lo impediría
     * con un error de integridad, que llegaría al cliente como 500; el servicio lo detecta antes
     * y el mensaje explica qué hacer: asignar el consultorio al médico.</p>
     *
     * @param excepcion excepción de negocio con el mensaje seguro
     * @param peticion  petición que originó el error
     * @return respuesta 409 con {@link ErrorResponse}
     */
    @ExceptionHandler(ConsultorioNoAsignadoAlMedicoException.class)
    public ResponseEntity<ErrorResponse> manejarConsultorioNoAsignadoAlMedico(
            ConsultorioNoAsignadoAlMedicoException excepcion, HttpServletRequest peticion) {
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
     * Responde 409 cuando una restricción de unicidad por organización rechazó la escritura.
     *
     * <p>Cubre las dos unicidades de negocio que el servicio comprueba antes de guardar: el correo
     * de una cuenta ({@code usuario_organizacion_id_email_key}, R2) y la CURP de un paciente
     * ({@code paciente_organizacion_id_curp_key}, S2). El servicio comprueba las dos antes de
     * escribir, pero dos peticiones simultáneas pueden pasar la comprobación: entonces la última
     * palabra la tiene el esquema y el cliente debe recibir el mismo 409 que en el camino normal, no
     * un 500 por error de integridad.</p>
     *
     * <p><strong>Esta rama es la única de todo el traductor que atiende {@code DataIntegrityViolationException},
     * y no cambia nada fuera de estos dos módulos:</strong> si la restricción violada es cualquier otra
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
     * @return respuesta 409 con {@link ErrorResponse} si la restricción es una de las dos unicidades
     * @throws DataIntegrityViolationException si la restricción violada es cualquier otra
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> manejarUnicidadDuplicadaEnCarrera(
            DataIntegrityViolationException excepcion, HttpServletRequest peticion) {
        if (violaAlgunaUnicidad(excepcion, RESTRICCION_CORREO_UNICO_POR_ORGANIZACION)) {
            return construir(HttpStatus.CONFLICT,
                    "Ya existe una cuenta con ese correo en esta organización. El correo es único dentro "
                            + "de la organización, aunque el mismo correo pueda tener cuentas independientes "
                            + "en otras organizaciones",
                    peticion);
        }
        if (violaAlgunaUnicidad(excepcion, RESTRICCION_CURP_UNICA_POR_ORGANIZACION)) {
            return construir(HttpStatus.CONFLICT,
                    "Ya existe un paciente con esa CURP en esta organización. La CURP es única dentro "
                            + "de la organización, aunque el mismo paciente pueda estar registrado en "
                            + "otras organizaciones. Si el paciente con esa CURP fue dado de baja, hay que "
                            + "reactivarlo en lugar de registrarlo de nuevo",
                    peticion);
        }
        // Cualquier otra violación de integridad conserva su comportamiento: no se traduce aquí.
        throw excepcion;
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
     * <p>Cada entrada del resumen es el mensaje que la restricción declara, si declara uno, o el
     * resumen por código que ya usaba este traductor. La diferencia importa: el resumen por código
     * nombra la propiedad —`El campo curp es inválido`— y sirve mientras el nombre del campo sea un
     * dato del contrato, pero una regla de clase se reporta sobre el nombre de un método
     * —`responsableInformadoCuandoHayParentesco`— y ese texto en la cara de quien consume la API no
     * dice nada. Una restricción que trae su propio mensaje ya está explicada y se publica tal cual.</p>
     *
     * <p><strong>Ninguna restricción existente cambia su texto.</strong> Hoy ninguna anotación del
     * proyecto declara `message`, así que todas siguen cayendo en {@link #MENSAJES_POR_CODIGO}, y
     * una anotación que declare uno se publica sin el prefijo `El campo …`, porque su texto es una
     * oración completa. Se comparan el valor y el valor por defecto del atributo: un mensaje
     * heredado del valor por defecto de la anotación no es una decisión de este proyecto.</p>
     *
     * @param excepcion excepción con el resultado del Bean Validation
     * @param peticion  petición que originó el error
     * @return respuesta 400 con {@link ErrorResponse}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> manejarValidacion(
            MethodArgumentNotValidException excepcion, HttpServletRequest peticion) {
        Object objetivo = excepcion.getBindingResult().getTarget();
        String resumen = excepcion.getBindingResult().getFieldErrors().stream()
                .map(error -> describirCampoInvalido(error, objetivo))
                .distinct()
                .collect(Collectors.joining("; "));
        if (resumen.isBlank()) {
            resumen = "La petición contiene datos inválidos";
        }
        return construir(HttpStatus.BAD_REQUEST, resumen, peticion);
    }

    /**
     * Texto de una entrada del resumen: el mensaje de la restricción o, si no declara uno, el
     * resumen por código con el nombre del campo al frente.
     *
     * @param error   error de campo del Bean Validation
     * @param objetivo cuerpo ya deserializado sobre el que se validó, o {@code null}
     * @return texto que se publica para ese campo
     */
    private static String describirCampoInvalido(FieldError error, Object objetivo) {
        String declarado = mensajeDeclarado(error, objetivo);
        if (declarado != null) {
            return declarado;
        }
        return "El campo " + error.getField() + " "
                + MENSAJES_POR_CODIGO.getOrDefault(error.getCode(), "es inválido");
    }

    /**
     * Mensaje que la restricción declara, o {@code null} si la anotación no declara ninguno.
     *
     * <p>Se lee la anotación que corresponde al código del error —que Spring nombra con el tipo de
     * la restricción, `NotBlank`, `Pattern`, `AssertTrue`— y se compara su valor con el valor por
     * defecto que la propia anotación declara. Iguales significa que el mensaje es el del framework
     * y no una decisión del proyecto.</p>
     *
     * <p>Los campos anidados o indexados se dejan fuera: la anotación no está en el objeto que
     * llega aquí y adivinar la ruta no aporta nada. Cualquier fallo de reflexión tampoco interrumpe
     * la respuesta: se cae al resumen por código, que es el comportamiento anterior.</p>
     *
     * @param error    error de campo del Bean Validation
     * @param objetivo cuerpo ya deserializado sobre el que se validó, o {@code null}
     * @return mensaje declarado por la restricción, o {@code null}
     */
    private static String mensajeDeclarado(FieldError error, Object objetivo) {
        String campo = error.getField();
        String codigo = error.getCode();
        if (objetivo == null || codigo == null || campo == null || campo.isBlank()
                || campo.indexOf('.') >= 0 || campo.indexOf('[') >= 0) {
            return null;
        }
        for (Annotation anotacion : anotacionesDeLaPropiedad(objetivo.getClass(), campo)) {
            if (!anotacion.annotationType().getSimpleName().equals(codigo)) {
                continue;
            }
            try {
                Method atributo = anotacion.annotationType().getMethod(ATRIBUTO_MENSAJE);
                Object valor = atributo.invoke(anotacion);
                Object porDefecto = atributo.getDefaultValue();
                if (valor instanceof String texto && !texto.isBlank() && !texto.equals(porDefecto)) {
                    return texto;
                }
            } catch (ReflectiveOperationException | RuntimeException excepcion) {
                // Una restricción sin atributo `message` —o una lectura imposible— no aporta texto:
                // se usa el resumen por código, que es lo que el cliente recibía antes.
                return null;
            }
        }
        return null;
    }

    /**
     * Anotaciones que la validación puede haber leído para una propiedad.
     *
     * <p>Un campo del cuerpo se declara de varias formas y cada una se anota en un lugar distinto:
     * un atributo de un `record` lleva sus anotaciones en el componente, que javac copia al campo y
     * al accesor; y una restricción de clase como `@AssertTrue` se escribe sobre el método
     * `isAlgo()`, de modo que Spring la reporta con el nombre de la propiedad y no del campo. Se
     * recogen las tres formas para no depender de cómo se declaró cada DTO.</p>
     *
     * @param tipo  clase del cuerpo validado
     * @param campo nombre del campo o de la propiedad tal como lo reporta Spring
     * @return anotaciones encontradas, posiblemente vacías
     */
    private static List<Annotation> anotacionesDeLaPropiedad(Class<?> tipo, String campo) {
        List<Annotation> anotaciones = new ArrayList<>();
        try {
            anotaciones.addAll(List.of(tipo.getDeclaredField(campo).getAnnotations()));
        } catch (NoSuchFieldException | RuntimeException excepcion) {
            // La propiedad puede no ser un campo: sigue con el accesor.
        }
        try {
            anotaciones.addAll(List.of(tipo.getDeclaredMethod(campo).getAnnotations()));
        } catch (NoSuchMethodException | RuntimeException excepcion) {
            // No es un accesor de `record`; sigue con las formas `getX` / `isX`.
        }
        String capitalizado = Character.toUpperCase(campo.charAt(0)) + campo.substring(1);
        for (String prefijo : new String[] {"get", "is"}) {
            try {
                anotaciones.addAll(List.of(tipo.getDeclaredMethod(prefijo + capitalizado).getAnnotations()));
            } catch (NoSuchMethodException | RuntimeException excepcion) {
                // La propiedad no se declara con ese prefijo.
            }
        }
        return anotaciones;
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
     * Indica si el error de integridad proviene de alguna de las restricciones de unicidad indicadas.
     *
     * <p>Recorre la cadena de causas porque el mensaje útil no está en la excepción de Spring, sino en
     * la de JDBC o en la del proveedor. Los nombres de las restricciones son únicos en el esquema, así
     * que encontrar uno en la cadena identifica la violación sin ambigüedad.</p>
     *
     * @param excepcion    error de integridad lanzado al escribir
     * @param restricciones nombres de restricción que se quieren reconocer
     * @return {@code true} si la violación es de alguna de esas restricciones
     */
    private static boolean violaAlgunaUnicidad(Throwable excepcion, String... restricciones) {
        for (var actual = excepcion; actual != null; actual = actual.getCause()) {
            var mensaje = actual.getMessage();
            if (mensaje != null && java.util.Arrays.stream(restricciones).anyMatch(mensaje::contains)) {
                return true;
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return false;
    }
}
