package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import com.intoglobe.clinicas.BackendApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del aviso de arranque cuando la documentación de la API queda habilitada fuera del
 * perfil {@code dev} (R-SEG-09, decisión de Eduardo).
 *
 * <p>La línea base versionada apaga springdoc ({@code application.yml}) y solo
 * {@code application-dev.properties} lo enciende. Si alguien lo enciende por línea de
 * comandos o con una variable de entorno fuera de {@code dev}, la documentación queda
 * accesible sin token: es una elección legítima para depurar, pero no puede pasar en
 * silencio. La decisión es <strong>avisar, no romper el arranque</strong>: un despliegue con
 * la bandera encendida tiene que seguir funcionando, y el aviso deja el rastro para
 * corregirlo.</p>
 *
 * <p>Se arranca la aplicación real tres veces (puerto efímero) porque lo que se prueba es el
 * arranque, no una unidad aislada: en el caso habilitado sin {@code dev} el contexto debe
 * quedar <em>activo</em> y aun así escribir el {@code WARN}. Cada arranque usa una
 * <strong>base de datos propia</strong> con nombre único y se cierra al terminar su prueba:
 * la base compartida por la suite no se toca ni se cierra.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentacionFueraDeDevAvisaAlArrancarTest {

    /**
     * Fragmento propio del aviso. Se usa para localizar su línea en la salida de arranque
     * sin depender del resto del texto del log de Spring.
     */
    private static final String MARCADOR_DEL_AVISO = "habilitada fuera del perfil";

    private static final String URL_BASE_PLANTILLA =
            "jdbc:h2:mem:aviso_docs_%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    @DisplayName("Con springdoc habilitado y sin el perfil dev el arranque publica un único WARN")
    void conDocumentacionHabilitadaFueraDeDevElArranqueAvisa(CapturedOutput salida) {
        try (ConfigurableApplicationContext aplicacion = arrancar(
                urlBasePropia("fuera_dev"),
                new String[]{"test"},
                "--springdoc.api-docs.enabled=true",
                "--springdoc.swagger-ui.enabled=true")) {

            assertThat(aplicacion.isActive())
                    .as("el aviso no puede impedir el arranque: la decisión es advertir, no fallar")
                    .isTrue();
        }

        List<String> avisos = lineasDelAviso(salida);

        assertThat(avisos)
                .as("con la documentación encendida fuera de dev tiene que quedar exactamente un aviso, "
                        + "aunque las dos propiedades estén en true")
                .hasSize(1);
        assertThat(avisos.get(0))
                .as("el aviso tiene que ser de nivel WARN")
                .contains("WARN");
        assertThat(avisos.get(0))
                .as("el aviso tiene que nombrar la propiedad que lo provoca")
                .contains("springdoc.api-docs.enabled")
                .contains("springdoc.swagger-ui.enabled");
        assertThat(avisos.get(0))
                .as("el aviso tiene que decir que está fuera de dev y cómo arrancar con dev")
                .contains("dev")
                .contains("--spring.profiles.active=dev")
                .contains("SPRING_PROFILES_ACTIVE=dev");
        assertThat(avisos.get(0))
                .as("el aviso tiene que explicar la consecuencia: la documentación queda sin autenticación")
                .contains("sin autenticación");
    }

    @Test
    @DisplayName("Con el perfil dev activo no hay aviso: es la forma prevista de encender la documentación")
    void conElPerfilDevActivoNoHayAviso(CapturedOutput salida) {
        try (ConfigurableApplicationContext aplicacion = arrancar(
                urlBasePropia("con_dev"),
                new String[]{"test", "dev"})) {

            assertThat(aplicacion.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                    .as("el perfil dev es el que enciende springdoc")
                    .isEqualTo("true");
        }

        assertThat(lineasDelAviso(salida))
                .as("encender la documentación desde el perfil dev es lo previsto: no se avisa")
                .isEmpty();
    }

    @Test
    @DisplayName("Con los defaults versionados (documentación apagada) no hay aviso")
    void conLosDefaultsVersionadosNoHayAviso(CapturedOutput salida) {
        try (ConfigurableApplicationContext aplicacion = arrancar(urlBasePropia("linea_base"), new String[]{"test"})) {
            assertThat(aplicacion.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                    .as("la línea base versionada mantiene springdoc apagado")
                    .isEqualTo("false");
            assertThat(aplicacion.getEnvironment().getProperty("springdoc.swagger-ui.enabled"))
                    .isEqualTo("false");
        }

        assertThat(lineasDelAviso(salida))
                .as("con la documentación apagada no hay nada que avisar")
                .isEmpty();
    }

    /**
     * Filtra de la salida de arranque las líneas del aviso.
     *
     * @param salida salida capturada del arranque
     * @return las líneas que contienen el marcador del aviso, en orden
     */
    private static List<String> lineasDelAviso(CapturedOutput salida) {
        return salida.getAll().lines()
                .filter(linea -> linea.contains(MARCADOR_DEL_AVISO))
                .toList();
    }

    /**
     * Compone una URL de base de datos efímera y única para una prueba.
     *
     * @param sufijo nombre lógico del caso, para poder reconocer la base en los logs
     * @return URL JDBC de una base propia de esta ejecución
     */
    private static String urlBasePropia(String sufijo) {
        return URL_BASE_PLANTILLA.formatted(sufijo + "_" + UUID.randomUUID());
    }

    /**
     * Arranca la aplicación real con puerto efímero, base de datos propia y los argumentos
     * indicados.
     *
     * <p>Los argumentos de línea de comandos tienen mayor precedencia que cualquier archivo
     * de configuración, así que la URL de la base propia gana sobre la compartida y las
     * banderas de springdoc ganan sobre la línea base versionada.</p>
     *
     * @param urlBaseDatos URL JDBC de la base de esta prueba
     * @param perfiles     perfiles activos
     * @param argumentos   argumentos adicionales de línea de comandos
     * @return el contexto arrancado (el llamador lo cierra)
     */
    private static ConfigurableApplicationContext arrancar(String urlBaseDatos, String[] perfiles,
            String... argumentos) {
        String[] todos = new String[argumentos.length + 2];
        todos[0] = "--server.port=0";
        todos[1] = "--spring.datasource.url=" + urlBaseDatos;
        System.arraycopy(argumentos, 0, todos, 2, argumentos.length);
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles(perfiles)
                .run(todos);
    }
}
