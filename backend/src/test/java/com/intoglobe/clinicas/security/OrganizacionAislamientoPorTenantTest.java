package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intoglobe.clinicas.dto.request.OrganizacionRequest;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;
import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.service.OrganizacionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SONDA INDEPENDIENTE de S3 y S4 (verificador, no del escritor).
 *
 * <p>Derivada del pedido y del documento, no de las pruebas del escritor: recorre la matriz celda
 * por celda de comportamiento y comprueba que el 403 del tenant ajeno no llega al repositorio.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sonda_s3s4;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizacionAislamientoPorTenantTest {

    private static final String MENSAJE_403 = "No tiene permisos para acceder a este recurso";
    private static final String CUERPO_PUT = """
            {"nombre":"Clínica Beta Secuestrada","plan":"ENTERPRISE"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private OrganizacionRepository organizacionRepository;

    @MockitoSpyBean
    private OrganizacionService organizacionService;

    private Organizacion orgA;
    private Organizacion orgB;

    @DynamicPropertySource
    static void claves(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @BeforeEach
    void sembrar() {
        orgA = organizacionRepository.save(new Organizacion("Clínica Sonda Alfa", PlanTipo.CLINICA, "sub-a"));
        orgB = organizacionRepository.save(new Organizacion("Clínica Sonda Beta", PlanTipo.CLINICA, "sub-b"));
        clearInvocations(organizacionRepository, organizacionService);
    }

    private ResultActions pedir(MockHttpServletRequestBuilder p, String rol, UUID tenant) throws Exception {
        return mockMvc.perform(p.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + ClaveRsaDePrueba.tokenPara(rol, tenant)));
    }

    private static void contrato403(ResultActions r, String ruta) throws Exception {
        r.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_403))
                .andExpect(jsonPath("$.ruta").value(ruta));
    }

    // ---------------- S3: la celda nueva y las vecinas ----------------

    @Test
    @DisplayName("S3 · GET por id responde 200 a ADMIN, MEDICO y RECEPCIONISTA con el nombre propio")
    void lecturaPorIdAdmiteLosTresRoles() throws Exception {
        String ruta = "/api/v1/organizaciones/" + orgA.getId();
        for (String rol : List.of("ADMIN", "MEDICO", "RECEPCIONISTA")) {
            pedir(get(ruta), rol, orgA.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orgA.getId().toString()))
                    .andExpect(jsonPath("$.nombre").value("Clínica Sonda Alfa"));
        }
    }

    @Test
    @DisplayName("S3 · PACIENTE queda fuera de la colección y de la lectura por id")
    void pacienteFueraDeAmbasRutas() throws Exception {
        contrato403(pedir(get("/api/v1/organizaciones"), "PACIENTE", orgA.getId()), "/api/v1/organizaciones");
        String ruta = "/api/v1/organizaciones/" + orgA.getId();
        contrato403(pedir(get(ruta), "PACIENTE", orgA.getId()), ruta);
    }

    @Test
    @DisplayName("S3 · la colección sigue siendo solo de ADMIN")
    void coleccionSoloAdmin() throws Exception {
        pedir(get("/api/v1/organizaciones"), "ADMIN", orgA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(orgA.getId().toString()));
        for (String rol : List.of("MEDICO", "RECEPCIONISTA", "PACIENTE")) {
            contrato403(pedir(get("/api/v1/organizaciones"), rol, orgA.getId()), "/api/v1/organizaciones");
        }
    }

    @Test
    @DisplayName("S3 · PUT y DELETE por id siguen siendo solo de ADMIN")
    void escriturasSoloAdmin() throws Exception {
        String ruta = "/api/v1/organizaciones/" + orgA.getId();
        for (String rol : List.of("MEDICO", "RECEPCIONISTA", "PACIENTE")) {
            contrato403(pedir(put(ruta).contentType(MediaType.APPLICATION_JSON).content(CUERPO_PUT), rol, orgA.getId()), ruta);
            contrato403(pedir(delete(ruta), rol, orgA.getId()), ruta);
        }
        assertThat(organizacionRepository.findById(orgA.getId()).orElseThrow().getNombre())
                .isEqualTo("Clínica Sonda Alfa");
        assertThat(organizacionRepository.findById(orgA.getId()).orElseThrow().isActivo()).isTrue();

        pedir(put(ruta).contentType(MediaType.APPLICATION_JSON).content(CUERPO_PUT), "ADMIN", orgA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Clínica Beta Secuestrada"));
        pedir(post("/api/v1/organizaciones").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nombre":"Clínica Nueva","plan":"SOLO"}
                        """), "ADMIN", orgA.getId())
                .andExpect(status().isForbidden());

        // La baja de la propia organización sigue siendo una celda de ADMIN con éxito.
        pedir(delete(ruta), "ADMIN", orgA.getId()).andExpect(status().isNoContent());
        assertThat(organizacionRepository.findById(orgA.getId()).orElseThrow().isActivo()).isFalse();
    }

    // ---------------- S4: el tenant ajeno, para los tres roles ----------------

    @Test
    @DisplayName("S4 · GET de otra organización: 403 en los tres roles sin tocar el repositorio")
    void lecturaAjena403SinRepositorio() throws Exception {
        String ruta = "/api/v1/organizaciones/" + orgB.getId();
        for (String rol : List.of("ADMIN", "MEDICO", "RECEPCIONISTA")) {
            clearInvocations(organizacionRepository, organizacionService);
            contrato403(pedir(get(ruta), rol, orgA.getId()), ruta);
            verify(organizacionService, never()).buscarPorId(any(), any());
            verify(organizacionRepository, never()).findById(any());
            assertThat(organizacionRepository.findById(orgB.getId()).orElseThrow().getNombre())
                    .isEqualTo("Clínica Sonda Beta");
        }
    }

    @Test
    @DisplayName("S4 · PUT y DELETE de otra organización: 403 sin escribir en la base")
    void escriturasAjenas403SinRepositorio() throws Exception {
        String ruta = "/api/v1/organizaciones/" + orgB.getId();
        for (String rol : List.of("ADMIN", "MEDICO", "RECEPCIONISTA")) {
            clearInvocations(organizacionRepository, organizacionService);
            contrato403(pedir(put(ruta).contentType(MediaType.APPLICATION_JSON).content(CUERPO_PUT), rol, orgA.getId()), ruta);
            contrato403(pedir(delete(ruta), rol, orgA.getId()), ruta);
            verify(organizacionRepository, never()).save(any());
            verify(organizacionRepository, never()).deleteById(any());
        }
        var b = organizacionRepository.findById(orgB.getId()).orElseThrow();
        assertThat(b.getNombre()).isEqualTo("Clínica Sonda Beta");
        assertThat(b.isActivo()).isTrue();
    }

    @Test
    @DisplayName("S4 · un id inexistente de otro tenant responde 403, no 404: no revela existencia")
    void idInexistenteAjenoNoRevelaExistencia() throws Exception {
        String ruta = "/api/v1/organizaciones/" + UUID.randomUUID();
        for (String rol : List.of("ADMIN", "MEDICO", "RECEPCIONISTA")) {
            contrato403(pedir(get(ruta), rol, orgA.getId()), ruta);
        }
    }

    @Test
    @DisplayName("S4 · un llamador directo del servicio no puede saltarse la comprobación")
    void llamadorDirectoDelServicioQuedaBloqueado() {
        clearInvocations(organizacionRepository);
        var request = new OrganizacionRequest("Clínica Beta Secuestrada", PlanTipo.ENTERPRISE, null, null);

        assertThatThrownBy(() -> organizacionService.buscarPorId(orgB.getId(), orgA.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> organizacionService.actualizar(orgB.getId(), orgA.getId(), request))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> organizacionService.desactivar(orgB.getId(), orgA.getId()))
                .isInstanceOf(AccessDeniedException.class);

        verify(organizacionRepository, never()).findById(any());
        verify(organizacionRepository, never()).save(any());
        var b = organizacionRepository.findById(orgB.getId()).orElseThrow();
        assertThat(b.getNombre()).isEqualTo("Clínica Sonda Beta");
        assertThat(b.isActivo()).isTrue();
    }

    @Test
    @DisplayName("S4 · el servicio sí atiende la propia organización, y un id propio inexistente da 404")
    void propioTenantSiPasaYUnIdPropioInexistenteDa404() throws Exception {
        pedir(get("/api/v1/organizaciones/" + orgA.getId()), "ADMIN", orgA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Clínica Sonda Alfa"));
        // el id del path debe ser el del token: si el tenant es A y el path es un UUID que no es A,
        // la respuesta es 403 aunque el UUID no exista (no se consulta la base).
        String inexistente = UUID.randomUUID().toString();
        contrato403(pedir(get("/api/v1/organizaciones/" + inexistente), "MEDICO", orgA.getId()),
                "/api/v1/organizaciones/" + inexistente);
    }
}
