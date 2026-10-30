package com.intoglobe.clinicas.security;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Pruebas de R-SEG-09: los defaults de configuración son seguros sin ningún perfil activo.
 *
 * <p>Dos artefactos de desarrollo no pueden viajar encendidos a producción: la
 * documentación de la API (springdoc) y la consola de H2. Aquí se fija que, tal como queda
 * el proyecto, <strong>ninguno de los dos existe</strong> si no se activa un perfil de
 * desarrollo, y que sus rutas siguen siendo {@code permitAll()} en
 * {@link SecurityConfig} —es decir, lo que las cierra es que no existan, no la
 * autenticación—. El estado habilitado se prueba aparte, en
 * {@link DocumentacionHabilitadaEnDesarrolloTest}.</p>
 *
 * <p>Se comprueban las dos mitades de cada control: el valor efectivo de la propiedad en el
 * entorno y la respuesta HTTP. Una propiedad apagada con una ruta todavía mapeada, o una
 * ruta ausente por casualidad, no sirven: la prueba tiene que fallar si cualquiera de las
 * dos cambia.</p>
 *
 * <p>Base de datos propia de la clase, mismo dialecto que el perfil {@code test}, para no
 * interferir con la base compartida de la suite.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:configuracion_segura;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfiguracionSeguraPorDefectoTest {

    private static final List<String> RUTAS_DE_DOCUMENTACION =
            List.of("/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html");

    private static final String RUTA_CONSOLA_H2 = "/h2-console";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment entorno;

    /**
     * Publica la clave pública de prueba para que el contexto arranque con el fail-fast
     * activo y se pueda autenticar la petición a la consola de H2.
     *
     * @param registro registro de propiedades dinámicas del contexto de prueba
     */
    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @Test
    @DisplayName("La documentación de la API está apagada por defecto: sus rutas responden 404, no 401 ni 403")
    void laDocumentacionEstaApagadaPorDefecto() throws Exception {
        assertThat(entorno.getProperty("springdoc.api-docs.enabled"))
                .as("springdoc tiene que estar apagado en la línea base, no solo sin configurar")
                .isEqualTo("false");
        assertThat(entorno.getProperty("springdoc.swagger-ui.enabled"))
                .isEqualTo("false");

        for (String ruta : RUTAS_DE_DOCUMENTACION) {
            int estado = mockMvc.perform(get(ruta)).andReturn().getResponse().getStatus();

            assertThat(estado)
                    .as("la ruta de documentación %s no puede existir sin el perfil de desarrollo", ruta)
                    .isEqualTo(404);
            assertThat(estado)
                    .as("la documentación sigue siendo permitAll(): no es la autenticación la que la cierra")
                    .isNotIn(401, 403);
        }
    }

    @Test
    @DisplayName("La consola de H2 está apagada, no es pública y no responde ni con token")
    void laConsolaDeH2EstaApagadaYNoEsPublica() throws Exception {
        assertThat(entorno.getProperty("spring.h2.console.enabled"))
                .as("la consola de H2 no puede quedar encendida por defecto")
                .isEqualTo("false");

        int sinToken = mockMvc.perform(get(RUTA_CONSOLA_H2)).andReturn().getResponse().getStatus();
        assertThat(sinToken)
                .as("la consola de H2 no puede ser una ruta pública")
                .isEqualTo(401);

        int conToken = mockMvc.perform(get(RUTA_CONSOLA_H2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ClaveRsaDePrueba.token().compactar()))
                .andReturn().getResponse().getStatus();
        assertThat(conToken)
                .as("la consola no existe en la aplicación: no hay nada que servir")
                .isEqualTo(404);
    }
}
