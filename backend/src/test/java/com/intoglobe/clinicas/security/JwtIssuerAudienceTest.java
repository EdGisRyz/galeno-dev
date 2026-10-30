package com.intoglobe.clinicas.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que {@code auth.issuer} y {@code auth.audience} son opcionales: cuando están
 * en blanco la validación de ese claim se omite; cuando tienen valor se exige.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtIssuerAudienceTest.ConfiguracionSonda.class)
class JwtIssuerAudienceTest {

    private static final String RUTA_SONDA = "/api/v1/sonda/issuer";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void inyectarSoloLaClavePublica(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
    }

    @Test
    @DisplayName("Sin auth.issuer ni auth.audience configurados el token sin iss/aud se acepta")
    void sinIssuerNiAudienceConfiguradosNoSeValidaEseClaim() throws Exception {
        String token = ClaveRsaDePrueba.token()
                .sinEmisorNiAudiencia()
                .compactar();

        mockMvc.perform(get(RUTA_SONDA).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.rol").value("ADMIN"));
    }

    @Test
    @DisplayName("Con auth.public.key presente una firma ajena sigue respondiendo 401")
    void firmaAjenaResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token()
                .sinEmisorNiAudiencia()
                .firmadoConOtraClavePrivada()
                .compactar();

        mockMvc.perform(get(RUTA_SONDA).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value("El token no es válido o está expirado"))
                .andExpect(jsonPath("$.ruta").value(RUTA_SONDA));
    }

    @TestConfiguration
    static class ConfiguracionSonda {

        @Bean
        ControladorSonda controladorSonda() {
            return new ControladorSonda();
        }
    }

    @RestController
    static class ControladorSonda {

        @GetMapping(RUTA_SONDA)
        Map<String, Object> estado() {
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            var autenticacion = SecurityContextHolder.getContext().getAuthentication();
            boolean autenticado = autenticacion != null
                    && autenticacion.getPrincipal() instanceof AuthenticatedUser;
            cuerpo.put("autenticado", autenticado);
            if (autenticado) {
                cuerpo.put("rol", ((AuthenticatedUser) autenticacion.getPrincipal()).role());
            }
            return cuerpo;
        }
    }
}
