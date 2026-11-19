package com.intoglobe.clinicas.security;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.model.Consultorio;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.MedicoConsultorio;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Paciente;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.PacienteRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de aislamiento por tenant (R-SEG-04, R-SEG-06) y de la matriz RBAC congelada
 * (R-SEG-05, ADR-013) sobre la cadena de filtros y los controladores reales.
 *
 * <p>Cada prueba siembra dos organizaciones completas —A y B, cada una con su consultorio, su
 * médico y su paciente— y emite tokens RS256 de verdad con el par que {@link ClaveRsaDePrueba}
 * genera en memoria. No hay usuarios simulados ni post-procesadores de seguridad: la
 * autorización que se ejerce es la de producción, de modo que una regla que falte en la
 * cadena o un recurso ajeno que se cuele en un service hacen fallar estas pruebas.</p>
 *
 * <h2>Cobertura de la matriz (sección 5.3)</h2>
 *
 * <ul>
 *   <li><strong>ADMIN</strong>: las veinticinco operaciones permitidas sobre su propio tenant
 *       ({@link #adminOperaLasVeinticincoOperacionesPermitidasSobreSuPropioTenant()}); las seis
 *       rutas de cuentas de usuario sobre su propio tenant
 *       ({@link #adminAdministraLasCuentasDeSuPropioTenant()}); el alta de
 *       organizaciones {@code 403} para los tres roles
 *       ({@link #crearOrganizacionResponde403ParaLosTresRoles()}); y las rutas con el
 *       {@code organizacionId} de otro tenant, todas {@code 403} sin excepción de rol
 *       ({@link #elPathDeOtroTenantResponde403TambienParaAdmin()}).</li>
 *   <li><strong>MEDICO</strong>: las trece operaciones de su tenant permitidas —las siete
 *       lecturas de los módulos 02, 03, paciente y su propia organización
 *       ({@link #medicoLeeSuPropioTenant()}, {@link #medicoLeeElNombreDeSuPropiaOrganizacion()}
 *       y {@link #medicoLeePacientesDeSuPropioTenant()}), los cinco endpoints de horarios sobre
 *       su propia agenda ({@link #medicoOperaSuPropiaAgendaDeHorarios()}) y la lista de
 *       consultorios asignados a su propio médico
 *       ({@link #medicoLeeLosConsultoriosDeSuPropioMedico()})— y las veinticuatro operaciones
 *       restantes {@code 403} ({@link #celdaDenegadaResponde403ConElContratoDeError}).</li>
 *   <li><strong>PACIENTE</strong>: las treinta y siete operaciones {@code 403}
 *       ({@link #celdaDenegadaResponde403ConElContratoDeError}).</li>
 *   <li><strong>RECEPCIONISTA</strong>: las catorce celdas que le concede la matriz —la lectura de
 *       la propia organización, las siete lecturas de consultorios, médicos, horarios y
 *       consultorios asignados, las dos lecturas de pacientes y las cuatro escrituras de pacientes
 *       (S3)— ejercidas de extremo a extremo con su código de éxito
 *       ({@link #recepcionConsultaConsultoriosDeSuOrganizacion()},
 *       {@link #recepcionistaLeeElNombreDeSuPropiaOrganizacion()} y las demás pruebas
 *       {@code recepcion*}), y las veintitrés operaciones restantes {@code 403}
 *       ({@link #celdaDenegadaResponde403ConElContratoDeError} y
 *       {@link #familiaDenegadaDeRecepcionResponde403}).</li>
 *   <li><strong>IDOR por id</strong>: token de A contra recursos de B en las operaciones por id de
 *       consultorio y médico, más las cuentas de usuario → {@code 404} sin revelar el recurso ni su
 *       organización ({@link #recursoDeOtroTenantResponde404SinRevelarlo()}).</li>
 *   <li><strong>R8.3(a)</strong>: {@code GET /organizaciones} devuelve solo la organización
 *       del token ({@link #listarOrganizacionesDevuelveSoloLaDelToken()}).</li>
 * </ul>
 *
 * <p><strong>Base de datos propia.</strong> El contexto arranca contra una base H2 exclusiva de
 * esta clase, igual que la sonda de arranque de T3: así el sembrado de organizaciones no
 * altera lo que otras pruebas de la suite esperan ver y esta clase no depende del orden de
 * ejecución de Surefire. La base se crea con el mismo dialecto y la misma espera de cierre
 * que la del perfil {@code test}, y ninguna prueba la cierra.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:aislamiento_rbac;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AislamientoYMatrizRbacTest {

    private static final String MENSAJE_ACCESO_DENEGADO =
            "No tiene permisos para acceder a este recurso";

    private static final String ROL_ADMIN = "ADMIN";
    private static final String ROL_MEDICO = "MEDICO";
    private static final String ROL_PACIENTE = "PACIENTE";
    private static final String ROL_RECEPCIONISTA = "RECEPCIONISTA";

    private static final String CUERPO_ORGANIZACION_NUEVA = """
            {"nombre":"Clínica Nueva","plan":"SOLO"}
            """;
    private static final String CUERPO_ORGANIZACION_ACTUALIZADA = """
            {"nombre":"Clínica Alfa Renombrada","plan":"ENTERPRISE"}
            """;
    private static final String CUERPO_CONSULTORIO_NUEVO = """
            {"nombre":"Consultorio Nuevo","direccion":"Calle Nueva 3","telefono":"+52 55 1111 2222"}
            """;
    private static final String CUERPO_CONSULTORIO_ACTUALIZADO = """
            {"nombre":"Consultorio Renombrado","direccion":"Calle Renombrada 9"}
            """;
    private static final String CUERPO_HORARIO_NUEVO = """
            {"diaSemana":3,"horaInicio":"09:00:00","horaFin":"11:00:00","duracionConsulta":30}
            """;
    private static final String CUERPO_HORARIO_ACTUALIZADO = """
            {"diaSemana":3,"horaInicio":"10:00:00","horaFin":"12:00:00","duracionConsulta":45}
            """;
    private static final String CUERPO_PACIENTE_NUEVO = """
            {"nombre":"Paciente Nuevo","telefono":"+52 55 3333 4444","fechaNacimiento":"1990-06-21","sexo":"F","email":"paciente.nuevo@example.com","notas":"Notas nuevas"}
            """;
    private static final String CUERPO_PACIENTE_ACTUALIZADO = """
            {"nombre":"Paciente Nuevo Renombrado","telefono":"+52 55 3333 5555","fechaNacimiento":"1990-06-21","sexo":"F","email":"paciente.nuevo@example.com","notas":null}
            """;
    private static final String CUERPO_USUARIO_NUEVO = """
            {"email":"cuenta.nueva@example.com","rol":"RECEPCIONISTA","medicoId":null}
            """;
    private static final String CUERPO_USUARIO_ACTUALIZADO = """
            {"email":"cuenta.actualizada@example.com","rol":"PACIENTE","medicoId":null}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private ConsultorioRepository consultorioRepository;

    @Autowired
    private MedicoRepository medicoRepository;

    @Autowired
    private PacienteRepository pacienteRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private com.intoglobe.clinicas.repository.MedicoConsultorioRepository medicoConsultorioRepository;

    private Organizacion organizacionA;
    private Organizacion organizacionB;
    private Consultorio consultorioA;
    private Consultorio consultorioB;
    private Medico medicoA;
    private Medico medicoB;
    private Paciente pacienteA;
    private Paciente pacienteB;

    private String tokenAdminA;
    private String tokenAdminB;
    private String tokenMedicoA;
    private String tokenPacienteA;
    private String tokenRecepcionA;

    /**
     * Publica la clave pública de prueba: los tokens de esta clase los firma
     * {@link ClaveRsaDePrueba} con la mitad privada de ese par.
     *
     * @param registro registro de propiedades dinámicas del contexto de prueba
     */
    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    /**
     * Siembra dos tenants completos con nombres imposibles de confundir, para poder afirmar
     * que una respuesta no filtra datos de la organización ajena.
     */
    @BeforeEach
    void sembrarDosTenants() {
        organizacionA = organizacionRepository
                .save(new Organizacion("Clínica Alfa", PlanTipo.CLINICA, "sub-alfa"));
        organizacionB = organizacionRepository
                .save(new Organizacion("Clínica Beta", PlanTipo.CLINICA, "sub-beta"));
        consultorioA = consultorioRepository.save(new Consultorio(
                organizacionA, "Consultorio Alfa Uno", "Calle Alfa 1", "+52 55 0000 0001"));
        consultorioB = consultorioRepository.save(new Consultorio(
                organizacionB, "Consultorio Secreto Beta", "Calle Beta 2", "+52 55 0000 0002"));
        medicoA = medicoRepository.save(new Medico(organizacionA, consultorioA, "Dra. Alfa",
                "Cardiología", "11111111", "+52 55 0000 0011", new BigDecimal("500.00")));
        medicoB = medicoRepository.save(new Medico(organizacionB, consultorioB, "Dr. Secreto Beta",
                "Dermatología", "22222222", "+52 55 0000 0022", new BigDecimal("700.00")));
        // El sembrado escribe a mano lo que el alta del médico deja en la misma transacción: la
        // asignación del consultorio principal. Sin ella, ningún bloque del médico puede existir
        // (clave horario_medico_consultorio_asignado_fkey de la V6).
        medicoConsultorioRepository.save(new MedicoConsultorio(medicoA, consultorioA, organizacionA));
        medicoConsultorioRepository.save(new MedicoConsultorio(medicoB, consultorioB, organizacionB));
        pacienteA = pacienteRepository.save(new Paciente(organizacionA, "Paciente Alfa",
                "+52 55 0000 0031", LocalDate.parse("1985-03-14"), "F",
                "paciente.alfa@example.com", "Notas Alfa"));
        pacienteB = pacienteRepository.save(new Paciente(organizacionB, "Paciente Secreto Beta",
                "+52 55 0000 0032", LocalDate.parse("1979-11-02"), "M",
                "paciente.beta@example.com", "Notas Beta"));

        tokenAdminA = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, organizacionA.getId());
        tokenAdminB = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, organizacionB.getId());
        tokenMedicoA = ClaveRsaDePrueba.tokenPara(ROL_MEDICO, organizacionA.getId());
        tokenPacienteA = ClaveRsaDePrueba.tokenPara(ROL_PACIENTE, organizacionA.getId());
        tokenRecepcionA = ClaveRsaDePrueba.tokenPara(ROL_RECEPCIONISTA, organizacionA.getId());
    }

    // ------------------------------------------------------------------
    // ADMIN: las veinticinco operaciones permitidas sobre su propio tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN opera las veinticinco operaciones permitidas sobre su propio tenant")
    void adminOperaLasVeinticincoOperacionesPermitidasSobreSuPropioTenant() throws Exception {
        String rutaOrganizacionA = "/api/v1/organizaciones/" + organizacionA.getId();
        String rutaConsultoriosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios";
        String rutaMedicosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos";

        // 1. POST /organizaciones — bloqueada para todos los roles (decisión de producto del
        // 2026-10-29): con el aislamiento por tenant aplicado, la organización recién creada
        // quedaría inalcanzable para cualquier rol, así que no se permite crear ninguna.
        long organizacionesAntes = organizacionRepository.count();
        afirmar403(ejecutar(post("/api/v1/organizaciones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_ORGANIZACION_NUEVA), tokenAdminA), "/api/v1/organizaciones");
        assertThat(organizacionRepository.count())
                .as("un alta rechazada no debe dejar ninguna organización nueva")
                .isEqualTo(organizacionesAntes);

        // 2. GET /organizaciones — solo la del token, ni la ajena ni una recién creada.
        ejecutar(get("/api/v1/organizaciones"), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(organizacionA.getId().toString()));

        // 3. GET /organizaciones/{id}
        ejecutar(get(rutaOrganizacionA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizacionA.getId().toString()));

        // 4. PUT /organizaciones/{id}
        ejecutar(put(rutaOrganizacionA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_ORGANIZACION_ACTUALIZADA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Clínica Alfa Renombrada"));

        // 5. POST /organizaciones/{organizacionId}/consultorios
        String consultorioCreadoId = idDe(ejecutar(post(rutaConsultoriosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_CONSULTORIO_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andReturn());

        // 6. GET /organizaciones/{organizacionId}/consultorios
        ejecutar(get(rutaConsultoriosA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(consultorioCreadoId)));

        // 7. GET /consultorios/{id}
        ejecutar(get("/api/v1/consultorios/" + consultorioCreadoId), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultorioCreadoId));

        // 8. PUT /consultorios/{id}
        ejecutar(put("/api/v1/consultorios/" + consultorioCreadoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_CONSULTORIO_ACTUALIZADO), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Consultorio Renombrado"));

        // 9. POST /organizaciones/{organizacionId}/medicos
        String medicoCreadoId = idDe(ejecutar(post(rutaMedicosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioCreadoId, "Dr. Nuevo")), tokenAdminA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andReturn());

        // 10. GET /organizaciones/{organizacionId}/medicos
        ejecutar(get(rutaMedicosA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(medicoCreadoId)));

        // 11. GET /medicos/{id}
        ejecutar(get("/api/v1/medicos/" + medicoCreadoId), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(medicoCreadoId));

        // 12. PUT /medicos/{id}
        ejecutar(put("/api/v1/medicos/" + medicoCreadoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioCreadoId, "Dr. Nuevo Renombrado")), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Dr. Nuevo Renombrado"));

        // 13. DELETE /medicos/{id}
        ejecutar(delete("/api/v1/medicos/" + medicoCreadoId), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 14. DELETE /consultorios/{id}
        ejecutar(delete("/api/v1/consultorios/" + consultorioCreadoId), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 15. POST /medicos/{medicoId}/horarios
        String horarioCreadoId = idDe(ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/horarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicoId").value(medicoA.getId().toString()))
                .andReturn());

        // 16. GET /medicos/{medicoId}/horarios
        ejecutar(get("/api/v1/medicos/" + medicoA.getId() + "/horarios"), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(horarioCreadoId)));

        // 17. GET /horarios/{id}
        ejecutar(get("/api/v1/horarios/" + horarioCreadoId), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(horarioCreadoId));

        // 18. PUT /horarios/{id}
        ejecutar(put("/api/v1/horarios/" + horarioCreadoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_ACTUALIZADO), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horaInicio").value("10:00:00"));

        // 19. DELETE /horarios/{id}
        ejecutar(delete("/api/v1/horarios/" + horarioCreadoId), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 20. POST /organizaciones/{organizacionId}/pacientes
        String rutaPacientesA = "/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes";
        String pacienteCreadoId = idDe(ejecutar(post(rutaPacientesA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_PACIENTE_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.activo").value(true))
                .andReturn());

        // 21. GET /organizaciones/{organizacionId}/pacientes
        ejecutar(get(rutaPacientesA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(pacienteCreadoId)));

        // 22. GET /pacientes/{id}
        ejecutar(get("/api/v1/pacientes/" + pacienteCreadoId), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pacienteCreadoId))
                .andExpect(jsonPath("$.nombre").value("Paciente Nuevo"));

        // 23. PUT /pacientes/{id}
        ejecutar(put("/api/v1/pacientes/" + pacienteCreadoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_PACIENTE_ACTUALIZADO), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Paciente Nuevo Renombrado"))
                .andExpect(jsonPath("$.activo").value(true));

        // 24. DELETE /pacientes/{id}
        ejecutar(delete("/api/v1/pacientes/" + pacienteCreadoId), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 25. POST /pacientes/{id}/activar
        ejecutar(post("/api/v1/pacientes/" + pacienteCreadoId + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 26. DELETE /organizaciones/{id}
        ejecutar(delete(rutaOrganizacionA), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /**
     * Reactivación de `médico` y `consultorio` sobre la cadena real: la celda de la matriz es
     * solo de `ADMIN` y el servicio devuelve el registro a la vida sin duplicarlo. La
     * reactivación es idempotente (204 las dos veces) y el recurso queda activo de verdad, no
     * solo con la respuesta correcta. El orden respeta la invariante de S6 y S7: para dar de baja
     * el consultorio principal, el médico tiene que estar inactivo, y el médico solo se reactiva
     * con su principal activo. La segunda mitad —reactivar sin el principal activo responde
     * 409— se ejerce en {@link #reactivarElMedicoSinElPrincipalActivoResponde409()}.
     */
    @Test
    @DisplayName("ADMIN reactiva un médico y un consultorio de su propio tenant (204 idempotente)")
    void adminReactivaMedicoYConsultorioDeSuPropioTenant() throws Exception {
        String rutaMedicosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos";
        String rutaConsultoriosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios";
        ejecutar(delete("/api/v1/medicos/" + medicoA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());

        // S8: el listado por defecto sigue mostrando solo activos, y el de bajas deja alcanzable
        // la reactivación desde la tabla.
        ejecutar(get(rutaMedicosA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers
                        .not(org.hamcrest.Matchers.hasItem(medicoA.getId().toString()))));
        ejecutar(get(rutaMedicosA).param("incluirInactivos", "true"), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + medicoA.getId() + "')].activo",
                        org.hamcrest.Matchers.contains(false)));

        ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(get("/api/v1/medicos/" + medicoA.getId()), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));

        // Un bloque existente se crea con el médico activo; después se comprueba que su agenda no
        // se edita con el médico de baja (S10) y que el borrado sigue permitido para limpiar datos.
        String horarioDeMedicoA = idDe(ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/horarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andReturn());

        // Su consultorio principal solo se puede dar de baja con el médico inactivo (S6).
        ejecutar(delete("/api/v1/medicos/" + medicoA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());
        ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/horarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"diaSemana":4,"horaInicio":"09:00:00","horaFin":"11:00:00","duracionConsulta":30}
                        """), tokenAdminA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers
                        .containsString("Reactiva primero al médico")));
        ejecutar(delete("/api/v1/horarios/" + horarioDeMedicoA), tokenAdminA)
                .andExpect(status().isNoContent());
        ejecutar(delete("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());

        ejecutar(get(rutaConsultoriosA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers
                        .not(org.hamcrest.Matchers.hasItem(consultorioA.getId().toString()))));
        ejecutar(get(rutaConsultoriosA).param("incluirInactivos", "true"), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + consultorioA.getId() + "')].activo",
                        org.hamcrest.Matchers.contains(false)));

        ejecutar(post("/api/v1/consultorios/" + consultorioA.getId() + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(post("/api/v1/consultorios/" + consultorioA.getId() + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(get("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));
    }

    /**
     * S6 y S7 de extremo a extremo, con la base real: no se da de baja el consultorio principal
     * de un médico activo, y no se reactiva un médico cuyo principal está dado de baja. Las dos
     * respuestas son {@code 409} con el mensaje que indica el camino, y el estado queda intacto.
     */
    @Test
    @DisplayName("S6 y S7: el consultorio principal y el médico activo no se separan (409)")
    void reactivarElMedicoSinElPrincipalActivoResponde409() throws Exception {
        ejecutar(delete("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers
                        .containsString("Da de baja primero al médico")));
        ejecutar(get("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));

        ejecutar(delete("/api/v1/medicos/" + medicoA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());
        ejecutar(delete("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());
        ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/activar"), tokenAdminA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers
                        .containsString("Reactiva primero el consultorio")));
        ejecutar(get("/api/v1/medicos/" + medicoA.getId()), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    // ------------------------------------------------------------------
    // ADMIN: las seis rutas de cuentas de usuario
    // ------------------------------------------------------------------

    /**
     * Las seis celdas que el módulo de usuario concede a {@code ADMIN} sobre su propio tenant,
     * ejercidas de extremo a extremo: alta con {@code Location}, listado acotado a la organización del
     * token, consulta por id, actualización, baja lógica y reactivación idempotente.
     *
     * <p>La cuenta que se crea no es administrativa a propósito: así la baja y el cambio de rol no
     * chocan con la protección de la última administradora (R8), que tiene sus propias pruebas en
     * {@code UsuarioServiceTest}. Se siembra además una cuenta en el tenant ajeno para fijar que el
     * listado y la consulta por id no la alcanzan.</p>
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("ADMIN administra las cuentas de su propio tenant: las seis rutas de usuario")
    void adminAdministraLasCuentasDeSuPropioTenant() throws Exception {
        String rutaUsuariosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/usuarios";
        Usuario cuentaAjena = usuarioRepository.save(new Usuario(organizacionB, null,
                "cuenta.beta@example.com", RolUsuario.ADMIN, UUID.randomUUID()));

        // 1. POST /organizaciones/{organizacionId}/usuarios — 201 y Location de la ruta plana.
        MvcResult creada = ejecutar(post(rutaUsuariosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_USUARIO_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.rol").value("RECEPCIONISTA"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.authUserId").doesNotExist())
                .andReturn();
        String cuentaCreadaId = idDe(creada);
        assertThat(creada.getResponse().getHeader(HttpHeaders.LOCATION))
                .as("el alta publica el Location de la ruta plana del recurso creado")
                .isEqualTo("/api/v1/usuarios/" + cuentaCreadaId);

        // 2. GET anidado — la cuenta creada sí, la del tenant ajeno no.
        ejecutar(get(rutaUsuariosA), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(cuentaCreadaId)))
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(cuentaAjena.getId().toString()))));

        // 3. GET /usuarios/{id}
        ejecutar(get("/api/v1/usuarios/" + cuentaCreadaId), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cuentaCreadaId))
                .andExpect(jsonPath("$.email").value("cuenta.nueva@example.com"));

        // 4. PUT /usuarios/{id} — cambia correo y rol, no la organización ni el estado lógico.
        ejecutar(put("/api/v1/usuarios/" + cuentaCreadaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_USUARIO_ACTUALIZADO), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("cuenta.actualizada@example.com"))
                .andExpect(jsonPath("$.rol").value("PACIENTE"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()));

        // 5. DELETE /usuarios/{id} — baja lógica: la fila se conserva y queda inactiva.
        ejecutar(delete("/api/v1/usuarios/" + cuentaCreadaId), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(usuarioRepository.findById(UUID.fromString(cuentaCreadaId)).orElseThrow().isActivo())
                .as("la baja es lógica: la cuenta se conserva, inactiva")
                .isFalse();

        // 6. POST /usuarios/{id}/activar — reactivación idempotente.
        ejecutar(post("/api/v1/usuarios/" + cuentaCreadaId + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(post("/api/v1/usuarios/" + cuentaCreadaId + "/activar"), tokenAdminA)
                .andExpect(status().isNoContent());
        assertThat(usuarioRepository.findById(UUID.fromString(cuentaCreadaId)).orElseThrow().isActivo())
                .as("la reactivación devuelve la cuenta a activa, sin duplicarla")
                .isTrue();

        assertThat(usuarioRepository.listarPorOrganizacion(organizacionB.getId()))
                .as("ninguna operación sobre el tenant propio debe crear cuentas en el ajeno")
                .extracting(Usuario::getId)
                .containsExactly(cuentaAjena.getId());
    }

    /**
     * R11 de extremo a extremo contra la base real: la administradora autenticada no puede darse de
     * baja a sí misma ni quitarse el rol, aunque su token sí pueda administrar cuentas.
     *
     * <p><strong>Por qué este caso existe.</strong> Las demás pruebas de R11 simulan el servicio y
     * fabrican el actor a mano, así que no ejercen la cadena real de identidad. Aquí la cuenta
     * sembrada tiene {@code auth_user_id} igual al claim {@code sub} del token —que es el sujeto del
     * servicio de identidad, no la clave de la fila— y la petición pasa por el filtro, el
     * controlador y el servicio. Eso hace que la prueba falle en las dos reincidencias posibles del
     * defecto: si el filtro propagara el id de la fila en vez del {@code sub}, y si el servicio
     * volviera a comparar contra {@code getId()} en lugar de {@code getAuthUserId()}.</p>
     *
     * <p>Se siembra una segunda cuenta {@code ADMIN} activa a propósito: así R8 no puede ser lo que
     * frene la operación y el {@code 403} observado solo puede venir de R11. Las dos operaciones
     * —baja y cambio de rol— se afirman contra la fila en la base, no solo contra la respuesta.
     *</p>
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("R11: la propia administradora no puede darse de baja ni cambiarse el rol, contra la base real")
    void laPropiaAdministradoraNoPuedeOperarSobreSuCuenta() throws Exception {
        var identidadDelToken = UUID.fromString(ClaveRsaDePrueba.ID_USUARIO);
        Usuario propia = usuarioRepository.save(new Usuario(organizacionA, null,
                "administradora.propia@example.com", RolUsuario.ADMIN, identidadDelToken));
        usuarioRepository.save(new Usuario(organizacionA, null, "otra.administradora@example.com",
                RolUsuario.ADMIN, UUID.randomUUID()));
        String ruta = "/api/v1/usuarios/" + propia.getId();

        // Baja de la propia cuenta: 403 con el motivo de R11, no el 403 genérico de la cadena ni un
        // 409 de última administradora.
        ejecutar(delete(ruta), tokenAdminA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("propia cuenta")))
                .andExpect(jsonPath("$.ruta").value(ruta));

        // Cambio de rol de la propia cuenta: el mismo 403.
        ejecutar(put(ruta)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_USUARIO_ACTUALIZADO), tokenAdminA)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("su propia cuenta")))
                .andExpect(jsonPath("$.ruta").value(ruta));

        // Y la fila sigue como estaba: ni dada de baja ni con otro rol.
        Usuario despues = usuarioRepository.findById(propia.getId()).orElseThrow();
        assertThat(despues.isActivo())
                .as("una baja rechazada por R11 no debe desactivar la propia cuenta")
                .isTrue();
        assertThat(despues.getRol())
                .as("un cambio de rol rechazado por R11 no debe modificar la propia cuenta")
                .isEqualTo(RolUsuario.ADMIN);
        assertThat(despues.getEmail())
                .as("el cuerpo rechazado tampoco debe aplicarse al correo")
                .isEqualTo("administradora.propia@example.com");
    }

    /**
     * El bloqueo del alta es de autorización, no de validación del cuerpo: se comprueba para
     * los tres roles y con un cuerpo válido, y se afirma que no se persistió nada.
     *
     * @param rol rol del token con el que se intenta el alta
     * @throws Exception si una aserción de MockMvc falla
     */
    @ParameterizedTest(name = "POST /organizaciones con rol {0} responde 403 y no crea nada")
    @ValueSource(strings = {ROL_ADMIN, ROL_MEDICO, ROL_PACIENTE})
    void crearOrganizacionResponde403ParaLosTresRoles(String rol) throws Exception {
        long organizacionesAntes = organizacionRepository.count();

        afirmar403(ejecutar(post("/api/v1/organizaciones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_ORGANIZACION_NUEVA), tokenDe(rol)), "/api/v1/organizaciones");

        assertThat(organizacionRepository.count())
                .as("el alta bloqueada no debe persistir ninguna organización")
                .isEqualTo(organizacionesAntes);
    }

    // ------------------------------------------------------------------
    // ADMIN: la asignación de consultorios de su propio médico
    // ------------------------------------------------------------------

    /**
     * Las tres celdas de la asignación vistas de extremo a extremo con el rol {@code ADMIN}:
     * asignar dos veces responde {@code 204} la segunda vez, el listado incluye el consultorio
     * recién asignado y retirarlo dos veces responde {@code 204}. Se comprueba además que el
     * consultorio principal del médico no se puede retirar ({@code 409}).
     *
     * <p>El consultorio que se asigna es uno nuevo de la organización del token: el sembrado es el
     * consultorio principal del médico, y retirar ese es precisamente la operación prohibida.</p>
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("ADMIN asigna y retira consultorios de su médico, con idempotencia y sin tocar el principal")
    void adminAsignaYRetiraLosConsultoriosDeSuMedico() throws Exception {
        Consultorio consultorioNuevo = consultorioRepository.save(new Consultorio(organizacionA,
                "Consultorio Alfa Dos", "Calle Alfa 2", "+52 55 0000 0003"));
        String rutaAsignados = "/api/v1/medicos/" + medicoA.getId() + "/consultorios";
        String cuerpo = cuerpoAsignacion(consultorioNuevo.getId());

        ejecutar(post(rutaAsignados).contentType(MediaType.APPLICATION_JSON).content(cuerpo), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(post(rutaAsignados).contentType(MediaType.APPLICATION_JSON).content(cuerpo), tokenAdminA)
                .andExpect(status().isNoContent());

        ejecutar(get(rutaAsignados), tokenAdminA)
                .andExpect(status().isOk())
                // El principal lo dejó el sembrado y el nuevo acaba de asignarse: el listado los
                // muestra ordenados por nombre, así que "Consultorio Alfa Dos" va primero.
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(consultorioNuevo.getId().toString()))
                .andExpect(jsonPath("$[0].organizacionId").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(consultorioA.getId().toString()));
        assertThat(medicoConsultorioRepository.buscarAsignacion(medicoA.getId(), consultorioNuevo.getId(),
                organizacionA.getId()))
                .as("la asignación repetida no debe duplicar la fila del par")
                .isPresent();

        ejecutar(delete(rutaAsignados + "/" + consultorioNuevo.getId()), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(delete(rutaAsignados + "/" + consultorioNuevo.getId()), tokenAdminA)
                .andExpect(status().isNoContent());
        ejecutar(get(rutaAsignados), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(consultorioA.getId().toString()));

        ejecutar(delete(rutaAsignados + "/" + consultorioA.getId()), tokenAdminA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value(409))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("consultorio principal")));
    }

    // ------------------------------------------------------------------
    // MEDICO: lecturas permitidas en su tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MEDICO lee consultorios y médicos de su propio tenant")
    void medicoLeeSuPropioTenant() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios"), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(consultorioA.getId().toString())));

        ejecutar(get("/api/v1/consultorios/" + consultorioA.getId()), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultorioA.getId().toString()));

        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/medicos"), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(medicoA.getId().toString())));

        ejecutar(get("/api/v1/medicos/" + medicoA.getId()), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(medicoA.getId().toString()))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()));
    }

    @Test
    @DisplayName("MEDICO opera los cinco endpoints de horarios de su propia agenda")
    void medicoOperaSuPropiaAgendaDeHorarios() throws Exception {
        String token = tokenMedicoDeSuAgenda();
        String rutaHorarios = "/api/v1/medicos/" + medicoA.getId() + "/horarios";

        String horarioCreadoId = idDe(ejecutar(post(rutaHorarios)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_NUEVO), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicoId").value(medicoA.getId().toString()))
                .andReturn());

        ejecutar(get(rutaHorarios), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(horarioCreadoId)));

        ejecutar(get("/api/v1/horarios/" + horarioCreadoId), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(horarioCreadoId));

        ejecutar(put("/api/v1/horarios/" + horarioCreadoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_ACTUALIZADO), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horaInicio").value("10:00:00"))
                .andExpect(jsonPath("$.duracionConsulta").value(45));

        ejecutar(delete("/api/v1/horarios/" + horarioCreadoId), token)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // El bloque eliminado no reaparece: el borrado es físico y el listado queda vacío.
        ejecutar(get(rutaHorarios), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * La celda de lectura de la asignación de consultorios: {@code MEDICO} consulta los
     * consultorios de su propio médico —el del claim {@code medicoId}, no el del path— y recibe
     * {@code 403} cuando el path apunta a otro médico de su misma organización. Es la misma regla
     * de propiedad que ya protege la agenda de horarios, aplicada a esta ruta.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("MEDICO lee los consultorios de su propio médico y solo los propios")
    void medicoLeeLosConsultoriosDeSuPropioMedico() throws Exception {
        String token = tokenMedicoDeSuAgenda();
        Medico otroMedicoDeLaMismaClinica = medicoRepository.save(new Medico(organizacionA, consultorioA,
                "Dr. Alfa Dos", "Dermatología", "33333333", "+52 55 0000 0033", new BigDecimal("600.00")));

        ejecutar(get("/api/v1/medicos/" + medicoA.getId() + "/consultorios"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(consultorioA.getId().toString()));

        ejecutar(get("/api/v1/medicos/" + otroMedicoDeLaMismaClinica.getId() + "/consultorios"), token)
                .andExpect(status().isForbidden());
    }

    /**
     * Celda nueva de S3: {@code MEDICO} lee el nombre de su propia organización. Es la misma
     * lectura que hace la cabecera del menú al iniciar la sesión, y el nombre tiene que ser el
     * de la organización del claim, no el de otra clínica.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("MEDICO lee el nombre de su propia organización")
    void medicoLeeElNombreDeSuPropiaOrganizacion() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId()), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Clínica Alfa"));
    }

    /**
     * S4 para {@code MEDICO}: pedir la organización de otro tenant responde {@code 403} con el
     * contrato de error y no toca la organización ajena. La comprobación es contra el claim, así
     * que no depende de que la organización del path exista.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("MEDICO pide la organización de otro tenant y recibe 403")
    void medicoNoLeeLaOrganizacionDeOtroTenant() throws Exception {
        String rutaB = "/api/v1/organizaciones/" + organizacionB.getId();

        afirmar403(ejecutar(get(rutaB), tokenMedicoA), rutaB);

        assertThat(organizacionRepository.findById(organizacionB.getId()).orElseThrow().getNombre())
                .as("una lectura rechazada no debe revelar ni modificar la organización ajena")
                .isEqualTo("Clínica Beta");
    }

    @Test
    @DisplayName("MEDICO lee los pacientes de su propio tenant")
    void medicoLeePacientesDeSuPropioTenant() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes"), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(pacienteA.getId().toString())))
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                pacienteB.getId().toString()))));

        ejecutar(get("/api/v1/pacientes/" + pacienteA.getId()), tokenMedicoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pacienteA.getId().toString()))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Paciente Alfa"));
    }

    // ------------------------------------------------------------------
    // RECEPCIONISTA: las catorce celdas permitidas de S3
    // ------------------------------------------------------------------

    /**
     * Crea un bloque de horario del médico sembrado con el token de administración, para que
     * recepción tenga algo que consultar: la escritura de la agenda no está entre sus celdas.
     *
     * @return identificador del bloque creado
     * @throws Exception si una aserción de MockMvc falla
     */
    private String sembrarHorarioDelMedicoA() throws Exception {
        return idDe(ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/horarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_HORARIO_NUEVO), tokenAdminA)
                .andExpect(status().isCreated())
                .andReturn());
    }

    /**
     * Celda nueva de S3: {@code RECEPCIONISTA} lee el nombre de su propia organización, que es lo
     * que la cabecera necesita para no mostrar el identificador del tenant.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("RECEPCIONISTA lee el nombre de su propia organización")
    void recepcionistaLeeElNombreDeSuPropiaOrganizacion() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId()), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Clínica Alfa"));
    }

    /**
     * S4 para {@code RECEPCIONISTA}: la organización ajena responde {@code 403} con el contrato de
     * error. Igual que en el caso de {@code MEDICO}, la negativa no confirma si esa organización
     * existe porque la comparación se hace contra el claim, sin consultar la base.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("RECEPCIONISTA pide la organización de otro tenant y recibe 403")
    void recepcionistaNoLeeLaOrganizacionDeOtroTenant() throws Exception {
        String rutaB = "/api/v1/organizaciones/" + organizacionB.getId();

        afirmar403(ejecutar(get(rutaB), tokenRecepcionA), rutaB);
    }

    /**
     * {@code PACIENTE} queda fuera de las dos rutas de organización, incluso con el tenant
     * correcto en el path: la denegación es de rol, no de aislamiento.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("PACIENTE no lee la colección ni la organización por id")
    void pacienteNoLeeNingunaRutaDeOrganizacion() throws Exception {
        afirmar403(ejecutar(get("/api/v1/organizaciones"), tokenPacienteA), "/api/v1/organizaciones");
        String rutaA = "/api/v1/organizaciones/" + organizacionA.getId();
        afirmar403(ejecutar(get(rutaA), tokenPacienteA), rutaA);
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta los consultorios de su organización")
    void recepcionConsultaConsultoriosDeSuOrganizacion() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios"), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(consultorioA.getId().toString())))
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                consultorioB.getId().toString()))));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta un consultorio de su organización")
    void recepcionConsultaUnConsultorio() throws Exception {
        ejecutar(get("/api/v1/consultorios/" + consultorioA.getId()), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultorioA.getId().toString()))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta los médicos de su organización")
    void recepcionConsultaMedicosDeSuOrganizacion() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/medicos"), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(medicoA.getId().toString())))
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                medicoB.getId().toString()))));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta un médico de su organización")
    void recepcionConsultaUnMedico() throws Exception {
        ejecutar(get("/api/v1/medicos/" + medicoA.getId()), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(medicoA.getId().toString()))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta los horarios de un médico para responder dónde y cuándo atiende")
    void recepcionConsultaHorariosDeUnMedico() throws Exception {
        String horarioId = sembrarHorarioDelMedicoA();

        ejecutar(get("/api/v1/medicos/" + medicoA.getId() + "/horarios"), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(horarioId)));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta un bloque de horario")
    void recepcionConsultaUnBloqueDeHorario() throws Exception {
        String horarioId = sembrarHorarioDelMedicoA();

        ejecutar(get("/api/v1/horarios/" + horarioId), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(horarioId))
                .andExpect(jsonPath("$.medicoId").value(medicoA.getId().toString()));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta los consultorios asignados a un médico")
    void recepcionConsultaConsultoriosAsignadosAUnMedico() throws Exception {
        ejecutar(get("/api/v1/medicos/" + medicoA.getId() + "/consultorios"), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(consultorioA.getId().toString()));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta los pacientes de su organización")
    void recepcionConsultaPacientesDeSuOrganizacion() throws Exception {
        ejecutar(get("/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes"), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.hasItem(pacienteA.getId().toString())))
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                pacienteB.getId().toString()))));
    }

    @Test
    @DisplayName("RECEPCIONISTA consulta un paciente de su organización")
    void recepcionConsultaUnPaciente() throws Exception {
        ejecutar(get("/api/v1/pacientes/" + pacienteA.getId()), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pacienteA.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Paciente Alfa"))
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()));
    }

    @Test
    @DisplayName("RECEPCIONISTA registra un paciente en su organización")
    void recepcionRegistraPaciente() throws Exception {
        String rutaPacientesA = "/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes";

        String creado = idDe(ejecutar(post(rutaPacientesA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_PACIENTE_NUEVO), tokenRecepcionA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizacionId").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Paciente Nuevo"))
                .andExpect(jsonPath("$.activo").value(true))
                .andReturn());

        assertThat(pacienteRepository.listarActivosPorOrganizacion(organizacionA.getId()))
                .as("el alta permitida debe persistir el paciente en el tenant del token")
                .extracting(Paciente::getId)
                .containsExactlyInAnyOrder(pacienteA.getId(), UUID.fromString(creado));
    }

    @Test
    @DisplayName("RECEPCIONISTA actualiza un paciente de su organización")
    void recepcionActualizaPaciente() throws Exception {
        ejecutar(put("/api/v1/pacientes/" + pacienteA.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_PACIENTE_ACTUALIZADO), tokenRecepcionA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Paciente Nuevo Renombrado"))
                .andExpect(jsonPath("$.activo").value(true));

        Paciente actualizado = pacienteRepository.findById(pacienteA.getId()).orElseThrow();
        assertThat(actualizado.getNombre())
                .as("la actualización permitida debe persistirse")
                .isEqualTo("Paciente Nuevo Renombrado");
        assertThat(actualizado.getTelefono()).isEqualTo("+52 55 3333 5555");
    }

    @Test
    @DisplayName("RECEPCIONISTA da de baja un paciente de su organización")
    void recepcionDaDeBajaPaciente() throws Exception {
        ejecutar(delete("/api/v1/pacientes/" + pacienteA.getId()), tokenRecepcionA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(pacienteRepository.findById(pacienteA.getId()).orElseThrow().isActivo())
                .as("la baja es lógica: el paciente se conserva inactivo")
                .isFalse();
    }

    @Test
    @DisplayName("RECEPCIONISTA reactiva un paciente de su organización")
    void recepcionReactivaPaciente() throws Exception {
        ejecutar(delete("/api/v1/pacientes/" + pacienteA.getId()), tokenAdminA)
                .andExpect(status().isNoContent());
        assertThat(pacienteRepository.findById(pacienteA.getId()).orElseThrow().isActivo())
                .as("el paciente debe quedar dado de baja antes de reactivarlo")
                .isFalse();

        ejecutar(post("/api/v1/pacientes/" + pacienteA.getId() + "/activar"), tokenRecepcionA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        ejecutar(post("/api/v1/pacientes/" + pacienteA.getId() + "/activar"), tokenRecepcionA)
                .andExpect(status().isNoContent());

        assertThat(pacienteRepository.findById(pacienteA.getId()).orElseThrow().isActivo())
                .as("la reactivación permitida devuelve el paciente a activo")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // RECEPCIONISTA: las familias que la matriz le deniega
    // ------------------------------------------------------------------

    /**
     * Familias de operaciones que S3 deniega a {@code RECEPCIONISTA}. Cada caso ejerce todas las
     * operaciones de la familia con el tenant correcto en el path, así que lo que se ejerce es el
     * rol y no el aislamiento, y afirma además que un rechazo no deja rastro en la base.
     *
     * <p>Las escrituras de horarios, consultorios, médicos y asignaciones se deniegan enteras: la
     * agenda del médico es suya y de la administración, y recepción solo la consulta.</p>
     *
     * @param familia nombre de la familia que se ejerce
     * @throws Exception si una aserción de MockMvc falla
     */
    @ParameterizedTest(name = "recepción · la familia {0} responde 403 con el contrato de error")
    @ValueSource(strings = {"organización", "consultorios", "médicos", "asignaciones de consultorios",
            "horarios en escritura", "cuentas del personal"})
    void familiaDenegadaDeRecepcionResponde403(String familia) throws Exception {
        String organizacion = organizacionA.getId().toString();
        String rutaOrganizacion = "/api/v1/organizaciones/" + organizacion;
        String rutaHorarios = "/api/v1/medicos/" + medicoA.getId() + "/horarios";

        switch (familia) {
            case "organización" -> {
                // La lectura por id ya es una celda concedida (S3); lo que sigue cerrado es la
                // colección y toda escritura sobre la organización.
                afirmar403(ejecutar(get("/api/v1/organizaciones"), tokenRecepcionA),
                        "/api/v1/organizaciones");
                afirmar403(ejecutar(put(rutaOrganizacion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_ORGANIZACION_ACTUALIZADA), tokenRecepcionA), rutaOrganizacion);
                afirmar403(ejecutar(delete(rutaOrganizacion), tokenRecepcionA), rutaOrganizacion);
            }
            case "consultorios" -> {
                afirmar403(ejecutar(post(rutaOrganizacion + "/consultorios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_CONSULTORIO_NUEVO), tokenRecepcionA),
                        rutaOrganizacion + "/consultorios");
                afirmar403(ejecutar(put("/api/v1/consultorios/" + consultorioA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_CONSULTORIO_ACTUALIZADO), tokenRecepcionA),
                        "/api/v1/consultorios/" + consultorioA.getId());
                afirmar403(ejecutar(delete("/api/v1/consultorios/" + consultorioA.getId()), tokenRecepcionA),
                        "/api/v1/consultorios/" + consultorioA.getId());
            }
            case "médicos" -> {
                afirmar403(ejecutar(post(rutaOrganizacion + "/medicos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoMedico(consultorioA.getId().toString(), "Dr. Nuevo")), tokenRecepcionA),
                        rutaOrganizacion + "/medicos");
                afirmar403(ejecutar(put("/api/v1/medicos/" + medicoA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoMedico(consultorioA.getId().toString(), "Dra. Intrusa")), tokenRecepcionA),
                        "/api/v1/medicos/" + medicoA.getId());
                afirmar403(ejecutar(delete("/api/v1/medicos/" + medicoA.getId()), tokenRecepcionA),
                        "/api/v1/medicos/" + medicoA.getId());
            }
            case "asignaciones de consultorios" -> {
                afirmar403(ejecutar(post("/api/v1/medicos/" + medicoA.getId() + "/consultorios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoAsignacion(consultorioA.getId())), tokenRecepcionA),
                        "/api/v1/medicos/" + medicoA.getId() + "/consultorios");
                afirmar403(ejecutar(delete("/api/v1/medicos/" + medicoA.getId() + "/consultorios/"
                        + consultorioA.getId()), tokenRecepcionA),
                        "/api/v1/medicos/" + medicoA.getId() + "/consultorios/" + consultorioA.getId());
            }
            case "horarios en escritura" -> {
                UUID bloqueExistente = UUID.randomUUID();
                afirmar403(ejecutar(post(rutaHorarios)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_HORARIO_NUEVO), tokenRecepcionA), rutaHorarios);
                afirmar403(ejecutar(put("/api/v1/horarios/" + bloqueExistente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_HORARIO_ACTUALIZADO), tokenRecepcionA),
                        "/api/v1/horarios/" + bloqueExistente);
                afirmar403(ejecutar(delete("/api/v1/horarios/" + bloqueExistente), tokenRecepcionA),
                        "/api/v1/horarios/" + bloqueExistente);
            }
            case "cuentas del personal" -> {
                String rutaUsuarios = rutaOrganizacion + "/usuarios";
                UUID cuenta = UUID.randomUUID();
                afirmar403(ejecutar(post(rutaUsuarios)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_USUARIO_NUEVO), tokenRecepcionA), rutaUsuarios);
                afirmar403(ejecutar(get(rutaUsuarios), tokenRecepcionA), rutaUsuarios);
                afirmar403(ejecutar(get("/api/v1/usuarios/" + cuenta), tokenRecepcionA),
                        "/api/v1/usuarios/" + cuenta);
                afirmar403(ejecutar(put("/api/v1/usuarios/" + cuenta)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_USUARIO_ACTUALIZADO), tokenRecepcionA),
                        "/api/v1/usuarios/" + cuenta);
                afirmar403(ejecutar(delete("/api/v1/usuarios/" + cuenta), tokenRecepcionA),
                        "/api/v1/usuarios/" + cuenta);
                afirmar403(ejecutar(post("/api/v1/usuarios/" + cuenta + "/activar"), tokenRecepcionA),
                        "/api/v1/usuarios/" + cuenta + "/activar");
            }
            default -> throw new IllegalArgumentException("Familia sin sonda en estas pruebas: " + familia);
        }

        afirmarQueNadaCambio("familia " + familia);
        assertThat(usuarioRepository.listarPorOrganizacion(organizacionA.getId()))
                .as("ninguna operación de la familia %s puede crear cuentas en el tenant del token",
                        familia)
                .isEmpty();
    }

    @Test
    @DisplayName("Un MEDICO no puede mutar aunque sea de su tenant: la escritura queda intacta")
    void medicoNoPuedeMutarYElRecursoQuedaIntacto() throws Exception {
        ejecutar(put("/api/v1/medicos/" + medicoA.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioA.getId().toString(), "Dra. Intrusa")), tokenMedicoA)
                .andExpect(status().isForbidden());

        ejecutar(delete("/api/v1/consultorios/" + consultorioA.getId()), tokenMedicoA)
                .andExpect(status().isForbidden());

        assertThat(medicoRepository.findById(medicoA.getId()).orElseThrow().getNombre())
                .as("una escritura rechazada no debe modificar el recurso")
                .isEqualTo("Dra. Alfa");
        assertThat(consultorioRepository.findById(consultorioA.getId()).orElseThrow().isActivo())
                .as("una baja rechazada no debe desactivar el recurso")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // PACIENTE y MEDICO: celdas denegadas de la matriz
    // ------------------------------------------------------------------

    /**
     * Celdas {@code 403} de la matriz: las treinta y siete operaciones para {@code PACIENTE}, las
     * veinticuatro que {@code MEDICO} no tiene permitidas y las veintitrés que {@code RECEPCIONISTA}
     * no tiene, todas con el tenant correcto en el path para que lo que se ejerza sea el rol y no
     * el aislamiento. Las trece celdas que la matriz concede a {@code MEDICO} no aparecen aquí:
     * cuatro se ejercen en {@link #medicoLeeSuPropioTenant()}, una en
     * {@link #medicoLeeElNombreDeSuPropiaOrganizacion()}, dos en
     * {@link #medicoLeePacientesDeSuPropioTenant()}, cinco en
     * {@link #medicoOperaSuPropiaAgendaDeHorarios()} y una en
     * {@link #medicoLeeLosConsultoriosDeSuPropioMedico()}. Las catorce celdas de
     * {@code RECEPCIONISTA} tampoco: se ejercen en las pruebas {@code recepcion*}.
     *
     * @return rol, descripción de la operación, método, ruta y cuerpo de cada celda denegada
     */
    Stream<Arguments> celdasDenegadas() {
        return Stream.of(ROL_PACIENTE, ROL_MEDICO, ROL_RECEPCIONISTA)
                .flatMap(this::celdasDenegadasDe)
                .filter(celda -> !esCeldaPermitida((String) celda.get()[0], (String) celda.get()[1]));
    }

    @ParameterizedTest(name = "{0} · {1} responde 403")
    @MethodSource("celdasDenegadas")
    void celdaDenegadaResponde403ConElContratoDeError(
            String rol, String descripcion, String metodo, String ruta, String cuerpo) throws Exception {
        afirmar403(ejecutar(peticion(metodo, ruta, cuerpo), tokenDe(rol)), ruta);
    }

    /**
     * Indica si la matriz concede la operación al rol. Las celdas permitidas de {@code MEDICO} y
     * {@code RECEPCIONISTA} no se ejercen aquí porque tienen sus propias pruebas de éxito.
     *
     * @param rol         rol del token
     * @param descripcion descripción de la operación
     * @return {@code true} si la celda está permitida para ese rol
     */
    private static boolean esCeldaPermitida(String rol, String descripcion) {
        if (ROL_MEDICO.equals(rol)) {
            return esCeldaPermitidaParaMedico(descripcion);
        }
        if (ROL_RECEPCIONISTA.equals(rol)) {
            return esCeldaPermitidaParaRecepcion(descripcion);
        }
        return false;
    }

    /**
     * Las catorce celdas que S3 concede a {@code RECEPCIONISTA}: la lectura de la propia
     * organización, las siete lecturas de consultorios, médicos, horarios y consultorios
     * asignados, las dos lecturas de pacientes y las cuatro escrituras de pacientes. La escritura
     * de la agenda y de los catálogos no está entre ellas.
     *
     * @param descripcion descripción de la operación
     * @return {@code true} si la celda está permitida para {@code RECEPCIONISTA}
     */
    private static boolean esCeldaPermitidaParaRecepcion(String descripcion) {
        return List.of("GET organización por id",
                "GET consultorios anidados", "GET consultorio por id",
                "GET médicos anidados", "GET médico por id",
                "GET horarios anidados", "GET horario por id", "GET consultorios del médico",
                "GET pacientes anidados", "GET paciente por id",
                "POST paciente", "PUT paciente", "DELETE paciente", "POST activar paciente")
                .contains(descripcion);
    }

    private Stream<Arguments> celdasDenegadasDe(String rol) {
        return Stream.of(
                Arguments.of(rol, "POST organización", "POST", "/api/v1/organizaciones",
                        CUERPO_ORGANIZACION_NUEVA),
                Arguments.of(rol, "GET organizaciones", "GET", "/api/v1/organizaciones", null),
                Arguments.of(rol, "GET organización por id", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId(), null),
                Arguments.of(rol, "PUT organización", "PUT",
                        "/api/v1/organizaciones/" + organizacionA.getId(), CUERPO_ORGANIZACION_ACTUALIZADA),
                Arguments.of(rol, "DELETE organización", "DELETE",
                        "/api/v1/organizaciones/" + organizacionA.getId(), null),
                Arguments.of(rol, "POST consultorio", "POST",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios",
                        CUERPO_CONSULTORIO_NUEVO),
                Arguments.of(rol, "GET consultorios anidados", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/consultorios", null),
                Arguments.of(rol, "GET consultorio por id", "GET",
                        "/api/v1/consultorios/" + consultorioA.getId(), null),
                Arguments.of(rol, "PUT consultorio", "PUT",
                        "/api/v1/consultorios/" + consultorioA.getId(), CUERPO_CONSULTORIO_ACTUALIZADO),
                Arguments.of(rol, "DELETE consultorio", "DELETE",
                        "/api/v1/consultorios/" + consultorioA.getId(), null),
                Arguments.of(rol, "POST activar consultorio", "POST",
                        "/api/v1/consultorios/" + consultorioA.getId() + "/activar", null),
                Arguments.of(rol, "POST médico", "POST",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos",
                        cuerpoMedico(consultorioA.getId().toString(), "Dr. Nuevo")),
                Arguments.of(rol, "GET médicos anidados", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos", null),
                Arguments.of(rol, "GET médico por id", "GET",
                        "/api/v1/medicos/" + medicoA.getId(), null),
                Arguments.of(rol, "PUT médico", "PUT", "/api/v1/medicos/" + medicoA.getId(),
                        cuerpoMedico(consultorioA.getId().toString(), "Dr. Nuevo")),
                Arguments.of(rol, "DELETE médico", "DELETE", "/api/v1/medicos/" + medicoA.getId(), null),
                Arguments.of(rol, "POST activar médico", "POST",
                        "/api/v1/medicos/" + medicoA.getId() + "/activar", null),
                Arguments.of(rol, "POST horario", "POST",
                        "/api/v1/medicos/" + medicoA.getId() + "/horarios", CUERPO_HORARIO_NUEVO),
                Arguments.of(rol, "GET horarios anidados", "GET",
                        "/api/v1/medicos/" + medicoA.getId() + "/horarios", null),
                Arguments.of(rol, "GET horario por id", "GET",
                        "/api/v1/horarios/" + UUID.randomUUID(), null),
                Arguments.of(rol, "PUT horario", "PUT",
                        "/api/v1/horarios/" + UUID.randomUUID(), CUERPO_HORARIO_ACTUALIZADO),
                Arguments.of(rol, "DELETE horario", "DELETE",
                        "/api/v1/horarios/" + UUID.randomUUID(), null),
                Arguments.of(rol, "POST asignar consultorio al médico", "POST",
                        "/api/v1/medicos/" + medicoA.getId() + "/consultorios",
                        cuerpoAsignacion(consultorioA.getId())),
                Arguments.of(rol, "GET consultorios del médico", "GET",
                        "/api/v1/medicos/" + medicoA.getId() + "/consultorios", null),
                Arguments.of(rol, "DELETE asignar consultorio al médico", "DELETE",
                        "/api/v1/medicos/" + medicoA.getId() + "/consultorios/" + consultorioA.getId(),
                        null),
                Arguments.of(rol, "POST paciente", "POST",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes",
                        CUERPO_PACIENTE_NUEVO),
                Arguments.of(rol, "GET pacientes anidados", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/pacientes", null),
                Arguments.of(rol, "GET paciente por id", "GET",
                        "/api/v1/pacientes/" + UUID.randomUUID(), null),
                Arguments.of(rol, "PUT paciente", "PUT",
                        "/api/v1/pacientes/" + UUID.randomUUID(), CUERPO_PACIENTE_ACTUALIZADO),
                Arguments.of(rol, "DELETE paciente", "DELETE",
                        "/api/v1/pacientes/" + UUID.randomUUID(), null),
                Arguments.of(rol, "POST activar paciente", "POST",
                        "/api/v1/pacientes/" + UUID.randomUUID() + "/activar", null),
                Arguments.of(rol, "POST usuario", "POST",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/usuarios",
                        CUERPO_USUARIO_NUEVO),
                Arguments.of(rol, "GET usuarios anidados", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/usuarios", null),
                Arguments.of(rol, "GET usuario por id", "GET",
                        "/api/v1/usuarios/" + UUID.randomUUID(), null),
                Arguments.of(rol, "PUT usuario", "PUT",
                        "/api/v1/usuarios/" + UUID.randomUUID(), CUERPO_USUARIO_ACTUALIZADO),
                Arguments.of(rol, "DELETE usuario", "DELETE",
                        "/api/v1/usuarios/" + UUID.randomUUID(), null),
                Arguments.of(rol, "POST activar usuario", "POST",
                        "/api/v1/usuarios/" + UUID.randomUUID() + "/activar", null));
    }

    /**
     * Las trece celdas que la matriz concede a {@code MEDICO}: las siete lecturas de su propio
     * tenant —organización, consultorios, médicos y pacientes—, los cinco endpoints de horarios y
     * la lista de consultorios asignados a su propio médico. Que las de horarios y la de la
     * asignación acaben en {@code 2xx} exige además que el actor sea el médico dueño de la agenda
     * o de los consultorios, y esa mitad la comprueba el service contra el claim
     * {@code medicoId}; aquí solo se fija que el rol no es lo que deniega.
     *
     * @param descripcion descripción de la operación
     * @return {@code true} si la celda está permitida para {@code MEDICO}
     */
    private static boolean esCeldaPermitidaParaMedico(String descripcion) {
        return List.of("GET organización por id",
                "GET consultorios anidados", "GET consultorio por id",
                "GET médicos anidados", "GET médico por id",
                "GET pacientes anidados", "GET paciente por id", "POST horario",
                "GET horarios anidados", "GET horario por id", "PUT horario", "DELETE horario",
                "GET consultorios del médico")
                .contains(descripcion);
    }

    // ------------------------------------------------------------------
    // D5: path del tenant ajeno → 403, también para ADMIN
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El organizacionId de otro tenant responde 403 aunque el rol sea ADMIN y no crea nada")
    void elPathDeOtroTenantResponde403TambienParaAdmin() throws Exception {
        String rutaOrganizacionB = "/api/v1/organizaciones/" + organizacionB.getId();
        String rutaConsultoriosB = rutaOrganizacionB + "/consultorios";
        String rutaMedicosB = rutaOrganizacionB + "/medicos";
        String rutaPacientesB = rutaOrganizacionB + "/pacientes";
        String rutaUsuariosB = rutaOrganizacionB + "/usuarios";

        afirmar403(ejecutar(get(rutaOrganizacionB), tokenAdminA), rutaOrganizacionB);
        afirmar403(ejecutar(put(rutaOrganizacionB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_ORGANIZACION_ACTUALIZADA), tokenAdminA), rutaOrganizacionB);
        afirmar403(ejecutar(delete(rutaOrganizacionB), tokenAdminA), rutaOrganizacionB);
        afirmar403(ejecutar(post(rutaConsultoriosB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_CONSULTORIO_NUEVO), tokenAdminA), rutaConsultoriosB);
        afirmar403(ejecutar(get(rutaConsultoriosB), tokenAdminA), rutaConsultoriosB);
        afirmar403(ejecutar(post(rutaMedicosB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioB.getId().toString(), "Dr. Intruso")), tokenAdminA),
                rutaMedicosB);
        afirmar403(ejecutar(get(rutaMedicosB), tokenAdminA), rutaMedicosB);
        afirmar403(ejecutar(post(rutaPacientesB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_PACIENTE_NUEVO), tokenAdminA), rutaPacientesB);
        afirmar403(ejecutar(get(rutaPacientesB), tokenAdminA), rutaPacientesB);
        afirmar403(ejecutar(post(rutaUsuariosB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_USUARIO_NUEVO), tokenAdminA), rutaUsuariosB);
        afirmar403(ejecutar(get(rutaUsuariosB), tokenAdminA), rutaUsuariosB);

        // El rechazo del path no altera nada del tenant ajeno ni crea recursos en él.
        assertThat(organizacionRepository.findById(organizacionB.getId()).orElseThrow().getNombre())
                .as("un 403 sobre el path no debe renombrar la organización ajena")
                .isEqualTo("Clínica Beta");
        assertThat(organizacionRepository.findById(organizacionB.getId()).orElseThrow().isActivo())
                .as("un 403 sobre el path no debe desactivar la organización ajena")
                .isTrue();
        assertThat(consultorioRepository.listarActivosPorOrganizacion(organizacionB.getId()))
                .as("un POST rechazado no debe crear consultorios en el tenant ajeno")
                .extracting(Consultorio::getId)
                .containsExactly(consultorioB.getId());
        assertThat(medicoRepository.listarActivosPorOrganizacion(organizacionB.getId()))
                .as("un POST rechazado no debe crear médicos en el tenant ajeno")
                .extracting(Medico::getId)
                .containsExactly(medicoB.getId());
        assertThat(pacienteRepository.listarActivosPorOrganizacion(organizacionB.getId()))
                .as("un POST rechazado no debe crear pacientes en el tenant ajeno")
                .extracting(Paciente::getId)
                .containsExactly(pacienteB.getId());
        assertThat(usuarioRepository.listarPorOrganizacion(organizacionB.getId()))
                .as("un POST rechazado no debe crear cuentas en el tenant ajeno")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // R-SEG-04: IDOR por id → 404 sin revelar la existencia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un token de A no alcanza los recursos de B por id: 404 sin revelarlos")
    void recursoDeOtroTenantResponde404SinRevelarlo() throws Exception {
        String consultorioAjeno = "/api/v1/consultorios/" + consultorioB.getId();
        String medicoAjeno = "/api/v1/medicos/" + medicoB.getId();
        Usuario cuentaAjena = usuarioRepository.save(new Usuario(organizacionB, null,
                "cuenta.secreta.beta@example.com", RolUsuario.PACIENTE, UUID.randomUUID()));
        String usuarioAjeno = "/api/v1/usuarios/" + cuentaAjena.getId();

        afirmar404(ejecutar(get(consultorioAjeno), tokenAdminA), consultorioAjeno, "Consultorio",
                consultorioB.getId());
        afirmar404(ejecutar(put(consultorioAjeno)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_CONSULTORIO_ACTUALIZADO), tokenAdminA), consultorioAjeno, "Consultorio",
                consultorioB.getId());
        afirmar404(ejecutar(delete(consultorioAjeno), tokenAdminA), consultorioAjeno, "Consultorio",
                consultorioB.getId());
        afirmar404(ejecutar(get(medicoAjeno), tokenAdminA), medicoAjeno, "Medico", medicoB.getId());
        afirmar404(ejecutar(put(medicoAjeno)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioB.getId().toString(), "Dr. Intruso")), tokenAdminA),
                medicoAjeno, "Medico", medicoB.getId());
        afirmar404(ejecutar(delete(medicoAjeno), tokenAdminA), medicoAjeno, "Medico", medicoB.getId());
        afirmar404(ejecutar(get(usuarioAjeno), tokenAdminA), usuarioAjeno, "Usuario", cuentaAjena.getId());
        afirmar404(ejecutar(put(usuarioAjeno)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_USUARIO_ACTUALIZADO), tokenAdminA), usuarioAjeno, "Usuario",
                cuentaAjena.getId());
        afirmar404(ejecutar(delete(usuarioAjeno), tokenAdminA), usuarioAjeno, "Usuario",
                cuentaAjena.getId());

        // El mismo 404 para un médico del cuerpo del alta: la organización ajena no se confirma por
        // ninguna de las dos vías, ni por la cuenta pedida por id ni por un identificador del cuerpo.
        String rutaUsuariosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/usuarios";
        afirmar404(ejecutar(post(rutaUsuariosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(usuarioMedicoConMedicoDe(medicoB.getId())), tokenAdminA), rutaUsuariosA,
                "Medico", medicoB.getId());

        // El 404 es indistinguible del de un id inexistente y no filtra datos del recurso.
        String inexistente = "/api/v1/consultorios/" + UUID.randomUUID();
        String cuerpoAjeno = ejecutar(get(consultorioAjeno), tokenAdminA).andReturn()
                .getResponse().getContentAsString();
        String cuerpoInexistente = ejecutar(get(inexistente), tokenAdminA).andReturn()
                .getResponse().getContentAsString();

        assertThat(plantillaDeMensaje(cuerpoAjeno, consultorioB.getId().toString()))
                .as("el 404 de un recurso ajeno debe usar el mismo mensaje que el de un id inexistente")
                .isEqualTo(plantillaDeMensaje(cuerpoInexistente, inexistente.substring(inexistente
                        .lastIndexOf('/') + 1)));
        assertThat(cuerpoAjeno)
                .as("el 404 no debe contener el nombre del recurso ajeno")
                .doesNotContain("Consultorio Secreto Beta");

        // Ni la lectura ni las mutaciones rechazadas tocaron el recurso ajeno.
        Consultorio ajeno = consultorioRepository.findById(consultorioB.getId()).orElseThrow();
        assertThat(ajeno.getNombre()).isEqualTo("Consultorio Secreto Beta");
        assertThat(ajeno.isActivo()).isTrue();
        assertThat(medicoRepository.findById(medicoB.getId()).orElseThrow().getNombre())
                .isEqualTo("Dr. Secreto Beta");
        assertThat(medicoRepository.findById(medicoB.getId()).orElseThrow().isActivo()).isTrue();

        Usuario ajenoUsuario = usuarioRepository.findById(cuentaAjena.getId()).orElseThrow();
        assertThat(ajenoUsuario.getEmail())
                .as("una mutación rechazada no debe cambiar el correo de la cuenta ajena")
                .isEqualTo("cuenta.secreta.beta@example.com");
        assertThat(ajenoUsuario.getRol())
                .as("una mutación rechazada no debe cambiar el rol de la cuenta ajena")
                .isEqualTo(RolUsuario.PACIENTE);
        assertThat(ajenoUsuario.isActivo())
                .as("una baja rechazada no debe desactivar la cuenta ajena")
                .isTrue();
        assertThat(usuarioRepository.listarPorOrganizacion(organizacionA.getId()))
                .as("ni la cuenta ajena ni el alta con médico ajeno pueden crear cuentas en el tenant "
                        + "del token")
                .isEmpty();
    }

    @Test
    @DisplayName("Una lectura por id de mi propio tenant funciona y la de otro tenant da 404, con el mismo token")
    void laLecturaPorIdDistinguePropioDeAjeno() throws Exception {
        ejecutar(get("/api/v1/consultorios/" + consultorioA.getId()), tokenAdminB)
                .andExpect(status().isNotFound());
        ejecutar(get("/api/v1/consultorios/" + consultorioB.getId()), tokenAdminB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultorioB.getId().toString()));
    }

    // ------------------------------------------------------------------
    // F3: un identificador del cuerpo también se resuelve dentro del tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un consultorio de otro tenant en el cuerpo del alta responde 404 y no crea el médico")
    void crearMedicoConConsultorioDeOtroTenantResponde404YNoCreaNada() throws Exception {
        String rutaMedicosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos";

        afirmar404(ejecutar(post(rutaMedicosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioB.getId().toString(), "Dr. Cruzado")), tokenAdminA),
                rutaMedicosA, "Consultorio", consultorioB.getId());

        assertThat(medicoRepository.listarActivosPorOrganizacion(organizacionA.getId()))
                .as("un alta rechazada no debe dejar ningún médico nuevo en el tenant del token")
                .extracting(Medico::getId)
                .containsExactly(medicoA.getId());
        assertThat(consultorioRepository.findById(consultorioB.getId()).orElseThrow().getOrganizacion().getId())
                .as("el consultorio ajeno no debe quedar referenciado desde el tenant del token")
                .isEqualTo(organizacionB.getId());
    }

    @Test
    @DisplayName("Un consultorio ajeno y un UUID al azar en el alta dan el mismo 404: no hay oráculo de existencia")
    void crearMedicoConConsultorioInexistenteNoSeDistingueDeUnoAjeno() throws Exception {
        String rutaMedicosA = "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos";
        UUID consultorioAlAzar = UUID.randomUUID();

        String cuerpoAjeno = ejecutar(post(rutaMedicosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioB.getId().toString(), "Dr. Cruzado")), tokenAdminA)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String cuerpoInexistente = ejecutar(post(rutaMedicosA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoMedico(consultorioAlAzar.toString(), "Dr. Cruzado")), tokenAdminA)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(plantillaDeMensaje(cuerpoAjeno, consultorioB.getId().toString()))
                .as("el mensaje del consultorio ajeno debe ser el mismo que el de un UUID inexistente")
                .isEqualTo(plantillaDeMensaje(cuerpoInexistente, consultorioAlAzar.toString()));
        assertThat(cuerpoAjeno)
                .as("el 404 no debe contener el nombre del consultorio ajeno")
                .doesNotContain("Consultorio Secreto Beta");
        assertThat(medicoRepository.listarActivosPorOrganizacion(organizacionA.getId()))
                .as("ninguna de las dos altas rechazadas puede dejar un médico nuevo")
                .extracting(Medico::getId)
                .containsExactly(medicoA.getId());
    }

    // ------------------------------------------------------------------
    // Gap 1: una ruta no canónica no puede abrir una celda denegada
    // ------------------------------------------------------------------

    /**
     * Celdas denegadas sondeadas por rutas no canónicas: barra final, doble barra y
     * {@code POST /organizaciones/}.
     *
     * <p>Motivo: la matriz se aplica con patrones de ruta, así que una ruta con una forma que
     * ningún patrón reconoce cae en la regla general {@code authenticated()} y no en la regla
     * del rol denegado. Eso es aceptable <em>solo si</em> esa forma tampoco llega al
     * controlador: si Spring MVC la normalizara y despachara, una celda {@code 403} quedaría
     * abierta por la puerta de atrás. Estas sondas fijan esa frontera.</p>
     *
     * @return rol, método, ruta no canónica y cuerpo de cada sonda
     */
    Stream<Arguments> rutasNoCanonicasDeCeldasDenegadas() {
        String organizacion = organizacionA.getId().toString();
        String consultorio = consultorioA.getId().toString();
        String medico = medicoA.getId().toString();

        return Stream.of(
                // Barra final en una lectura que MEDICO tiene permitida y PACIENTE no.
                Arguments.of(ROL_MEDICO, "GET", "/api/v1/organizaciones/" + organizacion + "/medicos/", null),
                Arguments.of(ROL_PACIENTE, "GET", "/api/v1/organizaciones/" + organizacion + "/medicos/", null),
                // Barra final en un alta anidada denegada a MEDICO y a PACIENTE.
                Arguments.of(ROL_MEDICO, "POST", "/api/v1/organizaciones/" + organizacion + "/consultorios/",
                        CUERPO_CONSULTORIO_NUEVO),
                Arguments.of(ROL_PACIENTE, "POST", "/api/v1/organizaciones/" + organizacion + "/consultorios/",
                        CUERPO_CONSULTORIO_NUEVO),
                // POST de organización con barra final: la celda está denegada a los dos.
                Arguments.of(ROL_MEDICO, "POST", "/api/v1/organizaciones/", CUERPO_ORGANIZACION_NUEVA),
                Arguments.of(ROL_PACIENTE, "POST", "/api/v1/organizaciones/", CUERPO_ORGANIZACION_NUEVA),
                // PUT y DELETE por id con barra final: denegados a los dos.
                Arguments.of(ROL_MEDICO, "PUT", "/api/v1/medicos/" + medico + "/",
                        cuerpoMedico(consultorio, "Dra. Intrusa")),
                Arguments.of(ROL_PACIENTE, "PUT", "/api/v1/medicos/" + medico + "/",
                        cuerpoMedico(consultorio, "Dra. Intrusa")),
                Arguments.of(ROL_MEDICO, "DELETE", "/api/v1/consultorios/" + consultorio + "/", null),
                Arguments.of(ROL_PACIENTE, "DELETE", "/api/v1/consultorios/" + consultorio + "/", null),
                // Doble barra, al inicio y dentro de la ruta.
                Arguments.of(ROL_MEDICO, "GET", "//api/v1/organizaciones/" + organizacion + "/consultorios", null),
                Arguments.of(ROL_PACIENTE, "GET", "//api/v1/consultorios/" + consultorio, null),
                Arguments.of(ROL_PACIENTE, "GET", "/api/v1//organizaciones/" + organizacion + "/medicos", null));
    }

    @ParameterizedTest(name = "{0} · {1} {2} (no canónica) no abre la celda denegada")
    @MethodSource("rutasNoCanonicasDeCeldasDenegadas")
    void unaRutaNoCanonicaNoAbreUnaCeldaDenegada(String rol, String metodo, String ruta, String cuerpo)
            throws Exception {
        int estado = ejecutar(peticion(metodo, ruta, cuerpo), tokenDe(rol)).andReturn().getResponse().getStatus();

        // Códigos observados al fijar estas sondas (2026-10-29): 404 en las formas con barra
        // final o doble barra inicial —ningún patrón las reconoce y Spring MVC tampoco las
        // despacha al controlador— y 403 en la doble barra interna, que la cadena sigue
        // tratando como la ruta de la celda denegada. Lo que se fija es la garantía, no el
        // código exacto: ninguna forma puede terminar en 2xx.
        assertThat(estado >= 200 && estado <= 299)
                .as("una forma no canónica de una celda denegada no puede responder 2xx: %s %s con rol %s (código %d)",
                        metodo, ruta, rol, estado)
                .isFalse();
        afirmarQueNadaCambio(rol + " · " + metodo + " " + ruta);
    }

    /**
     * Tenant del rol indicado, para las sondas que se expresan por rol.
     *
     * @param rol rol del token
     * @return el token correspondiente
     */
    private String tokenDe(String rol) {
        return switch (rol) {
            case ROL_ADMIN -> tokenAdminA;
            case ROL_MEDICO -> tokenMedicoA;
            case ROL_PACIENTE -> tokenPacienteA;
            case ROL_RECEPCIONISTA -> tokenRecepcionA;
            default -> throw new IllegalArgumentException("Rol sin token en estas pruebas: " + rol);
        };
    }

    /**
     * Token {@code MEDICO} de la organización A que además lleva el claim {@code medicoId} de su
     * propio médico.
     *
     * <p>Es el token que un médico real recibe del emisor, y el que permite ejercer las cinco
     * celdas de horarios: con el token sin {@code medicoId} el servicio falla cerrado con
     * {@code 403}, que es la mitad de propiedad de la agenda y no la de rol.</p>
     *
     * @return el token firmado del médico sembrado en {@link #medicoA}
     */
    private String tokenMedicoDeSuAgenda() {
        return ClaveRsaDePrueba.token()
                .conRol(ROL_MEDICO)
                .conInquilino(organizacionA.getId().toString())
                .conMedico(medicoA.getId().toString())
                .compactar();
    }

    /**
     * Comprueba que el sembrado del tenant propio sigue intacto.
     *
     * @param sonda descripción de la sonda que se acaba de ejecutar, para el mensaje de fallo
     */
    private void afirmarQueNadaCambio(String sonda) {
        assertThat(organizacionRepository.findById(organizacionA.getId()).orElseThrow().getNombre())
                .as("la sonda %s no debe renombrar la organización del token", sonda)
                .isEqualTo("Clínica Alfa");
        assertThat(organizacionRepository.findById(organizacionA.getId()).orElseThrow().isActivo())
                .as("la sonda %s no debe desactivar la organización del token", sonda)
                .isTrue();
        assertThat(consultorioRepository.listarActivosPorOrganizacion(organizacionA.getId()))
                .as("la sonda %s no debe crear ni desactivar consultorios en el tenant del token", sonda)
                .extracting(Consultorio::getId)
                .containsExactly(consultorioA.getId());
        assertThat(medicoRepository.listarActivosPorOrganizacion(organizacionA.getId()))
                .as("la sonda %s no debe crear, renombrar ni desactivar médicos en el tenant del token", sonda)
                .extracting(Medico::getId)
                .containsExactly(medicoA.getId());
        assertThat(medicoRepository.findById(medicoA.getId()).orElseThrow().getNombre())
                .as("la sonda %s no debe modificar el nombre del médico", sonda)
                .isEqualTo("Dra. Alfa");
    }

    // ------------------------------------------------------------------
    // R8.3(a) / R-SEG-06: el listado sale del claim, no del path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /organizaciones devuelve exactamente la organización del token")
    void listarOrganizacionesDevuelveSoloLaDelToken() throws Exception {
        ejecutar(get("/api/v1/organizaciones"), tokenAdminA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(organizacionA.getId().toString()))
                .andExpect(jsonPath("$[0].nombre").value("Clínica Alfa"))
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                organizacionB.getId().toString()))));

        ejecutar(get("/api/v1/organizaciones"), tokenAdminB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(organizacionB.getId().toString()));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Afirma el contrato completo del {@code 403} de la API.
     *
     * @param resultado resultado de la petición
     * @param ruta      ruta esperada en el campo {@code ruta} del cuerpo
     * @throws Exception si una aserción de MockMvc falla
     */
    private static void afirmar403(ResultActions resultado, String ruta) throws Exception {
        resultado
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(ruta))
                .andExpect(jsonPath("$.marcaTemporal").exists());
    }

    /**
     * Afirma el contrato del {@code 404} de un recurso ajeno.
     *
     * @param resultado resultado de la petición
     * @param ruta      ruta esperada en el campo {@code ruta}
     * @param recurso   nombre del recurso que aparecería en el mensaje si existiera
     * @param id        identificador solicitado
     * @throws Exception si una aserción de MockMvc falla
     */
    private static void afirmar404(ResultActions resultado, String ruta, String recurso, UUID id)
            throws Exception {
        resultado
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value("No se encontró el recurso " + recurso + " con id: " + id))
                .andExpect(jsonPath("$.ruta").value(ruta));
    }

    /**
     * Sustituye el identificador del mensaje por un marcador, para comparar dos {@code 404}
     * de recursos distintos sin que el id forme parte de la comparación.
     *
     * @param cuerpo JSON del cuerpo de error
     * @param id     identificador que aparece en el mensaje
     * @return el mensaje con el id reemplazado
     */
    private static String plantillaDeMensaje(String cuerpo, String id) {
        return JsonPath.read(cuerpo, "$.mensaje").toString().replace(id, "<id>");
    }

    /**
     * Cuerpo de la asignación de un consultorio a un médico.
     *
     * @param consultorioId consultorio que se asigna
     * @return cuerpo JSON de la petición
     */
    private static String cuerpoAsignacion(UUID consultorioId) {
        return """
                {"consultorioId":"%s"}
                """.formatted(consultorioId);
    }

    private static String cuerpoMedico(String consultorioId, String nombre) {
        return """
                {"consultorioId":"%s","nombre":"%s","especialidad":"Cardiología","tarifaBase":"500.00"}
                """.formatted(consultorioId, nombre);
    }

    /**
     * Cuerpo de alta de una cuenta {@code MEDICO} que apunta al médico indicado.
     *
     * @param medicoId identificador del médico que se intenta asociar
     * @return cuerpo JSON de la petición
     */
    private static String usuarioMedicoConMedicoDe(UUID medicoId) {
        return """
                {"email":"cuenta.medico@example.com","rol":"MEDICO","medicoId":"%s"}
                """.formatted(medicoId);
    }

    /**
     * Ejecuta la petición con el token indicado.
     *
     * @param peticion petición a ejecutar
     * @param token    token Bearer del rol y tenant que se quiere ejercer
     * @return resultado de la petición
     * @throws Exception si MockMvc no puede ejecutarla
     */
    private ResultActions ejecutar(MockHttpServletRequestBuilder peticion, String token) throws Exception {
        return mockMvc.perform(peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * Construye la petición de un método y una ruta dados.
     *
     * @param metodo verbo HTTP
     * @param ruta   ruta de la petición
     * @param cuerpo cuerpo JSON o {@code null} si la operación no lleva cuerpo
     * @return la petición lista para ejecutarse
     */
    private static MockHttpServletRequestBuilder peticion(String metodo, String ruta, String cuerpo) {
        MockHttpServletRequestBuilder constructor = switch (metodo) {
            case "GET" -> get(ruta);
            case "POST" -> post(ruta);
            case "PUT" -> put(ruta);
            case "DELETE" -> delete(ruta);
            default -> throw new IllegalArgumentException("Método no soportado en las pruebas: " + metodo);
        };
        if (cuerpo != null) {
            constructor.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return constructor;
    }

    /**
     * Extrae el campo {@code id} del cuerpo de una respuesta.
     *
     * @param resultado resultado de la petición
     * @return el identificador creado
     * @throws Exception si el cuerpo no puede leerse
     */
    private static String idDe(MvcResult resultado) throws Exception {
        return JsonPath.read(resultado.getResponse().getContentAsString(), "$.id");
    }

    /**
     * Sonda independiente de S3/S4: la tabla se deriva del pedido, no de las listas del test.
     * Cuerpo "{}" en las escrituras: la autorización corre antes de la validación.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("SONDA S3/S4: recepción obtiene exactamente las catorce celdas de la tabla")
    void matrizDeRecepcionSegunLaTablaS3() throws Exception {
        String org = organizacionA.getId().toString();
        String con = consultorioA.getId().toString();
        String med = medicoA.getId().toString();
        String hor = UUID.randomUUID().toString();
        String pac = UUID.randomUUID().toString();
        String usr = UUID.randomUUID().toString();
        String tabla = """
                POST   /api/v1/organizaciones                     DENEGADA
                GET    /api/v1/organizaciones                     DENEGADA
                GET    /api/v1/organizaciones/{org}               PERMITIDA
                PUT    /api/v1/organizaciones/{org}               DENEGADA
                DELETE /api/v1/organizaciones/{org}               DENEGADA
                POST   /api/v1/organizaciones/{org}/consultorios  DENEGADA
                GET    /api/v1/organizaciones/{org}/consultorios  PERMITIDA
                GET    /api/v1/consultorios/{con}                 PERMITIDA
                PUT    /api/v1/consultorios/{con}                 DENEGADA
                DELETE /api/v1/consultorios/{con}                 DENEGADA
                POST   /api/v1/consultorios/{con}/activar         DENEGADA
                POST   /api/v1/organizaciones/{org}/medicos       DENEGADA
                GET    /api/v1/organizaciones/{org}/medicos       PERMITIDA
                GET    /api/v1/medicos/{med}                      PERMITIDA
                PUT    /api/v1/medicos/{med}                      DENEGADA
                DELETE /api/v1/medicos/{med}                      DENEGADA
                POST   /api/v1/medicos/{med}/activar              DENEGADA
                POST   /api/v1/medicos/{med}/horarios             DENEGADA
                GET    /api/v1/medicos/{med}/horarios             PERMITIDA
                GET    /api/v1/horarios/{hor}                     PERMITIDA
                PUT    /api/v1/horarios/{hor}                     DENEGADA
                DELETE /api/v1/horarios/{hor}                     DENEGADA
                POST   /api/v1/medicos/{med}/consultorios         DENEGADA
                GET    /api/v1/medicos/{med}/consultorios         PERMITIDA
                DELETE /api/v1/medicos/{med}/consultorios/{con}   DENEGADA
                POST   /api/v1/organizaciones/{org}/pacientes     PERMITIDA
                GET    /api/v1/organizaciones/{org}/pacientes     PERMITIDA
                GET    /api/v1/pacientes/{pac}                    PERMITIDA
                PUT    /api/v1/pacientes/{pac}                    PERMITIDA
                DELETE /api/v1/pacientes/{pac}                    PERMITIDA
                POST   /api/v1/pacientes/{pac}/activar            PERMITIDA
                POST   /api/v1/organizaciones/{org}/usuarios      DENEGADA
                GET    /api/v1/organizaciones/{org}/usuarios      DENEGADA
                GET    /api/v1/usuarios/{usr}                     DENEGADA
                PUT    /api/v1/usuarios/{usr}                     DENEGADA
                DELETE /api/v1/usuarios/{usr}                     DENEGADA
                POST   /api/v1/usuarios/{usr}/activar             DENEGADA
                """;
        int permitidas = 0;
        int denegadas = 0;
        for (String linea : tabla.strip().split("\\n")) {
            String[] partes = linea.strip().split("\\s+");
            String metodo = partes[0];
            String ruta = partes[1].replace("{org}", org).replace("{con}", con).replace("{med}", med)
                    .replace("{hor}", hor).replace("{pac}", pac).replace("{usr}", usr);
            var resultado = ejecutar(peticion(metodo, ruta,
                    "GET".equals(metodo) || "DELETE".equals(metodo) ? null : "{}"), tokenRecepcionA);
            if ("PERMITIDA".equals(partes[2])) {
                permitidas++;
                assertThat(resultado.andReturn().getResponse().getStatus())
                        .as("S3 concede %s %s a RECEPCIONISTA: no puede ser 403", metodo, ruta)
                        .isNotEqualTo(403);
            } else {
                denegadas++;
                afirmar403(resultado, ruta);
            }
        }
        assertThat(permitidas).as("S3 concede catorce celdas a recepción").isEqualTo(14);
        assertThat(denegadas).as("las otras veintitrés quedan denegadas").isEqualTo(23);
    }

    /**
     * Sonda de S2: rol fuera del contrato responde 401 genérico aunque la ruta exista
     * y el tenant sea el correcto.
     *
     * @throws Exception si una aserción de MockMvc falla
     */
    @Test
    @DisplayName("SONDA S2: un token SUPERADMIN responde 401 genérico donde recepción sí entra")
    void rolInventadoResponde401Generico() throws Exception {
        String token = ClaveRsaDePrueba.tokenPara("SUPERADMIN", organizacionA.getId());
        ejecutar(get("/api/v1/pacientes/" + pacienteA.getId()), token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.mensaje").value(
                        com.intoglobe.clinicas.security.SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO));
    }
}
