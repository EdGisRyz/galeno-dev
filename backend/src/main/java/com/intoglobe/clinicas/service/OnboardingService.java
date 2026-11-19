package com.intoglobe.clinicas.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.RegistroRequest;
import com.intoglobe.clinicas.dto.response.PlanDisponibleResponse;
import com.intoglobe.clinicas.dto.response.RegistroResponse;
import com.intoglobe.clinicas.model.PlanTipo;

/**
 * Caso de uso público del onboarding: registrar una clínica y publicar el catálogo de planes.
 *
 * <p><strong>El alta es una sola transacción</strong> (S1). La organización y su primer
 * administrador se crean dentro de este método, y las dos escrituras se confirman o se deshacen
 * juntas: una clínica sin administración es un registro que nadie puede usar, y un administrador sin
 * organización apuntaría a un tenant inexistente. El {@code @Transactional} va aquí, en el caso de
 * uso, y no en cada servicio de dominio: cada uno de esos métodos abre su propia transacción cuando
 * se le llama por su cuenta, y al venir de aquí se suman a la de este método (propagación
 * {@code REQUIRED}), que es la que decide el desenlace.</p>
 *
 * <p><strong>No reimplementa nada.</strong> La organización la crea {@link OrganizacionService} —con
 * su plan y su slug único— y la cuenta la crea {@link UsuarioService#crearAdministrador}, que es el
 * mismo camino que el alta de cuentas. Este servicio solo ordena las dos escrituras y traduce al
 * contrato de salida.</p>
 *
 * <p>El catálogo de planes se publica desde {@link PlanTipo}, que es el único lugar donde viven las
 * etiquetas y los límites: el formulario los pinta en solo lectura y el registro no los recibe.</p>
 */
@Service
public class OnboardingService {

    private final OrganizacionService organizacionService;
    private final UsuarioService usuarioService;

    public OnboardingService(OrganizacionService organizacionService, UsuarioService usuarioService) {
        this.organizacionService = organizacionService;
        this.usuarioService = usuarioService;
    }

    /**
     * Crea la organización y su primer administrador en una sola transacción.
     *
     * @param request datos validados del formulario: nombre de la clínica, plan, nombre y correo del
     *                administrador
     * @return el identificador y el nombre de la organización creada; nunca datos de autenticación
     */
    @Transactional
    public RegistroResponse registrar(RegistroRequest request) {
        var organizacion = organizacionService.crearParaRegistro(request.nombreClinica(), request.plan());
        usuarioService.crearAdministrador(organizacion, request.nombreAdmin(), request.correoAdmin());
        return new RegistroResponse(organizacion.getId(), organizacion.getNombre());
    }

    /**
     * Publica los planes contratables con su etiqueta y sus límites de producto.
     *
     * @return los planes del esquema, en el orden declarado en {@link PlanTipo}
     */
    @Transactional(readOnly = true)
    public List<PlanDisponibleResponse> listarPlanes() {
        return Arrays.stream(PlanTipo.values())
                .map(plan -> new PlanDisponibleResponse(
                        plan, plan.getEtiqueta(), plan.getMaxMedicos(), plan.getMaxConsultorios()))
                .toList();
    }
}
