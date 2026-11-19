package com.intoglobe.clinicas.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El contrato público del onboarding, de extremo a extremo y sin token: el alta de una clínica
 * (S1, S2, S4, S5, S6) y el catálogo de planes (S2 ter).
 *
 * <p>Se ejerce la cadena de filtros y la base reales, porque lo que hay que demostrar no es que un
 * método devuelva un DTO sino que <strong>las dos filas se escriben</strong> —organización y primer
 * administrador— con los valores que el contrato fija, y que un rechazo de validación no deja nada a
 * medias. Las peticiones no llevan cabecera {@code Authorization} a propósito: la ruta es pública.</p>
 *
 * <p><strong>Base propia por clase</strong>, como las demás pruebas de contexto completo: crear
 * organizaciones aquí no altera lo que otras clases esperan ver y esta no depende del orden de
 * ejecución de Surefire.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingControllerTest {

    private static final String RUTA_REGISTRO = "/api/v1/onboarding/registro-clinica";
    private static final String RUTA_PLANES = "/api/v1/onboarding/planes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("registra la clínica y su administrador y devuelve solo id y nombre de la organización")
    void registraClinicaYAdministrador() throws Exception {
        String cuerpo = """
                {"nombreClinica":"Clínica del Valle","plan":"CLINICA",
                 "nombreAdmin":"Administración General","correoAdmin":"Administracion@Example.com"}
                """;

        String respuesta = mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombreClinica").value("Clínica del Valle"))
                .andReturn().getResponse().getContentAsString();

        // S2: ni contraseña, ni auth_user_id, ni rol. Exactamente los dos campos del contrato.
        Map<String, Object> campos = JsonPath.read(respuesta, "$");
        assertThat(campos.keySet())
                .as("la respuesta no lleva ningún dato de autenticación")
                .isEqualTo(Set.of("organizacionId", "nombreClinica"));

        Organizacion organizacion = organizacionRepository.buscarPorSlug("clinica-del-valle").orElseThrow();
        assertThat(organizacion.getId().toString())
                .isEqualTo(JsonPath.read(respuesta, "$.organizacionId"));
        assertThat(organizacion.isActivo()).as("nace activa").isTrue();
        assertThat(organizacion.getPlan()).as("S4: el plan que eligió la persona").isEqualTo(PlanTipo.CLINICA);
        assertThat(organizacion.getNombre()).isEqualTo("Clínica del Valle");

        Usuario administrador = usuarioRepository
                .buscarPorOrganizacionYEmail(organizacion.getId(), "administracion@example.com")
                .orElseThrow();
        assertThat(administrador.getRol()).isEqualTo(RolUsuario.ADMIN);
        assertThat(administrador.isActivo()).isTrue();
        assertThat(administrador.getMedico()).as("S5: sin médico asociado").isNull();
        assertThat(administrador.getAuthUserId()).as("S5: identidad de autenticación nueva").isNotNull();
        assertThat(administrador.getNombre())
                .as("T7: el nombre del administrador se persiste")
                .isEqualTo("Administración General");
        assertThat(administrador.getEmail())
                .as("el correo se guarda normalizado")
                .isEqualTo("administracion@example.com");
    }

    @Test
    @DisplayName("el plan elegido se persiste tal como llega, validado contra el enum")
    void persisteElPlanElegido() throws Exception {
        for (PlanTipo plan : PlanTipo.values()) {
            String nombre = "Clínica Plan " + plan;

            mockMvc.perform(post(RUTA_REGISTRO)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(cuerpoRegistro(nombre, plan.name(), "admin." + plan + "@example.com")))
                    .andExpect(status().isCreated());

            assertThat(organizacionPorNombre(nombre))
                    .as("la organización con el plan %s existe", plan)
                    .isPresent()
                    .get()
                    .extracting(Organizacion::getPlan)
                    .as("el plan %s no se cambia por otro", plan)
                    .isEqualTo(plan);
        }
    }

    @Test
    @DisplayName("un plan fuera del enum responde 400 y no crea nada")
    void planFueraDelEnumNoCreaNada() throws Exception {
        long organizacionesAntes = organizacionRepository.count();
        long cuentasAntes = usuarioRepository.count();

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Plan Inventado", "GOLD", "inventado@example.com")))
                .andExpect(status().isBadRequest());

        assertThat(organizacionRepository.count()).isEqualTo(organizacionesAntes);
        assertThat(usuarioRepository.count()).isEqualTo(cuentasAntes);
    }

    @Test
    @DisplayName("S4: dos clínicas con el mismo nombre se registran, la segunda con sufijo en el slug")
    void dosClinicasConElMismoNombreCompartenSlugConSufijo() throws Exception {
        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Repetida", "SOLO", "repetida.uno@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Repetida", "CLINICA", "repetida.dos@example.com")))
                .andExpect(status().isCreated());

        List<Organizacion> conEseNombre = organizacionRepository.findAll().stream()
                .filter(organizacion -> "Clínica Repetida".equals(organizacion.getNombre()))
                .toList();
        assertThat(conEseNombre).hasSize(2);
        assertThat(conEseNombre.stream().map(Organizacion::getSlug))
                .as("los slug son distintos: la columna es única en toda la plataforma")
                .containsExactlyInAnyOrder("clinica-repetida", "clinica-repetida-2");
    }

    @Test
    @DisplayName("S6: un correo ya usado en otra clínica no bloquea el registro")
    void unCorreoYaUsadoEnOtraClinicaNoBloqueaElRegistro() throws Exception {
        String correo = "misma.persona@example.com";

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Primera", "SOLO", correo)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Segunda", "SOLO", correo)))
                .andExpect(status().isCreated());

        List<Usuario> cuentas = usuarioRepository.findAll().stream()
                .filter(usuario -> correo.equals(usuario.getEmail()))
                .toList();
        assertThat(cuentas)
                .as("la unicidad es por organización: son dos cuentas independientes")
                .hasSize(2)
                .allSatisfy(cuenta -> assertThat(cuenta.getRol()).isEqualTo(RolUsuario.ADMIN));
    }

    @Test
    @DisplayName("S3: el nombre de la clínica exige al menos una letra y admite dígitos")
    void elNombreDeLaClinicaAdmiteDigitosPeroExigeLetras() throws Exception {
        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Consultorio 2", "SOLO", "sala.dos@example.com")))
                .andExpect(status().isCreated());

        long organizacionesAntes = organizacionRepository.count();

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("454545", "SOLO", "sin.letras@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", containsString("nombreClinica")));

        assertThat(organizacionRepository.count())
                .as("un nombre inválido no deja organización a medias")
                .isEqualTo(organizacionesAntes);
    }

    @Test
    @DisplayName("S3: el correo del administrador exige forma de correo")
    void elCorreoDelAdministradorExigeFormaDeCorreo() throws Exception {
        long organizacionesAntes = organizacionRepository.count();
        long cuentasAntes = usuarioRepository.count();

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Correo Roto", "SOLO", "juan@")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", containsString("correoAdmin")));

        assertThat(organizacionRepository.count()).isEqualTo(organizacionesAntes);
        assertThat(usuarioRepository.count()).isEqualTo(cuentasAntes);
    }

    @Test
    @DisplayName("T7: el nombre del administrador es obligatorio y no admite dígitos")
    void elNombreDelAdministradorEsObligatorioYSinDigitos() throws Exception {
        long cuentasAntes = usuarioRepository.count();

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Sin Nombre Admin", "SOLO", "sin.nombre@example.com", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", containsString("nombreAdmin")));

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoRegistro("Clínica Admin Numerico", "SOLO", "admin.123@example.com",
                        "Administrador 123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", containsString("nombreAdmin")));

        assertThat(usuarioRepository.count()).isEqualTo(cuentasAntes);
    }

    @Test
    @DisplayName("S2 ter: GET /onboarding/planes publica los tres planes con su etiqueta y sus límites")
    void elCatalogoDePlanesPublicaLosTresPlanesConSusLimites() throws Exception {
        String cuerpo = mockMvc.perform(get(RUTA_PLANES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(PlanTipo.values().length))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> planes = JsonPath.read(cuerpo, "$");
        assertThat(planes).allSatisfy(plan -> assertThat(plan.keySet())
                .as("cada plan publica su etiqueta y sus dos límites, sin campos de más")
                .isEqualTo(Set.of("plan", "etiqueta", "maxMedicos", "maxConsultorios")));

        assertThat(limitesDe(planes, "SOLO"))
                .as("un solo profesional: un médico y un consultorio")
                .containsExactly(1, 1);
        assertThat(limitesDe(planes, "CLINICA"))
                .as("clínica: diez médicos y seis consultorios")
                .containsExactly(10, 6);
        assertThat(limitesDe(planes, "ENTERPRISE"))
                .as("empresarial: sin límite, y el contrato lo dice con null")
                .containsExactly((Object) null, (Object) null);

        assertThat(planes).extracting(plan -> plan.get("etiqueta"))
                .as("todos los planes traen una etiqueta legible")
                .doesNotContainNull();
    }

    private Optional<Organizacion> organizacionPorNombre(String nombre) {
        return organizacionRepository.findAll().stream()
                .filter(organizacion -> nombre.equals(organizacion.getNombre()))
                .findFirst();
    }

    private static List<Object> limitesDe(List<Map<String, Object>> planes, String plan) {
        return planes.stream()
                .filter(entrada -> plan.equals(entrada.get("plan")))
                .findFirst()
                .map(entrada -> Arrays.asList(entrada.get("maxMedicos"), entrada.get("maxConsultorios")))
                .orElseThrow(() -> new AssertionError("el plan " + plan + " no está en el catálogo"));
    }

    /**
     * Cuerpo de alta de una clínica con un nombre de administrador por omisión.
     *
     * @param nombreClinica nombre de la clínica
     * @param plan          nombre del plan
     * @param correoAdmin   correo del administrador
     * @return cuerpo JSON de la petición
     */
    private static String cuerpoRegistro(String nombreClinica, String plan, String correoAdmin) {
        return cuerpoRegistro(nombreClinica, plan, correoAdmin, "Administración");
    }

    /**
     * Cuerpo de alta de una clínica.
     *
     * @param nombreClinica nombre de la clínica
     * @param plan          nombre del plan
     * @param correoAdmin   correo del administrador
     * @param nombreAdmin   nombre del administrador
     * @return cuerpo JSON de la petición
     */
    private static String cuerpoRegistro(String nombreClinica, String plan, String correoAdmin,
            String nombreAdmin) {
        return """
                {"nombreClinica":"%s","plan":"%s","nombreAdmin":"%s","correoAdmin":"%s"}
                """.formatted(nombreClinica, plan, nombreAdmin, correoAdmin);
    }
}
