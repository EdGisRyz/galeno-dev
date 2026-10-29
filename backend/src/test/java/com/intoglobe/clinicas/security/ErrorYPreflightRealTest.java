package com.intoglobe.clinicas.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de R-SEG-09 sobre un contenedor de servlets real: despacho de {@code ERROR},
 * {@code /error} dentro del contrato de error y preflight de CORS con la cadena cerrada.
 *
 * <p><strong>Por qué un contenedor real y no MockMvc.</strong> MockMvc no ejecuta el
 * despacho de error del contenedor: se limita a anotar el estado en la respuesta. El efecto
 * que T5 tiene que demostrar —que un {@code 404} del framework sobreviva a una cadena
 * deny-by-default y que {@code /error} no responda {@code 500}— solo aparece cuando el
 * contenedor reenvía la petición a {@code /error}, y ese reenvío vuelve a pasar por la
 * cadena de filtros de Spring Security. Por eso aquí se arranca la aplicación completa en
 * un puerto efímero y se la interroga con el cliente HTTP del JDK.</p>
 *
 * <p>La clase arranca con <strong>base de datos propia</strong>, igual que las demás
 * pruebas que levantan un contexto completo, para no interferir con la base compartida de
 * la suite. Ninguna prueba la cierra: el contexto lo hace al terminar la clase.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:error_y_preflight;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class ErrorYPreflightRealTest {

    private static final String RUTA_INEXISTENTE = "/api/v1/esta-ruta-no-existe";
    private static final String RUTA_ERROR = "/error";
    private static final String RUTA_PROTEGIDA = "/api/v1/organizaciones";

    private static final String ORIGEN_PERMITIDO = "http://localhost:5173";

    private static final String MENSAJE_SIN_RECURSO = "No se encontró el recurso solicitado";
    private static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";

    private final HttpClient cliente = HttpClient.newHttpClient();

    @LocalServerPort
    private int puerto;

    /**
     * Publica la clave pública de prueba y fija el origen de CORS que el preflight debe
     * aceptar.
     *
     * @param registro registro de propiedades dinámicas del contexto de prueba
     */
    @DynamicPropertySource
    static void inyectarClavePublicaDePrueba(DynamicPropertyRegistry registro) {
        registro.add("auth.public.key", () -> ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64);
        registro.add("auth.issuer", () -> ClaveRsaDePrueba.EMISOR);
        registro.add("auth.audience", () -> ClaveRsaDePrueba.AUDIENCIA);
        registro.add("app.cors.allowed-origins", () -> ORIGEN_PERMITIDO);
    }

    @Test
    @DisplayName("Una ruta inexistente sin token responde 401 en el contenedor real")
    void unaRutaInexistenteSinTokenResponde401() throws Exception {
        HttpResponse<String> respuesta = enviar("GET", RUTA_INEXISTENTE, null);

        assertThat(respuesta.statusCode()).isEqualTo(401);
        assertThat(respuesta.body())
                .contains("\"codigo\":401", "\"error\":\"Unauthorized\"")
                .contains(MENSAJE_CREDENCIALES_BEARER);
    }

    @Test
    @DisplayName("Una ruta inexistente con token responde 404 con el contrato de error, no 403 ni 500")
    void unaRutaInexistenteConTokenResponde404ConElContrato() throws Exception {
        // Este es el caso que exige el despacho de ERROR: la petición pasa la autorización,
        // el enrutado no encuentra controlador, el contenedor la reenvía a /error y ese
        // reenvío vuelve a entrar en la cadena de filtros. Sin permitir el despacho de ERROR,
        // el 404 se convertiría en 403.
        String token = ClaveRsaDePrueba.token().compactar();

        HttpResponse<String> respuesta = enviar("GET", RUTA_INEXISTENTE, token);

        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(respuesta.body())
                .contains("\"codigo\":404", "\"error\":\"Not Found\"")
                .contains(MENSAJE_SIN_RECURSO)
                .contains("\"ruta\":\"" + RUTA_INEXISTENTE + "\"");
        assertThat(respuesta.body())
                .as("el cuerpo del error no puede reexponer el token recibido (R-SEG-10)")
                .doesNotContain(token);
    }

    @Test
    @DisplayName("GET /error con token válido responde 404 con el contrato, no el 500 de hoy")
    void getErrorConTokenValidoResponde404ConElContrato() throws Exception {
        // /error no es un recurso de la API: cuando nadie reenvió un error, no hay nada que
        // representar. Antes de T5 esta petición llegaba al BasicErrorController sin
        // atributos de error y respondía 500.
        String token = ClaveRsaDePrueba.token().compactar();

        HttpResponse<String> respuesta = enviar("GET", RUTA_ERROR, token);

        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(respuesta.body())
                .contains("\"codigo\":404", "\"error\":\"Not Found\"")
                .contains(MENSAJE_SIN_RECURSO)
                .contains("\"ruta\":\"" + RUTA_ERROR + "\"");
    }

    @Test
    @DisplayName("GET /error sin token también responde 404 con el contrato: es un endpoint de error, no un recurso")
    void getErrorSinTokenResponde404ConElContrato() throws Exception {
        HttpResponse<String> respuesta = enviar("GET", RUTA_ERROR, null);

        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(respuesta.body()).contains("\"codigo\":404", "\"error\":\"Not Found\"");
        assertThat(respuesta.body())
                .as("el endpoint de error no puede publicar detalles internos ni credenciales")
                .doesNotContain("Exception")
                .doesNotContain("at com.intoglobe");
    }

    @Test
    @DisplayName("El cuerpo de error es JSON del contrato incluso si el cliente pide HTML")
    void elCuerpoDeErrorEsJsonAunqueElClientePidaHtml() throws Exception {
        // Este backend es una API y no tiene vistas HTML de error. Un navegador que pida
        // text/html recibe igual el contrato ErrorResponse, no el 406 de la negociación de
        // contenido ni una página de error del framework.
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(uri(RUTA_ERROR)))
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8")
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());

        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(respuesta.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .as("el contrato de error es JSON aunque el cliente negocie HTML")
                .hasValueSatisfying(tipo -> assertThat(tipo).contains("application/json"));
        assertThat(respuesta.body()).contains("\"codigo\":404");
    }

    @Test
    @DisplayName("El preflight de CORS sigue respondiendo sin token con la cadena deny-by-default")
    void elPreflightSigueRespondiendoSinToken() throws Exception {
        // El CorsFilter corre antes de la autorización, así que el preflight -que el
        // navegador manda sin cabecera Authorization- no puede quedar detrás del fallback.
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(uri(RUTA_PROTEGIDA)))
                .header(HttpHeaders.ORIGIN, ORIGEN_PERMITIDO)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .contains(ORIGEN_PERMITIDO);
        assertThat(respuesta.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .as("las peticiones viajan con credenciales (D3)")
                .contains("true");
    }

    /**
     * Ejecuta una petición contra el servidor arrancado.
     *
     * @param metodo verbo HTTP
     * @param ruta   ruta de la petición
     * @param token  token Bearer o {@code null} si la petición va sin credenciales
     * @return la respuesta completa del servidor
     * @throws Exception si el cliente HTTP no puede completar la petición
     */
    private HttpResponse<String> enviar(String metodo, String ruta, String token) throws Exception {
        HttpRequest.Builder constructor = HttpRequest.newBuilder(URI.create(uri(ruta)));
        if (token != null) {
            constructor.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return cliente.send(constructor.method(metodo, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Compone la URL absoluta del servidor de prueba.
     *
     * @param ruta ruta relativa de la petición
     * @return URL absoluta contra el puerto efímero
     */
    private String uri(String ruta) {
        return "http://localhost:" + puerto + ruta;
    }
}
