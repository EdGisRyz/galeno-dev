package com.intoglobe.clinicas.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.intoglobe.clinicas.dto.response.ErrorResponse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valida el token Bearer RS256 emitido por el AuthService de InToGlobe y publica la
 * identidad en el {@code SecurityContext} (R-SEG-01 y R-SEG-02).
 *
 * <p>Reglas de comportamiento:</p>
 * <ul>
 *   <li>Sin cabecera {@code Authorization} la cadena continúa y no se autentica a nadie:
 *       ese es el caso de las rebanadas MVC que no envían credenciales y el de los
 *       endpoints que T3 abrirá explícitamente.</li>
 *   <li>Con cabecera pero sin el formato {@code Bearer <token>}, o con un token cuya
 *       firma no verifica contra la clave pública, expirado, mal formado o con un
 *       algoritmo distinto de RS256, la respuesta es {@code 401} con el contrato de error
 *       único de la API ({@link ErrorResponse}). Un token HS256 firmado con los bytes de
 *       la clave pública se rechaza igual: la clave es RSA y el algoritmo se comprueba de
 *       forma explícita. Un token RS384, RS512 o PS256 firmado con la <em>misma</em> clave
 *       privada también se rechaza: jjwt verifica esas firmas correctamente y lo que las
 *       descarta es la comprobación explícita de {@code alg=RS256}. Esa es la prueba real
 *       del pin; el caso HS256 no, porque jjwt ya lo rechaza antes.</li>
 *   <li>Los claims {@code sub}, {@code role}, {@code tenantId} y {@code exp} son
 *       obligatorios y {@code role} debe pertenecer a {@code ADMIN}, {@code MEDICO} o
 *       {@code PACIENTE}; {@code email} y {@code medicoId} son opcionales. Cualquier claim
 *       obligatorio ausente o de tipo inválido también produce {@code 401}.</li>
 *   <li>{@code exp} es obligatorio: un token sin {@code exp} no caduca nunca y
 *       contradiría el TTL de 30 minutos (D7) y la ventana de inactividad HIPAA, así que
 *       se rechaza aquí aunque jjwt lo acepte.</li>
 *   <li>{@code sub}, {@code tenantId} y {@code medicoId} deben ser UUID bien formados: se
 *       convierten a {@link UUID} en el límite porque las columnas contra las que se
 *       comparan son UUID y un texto arbitrario saldría como {@code 500} en vez de
 *       {@code 401}.</li>
 * </ul>
 *
 * <p>El filtro falla cerrado: si no hay clave pública válida disponible rechaza cualquier
 * petición con credenciales en lugar de dejarla pasar. En un arranque real esa situación
 * no ocurre porque {@link AuthKeyStartupConfig} impide levantar la aplicación.</p>
 *
 * <p>Nunca se registra el token ni su contenido: los rechazos se registran con la ruta, el
 * método y el tipo de excepción (R-SEG-10).</p>
 *
 * <p><strong>Supuesto de desarrollo declarado (§5.6):</strong> {@code auth.issuer} y
 * {@code auth.audience} son opcionales en la configuración y, si están en blanco, la
 * validación de ese claim se omite. En producción ambos deben estar definidos; no es una
 * decisión del Product Owner, sino un supuesto de trabajo con fecha, sujeto a la
 * respuesta del emisor real de tokens.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Mensaje del 401 cuando la cabecera falta en formato {@code Bearer <token>}. */
    static final String MENSAJE_CREDENCIALES_BEARER =
            "Se requiere un token Bearer RS256 en la cabecera Authorization";

    /** Mensaje del 401 cuando el token no verifica o no cumple el contrato de claims. */
    static final String MENSAJE_TOKEN_INVALIDO = "El token no es válido o está expirado";

    private static final Logger REGISTRO = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String CABECERA_AUTORIZACION = "Authorization";
    private static final String PREFIJO_BEARER = "Bearer ";
    private static final String ALGORITMO_RS256 = "RS256";

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROL = "role";
    private static final String CLAIM_INQUILINO = "tenantId";
    private static final String CLAIM_MEDICO = "medicoId";

    /** Roles admitidos por el contrato de claims de R-SEG-02. */
    private static final Set<String> ROLES_PERMITIDOS = Set.of("ADMIN", "MEDICO", "PACIENTE");

    /**
     * Forma canónica de un UUID (8-4-4-4-12 hexadecimal). Se exige explícitamente porque
     * {@link UUID#fromString(String)} acepta formas abreviadas como {@code 1-1-1-1-1} y un
     * identificador abreviado no es lo que el AuthService emite.
     */
    private static final Pattern UUID_BIEN_FORMADO =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RSAPublicKey clavePublica;
    private final String issuer;
    private final String audience;
    private final JsonMapper jsonMapper;

    /**
     * Crea el filtro a partir de la configuración de la aplicación.
     *
     * @param clavePublicaBase64 {@code auth.public.key}: Base64 DER (SPKI) de la clave pública
     * @param issuer             {@code auth.issuer}: emisor exigido; si está en blanco se omite
     * @param audience           {@code auth.audience}: audiencia exigida; si está en blanco se omite
     * @param jsonMapper         serializador con el que se escribe el {@link ErrorResponse} del 401
     */
    public JwtAuthenticationFilter(String clavePublicaBase64, String issuer, String audience, JsonMapper jsonMapper) {
        this.clavePublica = leerClave(clavePublicaBase64);
        this.issuer = issuer;
        this.audience = audience;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        String cabecera = peticion.getHeader(CABECERA_AUTORIZACION);
        if (!StringUtils.hasText(cabecera)) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        boolean autenticado = false;
        try {
            String token = extraerToken(cabecera);
            if (token == null) {
                responderNoAutorizado(peticion, respuesta, MENSAJE_CREDENCIALES_BEARER);
                return;
            }
            if (clavePublica == null) {
                REGISTRO.warn("Clave pública del AuthService no disponible; se rechaza la petición a {} {}",
                        peticion.getMethod(), peticion.getRequestURI());
                responderNoAutorizado(peticion, respuesta, MENSAJE_TOKEN_INVALIDO);
                return;
            }

            AuthenticatedUser usuario;
            try {
                usuario = verificarToken(token);
            } catch (JwtException | IllegalArgumentException excepcion) {
                REGISTRO.warn("Token rechazado en {} {}: {}", peticion.getMethod(), peticion.getRequestURI(),
                        excepcion.getClass().getSimpleName());
                responderNoAutorizado(peticion, respuesta, MENSAJE_TOKEN_INVALIDO);
                return;
            }

            SecurityContextHolder.getContext().setAuthentication(autenticacionDe(usuario, peticion));
            autenticado = true;
            cadena.doFilter(peticion, respuesta);
        } finally {
            if (!autenticado) {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private static RSAPublicKey leerClave(String clavePublicaBase64) {
        try {
            return AuthPublicKey.desdeBase64(clavePublicaBase64);
        } catch (IllegalStateException excepcion) {
            // Fail-closed: sin clave no se autentica. El arranque real ya abortó con un
            // mensaje claro; aquí solo se llega en contextos de prueba que no envían
            // credenciales.
            return null;
        }
    }

    private static String extraerToken(String cabecera) {
        if (!cabecera.startsWith(PREFIJO_BEARER)) {
            return null;
        }
        String token = cabecera.substring(PREFIJO_BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private UsernamePasswordAuthenticationToken autenticacionDe(
            AuthenticatedUser usuario, HttpServletRequest peticion) {
        var autenticacion = new UsernamePasswordAuthenticationToken(
                usuario, null, usuario.autoridades());
        autenticacion.setDetails(new WebAuthenticationDetailsSource().buildDetails(peticion));
        return autenticacion;
    }

    private AuthenticatedUser verificarToken(String token) {
        JwtParserBuilder constructor = Jwts.parser().verifyWith(clavePublica);
        if (StringUtils.hasText(issuer)) {
            constructor.requireIssuer(issuer);
        }
        if (StringUtils.hasText(audience)) {
            constructor.requireAudience(audience);
        }

        Jws<Claims> firmado = constructor.build().parseSignedClaims(token);
        String algoritmo = firmado.getHeader().getAlgorithm();
        if (!ALGORITMO_RS256.equals(algoritmo)) {
            throw new UnsupportedJwtException("Algoritmo de firma no permitido: " + algoritmo);
        }

        Claims claims = firmado.getPayload();
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("Falta el claim obligatorio: " + Claims.EXPIRATION);
        }

        String rol = claimObligatorio(claims.get(CLAIM_ROL, String.class), CLAIM_ROL);
        if (!ROLES_PERMITIDOS.contains(rol)) {
            throw new MalformedJwtException("Rol fuera del contrato de claims: " + rol);
        }

        return new AuthenticatedUser(
                claimUuidObligatorio(claims.getSubject(), Claims.SUBJECT),
                claims.get(CLAIM_EMAIL, String.class),
                rol,
                claimUuidObligatorio(claims.get(CLAIM_INQUILINO, String.class), CLAIM_INQUILINO),
                claimUuidOpcional(claims.get(CLAIM_MEDICO, String.class), CLAIM_MEDICO));
    }

    private static String claimObligatorio(String valor, String nombreClaim) {
        if (!StringUtils.hasText(valor)) {
            throw new MalformedJwtException("Falta el claim obligatorio: " + nombreClaim);
        }
        return valor;
    }

    private static UUID claimUuidObligatorio(String valor, String nombreClaim) {
        if (!StringUtils.hasText(valor)) {
            throw new MalformedJwtException("Falta el claim obligatorio: " + nombreClaim);
        }
        return aUuid(valor, nombreClaim);
    }

    private static UUID claimUuidOpcional(String valor, String nombreClaim) {
        return valor == null ? null : aUuid(valor, nombreClaim);
    }

    private static UUID aUuid(String valor, String nombreClaim) {
        if (!UUID_BIEN_FORMADO.matcher(valor).matches()) {
            throw new MalformedJwtException("El claim " + nombreClaim + " no es un UUID bien formado");
        }
        return UUID.fromString(valor);
    }

    private void responderNoAutorizado(
            HttpServletRequest peticion, HttpServletResponse respuesta, String mensaje) throws IOException {
        ErrorResponse cuerpo = new ErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                mensaje,
                peticion.getRequestURI());
        respuesta.setStatus(HttpStatus.UNAUTHORIZED.value());
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getOutputStream().write(jsonMapper.writeValueAsBytes(cuerpo));
    }
}
