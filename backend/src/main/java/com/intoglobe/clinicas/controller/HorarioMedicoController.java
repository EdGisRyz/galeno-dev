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

import com.intoglobe.clinicas.dto.request.HorarioMedicoRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.HorarioMedicoResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.HorarioMedicoService;

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
 * Punto de entrada HTTP de los bloques de disponibilidad de un médico.
 *
 * <p>El controller no contiene reglas de negocio: solo valida la forma del request con Bean
 * Validation y delega en {@link HorarioMedicoService}. Ningún error se construye aquí, viajan
 * todos por {@code GlobalExceptionHandler} para que el contrato de error sea el mismo que el
 * del resto de los módulos. Eso incluye los cinco {@code 400} que <em>no</em> produce este
 * archivo: rango horario invertido, duración fuera del catálogo y solapamiento los decide el
 * service contra el estado de la base, y el {@code 404} del médico y del horario inexistentes
 * —o de otra organización— y el {@code 403} de un médico que opera sobre la agenda de otro
 * también. Aquí solo se propagan.</p>
 *
 * <p>La ruta base es {@code /api/v1} porque el módulo repite la convención del módulo 03: las
 * operaciones de colección cuelgan del dueño inmediato
 * ({@code /medicos/{medicoId}/horarios}) y las de ítem son planas
 * ({@code /horarios/{id}}). El dueño inmediato de un bloque es el médico, no la organización:
 * la agenda es del médico y la organización ya está a un salto de distancia, así que
 * anidar la ruta bajo la organización agregaría un segmento que el cliente tendría que
 * arrastrar sin obtener nada. Por eso {@code medicoId} viaja en el path del alta y el
 * {@code PUT} no lo recibe en absoluto —mover un bloque de un médico a otro dejaría la agenda
 * del primero sin ese bloque sin avisar— y el {@link HorarioMedicoRequest} es el mismo en
 * ambas operaciones.</p>
 *
 * <p>La cabecera {@code Location} del alta apunta siempre a la ruta plana del ítem y se emite
 * como ruta relativa, para que siga siendo correcta detrás de cualquier proxy reverso.</p>
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013).</strong> Este controller no decide
 * autorización: la cadena de filtros exige {@code ADMIN} o {@code MEDICO} en las cinco rutas y
 * deja a {@code PACIENTE} cerrado por el fallback deny-by-default. Lo único que hace aquí es
 * tomar el principal ya autenticado —con {@link TenantAccessGuard#usuarioActual()}, igual que
 * {@code MedicoController}, para que el {@code tenantId}, el {@code role} y el
 * {@code medicoId} salgan del token y nunca del path— y pasarlo al service. El acotamiento por
 * organización (404 para un médico o un bloque de otra clínica) y la propiedad de la agenda
 * (403 cuando un {@code MEDICO} toca la agenda de otro) viven en {@code HorarioMedicoService},
 * que es quien conoce el estado de la base.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · horarios", description = "Alta, consulta, actualización y baja de los bloques de disponibilidad de un médico.")
public class HorarioMedicoController {

    private final HorarioMedicoService horarioMedicoService;
    private final TenantAccessGuard tenantAccessGuard;

    public HorarioMedicoController(HorarioMedicoService horarioMedicoService,
            TenantAccessGuard tenantAccessGuard) {
        this.horarioMedicoService = horarioMedicoService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Registra un bloque de disponibilidad para el médico indicado en el path.
     *
     * <p>El médico no viaja en el cuerpo: llega por la ruta porque es el dueño de la agenda y
     * un bloque no puede cambiar de dueño. El identificador se resuelve antes de mirar el
     * cuerpo, así que un médico inexistente responde {@code 404} y no persiste nada, en lugar
     * de dejar que la clave foránea produzca un {@code 500}. Las tres reglas de negocio del
     * cuerpo las aplica el service, en un orden que forma parte del contrato.</p>
     *
     * @param medicoId médico dueño de la agenda, tomado del path
     * @param request  datos de entrada validados
     * @return 201 con el bloque creado y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping("/medicos/{medicoId}/horarios")
    @Operation(summary = "Crear bloque de disponibilidad",
            description = "Registra un bloque de disponibilidad para el médico indicado en la ruta y devuelve su representación junto con la cabecera Location apuntando a la ruta plana del recurso creado.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bloque de disponibilidad creado",
                    content = @Content(schema = @Schema(implementation = HorarioMedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado, identificador del médico con formato inválido, rango horario inválido, duración no permitida o solapamiento con otro bloque del mismo médico",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y la agenda indicada no es la suya",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<HorarioMedicoResponse> crear(
            @Parameter(description = "Identificador UUID del médico dueño de la agenda.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID medicoId,
            @Valid @RequestBody HorarioMedicoRequest request) {
        var creado = horarioMedicoService.crear(tenantAccessGuard.usuarioActual(), medicoId, request);
        return ResponseEntity.created(URI.create("/api/v1/horarios/" + creado.id())).body(creado);
    }

    /**
     * Lista los bloques de disponibilidad de un médico, ordenados por día y hora de inicio.
     *
     * <p>La existencia del padre es parte del contrato de la ruta anidada: un médico
     * inexistente responde {@code 404} en vez de un {@code 200 []} que ocultaría el error del
     * cliente. Esa comprobación vive en el service. El orden —día y después hora de
     * inicio— también: lo fija la consulta y es lo que permite pintar la rejilla semanal tal
     * como llega, sin reordenar en el cliente.</p>
     *
     * @param medicoId médico dueño de la agenda
     * @return 200 con el arreglo de bloques de ese médico
     */
    @GetMapping("/medicos/{medicoId}/horarios")
    @Operation(summary = "Listar bloques de disponibilidad de un médico",
            description = "Devuelve los bloques de disponibilidad del médico indicado, ordenados por día de la semana y hora de inicio.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de bloques de disponibilidad del médico",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = HorarioMedicoResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador del médico con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y la agenda consultada no es la suya",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<HorarioMedicoResponse>> listarPorMedico(
            @Parameter(description = "Identificador UUID del médico cuya agenda se lista.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID medicoId) {
        return ResponseEntity.ok(
                horarioMedicoService.listarPorMedico(tenantAccessGuard.usuarioActual(), medicoId));
    }

    /**
     * Consulta un bloque de disponibilidad por su identificador.
     *
     * <p>Es una ruta plana: el dueño no participa en la búsqueda porque el identificador del
     * bloque es único en la tabla. Un identificador inexistente responde {@code 404}, igual
     * que en el resto de los módulos, y un bloque de otra organización responde el mismo
     * {@code 404} para no revelar que existe. El service es quien compara la organización del
     * bloque con el tenant del token y quien rechaza con {@code 403} a un médico que pide el
     * bloque de otro.</p>
     *
     * @param id identificador del bloque
     * @return 200 con el bloque encontrado
     */
    @GetMapping("/horarios/{id}")
    @Operation(summary = "Buscar bloque de disponibilidad por id",
            description = "Devuelve un bloque de disponibilidad existente a partir de su identificador UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bloque de disponibilidad encontrado",
                    content = @Content(schema = @Schema(implementation = HorarioMedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y el bloque no es de su agenda",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Bloque de disponibilidad inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<HorarioMedicoResponse> buscarPorId(
            @Parameter(description = "Identificador UUID del bloque de disponibilidad.", example = "c1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id) {
        return ResponseEntity.ok(horarioMedicoService.buscarPorId(tenantAccessGuard.usuarioActual(), id));
    }

    /**
     * Reemplaza los cuatro campos editables de un bloque de disponibilidad existente.
     *
     * <p>El médico nunca cambia porque el request no lo contiene y el mapper no toca la
     * asociación: un {@code 200} que moviera el bloque a otro médico sería un cambio de dueño
     * silencioso. El service repite sobre el bloque editado las mismas tres reglas del alta,
     * esta vez sin contar el propio bloque como solapado consigo mismo, y publica sus dos
     * {@code 400} en el contrato para que el cliente no los confunda con el de un DTO
     * inválido.</p>
     *
     * @param id      identificador del bloque
     * @param request datos de entrada validados
     * @return 200 con el bloque actualizado
     */
    @PutMapping("/horarios/{id}")
    @Operation(summary = "Actualizar bloque de disponibilidad",
            description = "Reemplaza el día, la hora de inicio, la hora de fin y la duración de un bloque de disponibilidad existente. El médico dueño de la agenda no se modifica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bloque de disponibilidad actualizado",
                    content = @Content(schema = @Schema(implementation = HorarioMedicoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Rango horario inválido, duración no permitida o solapamiento con otro bloque del mismo médico",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y el bloque no es de su agenda",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Bloque de disponibilidad inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<HorarioMedicoResponse> actualizar(
            @Parameter(description = "Identificador UUID del bloque de disponibilidad a actualizar.", example = "c1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id,
            @Valid @RequestBody HorarioMedicoRequest request) {
        return ResponseEntity.ok(
                horarioMedicoService.actualizar(tenantAccessGuard.usuarioActual(), id, request));
    }

    /**
     * Elimina un bloque de disponibilidad.
     *
     * <p>El borrado es físico y no idempotente en el contrato: {@code horario_medico} no tiene
     * columna {@code activo} y ninguna otra tabla la referencia, así que no hay estado que
     * conservar, y repetir la operación sobre un bloque ya eliminado responde {@code 404} en
     * vez de {@code 204}.</p>
     *
     * @param id identificador del bloque
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/horarios/{id}")
    @Operation(summary = "Eliminar bloque de disponibilidad",
            description = "Elimina definitivamente un bloque de disponibilidad existente. No devuelve cuerpo y el bloque eliminado no puede recuperarse.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Bloque de disponibilidad eliminado, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y el bloque no es de su agenda",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Bloque de disponibilidad inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> eliminar(
            @Parameter(description = "Identificador UUID del bloque de disponibilidad a eliminar.", example = "c1a2b3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID id) {
        horarioMedicoService.eliminar(tenantAccessGuard.usuarioActual(), id);
        return ResponseEntity.noContent().build();
    }
}
