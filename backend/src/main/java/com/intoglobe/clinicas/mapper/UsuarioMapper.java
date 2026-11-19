package com.intoglobe.clinicas.mapper;

import java.util.UUID;

import com.intoglobe.clinicas.dto.request.UsuarioRequest;
import com.intoglobe.clinicas.dto.response.UsuarioResponse;
import com.intoglobe.clinicas.model.Medico;
import com.intoglobe.clinicas.model.Organizacion;
import com.intoglobe.clinicas.model.Usuario;

/**
 * Mapeo manual entre la entidad {@link Usuario} y sus DTOs.
 *
 * <p>Clase utilitaria sin estado, sin dependencias externas y sin librerías de mapeo: agrupa la
 * traducción explícita para que el service no conozca los detalles del modelo.</p>
 *
 * <p>El mapeo no decide nada de negocio. En particular no comprueba que un {@code MEDICO} traiga
 * médico ni que el médico sea de la organización: eso ocurre antes, en el service, porque
 * necesita la base y debe responder 400 o 404 con un cuerpo de error, no una excepción de
 * integridad.</p>
 */
public final class UsuarioMapper {

    private UsuarioMapper() {
    }

    /**
     * Construye una entidad nueva a partir de lo que resolvió el service.
     *
     * <p>La organización no viaja en el cuerpo —llega por el path— y el médico tampoco llega
     * como entidad: el service los resuelve y valida antes de mapear. {@code authUserId} es un
     * parámetro explícito y no un valor derivado aquí, porque quién lo aporta es una decisión
     * de negocio (UUID generado en local, {@code sub} del token contra la plataforma) que vive
     * en el service.</p>
     *
     * @param organizacion organización dueña, ya resuelta y validada por el service
     * @param medico       médico asociado ya validado, o {@code null} si la cuenta no atiende
     * @param request      datos de entrada validados
     * @param authUserId   identidad de la cuenta en el servicio de autenticación
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Usuario aEntidad(Organizacion organizacion, Medico medico, UsuarioRequest request,
            UUID authUserId) {
        return aEntidad(organizacion, medico, null, request, authUserId);
    }

    /**
     * Construye una entidad nueva con el nombre de la persona.
     *
     * <p>Es la variante del registro público, el único camino que hoy recibe un nombre. El nombre no
     * forma parte de {@link UsuarioRequest} porque el alta de cuentas desde la administración no lo
     * pide; por eso viaja como parámetro explícito y la otra sobrecarga delega aquí con
     * {@code null}.</p>
     *
     * @param organizacion organización dueña, ya resuelta y validada por el service
     * @param medico       médico asociado ya validado, o {@code null} si la cuenta no atiende
     * @param nombre       nombre de la persona, o {@code null} si no se registró
     * @param request      datos de entrada validados
     * @param authUserId   identidad de la cuenta en el servicio de autenticación
     * @return entidad transitoria activa con los campos editables asignados
     */
    public static Usuario aEntidad(Organizacion organizacion, Medico medico, String nombre,
            UsuarioRequest request, UUID authUserId) {
        return new Usuario(organizacion, medico, nombre, request.email(), request.rol(), authUserId);
    }

    /**
     * Construye la representación de salida de una entidad.
     *
     * <p>De las asociaciones se lee únicamente su identificador: Hibernate lo resuelve desde el
     * proxy sin consultar la base, mientras que cualquier otro getter dispararía la carga
     * perezosa y podría fallar fuera de la transacción. Por eso la respuesta es plana y el
     * {@code medicoId} se traduce a {@code null} cuando la cuenta no tiene médico.</p>
     *
     * @param usuario entidad de origen
     * @return DTO de respuesta con los siete campos públicos del registro
     */
    public static UsuarioResponse aResponse(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getOrganizacion().getId(),
                usuario.getMedico() == null ? null : usuario.getMedico().getId(),
                usuario.getEmail(),
                usuario.getRol(),
                usuario.isActivo(),
                usuario.getCreatedAt());
    }

    /**
     * Aplica los campos editables del request sobre una cuenta existente.
     *
     * <p>Delega en {@code actualizarDatos} del modelo, que solo modifica correo, rol y médico.
     * Nunca toca {@code id}, {@code organizacion}, {@code authUserId}, {@code activo} ni
     * {@code createdAt}: una cuenta dada de baja no se reactiva por actualizarla, la identidad
     * de autenticación no cambia y la fecha de alta no se reescribe. Un {@code medico} nulo
     * libera la asociación, que es lo que ocurre al cambiar de {@code MEDICO} a otro rol.</p>
     *
     * @param usuario entidad a modificar
     * @param medico  médico asociado ya validado, o {@code null} para liberar la asociación
     * @param request datos de entrada validados
     */
    public static void actualizarEntidad(Usuario usuario, Medico medico, UsuarioRequest request) {
        usuario.actualizarDatos(medico, request.email(), request.rol());
    }
}
