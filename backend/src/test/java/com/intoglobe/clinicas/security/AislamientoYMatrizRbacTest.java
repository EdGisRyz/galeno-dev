package com.intoglobe.clinicas.security;

import java.math.BigDecimal;
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
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.ConsultorioRepository;
import com.intoglobe.clinicas.repository.MedicoRepository;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

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
 * <p>Cada prueba siembra dos organizaciones completas —A y B, cada una con su consultorio y
 * su médico— y emite tokens RS256 de verdad con el par que {@link ClaveRsaDePrueba} genera en
 * memoria. No hay usuarios simulados ni post-procesadores de seguridad: la autorización que
 * se ejerce es la de producción, de modo que una regla que falte en la cadena o un recurso
 * ajeno que se cuele en un service hacen fallar estas pruebas.</p>
 *
 * <h2>Cobertura de la matriz (§5.3)</h2>
 *
 * <ul>
 *   <li><strong>ADMIN</strong>: las catorce operaciones permitidas sobre su propio tenant
 *       ({@link #adminOperaLasCatorceOperacionesPermitidasSobreSuPropioTenant()}); el alta de
 *       organizaciones {@code 403} para los tres roles
 *       ({@link #crearOrganizacionResponde403ParaLosTresRoles()}); y las rutas con el
 *       {@code organizacionId} de otro tenant, todas {@code 403} sin excepción de rol
 *       ({@link #elPathDeOtroTenantResponde403TambienParaAdmin()}).</li>
 *   <li><strong>MEDICO</strong>: las cuatro lecturas de su tenant permitidas
 *       ({@link #medicoLeeSuPropioTenant()}) y las once operaciones restantes {@code 403}
 *       ({@link #celdaDenegadaResponde403ConElContratoDeError}).</li>
 *   <li><strong>PACIENTE</strong>: las quince operaciones {@code 403}
 *       ({@link #celdaDenegadaResponde403ConElContratoDeError}).</li>
 *   <li><strong>IDOR por id</strong>: token de A contra recursos de B en las seis operaciones
 *       por id → {@code 404} sin revelar el recurso
 *       ({@link #recursoDeOtroTenantResponde404SinRevelarlo()}).</li>
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private ConsultorioRepository consultorioRepository;

    @Autowired
    private MedicoRepository medicoRepository;

    private Organizacion organizacionA;
    private Organizacion organizacionB;
    private Consultorio consultorioA;
    private Consultorio consultorioB;
    private Medico medicoA;
    private Medico medicoB;

    private String tokenAdminA;
    private String tokenAdminB;
    private String tokenMedicoA;
    private String tokenPacienteA;

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

        tokenAdminA = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, organizacionA.getId());
        tokenAdminB = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, organizacionB.getId());
        tokenMedicoA = ClaveRsaDePrueba.tokenPara(ROL_MEDICO, organizacionA.getId());
        tokenPacienteA = ClaveRsaDePrueba.tokenPara(ROL_PACIENTE, organizacionA.getId());
    }

    // ------------------------------------------------------------------
    // ADMIN: las catorce operaciones permitidas sobre su propio tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN opera las catorce operaciones permitidas sobre su propio tenant")
    void adminOperaLasCatorceOperacionesPermitidasSobreSuPropioTenant() throws Exception {
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

        // 15. DELETE /organizaciones/{id}
        ejecutar(delete(rutaOrganizacionA), tokenAdminA)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
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
     * Celdas {@code 403} de la matriz: las quince operaciones para {@code PACIENTE} y las
     * once que {@code MEDICO} no tiene permitidas, todas con el tenant correcto en el path
     * para que lo que se ejerza sea el rol y no el aislamiento.
     *
     * @return rol, descripción de la operación, método, ruta y cuerpo de cada celda denegada
     */
    Stream<Arguments> celdasDenegadas() {
        return Stream.concat(
                celdasDenegadasDe(ROL_PACIENTE),
                celdasDenegadasDe(ROL_MEDICO)
                        .filter(celda -> !esLecturaPermitida((String) celda.get()[1])));
    }

    @ParameterizedTest(name = "{0} · {1} responde 403")
    @MethodSource("celdasDenegadas")
    void celdaDenegadaResponde403ConElContratoDeError(
            String rol, String descripcion, String metodo, String ruta, String cuerpo) throws Exception {
        String token = ROL_PACIENTE.equals(rol) ? tokenPacienteA : tokenMedicoA;

        afirmar403(ejecutar(peticion(metodo, ruta, cuerpo), token), ruta);
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
                Arguments.of(rol, "POST médico", "POST",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos",
                        cuerpoMedico(consultorioA.getId().toString(), "Dr. Nuevo")),
                Arguments.of(rol, "GET médicos anidados", "GET",
                        "/api/v1/organizaciones/" + organizacionA.getId() + "/medicos", null),
                Arguments.of(rol, "GET médico por id", "GET",
                        "/api/v1/medicos/" + medicoA.getId(), null),
                Arguments.of(rol, "PUT médico", "PUT", "/api/v1/medicos/" + medicoA.getId(),
                        cuerpoMedico(consultorioA.getId().toString(), "Dr. Nuevo")),
                Arguments.of(rol, "DELETE médico", "DELETE", "/api/v1/medicos/" + medicoA.getId(), null));
    }

    /**
     * Las cuatro lecturas que la matriz concede a {@code MEDICO} en su propio tenant.
     *
     * @param descripcion descripción de la operación
     * @return {@code true} si la celda está permitida para {@code MEDICO}
     */
    private static boolean esLecturaPermitida(String descripcion) {
        return List.of("GET consultorios anidados", "GET consultorio por id",
                "GET médicos anidados", "GET médico por id").contains(descripcion);
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
    }

    // ------------------------------------------------------------------
    // R-SEG-04: IDOR por id → 404 sin revelar la existencia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un token de A no alcanza los recursos de B por id: 404 sin revelarlos")
    void recursoDeOtroTenantResponde404SinRevelarlo() throws Exception {
        String consultorioAjeno = "/api/v1/consultorios/" + consultorioB.getId();
        String medicoAjeno = "/api/v1/medicos/" + medicoB.getId();

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
            default -> throw new IllegalArgumentException("Rol sin token en estas pruebas: " + rol);
        };
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

    private static String cuerpoMedico(String consultorioId, String nombre) {
        return """
                {"consultorioId":"%s","nombre":"%s","especialidad":"Cardiología","tarifaBase":"500.00"}
                """.formatted(consultorioId, nombre);
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
}
