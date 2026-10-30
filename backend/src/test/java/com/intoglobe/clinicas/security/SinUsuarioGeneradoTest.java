package com.intoglobe.clinicas.security;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import com.intoglobe.clinicas.BackendApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pruebas del usuario en memoria que Spring Boot genera cuando no encuentra un
 * {@code UserDetailsService} (R-SEG-09 y ADR-014).
 *
 * <p>Ese usuario no autentica nada útil en esta API —el token lo emite el AuthService
 * externo y el backend solo valida su firma—, pero imprime en cada arranque una
 * contraseña generada en el log. Un log con credenciales es exactamente lo que la
 * auditoría de seguridad prohíbe, así que la autoconfiguración se excluye en
 * {@link BackendApplication} y esta prueba lo verifica desde el arranque real: sin
 * {@code UserDetailsService} en el contexto y sin la línea
 * {@code Using generated security password} en la salida.</p>
 *
 * <p><strong>La sonda arranca con base de datos propia.</strong> Arrancar la aplicación
 * completa con el perfil {@code test} y cerrarla dejaba inutilizable la base compartida
 * {@code jdbc:h2:mem:galeno_test}: tras el cierre, toda prueba posterior que la usara
 * fallaba con {@code [90098-240] The database has been closed}. El defecto era invisible
 * porque Surefire ejecuta alfabéticamente y {@code repository} va antes que
 * {@code security}; con la sonda primero aparecían 40 errores. Por eso el argumento
 * {@code spring.datasource.url} apunta a una base efímera propia de esta ejecución.</p>
 *
 * <p>Dos guardas impiden reintroducir el defecto: una estructural, que fija que la URL
 * efectiva de la sonda no es la compartida, y una de comportamiento, que comprueba que la
 * base compartida sigue respondiendo después de cerrar la sonda.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class SinUsuarioGeneradoTest {

    /**
     * URL de la base compartida por la suite, idéntica a la de
     * {@code application-test.properties}: así la guarda, si alguna vez tiene que crear la
     * base, la crea con el mismo dialecto y la misma espera de cierre que usa el resto de la
     * suite.
     */
    private static final String URL_BASE_COMPARTIDA =
            "jdbc:h2:mem:galeno_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** Usuario con el que la suite abre la base compartida. */
    private static final String USUARIO_BASE_COMPARTIDA = "sa";

    /** Fragmento que identifica la base compartida dentro de una URL JDBC. */
    private static final String NOMBRE_BASE_COMPARTIDA = "galeno_test";

    /**
     * Base efímera y única de esta ejecución: mismo dialecto y misma espera de cierre que
     * la del perfil {@code test}, pero aislada para que cerrarla no afecte a nadie más.
     */
    private static final String URL_BASE_PROPIA =
            "jdbc:h2:mem:sinusuario_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    @DisplayName("La exclusión vive en la clase de arranque versionada, no en una propiedad local")
    void laExclusionViveEnLaClaseDeArranqueVersionada() {
        // El arranque real de abajo corre con el perfil test, que además excluye la
        // autoconfiguración por propiedad para que las rebanadas no ensucien el log. Esta
        // aserción fija que la exclusión también está en el lugar versionado que gobierna la
        // aplicación real: sin ella, un `application.properties` ausente o distinto en otra
        // máquina volvería a publicar la contraseña generada.
        SpringBootApplication arranque = BackendApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(arranque).isNotNull();
        assertThat(arranque.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    @DisplayName("El contexto arrancado no tiene UserDetailsService y no registra una contraseña generada")
    void sinUserDetailsServiceYsinPasswordGenerada(CapturedOutput salida) {
        try (ConfigurableApplicationContext aplicacion = arrancarAplicacion(URL_BASE_PROPIA)) {

            assertThat(aplicacion.getBeanNamesForType(UserDetailsService.class))
                    .as("el backend no tiene directorio de credenciales: el emisor es externo (D2)")
                    .isEmpty();
            assertThat(aplicacion.getBeansOfType(InMemoryUserDetailsManager.class))
                    .as("no puede existir el usuario en memoria que genera Spring Boot")
                    .isEmpty();
        }

        assertThat(salida.getAll())
                .as("el arranque no puede publicar credenciales en el log (ADR-014)")
                .doesNotContain("Using generated security password")
                .doesNotContain("inMemoryUserDetailsManager");
    }

    @Test
    @DisplayName("La sonda arranca contra su propia base de datos y no contra la de la suite")
    void laSondaUsaUnaBaseDeDatosPropia() {
        // Esta es la guarda que detecta el defecto: si alguien quita el argumento
        // spring.datasource.url, la sonda vuelve a arrancar contra jdbc:h2:mem:galeno_test y
        // el cierre de su contexto deja la base compartida inutilizable para las pruebas de
        // repositorio que corren después.
        try (ConfigurableApplicationContext aplicacion = arrancarAplicacion(URL_BASE_PROPIA)) {
            String urlEfectiva = aplicacion.getEnvironment().getProperty("spring.datasource.url");

            assertThat(urlEfectiva)
                    .as("la sonda no puede usar la base de datos compartida de la suite")
                    .isEqualTo(URL_BASE_PROPIA)
                    .doesNotContain(NOMBRE_BASE_COMPARTIDA);
        }
    }

    @Test
    @DisplayName("La sonda no deja inutilizable la base de datos compartida de la suite")
    void laSondaNoDejaInutilizableLaBaseDeDatosCompartida() throws SQLException {
        // Guarda de comportamiento del mismo defecto: después de cerrar la sonda, la base
        // compartida debe seguir aceptando una conexión y respondiendo SELECT 1. Con la sonda
        // aislada pasa trivialmente; su valor es volver imposible el defecto sin que algo se
        // ponga en rojo, y documentar en el código por qué la sonda no usa la base compartida.
        assertThat(laBaseCompartidaResponde())
                .as("la base compartida debe estar usable antes de la sonda")
                .isTrue();

        try (ConfigurableApplicationContext aplicacion = arrancarAplicacion(URL_BASE_PROPIA)) {
            assertThat(aplicacion).isNotNull();
        }

        assertThatCode(SinUsuarioGeneradoTest::laBaseCompartidaResponde)
                .as("cerrar la sonda no puede cerrar la base de datos de la suite")
                .doesNotThrowAnyException();
        assertThat(laBaseCompartidaResponde())
                .as("después de cerrar la sonda la base compartida sigue respondiendo SELECT 1")
                .isTrue();
    }

    /**
     * Comprueba que la base compartida de la suite acepta una conexión y ejecuta una
     * consulta trivial.
     *
     * @return {@code true} si {@code SELECT 1} devuelve una fila
     * @throws SQLException si la base no acepta la conexión o la consulta
     */
    private static boolean laBaseCompartidaResponde() throws SQLException {
        try (Connection conexion = DriverManager.getConnection(URL_BASE_COMPARTIDA, USUARIO_BASE_COMPARTIDA, "");
                Statement sentencia = conexion.createStatement();
                ResultSet filas = sentencia.executeQuery("SELECT 1")) {
            return filas.next();
        }
    }

    /**
     * Arranca la aplicación real con el perfil {@code test}, puerto efímero y la base de
     * datos indicada.
     *
     * <p>El argumento de línea de comandos tiene mayor precedencia que
     * {@code application-test.properties}, así que la URL de la base propia gana sobre la
     * compartida.</p>
     *
     * @param urlBaseDatos URL JDBC de la base que usará esta sonda
     * @return el contexto ya arrancado (el llamador lo cierra)
     */
    private static ConfigurableApplicationContext arrancarAplicacion(String urlBaseDatos) {
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + urlBaseDatos);
    }
}
