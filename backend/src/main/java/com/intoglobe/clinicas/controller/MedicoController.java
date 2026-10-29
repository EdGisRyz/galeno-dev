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

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.MedicoService;

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
 * Punto de entrada HTTP de médicos.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request
 * (Bean Validation) y delega en {@link MedicoService}. Los errores no se construyen
 * aquí, viajan siempre por {@code GlobalExceptionHandler} para que el contrato de
 * error sea único en todos los módulos. Esto incluye los 404 de organización y
 * consultorio inexistentes y el 400 del cambio de consultorio: los tres dependen del
 * estado de la base, así que su comprobación vive en el service y aquí solo se
 * propaga.</p>
 *
 * <p>La ruta base es {@code /api/v1} porque el módulo expone dos formas, igual que el
 * módulo 02: las operaciones de colección cuelgan de la organización
 * ({@code /organizaciones/{organizacionId}/medicos}) y las de ítem son planas
 * ({@code /medicos/{id}}). El dueño inmediato del médico es la organización y por eso
 * no viaja en el cuerpo: en la creación llega por el path y la actualización no la
 * recibe. El consultorio, en cambio, sí viaja en el cuerpo porque es un atributo del
 * médico y no su dueño. La cabecera {@code Location} del alta apunta siempre a la ruta
 * plana del ítem y se emite como ruta relativa para que sea correcta detrás de
 * cualquier proxy reverso.</p>
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013):</strong> la cadena de filtros
 * exige {@code ADMIN} para crear, actualizar y desactivar, y admite {@code MEDICO} solo en
 * las lecturas. El {@code organizacionId} del path se acepta únicamente si coincide con el
 * {@code tenantId} del token —{@link TenantAccessGuard} responde {@code 403} en caso
 * contrario, sin exención de {@code ADMIN}— y es el tenant del claim el que se propaga al
 * service. En las rutas planas el tenant también sale del claim, y un médico de otra
 * organización responde {@code 404} para no revelar que existe: es el cierre del IDOR que
 * tenía este módulo cuando se buscaba por id sin mirar la organización.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · medicos", description = "Alta, consulta, actualización y baja lógica de médicos.")
public class MedicoController {

    private final MedicoService medicoService;
    private final TenantAccessGuard tenantAccessGuard;

    public MedicoController(MedicoService medicoService, TenantAccessGuard tenantAccessGuard) {
        this.medicoService = medicoService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Registra un médico dentro de la organización indicada en el path.
     *
     * <p>La organización no se recibe en el cuerpo: llega por la ruta porque un médico
     * no puede cambiar de dueño. El path se valida contra el claim del token; el tenant del
     * claim y el {@code consultorioId} del cuerpo los resuelve el service contra la base; si
     * alguno no existe, la excepción de negocio se traduce en 404 en vez de dejar que la
     * clave foránea produzca un 500.</p>
     *
     * @param organizacionId organización dueña tomada del path, validada contra el token
     * @param request        datos de entrada validados
     * @return 201 con el médico creado y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping("/organizaciones/{organizacionId}/medicos")
    @Operation(summary = "Crear médico",
            description = "Registra un médico dentro de la organización indicada en la ruta y devuelve su representación junto con la cabecera Location apuntando a la ruta plana del recurso creado.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Médico creado",
                    content = @Content(schema = @Schema(implementation = MedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización o consultorio inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicoResponse> crear(
            @Parameter(description = "Identificador UUID de la organización dueña del médico.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId,
            @Valid @RequestBody MedicoRequest request) {
        var creado = medicoService.crear(tenantAccessGuard.exigirTenantDeLaRuta(organizacionId), request);
        return ResponseEntity.created(URI.create("/api/v1/medicos/" + creado.id())).body(creado);
    }

    /**
     * Lista los médicos activos de una organización, ordenados por nombre.
     *
     * <p>La existencia del padre es parte del contrato de la ruta anidada: un id
     * inexistente responde 404 en vez de un {@code 200 []} que ocultaría el error del
     * cliente. Esa comprobación vive en el service, no aquí. Los médicos dados de baja
     * lógica quedan fuera del listado.</p>
     *
     * @param organizacionId organización dueña de los médicos, validada contra el token
     * @return 200 con el arreglo de médicos activos
     */
    @GetMapping("/organizaciones/{organizacionId}/medicos")
    @Operation(summary = "Listar médicos activos de una organización",
            description = "Devuelve los médicos activos de la organización indicada, ordenados alfabéticamente por nombre.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de médicos activos",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = MedicoResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<MedicoResponse>> listarPorOrganizacion(
            @Parameter(description = "Identificador UUID de la organización cuyos médicos se listan.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId) {
        return ResponseEntity.ok(
                medicoService.listarPorOrganizacion(tenantAccessGuard.exigirTenantDeLaRuta(organizacionId)));
    }

    /**
     * Consulta un médico existente de la propia organización del token.
     *
     * <p>Un médico de otra organización responde {@code 404}, igual que un identificador
     * inexistente: el mensaje de error no confirma que el recurso exista.</p>
     *
     * @param id identificador del médico
     * @return 200 con el médico encontrado
     */
    @GetMapping("/medicos/{id}")
    @Operation(summary = "Buscar médico por id",
            description = "Devuelve un médico existente a partir de su identificador UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Médico encontrado",
                    content = @Content(schema = @Schema(implementation = MedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicoResponse> buscarPorId(
            @Parameter(description = "Identificador UUID del médico.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id) {
        return ResponseEntity.ok(medicoService.buscarPorId(id, tenantAccessGuard.tenantActual()));
    }

    /**
     * Reemplaza los cinco campos editables de un médico existente.
     *
     * <p>La organización nunca cambia porque el request no la contiene. El consultorio
     * tampoco: si el cuerpo trae uno distinto del actual el service rechaza con 400 en
     * vez de responder 200 sin aplicar lo pedido. Este controller no compara nada; solo
     * publica en el contrato que ese 400 existe y que su causa es distinta de la de un
     * DTO inválido, para que el cliente no las confunda.</p>
     *
     * @param id      identificador del médico
     * @param request datos de entrada validados
     * @return 200 con el médico actualizado
     */
    @PutMapping("/medicos/{id}")
    @Operation(summary = "Actualizar médico",
            description = "Reemplaza nombre, especialidad, cédula, teléfono y tarifa base de un médico existente. Ni la organización dueña ni el consultorio pueden modificarse.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Médico actualizado",
                    content = @Content(schema = @Schema(implementation = MedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "El consultorio enviado no es el consultorio actual del médico: el consultorio no se modifica mediante la actualización",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicoResponse> actualizar(
            @Parameter(description = "Identificador UUID del médico a actualizar.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id,
            @Valid @RequestBody MedicoRequest request) {
        return ResponseEntity.ok(medicoService.actualizar(id, tenantAccessGuard.tenantActual(), request));
    }

    /**
     * Aplica la baja lógica de un médico. Es idempotente: repetir la operación sobre un
     * médico ya inactivo sigue devolviendo 204. No existe borrado físico porque las
     * claves foráneas de {@code cita} y {@code horario_medico} lo impiden o lo harían
     * destructivo en silencio.
     *
     * @param id identificador del médico
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/medicos/{id}")
    @Operation(summary = "Desactivar médico",
            description = "Aplica la baja lógica de un médico existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Médico dado de baja lógica, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "Identificador UUID del médico a desactivar.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id) {
        medicoService.desactivar(id, tenantAccessGuard.tenantActual());
        return ResponseEntity.noContent().build();
    }
}
