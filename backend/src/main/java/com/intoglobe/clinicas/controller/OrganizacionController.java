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

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.OrganizacionResponse;
import com.intoglobe.clinicas.service.OrganizacionService;

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
 * Punto de entrada HTTP de organizaciones.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request
 * (Bean Validation) y delega en {@link OrganizacionService}. Los errores no se
 * construyen aquí, viajan siempre por {@code GlobalExceptionHandler} para que el
 * contrato de error sea único en todos los módulos. La cabecera {@code Location}
 * del alta se emite como ruta relativa para que sea correcta detrás de cualquier
 * proxy reverso.</p>
 */
@RestController
@RequestMapping("/api/v1/organizaciones")
@Tag(name = "v1 · organizaciones", description = "Alta, consulta, actualización y baja lógica de organizaciones.")
public class OrganizacionController {

    private final OrganizacionService organizacionService;

    public OrganizacionController(OrganizacionService organizacionService) {
        this.organizacionService = organizacionService;
    }

    /**
     * Registra una organización nueva.
     *
     * @param request datos de entrada validados
     * @return 201 con la organización creada y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping
    @Operation(summary = "Crear organización",
            description = "Registra una organización nueva y devuelve su representación junto con la cabecera Location apuntando al recurso creado.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Organización creada",
                    content = @Content(schema = @Schema(implementation = OrganizacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado, enum o fecha inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrganizacionResponse> crear(@Valid @RequestBody OrganizacionRequest request) {
        var creada = organizacionService.crear(request);
        return ResponseEntity.created(URI.create("/api/v1/organizaciones/" + creada.id())).body(creada);
    }

    /**
     * Consulta una organización existente.
     *
     * @param id identificador de la organización
     * @return 200 con la organización encontrada
     */
    @GetMapping("/{id}")
    @Operation(summary = "Buscar organización por id",
            description = "Devuelve una organización existente a partir de su identificador UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organización encontrada",
                    content = @Content(schema = @Schema(implementation = OrganizacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrganizacionResponse> buscarPorId(
            @Parameter(description = "Identificador UUID de la organización.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID id) {
        return ResponseEntity.ok(organizacionService.buscarPorId(id));
    }

    /**
     * Lista las organizaciones activas ordenadas por nombre.
     *
     * @return 200 con el arreglo de organizaciones activas
     */
    @GetMapping
    @Operation(summary = "Listar organizaciones activas",
            description = "Devuelve todas las organizaciones activas ordenadas alfabéticamente por nombre.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de organizaciones activas",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = OrganizacionResponse.class))))
    })
    public ResponseEntity<List<OrganizacionResponse>> listarActivas() {
        return ResponseEntity.ok(organizacionService.listarActivas());
    }

    /**
     * Reemplaza los campos editables de una organización.
     *
     * @param id      identificador de la organización
     * @param request datos de entrada validados
     * @return 200 con la organización actualizada
     */
    @PutMapping("/{id}")
    @Operation(summary = "Actualizar organización",
            description = "Reemplaza nombre, plan, suscripción y fin de prueba de una organización existente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organización actualizada",
                    content = @Content(schema = @Schema(implementation = OrganizacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado, enum, fecha o identificador inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrganizacionResponse> actualizar(
            @Parameter(description = "Identificador UUID de la organización a actualizar.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID id,
            @Valid @RequestBody OrganizacionRequest request) {
        return ResponseEntity.ok(organizacionService.actualizar(id, request));
    }

    /**
     * Aplica la baja lógica de una organización. Es idempotente: repetir la
     * operación sobre una organización ya inactiva sigue devolviendo 204.
     *
     * @param id identificador de la organización
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar organización",
            description = "Aplica la baja lógica de una organización existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Organización dada de baja lógica, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "Identificador UUID de la organización a desactivar.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID id) {
        organizacionService.desactivar(id);
        return ResponseEntity.noContent().build();
    }
}
