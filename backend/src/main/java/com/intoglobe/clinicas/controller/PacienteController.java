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

import com.intoglobe.clinicas.dto.request.PacienteRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.PacienteResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.PacienteService;

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
 * Punto de entrada HTTP de pacientes.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request con Bean
 * Validation y delega en {@link PacienteService}. Ningún error se construye aquí, viajan
 * todos por {@code GlobalExceptionHandler} para que el contrato de error sea el mismo que el
 * del resto de los módulos. Eso incluye los {@code 404} que <em>no</em> produce este
 * archivo: la organización del path inexistente o distinta del tenant del token, y el
 * paciente inexistente o de otra organización, los decide el service contra el estado de la
 * base. Aquí solo se propagan.</p>
 *
 * <p>La ruta base es {@code /api/v1} porque el módulo repite la convención de los módulos
 * 02 a 04: las operaciones de colección cuelgan del dueño inmediato
 * ({@code /organizaciones/{organizacionId}/pacientes}) y las de ítem son planas
 * ({@code /pacientes/{id}}). El dueño inmediato de un paciente es la organización y por eso
 * no viaja en el cuerpo: en el alta llega por el path y la actualización no la recibe en
 * absoluto, así que el {@link PacienteRequest} es el mismo en ambas operaciones y el cuerpo
 * no puede expresar un cambio de dueño. La cabecera {@code Location} del alta apunta a la
 * ruta plana del ítem y se emite como ruta relativa, para que siga siendo correcta detrás
 * de cualquier proxy reverso.</p>
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013).</strong> La autorización tiene
 * tres capas y este controller no decide ninguna. La primera es la cadena de filtros, en
 * {@code SecurityConfig}: exige {@code ADMIN} para crear, actualizar, dar de baja y
 * reactivar, y admite {@code MEDICO} solo en las dos lecturas; {@code PACIENTE} queda
 * cerrado. La segunda y la tercera son del service contra el {@code tenantId} del token:
 * que la organización del path sea la del claim y que el paciente pertenezca a esa
 * organización. Lo único que hace este archivo es tomar el tenant del principal ya
 * autenticado —con {@link TenantAccessGuard#tenantActual()}, nunca el path sin
 * contrastar— y entregarlo junto con el {@code organizacionId} de la ruta, porque el service
 * documenta el orden de esas comprobaciones como parte de su contrato. Un paciente de otra
 * clínica responde {@code 404}, igual que un identificador inexistente: un {@code 403}
 * confirmaría que el recurso existe en otra organización.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · pacientes", description = "Alta, consulta, actualización, baja lógica y reactivación de pacientes.")
public class PacienteController {

    private final PacienteService pacienteService;
    private final TenantAccessGuard tenantAccessGuard;

    public PacienteController(PacienteService pacienteService, TenantAccessGuard tenantAccessGuard) {
        this.pacienteService = pacienteService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Registra un paciente dentro de la organización indicada en el path.
     *
     * <p>La organización no se recibe en el cuerpo: llega por la ruta porque un paciente no
     * puede cambiar de dueño. El service resuelve la organización contra la base y la
     * compara con el tenant del claim antes de nada, así que una organización ajena responde
     * {@code 404} sin consultar el repositorio; una inexistente responde el mismo {@code 404}
     * en vez de dejar que la clave foránea produzca un {@code 500}.</p>
     *
     * @param organizacionId organización dueña tomada del path
     * @param request        datos de entrada validados
     * @return 201 con el paciente creado y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping("/organizaciones/{organizacionId}/pacientes")
    @Operation(summary = "Crear paciente",
            description = "Registra un paciente dentro de la organización indicada en la ruta y devuelve su representación junto con la cabecera Location apuntando a la ruta plana del recurso creado.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Paciente creado",
                    content = @Content(schema = @Schema(implementation = PacienteResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente o distinta de la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PacienteResponse> crear(
            @Parameter(description = "Identificador UUID de la organización dueña del paciente.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId,
            @Valid @RequestBody PacienteRequest request) {
        var creado = pacienteService.crear(organizacionId, tenantAccessGuard.tenantActual(), request);
        return ResponseEntity.created(URI.create("/api/v1/pacientes/" + creado.id())).body(creado);
    }

    /**
     * Lista los pacientes activos de una organización, ordenados por nombre.
     *
     * <p>La existencia del padre es parte del contrato de la ruta anidada: un id inexistente
     * responde 404 en vez de un {@code 200 []} que ocultaría el error del cliente, y un id de
     * otra organización responde el mismo 404 sin llegar a consultar el listado. Las dos
     * comprobaciones viven en el service. Los pacientes dados de baja lógica quedan fuera del
     * listado, y el orden lo fija la consulta del repositorio: es parte del contrato y evita
     * que cada cliente reordene por su cuenta.</p>
     *
     * @param organizacionId organización dueña de los pacientes
     * @return 200 con el arreglo de pacientes activos
     */
    @GetMapping("/organizaciones/{organizacionId}/pacientes")
    @Operation(summary = "Listar pacientes activos de una organización",
            description = "Devuelve los pacientes activos de la organización indicada, ordenados alfabéticamente por nombre.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de pacientes activos",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PacienteResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente o distinta de la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<PacienteResponse>> listarPorOrganizacion(
            @Parameter(description = "Identificador UUID de la organización cuyos pacientes se listan.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId) {
        return ResponseEntity.ok(
                pacienteService.listarPorOrganizacion(organizacionId, tenantAccessGuard.tenantActual()));
    }

    /**
     * Consulta un paciente existente de la propia organización del token.
     *
     * <p>Un paciente de otra organización responde {@code 404}, igual que un identificador
     * inexistente: el mensaje de error no confirma que el recurso exista. La comparación la
     * hace el service con la organización del paciente y el tenant del claim.</p>
     *
     * @param id identificador del paciente
     * @return 200 con el paciente encontrado
     */
    @GetMapping("/pacientes/{id}")
    @Operation(summary = "Buscar paciente por id",
            description = "Devuelve un paciente existente a partir de su identificador UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente encontrado",
                    content = @Content(schema = @Schema(implementation = PacienteResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Paciente inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PacienteResponse> buscarPorId(
            @Parameter(description = "Identificador UUID del paciente.", example = "0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b")
            @PathVariable UUID id) {
        return ResponseEntity.ok(pacienteService.buscarPorId(id, tenantAccessGuard.tenantActual()));
    }

    /**
     * Reemplaza los seis campos editables de un paciente existente.
     *
     * <p>La organización nunca cambia porque el request no la contiene y el mapper no toca la
     * asociación. {@code id}, {@code activo} y {@code createdAt} tampoco: actualizar un
     * paciente inactivo no lo reactiva —para eso está {@code activar}— y la fecha de alta no
     * se reescribe. Un valor {@code null} en los campos opcionales borra el valor anterior,
     * que es el mismo criterio que el resto de los módulos.</p>
     *
     * @param id      identificador del paciente
     * @param request datos de entrada validados
     * @return 200 con el paciente actualizado
     */
    @PutMapping("/pacientes/{id}")
    @Operation(summary = "Actualizar paciente",
            description = "Reemplaza nombre, teléfono, fecha de nacimiento, sexo, correo y notas de un paciente existente. Ni la organización dueña, ni el estado lógico, ni la fecha de alta se modifican.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente actualizado",
                    content = @Content(schema = @Schema(implementation = PacienteResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Paciente inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PacienteResponse> actualizar(
            @Parameter(description = "Identificador UUID del paciente a actualizar.", example = "0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b")
            @PathVariable UUID id,
            @Valid @RequestBody PacienteRequest request) {
        return ResponseEntity.ok(
                pacienteService.actualizar(id, tenantAccessGuard.tenantActual(), request));
    }

    /**
     * Aplica la baja lógica de un paciente. Es idempotente: repetir la operación sobre un
     * paciente ya inactivo sigue devolviendo 204. No existe borrado físico porque
     * {@code cita.paciente_id} y {@code adjunto.paciente_id} son {@code NO ACTION} en V1
     * —la base lo bloquearía— y, si no lo hiciera, se perdería el historial clínico.
     *
     * @param id identificador del paciente
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/pacientes/{id}")
    @Operation(summary = "Desactivar paciente",
            description = "Aplica la baja lógica de un paciente existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Paciente dado de baja lógica, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Paciente inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "Identificador UUID del paciente a desactivar.", example = "0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b")
            @PathVariable UUID id) {
        pacienteService.desactivar(id, tenantAccessGuard.tenantActual());
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactiva un paciente dado de baja lógica. Es idempotente: activar un paciente que ya
     * está activo sigue devolviendo 204 y no duplica la fila.
     *
     * <p>Es una operación propia, no un efecto de {@code PUT}: un paciente que vuelve a la
     * clínica después de meses debe seguir siendo el mismo registro. Sin ella, la única forma
     * de recibirlo de nuevo sería crearlo otra vez, y eso partiría su historial clínico entre
     * dos identificadores. Por eso la actualización no reactiva y esta ruta existe.</p>
     *
     * @param id identificador del paciente
     * @return 204 sin cuerpo
     */
    @PostMapping("/pacientes/{id}/activar")
    @Operation(summary = "Reactivar paciente",
            description = "Revierte la baja lógica de un paciente existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Paciente reactivado, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Paciente inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> activar(
            @Parameter(description = "Identificador UUID del paciente a reactivar.", example = "0c9f8a1b-2d3e-4f56-8a9b-1c2d3e4f5a6b")
            @PathVariable UUID id) {
        pacienteService.activar(id, tenantAccessGuard.tenantActual());
        return ResponseEntity.noContent().build();
    }
}
