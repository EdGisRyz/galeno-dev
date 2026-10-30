package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de R-SEG-09: el fallback de la cadena es <strong>deny-by-default</strong>.
 *
 * <p>Antes de T5 la cadena terminaba en {@code anyRequest().authenticated()}: cualquier ruta
 * que no estuviera en la matriz quedaba abierta a <em>cualquier</em> rol autenticado. Es el
 * agujero <em>deny-by-omission</em>: el día que alguien agregue un controlador y olvide su
 * regla, todos los tokens válidos lo alcanzan. Este archivo fija la conducta contraria con
 * una sonda real: un controlador montado en {@code /api/v1/sonda/no-declarada} que
 * <strong>no</strong> tiene ninguna regla en {@link SecurityConfig}.</p>
 *
 * <p>El par de afirmaciones que hace que esto no rompa el contrato HTTP es el siguiente, y
 * se prueba junto a propósito:</p>
 * <ul>
 *   <li>Una ruta <strong>con controlador</strong> y sin regla → {@code 403} para los tres
 *       roles y {@code 401} sin token, con el {@code ErrorResponse} de la API.</li>
 *   <li>Una ruta <strong>inexistente</strong> → {@code 401} sin token y el {@code 404}
 *       normal del framework con token, no {@code 403}. El {@code 404} es la respuesta
 *       honesta: no hay ningún recurso protegido que denegar.</li>
 * </ul>
 *
 * <p>La base de datos es propia de la clase, con el mismo dialecto que el perfil
 * {@code test}, para que ampliar el contexto no interfiera con la base compartida por la
 * suite; ninguna prueba la cierra.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:deny_by_default;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DenyByDefaultTest.ConfiguracionSonda.class)
class DenyByDefaultTest {

    /** Ruta con controlador real y sin ninguna regla en la cadena: el caso del agujero. */
    private static final String RUTA_SIN_REGLA = "/api/v1/sonda/no-declarada";

    /** Ruta que no existe en ningún controlador. */
    private static final String RUTA_INEXISTENTE = "/api/v1/esta-ruta-no-existe";

    /** Ruta declarada en la matriz, usada para probar un verbo que no lo está. */
    private static final String RUTA_DECLARADA = "/api/v1/organizaciones";

    private static final String MENSAJE_ACCESO_DENEGADO = "No tiene permisos para acceder a este recurso";
    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";
    private static final String CUERPO_DE_LA_SONDA = "la sonda se alcanzó";

    private static final UUID TENANT = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");

    @Autowired
    private MockMvc mockMvc;

    /**
     * Publica la clave pública de prueba: los tokens de esta clase los firma
     * {@link ClaveRsaDePrueba} con la mitad privada de un par generado en memoria.
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
    @DisplayName("Una ruta con controlador y sin regla responde 403 a los tres roles y no llega al controlador")
    void unaRutaSinReglaResponde403ParaLosTresRoles() throws Exception {
        for (String rol : List.of("ADMIN", "MEDICO", "PACIENTE")) {
            String cuerpo = mockMvc.perform(get(RUTA_SIN_REGLA)
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + ClaveRsaDePrueba.tokenPara(rol, TENANT)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.codigo").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO))
                    .andExpect(jsonPath("$.ruta").value(RUTA_SIN_REGLA))
                    .andExpect(jsonPath("$.marcaTemporal").exists())
                    .andReturn().getResponse().getContentAsString();

            assertThat(cuerpo)
                    .as("el rol %s no puede alcanzar un controlador que la matriz no declara", rol)
                    .doesNotContain(CUERPO_DE_LA_SONDA);
        }
    }

    @Test
    @DisplayName("Una ruta con controlador y sin regla responde 401 sin token")
    void unaRutaSinReglaResponde401SinToken() throws Exception {
        mockMvc.perform(get(RUTA_SIN_REGLA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(RUTA_SIN_REGLA));
    }

    @Test
    @DisplayName("Un método no declarado sobre una ruta existente falla cerrado con 403, no con el 405 del framework")
    void unMetodoNoDeclaradoSobreUnaRutaExistenteFallaCerrado() throws Exception {
        // La matriz declara métodos concretos. Un verbo que nadie declaró -PATCH sobre
        // /organizaciones- no cae en ninguna regla y llega al fallback: la ruta tiene
        // controlador, así que se cierra. Antes de T5 la petición pasaba y respondía el 405
        // del framework; el contrato de seguridad elegido es cerrar, no informar qué métodos
        // existen sobre una ruta.
        mockMvc.perform(patch(RUTA_DECLARADA)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + ClaveRsaDePrueba.tokenPara("ADMIN", TENANT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(403))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_ACCESO_DENEGADO));
    }

    @Test
    @DisplayName("Una ruta inexistente con token válido responde 404, no 403: no hay recurso que denegar")
    void unaRutaInexistenteConTokenResponde404() throws Exception {
        mockMvc.perform(get(RUTA_INEXISTENTE)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + ClaveRsaDePrueba.tokenPara("ADMIN", TENANT)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Una ruta inexistente sin token responde 401: el anónimo no distingue rutas existentes")
    void unaRutaInexistenteSinTokenResponde401() throws Exception {
        mockMvc.perform(get(RUTA_INEXISTENTE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.mensaje").value(MENSAJE_CREDENCIALES_BEARER))
                .andExpect(jsonPath("$.ruta").value(RUTA_INEXISTENTE));
    }

    /**
     * Sonda: un controlador real que la matriz no declara. Responde {@code 200} si la
     * cadena deja pasar la petición, que es exactamente lo que no puede ocurrir.
     */
    @TestConfiguration
    static class ConfiguracionSonda {

        @Bean
        ControladorSonda controladorSonda() {
            return new ControladorSonda();
        }
    }

    @RestController
    static class ControladorSonda {

        @GetMapping(RUTA_SIN_REGLA)
        String atender() {
            return CUERPO_DE_LA_SONDA;
        }
    }
}
