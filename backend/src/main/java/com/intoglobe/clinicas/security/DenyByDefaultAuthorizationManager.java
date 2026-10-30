package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Fallback de la cadena de filtros: <strong>deny-by-default</strong> (R-SEG-09).
 *
 * <p>Antes de T5 la cadena terminaba en {@code anyRequest().authenticated()}. Eso dejaba
 * abierta a <em>cualquier</em> rol autenticado toda ruta que no estuviera en la matriz: el
 * agujero <em>deny-by-omission</em>. El día que alguien agregue un controlador y olvide su
 * regla de autorización, cualquier token válido —de cualquier organización y de cualquier
 * rol— lo alcanza. Este manager invierte la carga de la prueba: lo que no está declarado
 * está cerrado, y abrirlo exige una regla explícita en {@link SecurityConfig}.</p>
 *
 * <h2>Por qué no basta {@code denyAll()} a secas</h2>
 *
 * <p>{@code denyAll()} niega también las rutas que <em>no existen</em>, y ahí la respuesta
 * correcta no es {@code 403}. La autorización corre antes del enrutado, así que la cadena no
 * sabe si la ruta tiene controlador. Con {@code denyAll()} puro: una ruta inexistente
 * respondería {@code 403} a un cliente autenticado —afirmando que existe algo que se le
 * niega— y el {@code 404} honesto del framework nunca se produciría. Este manager aporta la
 * única pieza que falta: pregunta a los mapeos de MVC si la petición tiene controlador.</p>
 *
 * <table>
 *   <caption>Conducta resultante</caption>
 *   <tr><th>Petición</th><th>Anónimo</th><th>Autenticado</th></tr>
 *   <tr><td>Ruta con controlador y sin regla</td><td>{@code 401}</td><td>{@code 403}</td></tr>
 *   <tr><td>Ruta sin controlador</td><td>{@code 401}</td><td>{@code 404} del framework</td></tr>
 * </table>
 *
 * <p>El {@code 401} del anónimo en la ruta inexistente conserva la conducta que ya tenía la
 * API —un anónimo no puede distinguir qué rutas existen— y sale del mismísimo
 * {@link AuthenticatedAuthorizationManager} de Spring Security, no de una copia local de esa
 * regla. El {@code 404} del autenticado exige, además, que el despacho de {@code ERROR}
 * esté permitido en la cadena: el contenedor reenvía el {@code 404} a {@code /error} y ese
 * reenvío vuelve a pasar por los filtros.</p>
 *
 * <h2>Cómo se decide si la ruta tiene controlador</h2>
 *
 * <p>Se consultan los {@link HandlerMapping} del contexto en su orden, igual que hace el
 * {@code DispatcherServlet}, y se toma el primero que devuelve una cadena. Solo se considera
 * «controlador» un manejador que no sea el de recursos estáticos
 * ({@link ResourceHttpRequestHandler}): ese mapeo atiende {@code /**} y devuelve manejador
 * hasta para una ruta que no existe, así que tratarlo como controlador convertiría cualquier
 * ruta inventada en un {@code 403}. Un manejador que no sea de recursos —un
 * {@code HandlerMethod}, un endpoint funcional, lo que venga— cuenta como ruta declarada y
 * queda cerrado: ante la duda, se deniega.</p>
 *
 * <p>Los mapeos se resuelven de forma perezosa con {@link ObjectProvider} la primera vez que
 * llega una petición al fallback. Inyectarlos directamente en la configuración de seguridad
 * forzaría su creación durante el refresco del contexto, antes de que los
 * {@code WebMvcConfigurer} de la aplicación estén completos; resolverlos al primer uso evita
 * ese acoplamiento de orden de arranque. Si el contexto no tiene ningún mapeo —un contexto
 * sin MVC— se deniega: sin información de enrutado, el fallo seguro es cerrar.</p>
 *
 * <p>Una petición que MVC reconoce pero no puede resolver (por ejemplo un método no admitido
 * en una ruta existente) lanza al buscar el manejador y se trata como ruta declarada:
 * {@code 403}. Es la dirección conservadora; nunca se convierte una excepción en un
 * permiso.</p>
 */
final class DenyByDefaultAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** Denegación para las rutas con controlador: falsa para todos los roles. */
    private static final AuthorizationDecision DENEGADO = new AuthorizationDecision(false);

    /**
     * Regla de Spring Security para la ruta que no tiene controlador: autenticado pasa,
     * anónimo es rechazado. Se delega en vez de reimplementar la semántica de anónimo.
     */
    private static final AuthorizationManager<RequestAuthorizationContext> SOLO_AUTENTICADOS =
            AuthenticatedAuthorizationManager.authenticated();

    private final ObjectProvider<HandlerMapping> mapeosDeHandler;

    private volatile List<HandlerMapping> mapeos;

    /**
     * Crea el fallback con acceso perezoso a los mapeos de MVC.
     *
     * @param mapeosDeHandler proveedor de los {@link HandlerMapping} del contexto
     */
    DenyByDefaultAuthorizationManager(ObjectProvider<HandlerMapping> mapeosDeHandler) {
        this.mapeosDeHandler = mapeosDeHandler;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> autenticacion,
            RequestAuthorizationContext contexto) {
        if (hayControlador(contexto.getRequest())) {
            return DENEGADO;
        }
        return SOLO_AUTENTICADOS.authorize(autenticacion, contexto);
    }

    /**
     * Indica si la petición tiene un controlador que la atienda.
     *
     * @param peticion petición a resolver
     * @return {@code true} si algún mapeo devuelve un manejador distinto del de recursos
     *         estáticos, o si no hay mapeos que consultar (falla cerrado)
     */
    private boolean hayControlador(HttpServletRequest peticion) {
        List<HandlerMapping> disponibles = mapeosDisponibles();
        if (disponibles.isEmpty()) {
            return true;
        }
        for (HandlerMapping mapeo : disponibles) {
            HandlerExecutionChain cadena;
            try {
                cadena = mapeo.getHandler(peticion);
            } catch (Exception excepcion) {
                // Ruta reconocida pero no resoluble (405, tipo de contenido, etc.): se trata
                // como declarada para fallar cerrado.
                return true;
            }
            if (cadena != null) {
                return !(cadena.getHandler() instanceof ResourceHttpRequestHandler);
            }
        }
        return false;
    }

    /**
     * Resuelve los mapeos de MVC una sola vez.
     *
     * @return los {@link HandlerMapping} del contexto en su orden de resolución
     */
    private List<HandlerMapping> mapeosDisponibles() {
        List<HandlerMapping> resueltos = this.mapeos;
        if (resueltos == null) {
            resueltos = this.mapeosDeHandler.orderedStream().toList();
            this.mapeos = resueltos;
        }
        return resueltos;
    }
}
