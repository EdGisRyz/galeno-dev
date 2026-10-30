package com.intoglobe.clinicas.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Carga la clave pública RSA con la que se validan los tokens del AuthService de
 * InToGlobe (R8.2: el backend solo valida, nunca firma).
 *
 * <p><strong>Formato fijado:</strong> {@code AUTH_PUBLIC_KEY} es el valor
 * <em>Base64 del DER SubjectPublicKeyInfo (SPKI)</em> de una clave RSA, en una sola
 * línea (392 caracteres para RSA 2048). No es PEM, no es PKCS#1 y no es OpenSSH: por eso
 * se decodifica con {@link X509EncodedKeySpec} y no se acepta ningún encabezado
 * {@code -----BEGIN ...}.</p>
 *
 * <p>Si el decodificador cambia de fuente —por ejemplo si el AuthService publica un
 * JWKS—, se reemplaza esta clase y no la validación ni el mapeo de claims (supuesto de
 * trabajo de §5.6).</p>
 */
public final class AuthPublicKey {

    /**
     * Mensaje único de configuración inválida: nombra la propiedad y la variable de
     * entorno para que el arranque fallido diga exactamente qué falta.
     */
    static final String MENSAJE_CLAVE_INVALIDA =
            "Configuración de seguridad inválida: se requiere el valor Base64 DER (SPKI) de la clave pública "
            + "RSA del AuthService en la propiedad auth.public.key (variable de entorno AUTH_PUBLIC_KEY)";

    private static final String PROPIEDAD = "auth.public.key";
    private static final String ALGORITMO = "RSA";

    /** Propiedad con la clave pública en Base64 DER (SPKI). */
    static final String PROPIEDAD_CLAVE = PROPIEDAD;

    /** Propiedad opcional con el emisor ({@code iss}) esperado. */
    static final String PROPIEDAD_ISSUER = "auth.issuer";

    /** Propiedad opcional con la audiencia ({@code aud}) esperada. */
    static final String PROPIEDAD_AUDIENCE = "auth.audience";

    private AuthPublicKey() {
    }

    /**
     * Lee una propiedad de configuración tratando como ausente cualquier valor que no se
     * pueda resolver.
     *
     * @param entorno   entorno de la aplicación
     * @param propiedad nombre de la propiedad
     * @return el valor configurado o {@code null} si falta o tiene un placeholder sin resolver
     */
    static String valorConfigurado(Environment entorno, String propiedad) {
        try {
            return entorno.getProperty(propiedad);
        } catch (IllegalArgumentException excepcion) {
            return null;
        }
    }

    /**
     * Lee {@code auth.public.key} del entorno y la convierte en la clave pública RSA.
     *
     * @param entorno entorno de la aplicación
     * @return clave pública RSA lista para verificar firmas
     * @throws IllegalStateException si la propiedad falta, está en blanco o no es una
     *                               clave RSA en Base64 DER (SPKI)
     */
    public static RSAPublicKey desdeEntorno(Environment entorno) {
        return desdeBase64(valorConfigurado(entorno, PROPIEDAD));
    }

    /**
     * Convierte el valor Base64 DER (SPKI) en la clave pública RSA.
     *
     * @param valorBase64 Base64 del DER SubjectPublicKeyInfo, en una sola línea
     * @return clave pública RSA
     * @throws IllegalStateException si el valor falta, está en blanco, no es Base64 o no
     *                               corresponde a una clave pública RSA
     */
    public static RSAPublicKey desdeBase64(String valorBase64) {
        if (!StringUtils.hasText(valorBase64)) {
            throw new IllegalStateException(MENSAJE_CLAVE_INVALIDA);
        }
        try {
            byte[] der = Base64.getDecoder().decode(valorBase64.trim());
            PublicKey clave = KeyFactory.getInstance(ALGORITMO)
                    .generatePublic(new X509EncodedKeySpec(der));
            if (clave instanceof RSAPublicKey claveRsa) {
                return claveRsa;
            }
            throw new IllegalStateException(MENSAJE_CLAVE_INVALIDA);
        } catch (IllegalArgumentException | GeneralSecurityException excepcion) {
            throw new IllegalStateException(MENSAJE_CLAVE_INVALIDA, excepcion);
        }
    }
}
