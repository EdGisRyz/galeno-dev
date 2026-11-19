package com.intoglobe.clinicas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intoglobe.clinicas.dto.request.MedicoRequest;
import com.intoglobe.clinicas.dto.response.MedicoResponse;
import com.intoglobe.clinicas.exception.CambioDeConsultorioNoPermitidoException;
import com.intoglobe.clinicas.exception.MedicoConConsultorioPrincipalInactivoException;
import com.intoglobe.clinicas.exception.ResourceNotFoundException;
import com.intoglobe.clinicas.mapper.MedicoMapper;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

/**
 * Lógica de negocio de médicos.
 *
 * <p>Orquesta los cuatro repositorios y el {@link MedicoMapper}. Las lecturas son
 * transacciones de solo lectura; las escrituras usan una transacción de lectura y
 * escritura. Un recurso inexistente se traduce a
 * {@link ResourceNotFoundException}, que el handler convierte en 404.</p>
 *
 * <p>La organización que llega por el path y el consultorio que llega en el cuerpo se
 * resuelven siempre contra sus repositorios, y <strong>los dos acotados por el tenant</strong>.
 * Sin esas comprobaciones, crear un médico bajo una organización o un consultorio
 * inexistente haría fallar la clave foránea y el cliente recibiría un 500 en lugar de un
 * 404; en {@code crear} la resolución no es una consulta extra, porque aporta las
 * entidades que el mapper necesita. En el listado anidado la existencia del padre es parte
 * del contrato: un id inexistente responde 404 y no un {@code 200 []} que ocultaría el
 * error del cliente.</p>
 *
 * <p><strong>Invariante de tenencia del consultorio:</strong> {@code crear} exige que el
 * consultorio del cuerpo pertenezca a la organización del médico, y lo hace en la consulta
 * ({@link ConsultorioRepository#buscarPorIdYOrganizacion}), no comparando después de un
 * {@code findById}. {@code actualizar} no reasigna el consultorio en absoluto, así que toda
 * fila creada por la API mantiene el invariante. El schema <b>no</b> lo declara —no hay
 * clave compuesta ni comprobación en V1— y no se toca: la garantía es de aplicación y está
 * probada en {@code AislamientoYMatrizRbacTest}. También está deliberadamente ausente el
 * listado de médicos por consultorio, porque V1 no indexa {@code medico.consultorio_id}.</p>
 *
 * <p><strong>Aislamiento por tenant (R-SEG-04, D5.6):</strong> las cuatro operaciones por id
 * —buscar, actualizar, dar de baja y reactivar— reciben el {@code tenantId} del token y consultan
 * con {@link MedicoRepository#buscarPorIdYOrganizacion}. Un médico de otra organización responde
 * {@code 404}, igual que un identificador inexistente, para no revelar que existe. El tenant
 * lo aporta el llamador desde el claim del token —nunca desde el path sin validar— porque el
 * controlador ya lo obtuvo de {@code TenantAccessGuard}.</p>
 */
@Service
public class MedicoService {

    private final MedicoRepository medicoRepository;
    private final OrganizacionRepository organizacionRepository;
    private final ConsultorioRepository consultorioRepository;
    private final MedicoConsultorioRepository medicoConsultorioRepository;

    public MedicoService(MedicoRepository medicoRepository,
            OrganizacionRepository organizacionRepository,
            ConsultorioRepository consultorioRepository,
            MedicoConsultorioRepository medicoConsultorioRepository) {
        this.medicoRepository = medicoRepository;
        this.organizacionRepository = organizacionRepository;
        this.consultorioRepository = consultorioRepository;
        this.medicoConsultorioRepository = medicoConsultorioRepository;
    }

    /**
     * Crea un médico dentro de la organización indicada y su consultorio.
     *
     * <p>Ambas entidades se resuelven antes de mapear: si alguna no existe se responde
     * 404 en vez de dejar que la clave foránea produzca un 500.</p>
     *
     * <p><strong>El consultorio del cuerpo también se resuelve dentro del tenant</strong>
     * (R-SEG-04, ADR-013). El {@code consultorioId} lo elige el cliente, así que es una
     * entrada no confiable como el {@code organizacionId} del path: se busca con
     * {@link ConsultorioRepository#buscarPorIdYOrganizacion} y no con {@code findById}. Con
     * un {@code findById} sin acotar, un {@code ADMIN} de A podía enviar el consultorio de B
     * y recibía {@code 201}: confirmaba que un recurso ajeno existe (oráculo de existencia) y
     * creaba una fila cruzada entre organizaciones. Hoy un consultorio de otro tenant y un
     * UUID al azar producen el mismo {@code 404} y no se persiste nada.</p>
     *
     * <p><strong>El alta asigna el consultorio principal al médico</strong>, en la misma
     * transacción que lo crea. Es el invariante que sostiene el resto del módulo de horarios: el
     * consultorio principal ({@code medico.consultorio_id}) es el destino por defecto de un
     * bloque, y la clave {@code horario_medico_consultorio_asignado_fkey} de la V6 exige que el
     * par {@code (medico_id, consultorio_id)} exista en {@code medico_consultorio}. Sin esta
     * fila, el bloque de un médico recién creado por la API no se podría guardar. La asignación se
     * crea con el mismo constructor de negocio que usa la operación de asignar consultorios, así
     * que hereda sus invariantes: la organización sale del médico y el par es la clave.</p>
     *
     * @param organizacionId organización dueña: el tenant del token, ya validado contra el
     *                       path por el controlador
     * @param request        datos de entrada validados
     * @return representación del médico persistido
     * @throws ResourceNotFoundException si no existe la organización, o si el consultorio no
     *                                   existe <em>en esa organización</em>
     */
    @Transactional
    public MedicoResponse crear(UUID organizacionId, MedicoRequest request) {
        var organizacion = organizacionRepository.findById(organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizacion", organizacionId));
        var consultorio = consultorioRepository
                .buscarPorIdYOrganizacion(request.consultorioId(), organizacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", request.consultorioId()));
        var medico = MedicoMapper.aEntidad(organizacion, consultorio, request);
        var guardado = medicoRepository.save(medico);
        medicoConsultorioRepository.save(new MedicoConsultorio(guardado, consultorio, organizacion));
        return MedicoMapper.aResponse(guardado);
    }

    /**
     * Busca un médico por su identificador dentro del tenant indicado.
     *
     * @param id       identificador del médico
     * @param tenantId organización del token de la petición
     * @return representación del médico encontrado
     * @throws ResourceNotFoundException si no existe un médico con ese id <em>en ese
     *                                   tenant</em>; un médico de otra organización cae aquí
     *                                   a propósito, para no revelar que existe
     */
    @Transactional(readOnly = true)
    public MedicoResponse buscarPorId(UUID id, UUID tenantId) {
        return MedicoMapper.aResponse(buscarDelTenant(id, tenantId));
    }

    /**
     * Lista los médicos activos de una organización, ordenados por nombre.
     *
     * <p>Antes de listar comprueba que la organización exista: si el id del padre está
     * mal escrito, el cliente debe recibir 404 en vez de una lista vacía.</p>
     *
     * @param organizacionId organización dueña de los médicos
     * @return médicos activos de esa organización en su representación de salida
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<MedicoResponse> listarPorOrganizacion(UUID organizacionId) {
        return listarPorOrganizacion(organizacionId, false);
    }

    /**
     * Lista los médicos de una organización.
     *
     * <p>Por omisión devuelve solo los activos, que es el contrato declarado del listado. Con
     * {@code incluirInactivos} devuelve también los dados de baja, para que la pantalla pueda
     * ofrecer la reactivación en la misma fila (S8): sin ellos, el médico recién dado de baja
     * desaparece de la tabla y la operación queda inalcanzable.</p>
     *
     * <p>Las comprobaciones de acceso son las mismas en los dos casos y no dependen del
     * parámetro: la organización del path ya la contrastó el controlador contra el claim y aquí
     * se comprueba que exista.</p>
     *
     * @param organizacionId organización dueña de los médicos
     * @param incluirInactivos si se incluyen también los médicos dados de baja
     * @return médicos de esa organización en su representación de salida
     * @throws ResourceNotFoundException si no existe una organización con ese id
     */
    @Transactional(readOnly = true)
    public List<MedicoResponse> listarPorOrganizacion(UUID organizacionId, boolean incluirInactivos) {
        if (!organizacionRepository.existsById(organizacionId)) {
            throw new ResourceNotFoundException("Organizacion", organizacionId);
        }
        var medicos = incluirInactivos
                ? medicoRepository.listarPorOrganizacion(organizacionId)
                : medicoRepository.listarActivosPorOrganizacion(organizacionId);
        return medicos.stream()
                .map(MedicoMapper::aResponse)
                .toList();
    }

    /**
     * Reemplaza los cinco campos editables de un médico existente.
     *
     * <p>La organización nunca cambia: el request no la contiene. El consultorio
     * tampoco: si el cuerpo trae uno distinto del actual se rechaza con
     * {@link CambioDeConsultorioNoPermitidoException} —que el handler traduce a 400—
     * porque responder 200 sin aplicar lo pedido mentiría. Con el mismo valor la
     * actualización procede, y el mapper no toca las asociaciones.</p>
     *
     * @param id       identificador del médico a actualizar
     * @param tenantId organización del token de la petición
     * @param request  datos de entrada validados
     * @return representación del médico actualizado
     * @throws ResourceNotFoundException              si no existe un médico con ese id en ese
     *                                                tenant
     * @throws CambioDeConsultorioNoPermitidoException si el consultorio del cuerpo no es
     *                                                el consultorio actual del médico
     */
    @Transactional
    public MedicoResponse actualizar(UUID id, UUID tenantId, MedicoRequest request) {
        // El consultorioId del cuerpo no se resuelve contra el repositorio: solo se compara
        // con el consultorio ya cargado del médico acotado por tenant, así que un consultorio
        // ajeno no produce ni efecto ni oráculo de existencia.
        var medico = buscarDelTenant(id, tenantId);
        var consultorioActualId = medico.getConsultorio().getId();
        if (!request.consultorioId().equals(consultorioActualId)) {
            throw new CambioDeConsultorioNoPermitidoException(consultorioActualId, request.consultorioId());
        }
        MedicoMapper.actualizarEntidad(medico, request);
        var guardado = medicoRepository.save(medico);
        return MedicoMapper.aResponse(guardado);
    }

    /**
     * Aplica la baja lógica de un médico. Es idempotente: desactivar un médico ya
     * inactivo no produce error.
     *
     * <p>El borrado físico está descartado: la clave foránea de {@code cita} lo
     * bloquea y la de {@code horario_medico} borraría los horarios en silencio.</p>
     *
     * @param id       identificador del médico a desactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un médico con ese id en ese tenant
     */
    @Transactional
    public void desactivar(UUID id, UUID tenantId) {
        var medico = buscarDelTenant(id, tenantId);
        medico.desactivar();
        medicoRepository.save(medico);
    }

    /**
     * Reactiva un médico dado de baja lógica. Es idempotente: activar uno que ya está activo
     * no produce error ni duplica la fila. Solo cambia {@code activo}.
     *
     * <p><strong>Por qué existe:</strong> un médico dado de baja por error solo volvía
     * creando otro, y con el alta nueva se perdían sus bloques de horario y sus asignaciones
     * de consultorio. La baja es lógica justamente para que el registro se pueda recuperar;
     * esta operación es esa vuelta.</p>
     *
     * <p><strong>No reactiva la cuenta de usuario</strong> (S5): el acceso de un médico se cierra
     * por su {@code activo}, no por la cuenta, así que reactivarlo no reactiva ninguna cuenta
     * y una cuenta dada de baja sigue de baja.</p>
     *
     * <p><strong>El consultorio principal tiene que estar activo (S7).</strong> Es la otra mitad
     * de la invariante de S6: un médico activo con su principal dado de baja dejaría la agenda
     * apuntando a una sala cerrada. Se responde
     * {@link MedicoConConsultorioPrincipalInactivoException} —{@code 409}— con el camino para
     * desbloquear la operación: reactivar primero el consultorio.</p>
     *
     * @param id       identificador del médico a reactivar
     * @param tenantId organización del token de la petición
     * @throws ResourceNotFoundException si no existe un médico con ese id en ese tenant
     * @throws MedicoConConsultorioPrincipalInactivoException si su consultorio principal está
     *                                   dado de baja; el handler lo traduce a 409
     */
    @Transactional
    public void activar(UUID id, UUID tenantId) {
        var medico = buscarDelTenant(id, tenantId);
        if (!medico.getConsultorio().isActivo()) {
            throw MedicoConConsultorioPrincipalInactivoException.porTenerElPrincipalDadoDeBaja();
        }
        medico.activar();
        medicoRepository.save(medico);
    }

    /**
     * Recupera el médico del tenant indicado o lanza la excepción de negocio.
     *
     * @param id       identificador buscado
     * @param tenantId organización del token de la petición
     * @return la entidad encontrada en ese tenant
     * @throws ResourceNotFoundException si no existe en ese tenant
     */
    private Medico buscarDelTenant(UUID id, UUID tenantId) {
        return medicoRepository.buscarPorIdYOrganizacion(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Medico", id));
    }
}
