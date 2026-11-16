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

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.security.TenantAccessGuard;
import com.intoglobe.clinicas.service.UsuarioService;

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
 * Punto de entrada HTTP de las cuentas de usuario.
 *
 * <p>El controller no contiene reglas de negocio: valida la forma del request con Bean Validation y
 * delega en {@link UsuarioService}. Ningún error se construye aquí; todos viajan por
 * {@code GlobalExceptionHandler} para que el contrato de error sea el mismo que el del resto de los
 * módulos. Eso incluye los {@code 404} que no produce este archivo —organización del path inexistente
 * o distinta del tenant, cuenta o médico inexistente o de otra organización—, los {@code 409} del
 * correo repetido y de la última administradora, y el {@code 403} de la operación sobre la propia
 * cuenta: los decide el service contra el estado de la base y aquí solo se propagan.</p>
 *
 * <p>La ruta base es {@code /api/v1} y repite la convención de pacientes: la colección cuelga del
 * dueño inmediato ({@code /organizaciones/{organizacionId}/usuarios}) y las operaciones de ítem son
 * planas ({@code /usuarios/{id}}). La organización no viaja en el cuerpo —en el alta llega por el
 * path y la actualización no la recibe—, así que un único {@link UsuarioRequest} sirve para las dos
 * operaciones y el cuerpo no puede expresar un cambio de dueño. La cabecera {@code Location} del alta
 * apunta a la ruta plana del ítem y se emite como ruta relativa, para que siga siendo correcta detrás
 * de cualquier proxy reverso.</p>
 *
 * <p><strong>El actor sale del claim, nunca de la URL ni del cuerpo (R11).</strong> La baja y el
 * cambio de rol reciben el {@code AuthenticatedUser} del {@code SecurityContext} —con
 * {@link TenantAccessGuard#usuarioActual()}— porque el servicio necesita saber <em>quién</em> opera
 * para rechazar que una cuenta se dé de baja o se quite el rol a sí misma. El resto de las
 * operaciones solo necesita el tenant, y también lo toma del claim
 * ({@link TenantAccessGuard#tenantActual()}). El {@code organizacionId} del path se propaga tal cual
 * para que el servicio lo contraste, como en los módulos anteriores.</p>
 *
 * <p><strong>Autorización.</strong> Qué rol alcanza estas rutas lo decide la cadena de filtros, en
 * {@code SecurityConfig}, y no este archivo: mientras el módulo no tenga sus celdas en la matriz, el
 * fallback deny-by-default cierra las seis operaciones para todos los roles.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "v1 · usuarios", description = "Administración de las cuentas de usuario de una organización: alta, listado, consulta, actualización, baja lógica y reactivación.")
public class UsuarioController {

    private final UsuarioService usuarioService;
    private final TenantAccessGuard tenantAccessGuard;

    public UsuarioController(UsuarioService usuarioService, TenantAccessGuard tenantAccessGuard) {
        this.usuarioService = usuarioService;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    /**
     * Crea una cuenta dentro de la organización indicada en el path.
     *
     * <p>La organización no se recibe en el cuerpo. El servicio resuelve la organización contra la
     * base y la compara con el tenant del claim antes de nada, comprueba que el médico —si lo hay—
     * sea de esa misma organización y que el correo esté libre. La identidad de autenticación
     * ({@code authUserId}) la genera el servicio y no se recibe ni se devuelve.</p>
     *
     * @param organizacionId organización dueña tomada del path
     * @param request        datos de entrada validados
     * @return 201 con la cuenta creada y la cabecera {@code Location} hacia su recurso
     */
    @PostMapping("/organizaciones/{organizacionId}/usuarios")
    @Operation(summary = "Crear cuenta de usuario",
            description = "Crea una cuenta dentro de la organización indicada en la ruta y devuelve su representación junto con la cabecera Location apuntando a la ruta plana del recurso creado. La identidad de autenticación la genera el servidor y no se expone.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cuenta creada",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, rol fuera del conjunto admitido, JSON mal formado o falta el médico de una cuenta MEDICO",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "La organización de la ruta no es la del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización, cuenta o médico inexistente, o médico de otra organización",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "El correo ya está en uso dentro de la organización",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UsuarioResponse> crear(
            @Parameter(description = "Identificador UUID de la organización dueña de la cuenta.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId,
            @Valid @RequestBody UsuarioRequest request) {
        var creado = usuarioService.crear(organizacionId, tenantAccessGuard.tenantActual(), request);
        return ResponseEntity.created(URI.create("/api/v1/usuarios/" + creado.id())).body(creado);
    }

    /**
     * Lista las cuentas de una organización, activas e inactivas, ordenadas por correo ascendente.
     *
     * <p>Devuelve las dos cosas a propósito: la baja es lógica, así que ocultar las inactivas dejaría
     * al administrador sin forma de reactivar a quien vuelve. La existencia del padre es parte del
     * contrato de la ruta anidada —un id inexistente responde 404 en vez de un {@code 200 []}— y el
     * orden lo fija la consulta del repositorio.</p>
     *
     * @param organizacionId organización dueña de las cuentas
     * @return 200 con el arreglo de cuentas ordenado por correo ascendente
     */
    @GetMapping("/organizaciones/{organizacionId}/usuarios")
    @Operation(summary = "Listar cuentas de una organización",
            description = "Devuelve las cuentas de la organización indicada, tanto las activas como las dadas de baja lógica, ordenadas por correo ascendente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de cuentas activas e inactivas, por correo ascendente",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = UsuarioResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador de la organización con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "La organización de la ruta no es la del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organización inexistente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<UsuarioResponse>> listarPorOrganizacion(
            @Parameter(description = "Identificador UUID de la organización cuyas cuentas se listan.", example = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90")
            @PathVariable UUID organizacionId) {
        return ResponseEntity.ok(
                usuarioService.listarPorOrganizacion(organizacionId, tenantAccessGuard.tenantActual()));
    }

    /**
     * Consulta una cuenta existente de la propia organización del token.
     *
     * <p>Una cuenta de otra organización responde {@code 404}, igual que un identificador inexistente:
     * un {@code 403} confirmaría que existe en otra clínica.</p>
     *
     * @param id identificador de la cuenta
     * @return 200 con la cuenta encontrada
     */
    @GetMapping("/usuarios/{id}")
    @Operation(summary = "Buscar cuenta por id",
            description = "Devuelve una cuenta existente a partir de su identificador UUID. Una cuenta de otra organización responde 404, igual que un identificador inexistente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cuenta encontrada",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UsuarioResponse> buscarPorId(
            @Parameter(description = "Identificador UUID de la cuenta.", example = "b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13")
            @PathVariable UUID id) {
        return ResponseEntity.ok(usuarioService.buscarPorId(id, tenantAccessGuard.tenantActual()));
    }

    /**
     * Reemplaza el correo, el rol y el médico de una cuenta existente.
     *
     * <p>La organización, la identidad de autenticación, el estado lógico y la fecha de alta nunca
     * cambian. Cambiar de {@code MEDICO} a otro rol libera el médico, y retirarle el rol a la última
     * administradora activa responde 409. El actor de la sesión viaja al servicio para que nadie se
     * cambie el rol a sí mismo (R11), que responde 403.</p>
     *
     * @param id      identificador de la cuenta
     * @param request datos de entrada validados
     * @return 200 con la cuenta actualizada
     */
    @PutMapping("/usuarios/{id}")
    @Operation(summary = "Actualizar cuenta de usuario",
            description = "Reemplaza correo, rol y médico de una cuenta existente. Ni la organización dueña, ni el estado lógico, ni la fecha de alta, ni la identidad de autenticación se modifican. Una cuenta no puede cambiarse el rol a sí misma.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cuenta actualizada",
                    content = @Content(schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, rol fuera del conjunto admitido, JSON mal formado o falta el médico de una cuenta MEDICO",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "La cuenta intenta cambiarse el rol a sí misma",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente en la organización del token, o médico inexistente o de otra organización",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "El correo ya está en uso, o la operación dejaría a la organización sin ninguna cuenta ADMIN activa",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UsuarioResponse> actualizar(
            @Parameter(description = "Identificador UUID de la cuenta a actualizar.", example = "b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13")
            @PathVariable UUID id,
            @Valid @RequestBody UsuarioRequest request) {
        return ResponseEntity.ok(
                usuarioService.actualizar(tenantAccessGuard.usuarioActual(), id, request));
    }

    /**
     * Aplica la baja lógica de una cuenta. Es idempotente: repetirla sobre una cuenta ya inactiva
     * sigue devolviendo 204. No existe borrado físico: la cuenta conserva el historial de la
     * organización y su correo sigue ocupado por la restricción de unicidad.
     *
     * <p>Responde 403 si el actor intenta darse de baja a sí mismo (R11) y 409 si es la última
     * administradora activa de la organización (R8).</p>
     *
     * @param id identificador de la cuenta
     * @return 204 sin cuerpo
     */
    @DeleteMapping("/usuarios/{id}")
    @Operation(summary = "Dar de baja una cuenta",
            description = "Aplica la baja lógica de una cuenta existente. La operación es idempotente y no devuelve cuerpo. Una cuenta no puede darse de baja a sí misma y la última cuenta ADMIN activa no puede darse de baja.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cuenta dada de baja lógica, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "La cuenta intenta darse de baja a sí misma",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Es la última cuenta ADMIN activa de la organización",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "Identificador UUID de la cuenta a dar de baja.", example = "b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13")
            @PathVariable UUID id) {
        usuarioService.desactivar(tenantAccessGuard.usuarioActual(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactiva una cuenta dada de baja lógica. Es idempotente: activar una cuenta que ya está activa
     * sigue devolviendo 204 y no duplica la fila.
     *
     * <p>Es una operación propia, no un efecto de {@code PUT}: sin ella, la única forma de recuperar a
     * una persona que vuelve sería crear otra cuenta, y eso chocaría con la unicidad del correo
     * además de partir su historial en dos identificadores.</p>
     *
     * @param id identificador de la cuenta
     * @return 204 sin cuerpo
     */
    @PostMapping("/usuarios/{id}/activar")
    @Operation(summary = "Reactivar cuenta",
            description = "Revierte la baja lógica de una cuenta existente. La operación es idempotente y no devuelve cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cuenta reactivada, sin cuerpo",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Identificador con formato inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Cuenta inexistente en la organización del token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> activar(
            @Parameter(description = "Identificador UUID de la cuenta a reactivar.", example = "b7d4f2c8-1a3e-4c59-8f60-2d7b9e5a4c13")
            @PathVariable UUID id) {
        usuarioService.activar(id, tenantAccessGuard.tenantActual());
        return ResponseEntity.noContent().build();
    }
}
