package com.intoglobe.clinicas.security;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Guardia de tenant de la petición: convierte el {@code tenantId} del token en la única
 * fuente de verdad y comprueba contra él el {@code organizacionId} del path (R-SEG-04,
 * R-SEG-06, ADR-013).
 *
 * <p>Existe por tres razones:</p>
 * <ol>
 *   <li><strong>El path nunca es fuente de verdad.</strong> El
 *       {@code {organizacionId}} de la ruta es un parámetro que el cliente puede
 *       falsificar. Los controladores no lo usan para leer ni para escribir: llaman aquí,
 *       y lo que se propaga a los servicios es el {@code tenantId} del claim.</li>
 *   <li><strong>Un solo punto de comprobación.</strong> La regla «path distinto del claim
 *       equivale a {@code 403}, sin excepción de rol» vive en un método en lugar de estar
 *       repetida en cada controlador, y su mensaje de error nunca llega al cliente: viaja
 *       como {@link AccessDeniedException}, que {@code GlobalExceptionHandler} traduce al
 *       {@code 403} con el contrato {@code ErrorResponse} (D5.7).</li>
 *   <li><strong>No hay rol de plataforma.</strong> No se consulta el rol aquí a propósito:
 *       un {@code ADMIN} tampoco puede operar sobre otra organización (D5 corregido el
 *       2026-10-29). La matriz por rol la aplica la cadena de filtros, en
 *       {@link SecurityConfig}.</li>
 * </ol>
 *
 * <p>Cuando el tenant de la ruta y el del claim coinciden, el método devuelve el valor del
 * claim —no el del path— para que quien lo llame propague la autoridad correcta incluso si
 * ambos se compararon con éxito.</p>
 *
 * <p>El registro de eventos de seguridad no ocurre aquí: {@code GlobalExceptionHandler}
 * registra el rechazo con método y ruta, sin token ni claims (R-SEG-10).</p>
 */
public class TenantAccessGuard {

    /**
     * Recupera la identidad autenticada de la petición.
     *
     * <p>Si no hay principal —escenario imposible con la cadena de filtros cerrada, porque
     * una petición sin token no llega al controlador— se falla cerrado con la misma
     * denegación que un tenant que no coincide, en lugar de dejar pasar la petición sin
     * tenant.</p>
     *
     * @return el usuario autenticado con el {@code tenantId} y el {@code role} del token
     * @throws AccessDeniedException si la petición no tiene un usuario autenticado de este
     *                               contrato de claims
     */
    public AuthenticatedUser usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || !(autenticacion.getPrincipal() instanceof AuthenticatedUser usuario)) {
            throw new AccessDeniedException("La petición no tiene un usuario autenticado del contrato de claims");
        }
        return usuario;
    }

    /**
     * Tenant del token de la petición.
     *
     * @return el {@code tenantId} del claim, que es la organización dueña de la sesión
     * @throws AccessDeniedException si la petición no tiene un usuario autenticado
     */
    public UUID tenantActual() {
        return usuarioActual().tenantId();
    }

    /**
     * Comprueba que el tenant de la ruta es el del token y devuelve el del token.
     *
     * @param organizacionIdDeLaRuta {@code organizacionId} tomado del path de la petición,
     *                               usado solo como validación cruzada
     * @return el {@code tenantId} del claim, listo para propagarse al servicio
     * @throws AccessDeniedException si el path no coincide con el claim; el manejador
     *                               global lo traduce a {@code 403} con {@code ErrorResponse}
     */
    public UUID exigirTenantDeLaRuta(UUID organizacionIdDeLaRuta) {
        UUID tenant = tenantActual();
        if (!tenant.equals(organizacionIdDeLaRuta)) {
            throw new AccessDeniedException(
                    "El organizacionId de la ruta no corresponde al tenant del token");
        }
        return tenant;
    }
}
