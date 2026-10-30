package com.intoglobe.clinicas.security;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import com.intoglobe.clinicas.BackendApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica el arranque fail-fast de la clave pública: el contexto no puede levantarse
 * sin una clave pública RSA válida en formato Base64 DER (SPKI).
 *
 * <p>Se usa un contexto mínimo ({@link ApplicationContextRunner}) para probar las
 * variantes de valor inválido sin pagar un arranque completo. Además hay dos pruebas que
 * arrancan la aplicación real (sin servidor web, puerto efímero) con el perfil
 * {@code test} activo: una comprueba que la clave en blanco sigue abortando el arranque
 * aunque el perfil {@code test} esté activo, y la otra que el valor no secreto de
 * {@code application-test.properties} prevalece sobre el placeholder
 * {@code ${AUTH_PUBLIC_KEY}} de {@code application.properties} y deja arrancar.</p>
 */
class AuthPublicKeyStartupTest {

    private static final String MENSAJE_BASE64_DER_SPKI = "Base64 DER (SPKI)";

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(AuthKeyStartupConfig.class);

    @Test
    @DisplayName("Sin la propiedad auth.public.key el contexto falla al arrancar")
    void sinPropiedadElContextoNoArranca() {
        contexto.run(aplicacion -> esperarArranqueFallido(aplicacion));
    }

    @Test
    @DisplayName("Con auth.public.key en blanco el contexto falla al arrancar")
    void conPropiedadEnBlancoElContextoNoArranca() {
        contexto.withPropertyValues("auth.public.key=   ")
                .run(aplicacion -> esperarArranqueFallido(aplicacion));
    }

    @Test
    @DisplayName("Con un valor que no es Base64 el contexto falla al arrancar")
    void conValorQueNoEsBase64ElContextoNoArranca() {
        contexto.withPropertyValues("auth.public.key=esto-no-es-base64-der")
                .run(aplicacion -> esperarArranqueFallido(aplicacion));
    }

    @Test
    @DisplayName("Con la clave pública de otro algoritmo el contexto falla al arrancar")
    void conClaveQueNoEsRsaElContextoNoArranca() {
        contexto.withPropertyValues("auth.public.key=" + clavePublicaEcEnBase64())
                .run(aplicacion -> esperarArranqueFallido(aplicacion));
    }

    @Test
    @DisplayName("Con una clave pública RSA válida el contexto arranca")
    void conClaveRsaValidaElContextoArranca() {
        contexto.withPropertyValues("auth.public.key=" + ClaveRsaDePrueba.CLAVE_PUBLICA_BASE64)
                .run(aplicacion -> assertThat(aplicacion).hasNotFailed());
    }

    @Test
    @DisplayName("Con el perfil test activo y auth.public.key en blanco la aplicación real NO arranca")
    void conPerfilTestYClaveEnBlancoElArranqueFalla() {
        assertThatThrownBy(() -> arrancarAplicacion("--auth.public.key="))
                .as("el perfil test no puede desactivar el fail-fast de la clave pública")
                .hasStackTraceContaining(IllegalStateException.class.getName())
                .hasStackTraceContaining("auth.public.key")
                .hasStackTraceContaining("AUTH_PUBLIC_KEY")
                .hasStackTraceContaining(MENSAJE_BASE64_DER_SPKI);
    }

    @Test
    @DisplayName("Con el perfil test activo la clave de application-test.properties prevalece y la aplicación arranca")
    void conPerfilTestLaClaveDelPerfilPrevaleceYElContextoArranca() {
        try (ConfigurableApplicationContext aplicacion = arrancarAplicacion()) {
            assertThat(AuthPublicKey.desdeEntorno(aplicacion.getEnvironment()).getModulus().bitLength())
                    .as("la clave pública del perfil test es una RSA 2048 utilizable")
                    .isEqualTo(2048);
        }
    }

    /**
     * Arranca la aplicación real con el perfil {@code test}.
     *
     * <p>El puerto es efímero para no chocar con nada y, en el caso que falla, el servidor
     * web se cierra cuando el contexto aborta. Los argumentos se pasan por línea de
     * comandos, que es la fuente de mayor precedencia: así {@code --auth.public.key=}
     * gana sobre {@code application-test.properties}, que es exactamente el bypass que se
     * quiere descartar.</p>
     *
     * @param argumentos argumentos de línea de comandos adicionales
     * @return el contexto ya arrancado (el llamador lo cierra)
     */
    private static ConfigurableApplicationContext arrancarAplicacion(String... argumentos) {
        String[] todos = new String[argumentos.length + 1];
        todos[0] = "--server.port=0";
        System.arraycopy(argumentos, 0, todos, 1, argumentos.length);
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("test")
                .run(todos);
    }

    private static void esperarArranqueFallido(AssertableApplicationContext aplicacion) {
        assertThat(aplicacion).hasFailed();
        assertThat(aplicacion).getFailure()
                .hasStackTraceContaining(IllegalStateException.class.getName())
                .hasStackTraceContaining("AUTH_PUBLIC_KEY")
                .hasStackTraceContaining("auth.public.key")
                .hasStackTraceContaining(MENSAJE_BASE64_DER_SPKI);
    }

    private static String clavePublicaEcEnBase64() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
            generador.initialize(256);
            return Base64.getEncoder().encodeToString(generador.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException excepcion) {
            throw new IllegalStateException("La JVM de pruebas no soporta EC", excepcion);
        }
    }
}
