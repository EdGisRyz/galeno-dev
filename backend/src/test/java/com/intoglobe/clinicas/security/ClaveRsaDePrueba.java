package com.intoglobe.clinicas.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.json.JsonMapper;

/**
 * Par de claves RSA y fábrica de tokens para las pruebas de seguridad.
 *
 * <p>El par se genera en memoria en cada ejecución de la suite: nunca se
 * versiona una clave privada ni un archivo de clave fijo. La clave pública se
 * expone ya codificada en Base64 DER (SPKI), que es exactamente el formato que
 * consume {@code auth.public.key}.</p>
 */
public final class ClaveRsaDePrueba {

    public static final String EMISOR = "https://auth.intoglobe.test";
    public static final String AUDIENCIA = "galeno-api";
    static final String ID_USUARIO = "9f0d2c4a-1b2e-4c7d-9a11-5b7f3e2d1a01";
    static final String EMAIL = "ana.torres@clinicasanrafael.test";
    static final String TENANT_ID = "3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90";
    static final String MEDICO_ID = "1c7d9a11-4b2e-4c7d-9a11-77aa3e2d1a55";

    /** Par de claves del emisor simulado. */
    static final KeyPair PAR = generarPar();

    /** Otro par, para firmar tokens con una clave privada distinta a la pública configurada. */
    static final KeyPair OTRO_PAR = generarPar();

    /** Clave pública en Base64 DER (SPKI) en una sola línea: formato de {@code auth.public.key}. */
    public static final String CLAVE_PUBLICA_BASE64 = Base64.getEncoder().encodeToString(PAR.getPublic().getEncoded());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private ClaveRsaDePrueba() {
    }

    private static KeyPair generarPar() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (NoSuchAlgorithmException excepcion) {
            throw new IllegalStateException("La JVM de pruebas no soporta RSA", excepcion);
        }
    }

    /**
     * Inicia la construcción de un token con los claims del contrato vigentes.
     *
     * @return constructor fluido de token
     */
    public static Token token() {
        return new Token();
    }

    /**
     * Construye un JWS firmado a mano con {@link Signature}, sin pasar por el constructor
     * de jjwt, para poder emitir tokens con un {@code alg} distinto de RS256.
     *
     * <p>jjwt solo permite firmar con el algoritmo que su propio constructor elige; la
     * comprobación de que el backend no acepta otra cosa que RS256 necesita tokens
     * genuinamente firmados con RS384, RS512 o PS256, y esos solo se pueden producir
     * llamando a {@link Signature} directamente.</p>
     *
     * @param algoritmoJws algoritmo del JWS: {@code RS256}, {@code RS384}, {@code RS512},
     *                     {@code PS256}, {@code PS384}, {@code PS512} o {@code HS256}
     * @return token compacto JWS firmado con la clave privada del emisor simulado
     */
    static String tokenCrudo(String algoritmoJws) {
        Map<String, Object> cabecera = new LinkedHashMap<>();
        cabecera.put("alg", algoritmoJws);
        cabecera.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", EMISOR);
        claims.put("aud", AUDIENCIA);
        claims.put("sub", ID_USUARIO);
        claims.put("email", EMAIL);
        claims.put("role", "ADMIN");
        claims.put("tenantId", TENANT_ID);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().plus(Duration.ofMinutes(30)).getEpochSecond());

        String entrada = BASE64_URL.encodeToString(JSON.writeValueAsBytes(cabecera))
                + "." + BASE64_URL.encodeToString(JSON.writeValueAsBytes(claims));
        return entrada + "." + BASE64_URL.encodeToString(firmar(algoritmoJws, entrada));
    }

    private static byte[] firmar(String algoritmoJws, String entrada) {
        byte[] bytes = entrada.getBytes(StandardCharsets.US_ASCII);
        try {
            if ("HS256".equals(algoritmoJws)) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(PAR.getPublic().getEncoded(), "HmacSHA256"));
                return mac.doFinal(bytes);
            }
            Signature firma = Signature.getInstance(algoritmoJavaDe(algoritmoJws));
            PSSParameterSpec parametrosPss = parametrosPssDe(algoritmoJws);
            if (parametrosPss != null) {
                firma.setParameter(parametrosPss);
            }
            firma.initSign(PAR.getPrivate());
            firma.update(bytes);
            return firma.sign();
        } catch (GeneralSecurityException excepcion) {
            throw new IllegalStateException("La JVM de pruebas no pudo firmar con " + algoritmoJws, excepcion);
        }
    }

    private static String algoritmoJavaDe(String algoritmoJws) {
        return switch (algoritmoJws) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "PS256", "PS384", "PS512" -> "RSASSA-PSS";
            default -> throw new IllegalArgumentException("Algoritmo de firma no soportado en pruebas: " + algoritmoJws);
        };
    }

    private static PSSParameterSpec parametrosPssDe(String algoritmoJws) {
        return switch (algoritmoJws) {
            case "PS256" -> new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
            case "PS384" -> new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1);
            case "PS512" -> new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1);
            default -> null;
        };
    }

    /**
     * Constructor fluido de tokens firmados para las pruebas.
     */
    public static final class Token {

        private String identificador = ID_USUARIO;
        private String correo = EMAIL;
        private String rol = "ADMIN";
        private String inquilino = TENANT_ID;
        private String medico;
        private String emisor = EMISOR;
        private String audiencia = AUDIENCIA;
        private Instant expiracion = Instant.now().plus(Duration.ofMinutes(30));
        private boolean sinExpiracion;
        private PrivateKey clave = PAR.getPrivate();
        private boolean firmarConHmacDeClavePublica;

        private Token() {
        }

        Token conIdentificador(String valor) {
            this.identificador = valor;
            return this;
        }

        Token conRol(String valor) {
            this.rol = valor;
            return this;
        }

        Token conInquilino(String valor) {
            this.inquilino = valor;
            return this;
        }

        Token sinInquilino() {
            this.inquilino = null;
            return this;
        }

        Token conMedico(String valor) {
            this.medico = valor;
            return this;
        }

        Token conEmisor(String valor) {
            this.emisor = valor;
            return this;
        }

        Token conAudiencia(String valor) {
            this.audiencia = valor;
            return this;
        }

        Token sinEmisorNiAudiencia() {
            this.emisor = null;
            this.audiencia = null;
            return this;
        }

        Token expirado() {
            this.expiracion = Instant.now().minus(Duration.ofMinutes(5));
            return this;
        }

        /**
         * Omite por completo el claim {@code exp}: un token así no caduca nunca, y el
         * backend lo rechaza porque contradice el TTL de 30 minutos (D7) y la ventana de
         * inactividad HIPAA.
         *
         * @return este constructor
         */
        Token sinExpiracion() {
            this.sinExpiracion = true;
            this.expiracion = null;
            return this;
        }

        Token firmadoConOtraClavePrivada() {
            this.clave = OTRO_PAR.getPrivate();
            return this;
        }

        /**
         * Firma el token con HS256 usando los bytes de la clave pública como secreto:
         * el ataque clásico de confusión de algoritmo que el filtro debe rechazar.
         *
         * @return este constructor
         */
        Token firmadoConHmacUsandoLaClavePublica() {
            this.firmarConHmacDeClavePublica = true;
            return this;
        }

        public String compactar() {
            JwtBuilder constructor = Jwts.builder()
                    .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(1))));
            if (!sinExpiracion) {
                constructor.expiration(Date.from(expiracion));
            }
            if (identificador != null) {
                constructor.subject(identificador);
            }
            if (correo != null) {
                constructor.claim("email", correo);
            }
            if (rol != null) {
                constructor.claim("role", rol);
            }
            if (inquilino != null) {
                constructor.claim("tenantId", inquilino);
            }
            if (medico != null) {
                constructor.claim("medicoId", medico);
            }
            if (emisor != null) {
                constructor.issuer(emisor);
            }
            if (audiencia != null) {
                constructor.audience().single(audiencia);
            }
            if (firmarConHmacDeClavePublica) {
                return constructor.signWith(Keys.hmacShaKeyFor(PAR.getPublic().getEncoded()), Jwts.SIG.HS256)
                        .compact();
            }
            return constructor.signWith(clave, Jwts.SIG.RS256).compact();
        }
    }
}
