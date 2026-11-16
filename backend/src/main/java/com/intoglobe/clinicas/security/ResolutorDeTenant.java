package com.intoglobe.clinicas.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.repository.OrganizacionRepository;

/**
 * Traduce el claim {@code tenantId} cuando llega como slug de organización (S3, S11).
 *
 * <p>El emisor de tokens de la plataforma identifica la organización por su slug
 * ({@code "acme"}), mientras que el backend guarda y compara {@link UUID}. Este componente
 * es la pieza que salva esa diferencia: el filtro le pregunta si reconoce el texto y, si no,
 * rechaza la petición.</p>
 *
 * <p>Vive fuera de {@link JwtAuthenticationFilter} por dos razones:</p>
 * <ul>
 *   <li><strong>El filtro no se acopla a JPA.</strong> Recibe este colaborador por
 *       constructor, así que sus pruebas pueden usar un doble que cuente invocaciones sin
 *       levantar una base de datos.</li>
 *   <li><strong>La consulta tiene un solo dueño.</strong> Si mañana cambia la forma de
 *       resolver el tenant (otra columna, una caché, un directorio de organizaciones), se
 *       cambia aquí y el filtro no se entera.</li>
 * </ul>
 *
 * <p>Un slug vacío o nulo no se consulta y se responde «no resuelto»: así el resolutor falla
 * cerrado por sí mismo y no depende de que quien lo llame haya validado antes la entrada.</p>
 *
 * <p><strong>Lo que no hace, y es deliberado:</strong> la resolución traduce identidad, no
 * autoriza. No se filtra por {@code organizacion.activo} —una organización dada de baja cuyo
 * slug esté cargado sigue resolviendo su tenant— y el camino que trae el UUID tampoco comprueba
 * que la organización exista. Es una asimetría preexistente y consciente: qué se puede hacer
 * con el tenant lo deciden la matriz de autorización y los servicios, no la puerta de entrada.
 * Un fallo del resolutor sí se trata como rechazo: sin tenant verificado no se autentica a
 * nadie.</p>
 */
@Component
public class ResolutorDeTenant {

    private final OrganizacionRepository organizaciones;

    /**
     * Crea el resolutor sobre el repositorio de organizaciones.
     *
     * @param organizaciones acceso a la tabla {@code organizacion}
     */
    public ResolutorDeTenant(OrganizacionRepository organizaciones) {
        this.organizaciones = organizaciones;
    }

    /**
     * Busca el identificador de la organización dueña del slug.
     *
     * <p>Se invoca una sola vez por petición: quien llama no debe repetir la consulta para el
     * mismo claim.</p>
     *
     * @param slug texto del claim {@code tenantId}
     * @return el identificador de la organización con ese slug, o vacío si el texto está en
     *         blanco o no corresponde a ninguna organización
     */
    public Optional<UUID> porSlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            return Optional.empty();
        }
        return organizaciones.buscarPorSlug(slug).map(Organizacion::getId);
    }
}
