package com.intoglobe.clinicas.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intoglobe.clinicas.dto.request.AsignacionConsultorioRequest;
import com.intoglobe.clinicas.dto.response.ConsultorioResponse;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.MedicoConsultorioService;

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
 * Punto de entrada HTTP de la asignación de consultorios a un médico.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request con Bean Validation
 * y delega en {@link MedicoConsultorioService}. Ningún error se construye aquí, viajan todos por
 * {@code GlobalExceptionHandler} para que el contrato de error sea el mismo que el del resto de los
 * módulos. Eso incluye los {@code 404} del médico y del consultorio inexistentes o de otra
 * organización, el {@code 403} de propiedad, y los dos {@code 409} de las reglas de conflicto: los
 * cuatro dependen del estado de la base y su comprobación vive en el service.</p>
 *
 * <p>La ruta es una colección anidada al médico —{@code /api/v1/medicos/{medicoId}/consultorios}—
 * porque el dueño inmediato del conjunto es el médico y no la organización: la asignación dice en
 * qué espacios atiende <em>ese</em> médico, y la organización ya está a un salto de distancia a
 * través de él. Por eso el {@code medicoId} viaja en el path y no hay {@code organizacionId} que
 * validar contra el claim: la organización sale siempre del token, y el cruce de tenant responde
 * {@code 404} en el service, igual que en el módulo de horarios.</p>
 *
 * <p>Las dos escrituras responden {@code 204} sin cuerpo y son <strong>idempotentes</strong>:
 * asignar un consultorio que ya estaba asignado y retirar uno que ya no estaba responden igual que
 * la primera vez. No se devuelve la representación de la asignación porque el recurso que el
 * cliente ve es la propia lista de consultorios del médico.</p>
 *
 * <p><strong>Tenant y rol (R-SEG-04 a R-SEG-06, ADR-013).</strong> La cadena de filtros exige
 * {@code ADMIN} en las dos escrituras y admite {@code MEDICO} solo en la lectura; {@code PACIENTE}
 * queda cerrado por el fallback deny-by-default. Lo único que hace aquí el controller es tomar el
 * principal ya autenticado con {@link TenantAccessGuard#usuarioActual()}, igual que
 * {@code HorarioMedicoController}, para que el {@code tenantId}, el {@code role} y el
 * {@code medicoId} salgan del token y nunca del path, y pasarlo al service.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · medicos · consultorios",
        description = "Asignación de consultorios a un médico: listado, alta idempotente y baja idempotente.")
public class MedicoConsultorioController {

    private final MedicoConsultorioService medicoConsultorioService;
    private final TenantAccessGuard tenantAccessGuard;

    public MedicoConsultorioController(MedicoConsultorioService medicoConsultorioService,
            TenantAccessGuard tenantAccessGuard) {
        this.medicoConsultorioService = medicoConsultorioService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Lista los consultorios asignados al médico indicado, ordenados por nombre del consultorio.
     *
     * <p>La existencia del padre es parte del contrato de la ruta anidada: un médico inexistente
     * responde {@code 404} en vez de un {@code 200 []} que ocultaría el error del cliente. Esa
     * comprobación vive en el service, igual que la regla de propiedad que rechaza a un
     * {@code MEDICO} que pide los consultorios de otro médico.</p>
     *
     * @param medicoId médico cuyos consultorios se listan
     * @return 200 con el arreglo de consultorios asignados a ese médico
     */
    @GetMapping("/medicos/{medicoId}/consultorios")
    @Operation(summary = "Listar los consultorios asignados a un médico",
            description = "Devuelve los consultorios asignados al médico indicado, ordenados alfabéticamente por nombre del consultorio.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de consultorios asignados al médico",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConsultorioResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador del médico con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El token es de un médico y los consultorios consultados no son los suyos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<ConsultorioResponse>> listar(
            @Parameter(description = "Identificador UUID del médico cuyos consultorios se listan.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID medicoId) {
        return ResponseEntity.ok(
                medicoConsultorioService.listar(tenantAccessGuard.usuarioActual(), medicoId));
    }

    /**
     * Asigna un consultorio al médico indicado. Es idempotente y no devuelve cuerpo.
     *
     * <p>El médico viaja en el path y el consultorio en el cuerpo: el primero es el dueño del
     * conjunto y no puede cambiar; el segundo es el elemento que se agrega. Un consultorio
     * inexistente, o de otra organización, responde {@code 404} —nunca {@code 403}, que confirmaría
     * que existe en otra clínica— antes de escribir nada.</p>
     *
     * @param medicoId médico al que se le asigna el consultorio
     * @param request  cuerpo validado con el consultorio a asignar
     * @return 204 sin cuerpo, también cuando el consultorio ya estaba asignado
     */
    @PostMapping("/medicos/{medicoId}/consultorios")
    @Operation(summary = "Asignar un consultorio a un médico",
            description = "Asigna el consultorio indicado en el cuerpo al médico de la ruta. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Consultorio asignado al médico, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, JSON mal formado o identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El rol del token no administra la asignación de consultorios",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico o consultorio inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> asignar(
            @Parameter(description = "Identificador UUID del médico al que se le asigna el consultorio.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID medicoId,
            @Valid @RequestBody AsignacionConsultorioRequest request) {
        medicoConsultorioService.asignar(tenantAccessGuard.usuarioActual(), medicoId,
                request.consultorioId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Retira un consultorio de un médico. Es idempotente y no devuelve cuerpo.
     *
     * <p>El {@code consultorioId} viaja en el path porque identifica el elemento del conjunto, no
     * un atributo que se reemplace. Dos reglas pueden impedir la baja y el service las comprueba
     * antes de borrar: el consultorio principal del médico y un consultorio que todavía tiene
     * bloques de horario suyos. Las dos responden {@code 409} con el motivo, no un error de
     * integridad.</p>
     *
     * @param medicoId      médico al que se le retira el consultorio
     * @param consultorioId consultorio que se retira
     * @return 204 sin cuerpo, también cuando el consultorio no estaba asignado
     */
    @DeleteMapping("/medicos/{medicoId}/consultorios/{consultorioId}")
    @Operation(summary = "Retirar un consultorio de un médico",
            description = "Retira el consultorio indicado de la lista del médico. La operación es idempotente, no devuelve cuerpo y no permite retirar el consultorio principal ni uno con bloques de horario del médico.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Consultorio retirado del médico, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El rol del token no administra la asignación de consultorios",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Médico o consultorio inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "El consultorio es el principal del médico o tiene bloques de horario del médico",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> quitar(
            @Parameter(description = "Identificador UUID del médico al que se le retira el consultorio.", example = "b1a2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
            @PathVariable UUID medicoId,
            @Parameter(description = "Identificador UUID del consultorio a retirar.", example = "7d2e4c18-5b6a-4f3d-8e01-9a2b3c4d5e6f")
            @PathVariable UUID consultorioId) {
        medicoConsultorioService.quitar(tenantAccessGuard.usuarioActual(), medicoId, consultorioId);
        return ResponseEntity.noContent().build();
    }
}
