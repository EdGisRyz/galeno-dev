package com.intoglobe.clinicas.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de R-SEG-09: la documentación de la API existe <strong>solo</strong> con el perfil
 * de desarrollo activo, y cuando existe es pública.
 *
 * <p>Es la otra mitad de {@link ConfiguracionSeguraPorDefectoTest}. Aquel fija que sin perfil
 * de desarrollo las rutas de documentación responden {@code 404}; este arranca con el perfil
 * {@code dev} y comprueba que vuelven a responder {@code 200} <em>sin token</em>. Las dos
 * juntas demuestran lo que afirma el Javadoc de {@link SecurityConfig}: las reglas
 * {@code permitAll()} de documentación son inocuas cuando springdoc está apagado, porque no
 * abren nada que no exista.</p>
 *
 * <p>El perfil se activa por nombre, no repitiendo las propiedades en la prueba: así la
 * prueba falla si alguien apaga springdoc en {@code dev} o si el archivo del perfil
 * desaparece, que es justo el contrato que se quiere fijar.</p>
 *
 * <p>Base de datos propia de la clase, mismo dialecto que el perfil {@code test}, para no
 * interferir con la base compartida de la suite.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:docs_en_dev;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DocumentacionHabilitadaEnDesarrolloTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment entorno;

    /**
     * Publica la clave pública de prueba: el fail-fast de arranque corre en todos los
     * perfiles (ADR-009), {@code dev} incluido.
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
    @DisplayName("Con el perfil dev activo el perfil vuelve a encender springdoc")
    void elPerfilDevVuelveAEncenderSpringdoc() {
        assertThat(entorno.getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
        assertThat(entorno.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("Con el perfil dev el documento OpenAPI responde 200 sin token")
    void elDocumentoOpenApiResponde200SinToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"openapi\"")));
    }

    @Test
    @DisplayName("Con el perfil dev la interfaz de Swagger responde 200 sin token")
    void laInterfazDeSwaggerResponde200SinToken() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
