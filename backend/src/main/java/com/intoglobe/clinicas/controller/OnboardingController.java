package com.intoglobe.clinicas.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intoglobe.clinicas.dto.request.RegistroRequest;
import com.intoglobe.clinicas.dto.response.ErrorResponse;
import com.intoglobe.clinicas.dto.response.PlanDisponibleResponse;
import com.intoglobe.clinicas.dto.response.RegistroResponse;
import com.intoglobe.clinicas.service.OnboardingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Punto de entrada público del onboarding: el alta de una clínica y el catálogo de planes.
 *
 * <p><strong>Las dos operaciones son públicas</strong>, sin token, y son las únicas de la API que
 * escriben y lo son. La celda {@code permitAll} vive en
 * {@code com.intoglobe.clinicas.security.SecurityConfig}, acotada a estos dos verbos y rutas
 * exactos: {@code POST /api/v1/onboarding/registro-clinica} y {@code GET /api/v1/onboarding/planes}.
 * La prueba de aislamiento {@code OnboardingRutasPublicasTest} demuestra que abrirlas no abre ninguna
 * otra escritura —ni {@code POST /organizaciones}, que sigue cerrado— y que un verbo no declarado
 * sobre la misma ruta, o un segmento de más, quedan cerrados.</p>
 *
 * <p><strong>La ruta la fija el cliente, no este servidor.</strong> El formulario de la landing ya
 * posteaba a {@code /onboarding/registro-clinica} y esperaba {@code { organizacionId, nombreClinica }}:
 * el contrato se implementa tal como estaba escrito (S1, S2).</p>
 *
 * <p>El controlador no tiene reglas de negocio: valida la forma con Bean Validation y delega en
 * {@link OnboardingService}. Todos los errores —validación, plan fuera del enum, JSON mal formado—
 * viajan por {@code GlobalExceptionHandler} con el {@code ErrorResponse} del resto de la API.</p>
 *
 * <p><strong>No hay cabecera {@code Location}.</strong> El recurso creado está detrás de la matriz de
 * autorización —leer la organización exige un token de su propio tenant, y quien se acaba de
 * registrar todavía no tiene sesión—, así que un {@code Location} apuntaría a una ruta que responde
 * {@code 401}. El contrato del cliente pide el identificador en el cuerpo y eso es lo que se
 * devuelve.</p>
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "v1 · onboarding", description = "Alta pública de una clínica con su primer administrador y catálogo de planes.")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * Registra una clínica y su primer administrador.
     *
     * @param request nombre de la clínica, plan, nombre y correo del administrador
     * @return {@code 201} con el identificador y el nombre de la organización creada
     */
    @PostMapping("/registro-clinica")
    @Operation(summary = "Registrar una clínica",
            description = "Crea la organización —activa, con el plan elegido y un slug único derivado del nombre— y su primer administrador en una sola transacción. Es público: no requiere token. La contraseña no viaja en el cuerpo y la cuenta entra con su correo y la contraseña del entorno del servicio de identidad.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Clínica y administrador creados",
                    content = @Content(schema = @Schema(implementation = RegistroResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos: nombre de la clínica sin letras, plan fuera del enum, nombre del administrador vacío o con dígitos, correo sin forma de correo, JSON mal formado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RegistroResponse> registrar(@Valid @RequestBody RegistroRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.registrar(request));
    }

    /**
     * Publica los planes contratables con su etiqueta y sus límites.
     *
     * @return {@code 200} con el catálogo de planes
     */
    @GetMapping("/planes")
    @Operation(summary = "Catálogo de planes",
            description = "Devuelve cada plan con su etiqueta y sus límites de producto: SOLO un médico y un consultorio, CLINICA diez y seis, ENTERPRISE sin tope (null). El formulario los muestra en solo lectura; no se reciben del cliente. Es público: no requiere token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Catálogo de planes",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PlanDisponibleResponse.class))))
    })
    public ResponseEntity<List<PlanDisponibleResponse>> listarPlanes() {
        return ResponseEntity.ok(onboardingService.listarPlanes());
    }
}
