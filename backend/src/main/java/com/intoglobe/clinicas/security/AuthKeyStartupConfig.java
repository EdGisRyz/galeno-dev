package com.intoglobe.clinicas.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Comprobación de arranque de la clave pública del AuthService (R-SEG-09).
 *
 * <p>El contexto se niega a levantarse si {@code auth.public.key} falta, está en blanco,
 * no es Base64 o no es una clave pública RSA en DER (SPKI). Antes de esta comprobación
 * el default era la cadena vacía y el fallo aparecía tarde y confuso, en la primera
 * petición autenticada.</p>
 *
 * <p><strong>La comprobación no se condiciona a ningún perfil.</strong> Estuvo gated con
 * {@code @Profile("!test")} y eso era un bypass real: bastaba arrancar con
 * {@code --spring.profiles.active=test} (o {@code SPRING_PROFILES_ACTIVE=test}) para
 * levantar la aplicación sin clave pública, con la API abierta por {@code permitAll()} y
 * cualquier petición con credenciales respondiendo {@code 401}. Ahora el chequeo corre en
 * todo arranque, incluido el perfil {@code test}: las pruebas que solo necesitan arrancar
 * el contexto reciben una clave pública de prueba, pública y no secreta, desde
 * {@code application-test.properties}; las que validan firmas la sobrescriben con
 * {@code @DynamicPropertySource} y un par generado en memoria.</p>
 *
 * <p><strong>Supuesto de trabajo declarado (§5.6):</strong> en producción
 * {@code auth.issuer} y {@code auth.audience} deben estar definidos; en desarrollo pueden
 * quedar en blanco y entonces esa validación se omite. Este chequeo solo cubre la clave
 * pública, que es la que no puede faltar para validar una firma.</p>
 */
@Configuration
public class AuthKeyStartupConfig {

    /**
     * Fuerza la lectura de la clave pública durante el arranque.
     *
     * @param entorno entorno de la aplicación, del que se lee {@code auth.public.key}
     * @throws IllegalStateException si la clave pública falta o es inválida
     */
    public AuthKeyStartupConfig(Environment entorno) {
        AuthPublicKey.desdeEntorno(entorno);
    }
}
