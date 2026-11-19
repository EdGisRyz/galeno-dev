package com.intoglobe.clinicas.security;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.intoglobe.clinicas.repository.OrganizacionRepository;
import com.intoglobe.clinicas.repository.UsuarioRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las dos celdas públicas del onboarding y lo que <strong>no</strong> abren (S8).
 *
 * <p>Un {@code permitAll()} sobre una ruta que escribe es el permiso más peligroso de esta cadena:
 * si la regla queda ancha —un comodín, o un {@code permitAll} por verbo mal puesto— cualquier
 * cliente anónimo alcanza escrituras que la matriz cerraba. Estas pruebas fijan tres cosas:</p>
 * <ul>
 *   <li>Las dos celdas declaradas funcionan <strong>sin token</strong>: {@code POST
 *       /api/v1/onboarding/registro-clinica} responde {@code 201} y crea las dos filas, y {@code GET
 *       /api/v1/onboarding/planes} responde {@code 200}.</li>
 *   <li>Ninguna otra ruta de escritura se abrió: sin token siguen respondiendo {@code 401} con el
 *       contrato de error, y ninguna deja filas nuevas. {@code POST /organizaciones} —la que la spec
 *       nombra— sigue además {@code 403} para {@code ADMIN}, que es su celda de siempre.</li>
 *   <li>La celda es <strong>exacta</strong>: el verbo que no se declaró sobre la misma ruta, un
 *       segmento de más y la ruta padre quedan cerrados. Una regla de prefijo habría abierto los
 *       tres.</li>
 * </ul>
 *
 * <p>La base es propia de la clase para que el alta real no altere lo que otras pruebas esperan ver;
 * los tokens se firman con el par de {@link ClaveRsaDePrueba} y los ejerce la cadena de producción.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding_publico;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingRutasPublicasTest {

    private static final String RUTA_REGISTRO = "/api/v1/onboarding/registro-clinica";
    private static final String RUTA_PLANES = "/api/v1/onboarding/planes";
    private static final String RUTA_ORGANIZACIONES = "/api/v1/organizaciones";

    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";
    private static final String MENSAJE_ACCESO_DENEGADO =
            "No tiene permisos para acceder a este recurso";

    private static final UUID ID = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final String ROL_ADMIN = "ADMIN";

    private static final String CUERPO_REGISTRO = """
            {"nombreClinica":"Clínica Pública","plan":"CLINICA",
             "nombreAdmin":"Administración","correoAdmin":"publica@example.com"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizacionRepository organizacionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @Test
    @DisplayName("las dos celdas declaradas responden sin token: 201 el alta y 200 el catálogo de planes")
    void lasDosCeldasDeclaradasRespondenSinToken() throws Exception {
        long cuentasAntes = usuarioRepository.count();

        mockMvc.perform(MockMvcRequestBuilders.get(RUTA_PLANES))
                .andExpect(status().isOk());

        mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO_REGISTRO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombreClinica").value("Clínica Pública"));

        assertThat(organizacionRepository.buscarPorSlug("clinica-publica"))
                .as("el alta anónima escribe la organización")
                .isPresent();
        assertThat(usuarioRepository.count())
                .as("y su administrador: las dos filas, no una")
                .isEqualTo(cuentasAntes + 1);
    }

    /**
     * Todas las escrituras de la API que <strong>no</strong> son del onboarding.
     *
     * <p>Se ejercen sin token: si el {@code permitAll} del onboarding hubiera quedado ancho, alguna
     * respondería {@code 201}, {@code 200} o {@code 204} en vez de {@code 401}.</p>
     *
     * @return verbo y ruta de cada escritura ajena al onboarding
     */
    static Stream<Arguments> escriturasAjenasAlOnboarding() {
        return Stream.of(
                Arguments.of("POST", RUTA_ORGANIZACIONES),
                Arguments.of("PUT", RUTA_ORGANIZACIONES + "/" + ID),
                Arguments.of("DELETE", RUTA_ORGANIZACIONES + "/" + ID),
                Arguments.of("POST", RUTA_ORGANIZACIONES + "/" + ID + "/consultorios"),
                Arguments.of("POST", RUTA_ORGANIZACIONES + "/" + ID + "/medicos"),
                Arguments.of("POST", RUTA_ORGANIZACIONES + "/" + ID + "/pacientes"),
                Arguments.of("POST", RUTA_ORGANIZACIONES + "/" + ID + "/usuarios"),
                Arguments.of("POST", "/api/v1/consultorios/" + ID + "/activar"),
                Arguments.of("POST", "/api/v1/medicos/" + ID + "/activar"),
                Arguments.of("POST", "/api/v1/pacientes/" + ID + "/activar"),
                Arguments.of("POST", "/api/v1/usuarios/" + ID + "/activar"),
                Arguments.of("PUT", "/api/v1/pacientes/" + ID),
                Arguments.of("PUT", "/api/v1/usuarios/" + ID));
    }

    @ParameterizedTest(name = "{0} {1} sigue cerrada sin token")
    @MethodSource("escriturasAjenasAlOnboarding")
    void ningunaOtraEscrituraQuedaAbierta(String metodo, String ruta) throws Exception {
        long organizacionesAntes = organizacionRepository.count();
        long cuentasAntes = usuarioRepository.count();

        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(metodo), ruta)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(ruta));

        assertThat(organizacionRepository.count())
                .as("una escritura ajena rechazada no deja organización")
                .isEqualTo(organizacionesAntes);
        assertThat(usuarioRepository.count())
                .as("ni cuenta")
                .isEqualTo(cuentasAntes);
    }

    @Test
    @DisplayName("POST /organizaciones sigue siendo 403 para ADMIN aunque el onboarding esté abierto")
    void elAltaDeOrganizacionesSigueCerradaParaAdmin() throws Exception {
        String token = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, ID);
        long organizacionesAntes = organizacionRepository.count();

        mockMvc.perform(post(RUTA_ORGANIZACIONES)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nombre":"Clínica Alfa","plan":"SOLO"}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO));

        assertThat(organizacionRepository.count())
                .as("el alta cerrada no deja filas")
                .isEqualTo(organizacionesAntes);
    }

    /**
     * Rutas vecinas de las dos celdas públicas, con el verbo que no se declaró o un segmento de más.
     *
     * @return verbo y ruta de cada vecina que tiene que seguir cerrada
     */
    static Stream<Arguments> vecinasQueSiguenCerradas() {
        return Stream.of(
                // GET sobre la ruta de alta: el verbo no se declaró.
                Arguments.of("GET", RUTA_REGISTRO),
                // POST sobre el catálogo de planes: tampoco.
                Arguments.of("POST", RUTA_PLANES),
                // Un segmento de más: una regla de prefijo lo habría abierto.
                Arguments.of("POST", RUTA_REGISTRO + "/otra-cosa"),
                // La ruta padre del onboarding.
                Arguments.of("GET", "/api/v1/onboarding"));
    }

    @ParameterizedTest(name = "{0} {1} no es pública")
    @MethodSource("vecinasQueSiguenCerradas")
    void laCeldaPublicaEsExacta(String metodo, String ruta) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(metodo), ruta)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER));
    }

    @Test
    @DisplayName("GET /onboarding/planes sigue siendo público cuando la petición trae un token")
    void elCatalogoSigueSiendoPublicoConToken() throws Exception {
        String token = ClaveRsaDePrueba.tokenPara(ROL_ADMIN, ID);

        mockMvc.perform(MockMvcRequestBuilders.get(RUTA_PLANES)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}
