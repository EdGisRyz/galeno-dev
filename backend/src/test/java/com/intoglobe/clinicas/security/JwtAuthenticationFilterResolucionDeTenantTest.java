package com.intoglobe.clinicas.security;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la resolución del claim {@code tenantId} en {@link JwtAuthenticationFilter}
 * (S3, S11).
 *
 * <p>El emisor de la plataforma identifica la organización por su slug y el emisor de
 * desarrollo por su UUID. El filtro tiene que aceptar las dos formas sin cambiar nada del
 * camino que ya funcionaba:</p>
 * <ul>
 *   <li>un UUID se usa directo y no consulta la base;</li>
 *   <li>un texto se resuelve <strong>una sola vez</strong> contra
 *       {@code organizacion.slug};</li>
 *   <li>un texto que no resuelve se rechaza con {@code 403} antes de la cadena, sin revelar
 *       si el slug existe;</li>
 *   <li>un claim ausente o de un tipo imposible sigue siendo {@code 401}.</li>
 * </ul>
 *
 * <p>Se ejerce el filtro directamente, con petición y respuesta simuladas y un doble del
 * resolutor que cuenta invocaciones: ni base de datos ni contexto de Spring. Eso es
 * justamente lo que el resolutor separado permite afirmar, y por eso la cuenta de consultas
 * es exacta.</p>
 */
class JwtAuthenticationFilterResolucionDeTenantTest {

    private static final String RUTA = "/api/v1/medicos/1c7d9a11-4b2e-4c7d-9a11-77aa3e2d1a55";
    private static final String SLUG = "acme";
    private static final UUID TENANT = UUID.fromString(ClaveRsaDePrueba.TENANT_ID);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ResolutorDeTenant resolutor = mock(ResolutorDeTenant.class);

    /** Peticiones que llegaron a la cadena, es decir al controlador. */
    private final AtomicInteger llegadasAlControlador = new AtomicInteger();

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Un tenantId en UUID se usa directo y no consulta el resolutor")
    void unTenantIdEnUuidSeUsaDirectoYNoConsultaElResolutor() throws Exception {
        var respuesta = ejecutar(filtroCon(resolutor), tokenConInquilino(TENANT.toString()));

        assertThat(respuesta.getStatus())
                .as("el UUID es el camino que ya funcionaba: tiene que seguir autenticando")
                .isEqualTo(HttpStatus.OK.value());
        assertThat(usuarioAutenticado().tenantId()).isEqualTo(TENANT);
        assertThat(llegadasAlControlador).hasValue(1);
        verifyNoInteractions(resolutor);
    }

    @Test
    @DisplayName("Un slug que resuelve autentica igual que el UUID equivalente")
    void unSlugQueResuelveAutenticaIgualQueElUuidEquivalente() throws Exception {
        when(resolutor.porSlug(SLUG)).thenReturn(Optional.of(TENANT));

        var respuesta = ejecutar(filtroCon(resolutor), tokenConInquilino(SLUG));
        AuthenticatedUser porSlug = usuarioAutenticado();

        SecurityContextHolder.clearContext();

        ejecutar(filtroCon(resolutor), tokenConInquilino(TENANT.toString()));
        AuthenticatedUser porUuid = usuarioAutenticado();

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(porSlug)
                .as("el principal tiene que ser el mismo: mismo sub, mismo tenant, mismo rol")
                .isEqualTo(porUuid);
        assertThat(porSlug.tenantId()).isEqualTo(TENANT);
        assertThat(porSlug.id().toString()).isEqualTo(ClaveRsaDePrueba.ID_USUARIO);
        assertThat(porSlug.role()).isEqualTo("ADMIN");
        assertThat(llegadasAlControlador)
                .as("las dos peticiones llegan al controlador")
                .hasValue(2);
    }

    @Test
    @DisplayName("Un slug que no resuelve responde 403 y no llega al controlador")
    void unSlugQueNoResuelveResponde403YNoLlegaAlControlador() throws Exception {
        when(resolutor.porSlug("clinica-inexistente")).thenReturn(Optional.empty());

        var respuesta = ejecutar(filtroCon(resolutor), tokenConInquilino("clinica-inexistente"));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(respuesta.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":403")
                .contains("\"error\":\"Forbidden\"")
                .contains(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO)
                .contains("\"ruta\":\"" + RUTA + "\"")
                .doesNotContain("clinica-inexistente");
        assertThat(llegadasAlControlador)
                .as("el rechazo ocurre antes de la cadena: el controlador no se ejecuta")
                .hasValue(0);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("una petición rechazada no deja identidad en el contexto")
                .isNull();
        verify(resolutor).porSlug("clinica-inexistente");
    }

    @Test
    @DisplayName("El resolutor se consulta una sola vez por petición, y solo con el claim de texto")
    void elResolutorSeConsultaUnaSolaVezPorPeticion() throws Exception {
        when(resolutor.porSlug(SLUG)).thenReturn(Optional.of(TENANT));

        ejecutar(filtroCon(resolutor), tokenConInquilino(SLUG));
        verify(resolutor, times(1)).porSlug(SLUG);

        ejecutar(filtroCon(resolutor), tokenConInquilino(SLUG));
        verify(resolutor, times(2)).porSlug(SLUG);
        verifyNoMoreInteractions(resolutor);
    }

    @Test
    @DisplayName("Un tenantId en forma abreviada de UUID no se acepta como UUID: se trata como slug")
    void unTenantIdAbreviadoSeTrataComoSlug() throws Exception {
        // UUID.fromString aceptaría "1-1-1-1-1", pero el contrato del emisor es la forma
        // canónica 8-4-4-4-12. Lo que no es canónico se trata como slug y, sin resolución,
        // se rechaza cerrado.
        when(resolutor.porSlug("1-1-1-1-1")).thenReturn(Optional.empty());

        var respuesta = ejecutar(filtroCon(resolutor), tokenConInquilino("1-1-1-1-1"));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(resolutor).porSlug("1-1-1-1-1");
    }

    @Test
    @DisplayName("Sin resolutor disponible, un claim de texto se rechaza cerrado con 403")
    void sinResolutorDisponibleUnSlugSeRechazaCerrado() throws Exception {
        // El resolutor puede faltar en contextos que no levantan JPA (rebanadas MVC): en ese
        // caso el filtro no puede resolver el slug y falla cerrado en lugar de dejar pasar
        // una petición sin tenant verificado.
        var respuesta = ejecutar(filtroCon(null), tokenConInquilino(SLUG));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(respuesta.getContentAsString()).contains(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO);
        assertThat(llegadasAlControlador).hasValue(0);
    }

    @Test
    @DisplayName("Un token sin tenantId responde 401, como antes")
    void unTokenSinTenantIdResponde401() throws Exception {
        var respuesta = ejecutar(filtroCon(resolutor), ClaveRsaDePrueba.token().sinInquilino().compactar());

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(respuesta.getContentAsString()).contains(SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO);
        assertThat(llegadasAlControlador).hasValue(0);
        verifyNoInteractions(resolutor);
    }

    @Test
    @DisplayName("Un tenantId de un tipo imposible responde 401 y no consulta el resolutor")
    void unTenantIdDeTipoImposibleResponde401() throws Exception {
        var respuesta = ejecutar(filtroCon(resolutor),
                ClaveRsaDePrueba.token().conInquilinoDeTipoImposible(42).compactar());

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(respuesta.getContentAsString()).contains(SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO);
        assertThat(llegadasAlControlador).hasValue(0);
        verifyNoInteractions(resolutor);
    }

    @Test
    @DisplayName("Un claim inválido sigue respondiendo 401 aunque el tenant venga como slug sin resolver")
    void unClaimInvalidoSigueRespondiendo401AunqueElTenantNoResuelva() throws Exception {
        // El 403 solo puede ganarle al 401 cuando el token está completo y bien formado: un
        // medicoId fuera de contrato es un token inválido, y así se sigue respondiendo.
        when(resolutor.porSlug(SLUG)).thenReturn(Optional.empty());

        var respuesta = ejecutar(filtroCon(resolutor),
                ClaveRsaDePrueba.token().conInquilino(SLUG).conMedico("no-es-uuid").compactar());

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(respuesta.getContentAsString()).contains(SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO);
        assertThat(llegadasAlControlador).hasValue(0);
        verifyNoInteractions(resolutor);
    }

    @Test
    @DisplayName("Si el resolutor falla, la petición se rechaza con 403 en lugar de acabar en un 500")
    void siElResolutorFallaSeResponde403() throws Exception {
        // Un fallo de la base de datos no puede convertirse en un error de servidor en la
        // puerta de entrada: el filtro no autentica a nadie y responde el mismo acceso
        // denegado que un slug desconocido.
        when(resolutor.porSlug(SLUG)).thenThrow(new DataAccessResourceFailureException("la base no responde"));

        var respuesta = ejecutar(filtroCon(resolutor), tokenConInquilino(SLUG));

        assertThat(respuesta.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":403")
                .contains(SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO)
                .doesNotContain("la base no responde");
        assertThat(llegadasAlControlador)
                .as("el controlador no se invoca cuando el filtro no puede resolver el tenant")
                .hasValue(0);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private JwtAuthenticationFilter filtroCon(ResolutorDeTenant resolutorDeTenant) {
        return new JwtAuthenticationFilter(
                ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64,
                ClaveRsaDePrueba.EMISOR,
                ClaveRsaDePrueba.AUDIENCIA,
                JSON,
                resolutorDeTenant);
    }

    private static String tokenConInquilino(String inquilino) {
        return ClaveRsaDePrueba.token().conInquilino(inquilino).compactar();
    }

    private MockHttpServletResponse ejecutar(JwtAuthenticationFilter filtro, String token) throws Exception {
        var peticion = new MockHttpServletRequest("GET", RUTA);
        peticion.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        var respuesta = new MockHttpServletResponse();
        FilterChain cadena = (solicitud, salida) -> llegadasAlControlador.incrementAndGet();

        filtro.doFilter(peticion, respuesta, cadena);

        return respuesta;
    }

    private static AuthenticatedUser usuarioAutenticado() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        assertThat(autenticacion).isNotNull();
        assertThat(autenticacion.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        return (AuthenticatedUser) autenticacion.getPrincipal();
    }
}
