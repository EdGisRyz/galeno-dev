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
import com.intoglobe.clinicas.security.TenantAccessGuard;
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
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013):</strong> el rol lo exige la
 * cadena de filtros ({@code ADMIN} para todo el módulo) y el tenant sale del claim. El
 * {@code {id}} del path solo se acepta si coincide con el {@code tenantId} del token, y
 * para eso se consulta {@link TenantAccessGuard}: si no coincide, la respuesta es {@code 403}
 * <em>también</em> para {@code ADMIN}, porque no existe un rol de plataforma (D5 corregido el
 * 2026-10-29). El {@code id} que se propaga al service es el del token, nunca el del path.
 * El listado devuelve únicamente la organización del token.</p>
 */
@RestController
@RequestMapping("/api/v1/organizaciones")
@Tag(name = "v1 · organizaciones", description = "Alta, consulta, actualización y baja lógica de organizaciones.")
public class OrganizacionController {

    private final OrganizacionService organizacionService;
    private final TenantAccessGuard tenantAccessGuard;

    public OrganizacionController(OrganizacionService organizacionService,
            TenantAccessGuard tenantAccessGuard) {
        this.organizacionService = organizacionService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Registra una organización nueva. Método conservado para el flujo de onboarding: hoy
     * <strong>no es alcanzable por HTTP</strong>.
     *
     * <p><strong>El alta está bloqueada a propósito.</strong> {@code POST /api/v1/organizaciones}
     * responde {@code 403} para {@code ADMIN}, {@code MEDICO} y {@code PACIENTE} porque, con el
     * aislamiento por tenant aplicado, la organización recién creada quedaría inalcanzable para
     * quien la crea —el {@code Location} del {@code 201} respondería {@code 403} a la lectura, al
     * alta de hijos y a la baja— y dejaría una fila activa huérfana. Es una decisión de producto
     * del 2026-10-29 y la regla vive en {@code SecurityConfig}, que documenta el bloqueo y pide no
     * reabrirlo sin el onboarding. El método, su firma y su lógica se conservan intactos para ese
     * flujo (Sprint 2), que es cuando este contrato vuelve a publicar el {@code 201}.</p>
     *
     * <p><strong>El {@code 400} ya no se documenta porque no es alcanzable:</strong> la cadena de
     * filtros deniega antes de deserializar y validar, así que ni un JSON mal formado ni un campo
     * inválido llegan a producir un {@code 400} en esta ruta. Las reglas de
     * {@link OrganizacionRequest} (Bean Validation, enum y fecha) siguen cubiertas a través de
     * {@code PUT /organizaciones/{id}}, que recibe el mismo DTO y sigue permitido; documentar aquí
     * un {@code 400} que nadie puede recibir sería exactamente el contrato que este cambio corrige.</p>
     *
     * @param request datos de entrada validados
     * @return 201 con la organización creada y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping
    @Operation(summary = "Crear organización (bloqueada temporalmente)",
            description = "El alta de organizaciones está bloqueada a propósito: responde 403 para todos los roles hasta que exista el flujo de onboarding dedicado (Sprint 2). La regla y su justificación están en SecurityConfig.")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "El alta de organizaciones está bloqueada para todos los roles hasta que exista el flujo de onboarding dedicado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrganizacionResponse> crear(@Valid @RequestBody OrganizacionRequest request) {
        var creada = organizacionService.crear(request);
        return ResponseEntity.created(URI.create("/api/v1/organizaciones/" + creada.id())).body(creada);
    }

    /**
     * Consulta una organización existente de la propia organización del token.
     *
     * @param id identificador de la organización; debe ser el {@code tenantId} del token
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
        // El id que se consulta es el del claim; el del path solo sirve de comprobación cruzada.
        return ResponseEntity.ok(organizacionService.buscarPorId(tenantAccessGuard.exigirTenantDeLaRuta(id)));
    }

    /**
     * Lista las organizaciones activas visibles para el token.
     *
     * <p>Resuelve la pregunta abierta R8.3(a): el endpoint ya no enumera la plataforma y
     * devuelve exclusivamente la organización del token, en un arreglo de cero o un elemento.</p>
     *
     * @return 200 con el arreglo de organizaciones activas del token
     */
    @GetMapping
    @Operation(summary = "Listar organizaciones activas",
            description = "Devuelve la organización activa del token. El endpoint ya no enumera organizaciones de otros tenants.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organización activa del token, en un arreglo de cero o un elemento",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = OrganizacionResponse.class))))
    })
    public ResponseEntity<List<OrganizacionResponse>> listarActivas() {
        return ResponseEntity.ok(organizacionService.listarActivas(tenantAccessGuard.tenantActual()));
    }

    /**
     * Reemplaza los campos editables de una organización.
     *
     * @param id      identificador de la organización; debe ser el {@code tenantId} del token
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
        return ResponseEntity.ok(
                organizacionService.actualizar(tenantAccessGuard.exigirTenantDeLaRuta(id), request));
    }

    /**
     * Aplica la baja lógica de una organización. Es idempotente: repetir la
     * operación sobre una organización ya inactiva sigue devolviendo 204.
     *
     * @param id identificador de la organización; debe ser el {@code tenantId} del token
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
        organizacionService.desactivar(tenantAccessGuard.exigirTenantDeLaRuta(id));
        return ResponseEntity.noContent().build();
    }
}
