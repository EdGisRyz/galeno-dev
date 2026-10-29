package com.intoglobe.clinicas.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas estructurales de R-SEG-09: la consola de H2 no viaja en el artefacto de producción
 * y el repositorio no puede versionar material de clave.
 *
 * <p>Son dos hechos de <em>build y repositorio</em>, no de comportamiento en ejecución, así
 * que no se pueden observar con una petición HTTP:</p>
 * <ul>
 *   <li>{@code spring-boot-h2console} estaba en el alcance por defecto, es decir se
 *       empaquetaba en el artefacto. Pasa a {@code test}: la consola deja de existir en
 *       producción por ausencia de la dependencia, no por una bandera que alguien pueda
 *       encender.</li>
 *   <li>{@code .gitignore} no cubría {@code *.pem} ni {@code *.key}, y esa era la razón por
 *       la que la guía de claves obligaba a guardar el par RSA fuera del repositorio. Ahora
 *       los patrones están, y ninguna excepción los vuelve a incluir.</li>
 * </ul>
 *
 * <p>El valor de estas pruebas es que el hecho que protegen es invisible: una dependencia
 * que vuelve al alcance por defecto o una línea de {@code .gitignore} que se pierde en un
 * merge no rompen ningún test de comportamiento. Las dos se verifican leyendo el archivo
 * real del repositorio, con la ruta resuelta desde el directorio de trabajo, y sin
 * depender de git: nunca leen historial ni un commit concreto.</p>
 */
class HigieneDeDependenciasYRepositorioTest {

    private static final String ARTEFACTO_CONSOLA_H2 = "spring-boot-h2console";

    private static final List<String> PATRONES_DE_CLAVE = List.of("*.pem", "*.key", "*.p12", "*.jks");

    @Test
    @DisplayName("spring-boot-h2console queda en alcance test: no se empaqueta en el artefacto")
    void laConsolaDeH2QuedaFueraDelArtefactoDeProduccion() throws Exception {
        String alcance = alcanceDeDependencia(raizDelRepositorio().resolve("backend/pom.xml"), ARTEFACTO_CONSOLA_H2);

        assertThat(alcance)
                .as("%s tiene que existir en el pom (se mueve, no se borra) y declararse con alcance test",
                        ARTEFACTO_CONSOLA_H2)
                .isEqualTo("test");
    }

    @Test
    @DisplayName("Las dependencias de producción que sostienen la seguridad siguen en el artefacto")
    void lasDependenciasDeProduccionSiguenEnElArtefacto() throws Exception {
        // Control del cambio anterior: mover la consola no puede llevarse por delante lo que
        // sí tiene que viajar. Si alguien "limpia" el pom, esto falla.
        Path pom = raizDelRepositorio().resolve("backend/pom.xml");

        assertThat(alcanceDeDependencia(pom, "spring-boot-starter-security"))
                .as("la cadena de filtros es una dependencia de producción")
                .isNull();
        assertThat(alcanceDeDependencia(pom, "spring-boot-starter-webmvc")).isNull();
    }

    @Test
    @DisplayName(".gitignore cubre claves y archivos de entorno, y ninguna excepción los reabre")
    void elGitignoreCubreClavesYArchivosDeEntorno() throws IOException {
        List<String> lineas = lineasDelGitignore();

        assertThat(lineas)
                .as("sin estos patrones la clave privada puede terminar versionada (R-SEG-09)")
                .containsAll(PATRONES_DE_CLAVE)
                .contains(".env*");
        assertThat(lineas)
                .as("la plantilla sin secretos sigue versionándose")
                .contains("!.env.example");
        assertThat(lineas)
                .as("ninguna excepción puede volver a incluir material de clave")
                .noneMatch(linea -> linea.startsWith("!") && PATRONES_DE_CLAVE.stream().anyMatch(linea::equals));
    }

    /**
     * Lee las líneas significativas (no vacías y no comentarios) de {@code .gitignore}.
     *
     * @return líneas recortadas del archivo real del repositorio
     * @throws IOException si el archivo no puede leerse
     */
    private static List<String> lineasDelGitignore() throws IOException {
        return Files.readAllLines(raizDelRepositorio().resolve(".gitignore")).stream()
                .map(String::trim)
                .filter(linea -> !linea.isEmpty() && !linea.startsWith("#"))
                .toList();
    }

    /**
     * Devuelve el alcance declarado de una dependencia del pom.
     *
     * @param pom        ruta al {@code pom.xml} real
     * @param artefacto  {@code artifactId} buscado
     * @return el texto de {@code <scope>}, o {@code null} si la dependencia no declara
     *         alcance (es decir, {@code compile}) o no existe
     * @throws ParserConfigurationException si el parser XML no se puede configurar
     * @throws SAXException                 si el pom no es XML válido
     * @throws IOException                  si el archivo no puede leerse
     */
    private static String alcanceDeDependencia(Path pom, String artefacto)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrica.setExpandEntityReferences(false);
        Document documento = fabrica.newDocumentBuilder().parse(pom.toFile());

        NodeList dependencias = documento.getElementsByTagName("dependency");
        for (int i = 0; i < dependencias.getLength(); i++) {
            Element dependencia = (Element) dependencias.item(i);
            if (!artefacto.equals(textoDe(dependencia, "artifactId"))) {
                continue;
            }
            String alcance = textoDe(dependencia, "scope");
            return alcance == null ? null : alcance.trim();
        }
        return null;
    }

    /**
     * Texto del primer elemento hijo con el nombre indicado.
     *
     * @param padre elemento contenedor
     * @param nombre nombre del elemento hijo
     * @return el texto recortado o {@code null} si no existe
     */
    private static String textoDe(Element padre, String nombre) {
        NodeList hijos = padre.getElementsByTagName(nombre);
        if (hijos.getLength() == 0) {
            return null;
        }
        return hijos.item(0).getTextContent().trim();
    }

    /**
     * Resuelve la raíz del repositorio subiendo desde el directorio de trabajo hasta
     * encontrar un directorio que contenga {@code .gitignore} y {@code backend/pom.xml}.
     *
     * @return la raíz del repositorio
     */
    private static Path raizDelRepositorio() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            if (Files.isRegularFile(actual.resolve(".gitignore"))
                    && Files.isRegularFile(actual.resolve("backend/pom.xml"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException(
                "No se encontró la raíz del repositorio desde " + Path.of("").toAbsolutePath());
    }
}
