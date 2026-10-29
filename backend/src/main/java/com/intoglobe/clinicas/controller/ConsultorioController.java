package com.intoglobe.clinicas.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intoglobe.clinicas.dto.request.ConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.ConsultorioService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Punto de entrada HTTP de consultorios.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request
 * (Bean Validation) y delega en {@link ConsultorioService}. Los errores no se
 * construyen aquí, viajan siempre por {@code GlobalExceptionHandler} para que el
 * contrato de error sea único en todos los módulos. La existencia de la
 * organización la decide el service: si el padre no existe, responde 404 sin que
 * este controller lo compruebe.</p>
 *
 * <p>La ruta base es {@code /api/v1} porque el módulo expone dos formas: las
 * operaciones de colección cuelgan de la organización
 * ({@code /organizaciones/{organizacionId}/consultorios}) y las de ítem son planas
 * ({@code /consultorios/{id}}). La organización es inmutable y por eso no viaja en
 * el cuerpo: en la creación llega por el path y la actualización no la recibe. La
 * cabecera {@code Location} del alta apunta siempre a la ruta plana del ítem y se
 * emite como ruta relativa para que sea correcta detrás de cualquier proxy
 * reverso.</p>
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013):</strong> la cadena de filtros
 * exige {@code ADMIN} para crear, actualizar y desactivar, y admite {@code MEDICO} solo en
 * las lecturas. El {@code organizacionId} del path se acepta únicamente si coincide con el
 * {@code tenantId} del token —{@link TenantAccessGuard} responde {@code 403} en caso
 * contrario, sin exención de {@code ADMIN}— y es el tenant del claim el que se propaga al
 * service. En las rutas planas el tenant también sale del claim, y un consultorio de otra
 * organización responde {@code 404} para no revelar que existe.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · consultorios", description = "Alta, consulta, actualización y baja lógica de consultorios.")
public class ConsultorioController {

    private final ConsultorioService consultorioService;
    private final TenantAccessGuard tenantAccessGuard;

    public ConsultorioController(ConsultorioService consultorioService,
            TenantAccessGuard tenantAccessGuard) {
        this.consultorioService = consultorioService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Registra un consultorio dentro de la organización indicada en el path.
     *
     * <p>La organización no se recibe en el cuerpo: llega por la ruta porque un
     * consultorio no puede cambiar de dueño. El path se valida contra el claim del token y,
     * si coincide, el service resuelve el tenant del token contra la base; si no existe, la
     * excepción de negocio se traduce en 404.</p>
     *
     * @param organizacionId organización dueña tomada del path, validada contra el token
     * @param request        datos de entrada validados
     * @return 201 con el consultorio creado y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping("/organizaciones/{organizacionId}/consultorios")
    @Operation(summary = "Crear consultorio",
            description = "Registra un consultorio dentro de la organización indicada en la ruta y devuelve su representación junto con la cabecera Location apuntando a la ruta plana del recurso creado.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Consultorio creado",
                    content = @Content(schema = @Schema(implementation = ConsultorioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ConsultorioResponse> crear(
            @Parameter(description = "Identificador UUID de la organización dueña del consultorio.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId,
            @Valid @RequestBody ConsultorioRequest request) {
        var creado = consultorioService.crear(tenantAccessGuard.exigirTenantDeLaRuta(organizacionId), request);
        return ResponseEntity.created(URI.create("/api/v1/consultorios/" + creado.id())).body(creado);
    }

    /**
     * Lista los consultorios activos de una organización, ordenados por nombre.
     *
     * <p>La existencia del padre es parte del contrato de la ruta anidada: un id
     * inexistente responde 404 en vez de un {@code 200 []} que ocultaría el error
     * del cliente. Esa comprobación vive en el service, no aquí.</p>
     *
     * @param organizacionId organización dueña de los consultorios, validada contra el token
     * @return 200 con el arreglo de consultorios activos
     */
    @GetMapping("/organizaciones/{organizacionId}/consultorios")
    @Operation(summary = "Listar consultorios activos de una organización",
            description = "Devuelve los consultorios activos de la organización indicada, ordenados alfabéticamente por nombre.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de consultorios activos",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConsultorioResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<ConsultorioResponse>> listarPorOrganizacion(
            @Parameter(description = "Identificador UUID de la organización cuyos consultorios se listan.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId) {
        return ResponseEntity.ok(
                consultorioService.listarPorOrganizacion(tenantAccessGuard.exigirTenantDeLaRuta(organizacionId)));
    }

    /**
     * Consulta un consultorio existente de la propia organización del token.
     *
     * <p>Un consultorio de otra organización responde {@code 404}, igual que un
     * identificador inexistente: el mensaje de error no confirma que el recurso exista.</p>
     *
     * @param id identificador del consultorio
     * @return 200 con el consultorio encontrado
     */
    @GetMapping("/consultorios/{id}")
    @Operation(summary = "Buscar consultorio por id",
            description = "Devuelve un consultorio existente a partir de su identificador UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consultorio encontrado",
                    content = @Content(schema = @Schema(implementation = ConsultorioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Consultorio inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ConsultorioResponse> buscarPorId(
            @Parameter(description = "Identificador UUID del consultorio.", example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
            @PathVariable UUID id) {
        return ResponseEntity.ok(consultorioService.buscarPorId(id, tenantAccessGuard.tenantActual()));
    }

    /**
     * Reemplaza los tres campos editables de un consultorio existente.
     *
     * <p>La organización no se toca aunque la columna viaje en el {@code UPDATE}:
     * el request no la contiene y el mapper no la reasigna, porque mover un
     * consultorio dejaría huérfanos a sus médicos y citas.</p>
     *
     * @param id      identificador del consultorio
     * @param request datos de entrada validados
     * @return 200 con el consultorio actualizado
     */
    @PutMapping("/consultorios/{id}")
    @Operation(summary = "Actualizar consultorio",
            description = "Reemplaza nombre, dirección y teléfono de un consultorio existente. La organización dueña no puede modificarse.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consultorio actualizado",
                    content = @Content(schema = @Schema(implementation = ConsultorioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Consultorio inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ConsultorioResponse> actualizar(
            @Parameter(description = "Identificador UUID del consultorio a actualizar.", example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
            @PathVariable UUID id,
            @Valid @RequestBody ConsultorioRequest request) {
        return ResponseEntity.ok(
                consultorioService.actualizar(id, tenantAccessGuard.tenantActual(), request));
    }

    /**
     * Aplica la baja lógica de un consultorio. Es idempotente: repetir la
     * operación sobre un consultorio ya inactivo sigue devolviendo 204. No existe
     * borrado físico porque las claves foráneas de médicos y citas lo impedirían.
     *
     * @param id identificador del consultorio
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/consultorios/{id}")
    @Operation(summary = "Desactivar consultorio",
            description = "Aplica la baja lógica de un consultorio existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Consultorio dado de baja lógica, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Consultorio inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "Identificador UUID del consultorio a desactivar.", example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
            @PathVariable UUID id) {
        consultorioService.desactivar(id, tenantAccessGuard.tenantActual());
        return ResponseEntity.noContent().build();
    }
}
