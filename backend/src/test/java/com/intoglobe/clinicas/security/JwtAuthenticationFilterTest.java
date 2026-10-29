package com.intoglobe.clinicas.security;

import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de comportamiento de {@link JwtAuthenticationFilter} sobre el contexto real
 * de la aplicación.
 *
 * <p>La clave pública se inyecta con {@link DynamicPropertySource} a partir de un par RSA
 * generado en memoria durante la prueba: no se versiona ninguna clave. El endpoint de
 * sonda devuelve el estado del {@code SecurityContext} para poder afirmar tanto la
 * autenticación exitosa como la ausencia de autenticación cuando no hay credenciales.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtAuthenticationFilterTest.ConfiguracionSonda.class)
@ExtendWith(OutputCaptureExtension.class)
class JwtAuthenticationFilterTest {

    private static final String RUTA_SONDA = "/api/v1/sonda/autenticacion";
    private static final String MENSAJE_TOKEN_INVALIDO = "El token no es válido o está expirado";
    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void inyectarClavePublica(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
    }

    @Test
    @DisplayName("La clave pública del entorno se decodifica con el formato documentado: RSA 2048 en Base64 DER (SPKI)")
    void laClavePublicaConfiguradaSeDecodificaComoRsaDe2048Bits() {
        assertThatCode(() -> AuthPublicKey.desdeBase64(ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64))
                .doesNotThrowAnyException();

        RSAPublicKey clave = AuthPublicKey.desdeBase64(ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);

        assertThat(clave.getAlgorithm()).isEqualTo("RSA");
        assertThat(clave.getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    @DisplayName("Un token RS256 válido autentica al usuario con los claims del contrato")
    void tokenValidoAutenticaConLosClaimsDelContrato() throws Exception {
        String token = ClaveRsaDePrueba.token()
                .conMedico(ClaveRsaDePrueba.MEDICO_ID)
                .compactar();

        ResultActions respuesta = sonda("Bearer " + token);

        respuesta.andExpect(status().isOk())
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.tipoPrincipal").value(AuthenticatedUser.class.getSimpleName()))
                .andExpect(jsonPath("$.id").value(ClaveRsaDePrueba.ID_USUARIO))
                .andExpect(jsonPath("$.email").value(ClaveRsaDePrueba.EMAIL))
                .andExpect(jsonPath("$.rol").value("ADMIN"))
                .andExpect(jsonPath("$.tenantId").value(ClaveRsaDePrueba.TENANT_ID))
                .andExpect(jsonPath("$.medicoId").value(ClaveRsaDePrueba.MEDICO_ID))
                .andExpect(jsonPath("$.autoridades[0]").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("Sin claim medicoId el usuario autenticado lo deja nulo y el rol se refleja en las autoridades")
    void tokenSinMedicoIdAutenticaConMedicoNulo() throws Exception {
        String token = ClaveRsaDePrueba.token().conRol("MEDICO").compactar();

        sonda("Bearer " + token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.rol").value("MEDICO"))
                .andExpect(jsonPath("$.medicoId").doesNotExist())
                .andExpect(jsonPath("$.autoridades[0]").value("ROLE_MEDICO"));
    }

    @Test
    @DisplayName("Un token firmado con otra clave privada responde 401 con ErrorResponse")
    void firmaInvalidaResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().firmadoConOtraClavePrivada().compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token expirado responde 401 con ErrorResponse")
    void tokenExpiradoResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().expirado().compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token firmado con HS256 usando los bytes de la clave pública responde 401")
    void tokenFirmadoConHs256UsandoLaClavePublicaResponde401() throws Exception {
        // Este caso NO es la prueba del pin RS256: jjwt ya lo rechaza antes de la
        // comprobación explícita del header, porque la clave configurada es RSA y no un
        // secreto HMAC. El pin se prueba con RS384, RS512 y PS256 (abajo), que jjwt sí
        // verifica correctamente con esta misma clave RSA y que solo el chequeo explícito
        // de alg=RS256 rechaza.
        String token = ClaveRsaDePrueba.token().firmadoConHmacUsandoLaClavePublica().compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token RS512 firmado con la misma clave privada responde 401: el pin es RS256, no \"RSA\"")
    void tokenFirmadoConRs512Responde401() throws Exception {
        String token = ClaveRsaDePrueba.tokenCrudo("RS512");

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token RS384 firmado con la misma clave privada responde 401")
    void tokenFirmadoConRs384Responde401() throws Exception {
        String token = ClaveRsaDePrueba.tokenCrudo("RS384");

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token PS256 firmado con la misma clave privada responde 401")
    void tokenFirmadoConPs256Responde401() throws Exception {
        String token = ClaveRsaDePrueba.tokenCrudo("PS256");

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("El mismo token firmado en crudo con RS256 sí autentica: el rechazo es por el alg, no por la firma")
    void tokenCrudoFirmadoConRs256Autentica() throws Exception {
        String token = ClaveRsaDePrueba.tokenCrudo("RS256");

        sonda("Bearer " + token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autenticado").value(true))
                .andExpect(jsonPath("$.tenantId").value(ClaveRsaDePrueba.TENANT_ID));
    }

    @Test
    @DisplayName("Un token RS256 válido sin claim exp responde 401: sin exp no caduca nunca")
    void tokenSinExpiracionResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().sinExpiracion().compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con sub que no es UUID responde 401")
    void subQueNoEsUuidResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conIdentificador("no-es-uuid").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con tenantId que no es UUID responde 401")
    void tenantIdQueNoEsUuidResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conInquilino("no-es-uuid").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con medicoId que no es UUID responde 401")
    void medicoIdQueNoEsUuidResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conMedico("no-es-uuid").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("El registro del rechazo no contiene el token recibido (R-SEG-10)")
    void elRegistroDelRechazoNoContieneElToken(CapturedOutput salida) throws Exception {
        // El token es deliberadamente distintivo para que la aserción no pueda pasar por
        // casualidad, y está mal formado (no es un JWS de tres segmentos).
        String tokenMalformado = "token-malformado-que-no-debe-llegar-al-log-8f2c41d7";

        esperarNoAutorizado(sonda("Bearer " + tokenMalformado), MENSAJE_TOKEN_INVALIDO);

        assertThat(salida.getAll())
                .as("un token nunca puede aparecer en el log, ni entero ni en fragmentos largos (R-SEG-10)")
                .doesNotContain(tokenMalformado)
                .doesNotContain(tokenMalformado.substring(0, 24));
    }

    @Test
    @DisplayName("El 401 no reexpone el token recibido en el cuerpo de la respuesta")
    void elCuerpoDelNoAutorizadoNoRepiteElToken() throws Exception {
        String token = ClaveRsaDePrueba.token().firmadoConOtraClavePrivada().compactar();

        String cuerpo = sonda("Bearer " + token)
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(cuerpo).doesNotContain(token);
        assertThat(cuerpo).doesNotContain("Bearer");
    }

    @Test
    @DisplayName("Un token mal formado responde 401 con ErrorResponse")
    void tokenMalFormadoResponde401() throws Exception {
        esperarNoAutorizado(sonda("Bearer esto.no.es.un.jwt"), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Una cabecera que no es Bearer responde 401 con ErrorResponse")
    void cabeceraQueNoEsBearerResponde401() throws Exception {
        esperarNoAutorizado(sonda("Basic dXN1YXJpbzpjbGF2ZQ=="), MENSAJE_CREDENCIALES_BEARER);
    }

    @Test
    @DisplayName("Una cabecera Bearer sin token responde 401 con ErrorResponse")
    void cabeceraBearerVaciaResponde401() throws Exception {
        esperarNoAutorizado(sonda("Bearer   "), MENSAJE_CREDENCIALES_BEARER);
    }

    @Test
    @DisplayName("Un token sin el claim obligatorio tenantId responde 401")
    void tokenSinTenantIdResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().sinInquilino().compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token sin el claim obligatorio role responde 401")
    void tokenSinRolResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conRol(null).compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con un rol fuera del conjunto permitido responde 401")
    void rolFueraDelConjuntoPermitidoResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conRol("SUPERADMIN").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con iss inesperado responde 401")
    void emisorInesperadoResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conEmisor("https://otro-emisor.test").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Un token con aud inesperada responde 401")
    void audienciaInesperadaResponde401() throws Exception {
        String token = ClaveRsaDePrueba.token().conAudiencia("otra-api").compactar();

        esperarNoAutorizado(sonda("Bearer " + token), MENSAJE_TOKEN_INVALIDO);
    }

    @Test
    @DisplayName("Sin cabecera Authorization el filtro no autentica a nadie: la cadena responde 401")
    void sinCabeceraAuthorizationElFiltroNoAutentica() throws Exception {
        // El filtro deja pasar la petición sin principal; quien responde es el
        // AuthenticationEntryPoint de la cadena cerrada (R-SEG-03), con el mismo contrato
        // de error que usa el filtro para un token inválido.
        esperarNoAutorizado(sonda(null), SecurityErrorResponder.MENSAJE_CREDENCIALES_BEARER);
    }

    private ResultActions sonda(String cabecera) throws Exception {
        MockHttpServletRequestBuilder peticion = get(RUTA_SONDA);
        if (cabecera != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, cabecera);
        }
        return mockMvc.perform(peticion);
    }

    private ResultActions esperarNoAutorizado(ResultActions respuesta, String mensaje) throws Exception {
        return respuesta
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.codigo").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.mensaje").value(mensaje))
                .andExpect(jsonPath("$.ruta").value(RUTA_SONDA))
                .andExpect(jsonPath("$.marcaTemporal").exists());
    }

    /**
     * Sonda mínima que publica el estado del {@code SecurityContext} de la petición.
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

        @GetMapping(RUTA_SONDA)
        Map<String, Object> estado() {
            Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            boolean anonimo = autenticacion == null || autenticacion instanceof AnonymousAuthenticationToken;
            cuerpo.put("anonimo", anonimo);
            if (anonimo || !(autenticacion.getPrincipal() instanceof AuthenticatedUser usuario)) {
                cuerpo.put("autenticado", false);
                return cuerpo;
            }
            cuerpo.put("autenticado", true);
            cuerpo.put("tipoPrincipal", usuario.getClass().getSimpleName());
            cuerpo.put("id", usuario.id().toString());
            cuerpo.put("email", usuario.email());
            cuerpo.put("rol", usuario.role());
            cuerpo.put("tenantId", usuario.tenantId().toString());
            if (usuario.medicoId() != null) {
                cuerpo.put("medicoId", usuario.medicoId().toString());
            }
            cuerpo.put("autoridades", autenticacion.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList());
            return cuerpo;
        }
    }
}
