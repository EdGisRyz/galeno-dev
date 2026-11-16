package com.intoglobe.clinicas.security;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 *       quien decide es la cadena de filtros, que responde {@code 401} con
 *       {@link RestAuthenticationEntryPoint} salvo en las rutas públicas de documentación.
 *       El filtro no responde por su cuenta en ese caso para no adelantarse a la
 *       autorización.</li>
 *   <li>Con cabecera pero sin el formato {@code Bearer <token>}, o con un token cuya
 *       firma no verifica contra la clave pública, expirado, mal formado o con un
 *       algoritmo distinto de RS256, la respuesta es {@code 401} con el contrato de error
 *       único de la API ({@link ErrorResponse}), construido con
 *       {@link SecurityErrorResponder} igual que el {@code 401} del entry point. Un token HS256 firmado con los bytes de
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
 *   <li>{@code sub} y {@code medicoId} deben ser UUID bien formados: se convierten a
 *       {@link UUID} en el límite porque las columnas contra las que se comparan son UUID y
 *       un texto arbitrario saldría como {@code 500} en vez de {@code 401}.</li>
 *   <li><strong>Excepción declarada: {@code tenantId} también admite el slug de la
 *       organización.</strong> El emisor de la plataforma identifica la organización por su
 *       slug ({@code "acme"}) y el de desarrollo por su UUID (S3, S11), así que el claim
 *       tiene dos formas válidas: un UUID se usa directo, sin consultar nada; un texto se
 *       resuelve <em>una sola vez</em> contra {@code organizacion.slug} al final de la
 *       validación. El resto de los claims se valida primero, de modo que un claim ausente o
 *       de un tipo imposible sigue respondiendo {@code 401} aunque el tenant venga como
 *       texto.</li>
 * </ul>
 *
 * <p><strong>Un slug que no resuelve responde {@code 403} antes de la cadena de
 * autorización</strong> (nunca llega al controlador), con el mismo {@code ErrorResponse} de
 * acceso denegado que usa el resto de la seguridad: un token legítimo de una organización
 * desconocida no es un token inválido, y la respuesta no revela si el slug existe. Si el
 * resolutor no está disponible —contextos sin JPA— el filtro falla cerrado y rechaza igual
 * cualquier claim con forma de slug. Un <strong>fallo del resolutor</strong> (la base de datos
 * caída, por ejemplo) se trata exactamente igual: sin tenant verificado no se autentica a
 * nadie, y la puerta de entrada responde {@code 403} en lugar de convertir el fallo en un
 * {@code 500}.</p>
 *
 * <p>El filtro falla cerrado: si no hay clave pública válida disponible rechaza cualquier
 * petición con credenciales en lugar de dejarla pasar. En un arranque real esa situación
 * no ocurre porque {@link AuthKeyStartupConfig} impide levantar la aplicación.</p>
 *
 * <p>Nunca se registra el token ni su contenido: los rechazos se registran con la ruta, el
 * método y el tipo de excepción (R-SEG-10).</p>
 *
 * <p><strong>Supuesto de desarrollo declarado (sección 5.6):</strong> {@code auth.issuer} y
 * {@code auth.audience} son opcionales en la configuración y, si están en blanco, la
 * validación de ese claim se omite. En producción ambos deben estar definidos; no es una
 * decisión del Product Owner, sino un supuesto de trabajo con fecha, sujeto a la
 * respuesta del emisor real de tokens.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
     * identificador abreviado no es lo que el AuthService emite: el claim {@code tenantId}
     * que no cumple esta forma se trata como slug, nunca como UUID.
     */
    private static final Pattern UUID_BIEN_FORMADO =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RSAPublicKey clavePublica;
    private final String issuer;
    private final String audience;
    private final JsonMapper jsonMapper;

    /**
     * Traductor del claim {@code tenantId} cuando llega como slug. Puede ser {@code null} en
     * contextos que no levantan JPA (rebanadas MVC que importan
     * {@link SecurityConfig}); en ese caso un claim de texto se rechaza cerrado.
     */
    private final ResolutorDeTenant resolutorDeTenant;

    /**
     * Crea el filtro a partir de la configuración de la aplicación.
     *
     * @param clavePublicaBase64 {@code auth.public.key}: Base64 DER (SPKI) de la clave pública
     * @param issuer             {@code auth.issuer}: emisor exigido; si está en blanco se omite
     * @param audience           {@code auth.audience}: audiencia exigida; si está en blanco se omite
     * @param jsonMapper         serializador con el que se escribe el {@link ErrorResponse} del 401
     * @param resolutorDeTenant  traductor de slug a UUID, o {@code null} si el contexto no
     *                           tiene acceso a la tabla de organizaciones
     */
    public JwtAuthenticationFilter(String clavePublicaBase64, String issuer, String audience, JsonMapper jsonMapper,
            ResolutorDeTenant resolutorDeTenant) {
        this.clavePublica = leerClave(clavePublicaBase64);
        this.issuer = issuer;
        this.audience = audience;
        this.jsonMapper = jsonMapper;
        this.resolutorDeTenant = resolutorDeTenant;
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
                responderNoAutorizado(peticion, respuesta, SecurityErrorResponder.MENSAJE_CREDENCIALES_BEARER);
                return;
            }
            if (clavePublica == null) {
                REGISTRO.warn("Clave pública del AuthService no disponible; se rechaza la petición a {} {}",
                        peticion.getMethod(), peticion.getRequestURI());
                responderNoAutorizado(peticion, respuesta, SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO);
                return;
            }

            AuthenticatedUser usuario;
            try {
                usuario = verificarToken(token);
            } catch (TenantNoResueltoException excepcion) {
                // El token verifica y sus claims son válidos: lo que no se puede es ubicar su
                // organización. Se rechaza cerrado antes de la cadena y sin repetir el slug
                // en la respuesta ni en el registro.
                REGISTRO.warn("Token con un tenant que no se puede resolver en {} {}: se rechaza la petición",
                        peticion.getMethod(), peticion.getRequestURI());
                responderAccesoDenegado(peticion, respuesta);
                return;
            } catch (JwtException | IllegalArgumentException excepcion) {
                REGISTRO.warn("Token rechazado en {} {}: {}", peticion.getMethod(), peticion.getRequestURI(),
                        excepcion.getClass().getSimpleName());
                responderNoAutorizado(peticion, respuesta, SecurityErrorResponder.MENSAJE_TOKEN_INVALIDO);
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

        // El tenant se resuelve al final y por separado: es el único paso que puede producir un
        // 403, así que cualquier otro claim inválido del token conserva la precedencia del 401.
        UUID identificador = claimUuidObligatorio(claims.getSubject(), Claims.SUBJECT);
        String correo = claims.get(CLAIM_EMAIL, String.class);
        UUID medico = claimUuidOpcional(claims.get(CLAIM_MEDICO, String.class), CLAIM_MEDICO);
        UUID inquilino = tenantDe(claimObligatorio(claims.get(CLAIM_INQUILINO, String.class), CLAIM_INQUILINO));

        return new AuthenticatedUser(identificador, correo, rol, inquilino, medico);
    }

    /**
     * Traduce el claim {@code tenantId} al UUID de la organización.
     *
     * <p>Las dos formas del contrato conviven sin cambiar el camino que ya funcionaba: un
     * UUID canónico se usa tal cual y <strong>no consulta nada</strong>; cualquier otro texto
     * se considera un slug y se resuelve una única vez contra la tabla de organizaciones. El
     * texto que no corresponde a ninguna organización interrumpe la petición con
     * {@link TenantNoResueltoException}, que el filtro traduce a {@code 403}.</p>
     *
     * <p>Un fallo al resolver —desde una excepción de acceso a datos hasta cualquier error no
     * comprobado del resolutor— también termina en {@link TenantNoResueltoException}: la
     * puerta de entrada no puede convertir una base caída en un {@code 500} ni, mucho menos,
     * dejar pasar la petición sin tenant verificado.</p>
     *
     * @param valor claim {@code tenantId} ya validado como texto no vacío
     * @return el UUID de la organización de la sesión
     * @throws TenantNoResueltoException si el texto no es un UUID, no corresponde a ninguna
     *                                  organización o el resolutor falla
     */
    private UUID tenantDe(String valor) {
        if (UUID_BIEN_FORMADO.matcher(valor).matches()) {
            return UUID.fromString(valor);
        }
        if (resolutorDeTenant == null) {
            // Sin acceso a la tabla de organizaciones no se puede resolver un slug: se falla
            // cerrado en lugar de dejar pasar una petición con el tenant sin verificar.
            throw new TenantNoResueltoException();
        }
        Optional<UUID> resuelto;
        try {
            resuelto = resolutorDeTenant.porSlug(valor);
        } catch (RuntimeException excepcion) {
            // Falla cerrado: sin tenant verificado no se autentica a nadie. Se registra solo el
            // tipo de la excepción, nunca el token ni el slug (R-SEG-10).
            REGISTRO.warn("Fallo al resolver el tenant del token ({}): se rechaza la petición",
                    excepcion.getClass().getSimpleName());
            throw new TenantNoResueltoException();
        }
        if (resuelto == null) {
            // Un resolutor que devuelve nulo no resolvió nada: se trata como desconocido.
            throw new TenantNoResueltoException();
        }
        return resuelto.orElseThrow(TenantNoResueltoException::new);
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
        SecurityErrorResponder.escribir(
                peticion, respuesta, HttpStatus.UNAUTHORIZED, mensaje, jsonMapper);
    }

    private void responderAccesoDenegado(
            HttpServletRequest peticion, HttpServletResponse respuesta) throws IOException {
        SecurityErrorResponder.escribir(
                peticion, respuesta, HttpStatus.FORBIDDEN, SecurityErrorResponder.MENSAJE_ACCESO_DENEGADO, jsonMapper);
    }

    /**
     * Señal interna: el claim {@code tenantId} no es un UUID canónico y no corresponde a
     * ninguna organización.
     *
     * <p>No hereda de {@link JwtException} a propósito: el token es válido y el rechazo por
     * tenant desconocido es un {@code 403}, no el {@code 401} de la credencial inválida. El
     * mensaje nunca llega al cliente; lo que se escribe es el contrato de acceso denegado.</p>
     */
    private static final class TenantNoResueltoException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private TenantNoResueltoException() {
            super("El tenant del token no corresponde a ninguna organización");
        }
    }
}
