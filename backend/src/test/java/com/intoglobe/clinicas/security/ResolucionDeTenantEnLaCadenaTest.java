package com.intoglobe.clinicas.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.PlanTipo;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de la resolución del tenant a través de la cadena de filtros real: el
 * {@link SecurityConfig} de producción, el {@link JwtAuthenticationFilter} cableado por esa
 * configuración y el {@link ResolutorDeTenant} de verdad sobre la tabla de organizaciones.
 *
 * <p>Existen porque el cableado es invisible en el resto de las pruebas: si el resolutor no
 * llegara al filtro, todo seguiría compilando y todo lo que hoy se prueba del camino con UUID
 * seguiría en verde, mientras los tokens con slug responderían {@code 403} siempre. El
 * contador del controlador prueba además que el rechazo ocurre <em>antes</em> del controlador
 * y no dentro de él.</p>
 *
 * <p>La fila de la organización se crea dentro de la transacción de la prueba y se revierte
 * al terminarla: la base compartida por el resto de la suite no se contamina.</p>
 *
 * <p>{@link ResolutorDeTenant} se registra como espía, no como doble: el método real sigue
 * consultando la base —es lo que da sentido a estas pruebas— y el espía solo añade la
 * posibilidad de contar invocaciones, que es cómo se demuestra que el camino con UUID no
 * consulta nada y que aceptar slugs no lo encareció.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ResolucionDeTenantEnLaCadenaTest.ConfiguracionSonda.class)
@Transactional
class ResolucionDeTenantEnLaCadenaTest {

    private static final String RUTA_SONDA = "/api/v1/sonda/tenant";
    private static final String SLUG = "acme-tenant";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ControladorSonda controlador;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private ResolutorDeTenant resolutor;

    private UUID organizacionId;

    @DynamicPropertySource
    static void inyectarClavePublica(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @BeforeEach
    void prepararOrganizacion() {
        controlador.reiniciar();
        organizacionId = crearOrganizacionConSlug(SLUG);
    }

    @Test
    @DisplayName("Un slug que resuelve autentica la petición con el UUID de su organización")
    void unSlugQueResuelveAutenticaConElUuidDeSuOrganizacion() throws Exception {
        sonda(ClaveRsaDePrueba.token().conInquilino(SLUG).compactar())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(organizacionId.toString()))
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        assertThat(controlador.llegadas())
                .as("un slug resuelto es un token legítimo: la petición llega al controlador")
                .isEqualTo(1);
        verify(resolutor).porSlug(SLUG);
    }

    @Test
    @DisplayName("Un slug que no resuelve responde 403 antes del controlador")
    void unSlugQueNoResuelveResponde403AntesDelControlador() throws Exception {
        sonda(ClaveRsaDePrueba.token().conInquilino("clinica-inexistente").compactar())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.mensaje").value(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO))
                .andExpect(jsonPath("$.ruta").value(RUTA_SONDA));

        assertThat(controlador.llegadas())
                .as("el rechazo ocurre en el filtro, antes de la cadena de autorización")
                .isZero();
    }

    @Test
    @DisplayName("Un tenantId en UUID sigue autenticando y no consulta el resolutor")
    void unTenantIdEnUuidSigueAutenticandoSinConsultarElResolutor() throws Exception {
        sonda(ClaveRsaDePrueba.token().conInquilino(organizacionId.toString()).compactar())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(organizacionId.toString()));

        assertThat(controlador.llegadas()).isEqualTo(1);
        verify(resolutor, never()).porSlug(anyString());
    }

    /**
     * Declara, con una prueba, una decisión de diseño: el filtro traduce identidad y no
     * valida existencia ni estado de la organización.
     *
     * <p>Un token con un UUID canónico que no corresponde a ninguna organización atraviesa
     * el filtro sin {@code 403} y la petición llega a la operación, que responde {@code 404}
     * con el contrato de error —no {@code 500} ni una lista vacía—. Es el comportamiento
     * deliberado, y estas son las razones:</p>
     * <ul>
     *   <li>El filtro <strong>traduce identidad</strong>, no autoriza ni comprueba existencia:
     *       deja el tenant en un UUID y quien decide qué se puede hacer con él es la matriz y
     *       la capa de servicio.</li>
     *   <li>Consultar la organización en cada petición costaría <strong>una consulta por
     *       request</strong> justo en el camino que hoy no cuesta nada, el de los tokens con
     *       UUID.</li>
     *   <li>Validar existencia y estado activo le corresponde a <strong>la capa que conoce la
     *       organización</strong>. Ahí ya se hace: la operación resuelve la organización del
     *       path y responde {@code 404} cuando no existe.</li>
     *   <li>El camino con slug difiere <strong>a propósito</strong>: si el texto no resuelve,
     *       la petición no tiene identidad posible y se corta con {@code 403}, porque no hay
     *       ningún UUID con el que continuar.</li>
     * </ul>
     */
    @Test
    @DisplayName("Un tenantId en UUID inexistente no se rechaza en el filtro: responde 404 la capa que conoce la organización")
    void unTenantIdEnUuidInexistenteNoSeRechazaEnElFiltroYResponde404() throws Exception {
        UUID tenantFantasma = UUID.fromString("00000000-0000-4000-8000-000000000000");
        String ruta = "/api/v1/organizaciones/" + tenantFantasma + "/pacientes";

        mockMvc.perform(get(ruta).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + ClaveRsaDePrueba.token().conInquilino(tenantFantasma.toString()).compactar()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje")
                        .value("No se encontró el recurso Organizacion con id: " + tenantFantasma))
                .andExpect(jsonPath("$.ruta").value(ruta));

        verify(resolutor, never()).porSlug(anyString());
    }

    private ResultActions sonda(String token) throws Exception {
        return mockMvc.perform(get(RUTA_SONDA).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * Crea una organización con el slug indicado y devuelve su identificador.
     *
     * @param slug slug a guardar en la columna real
     * @return identificador de la organización creada
     */
    private UUID crearOrganizacionConSlug(String slug) {
        var organizacion = new Organizacion("Clínica de la sonda", PlanTipo.CLINICA, null);
        organizacion.asignarSlug(slug);
        entityManager.persist(organizacion);
        entityManager.flush();
        return organizacion.getId();
    }

    /**
     * Sonda que publica el tenant autenticado y cuenta las peticiones que la alcanzan.
     *
     * <p>El contador es la prueba de que un rechazo del filtro ocurre <em>antes</em> del
     * controlador: la respuesta de error no basta, porque un controlador también podría
     * producirla.</p>
     */
    @TestConfiguration
    static class ConfiguracionSonda {

        @Bean
        ControladorSonda controladorSonda() {
            return new ControladorSonda();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain cadenaDeSonda(HttpSecurity http, JwtAuthenticationFilter filtroJwt,
                RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler accesoDenegado) throws Exception {
            return CadenaDeSonda.crear(http, RUTA_SONDA, filtroJwt, entryPoint, accesoDenegado);
        }
    }

    @RestController
    static class ControladorSonda {

        private final AtomicInteger llegadas = new AtomicInteger();

        @GetMapping(RUTA_SONDA)
        Map<String, Object> estado() {
            llegadas.incrementAndGet();
            Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            if (autenticacion == null || !(autenticacion.getPrincipal() instanceof AuthenticatedUser usuario)) {
                cuerpo.put("autenticado", false);
                return cuerpo;
            }
            cuerpo.put("autenticado", true);
            cuerpo.put("tenantId", usuario.tenantId().toString());
            cuerpo.put("rol", usuario.role());
            return cuerpo;
        }

        int llegadas() {
            return llegadas.get();
        }

        void reiniciar() {
            llegadas.set(0);
        }
    }
}
