package com.intoglobe.clinicas.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Identidad autenticada extraída de los claims del token RS256 emitido por el
 * AuthService de InToGlobe.
 *
 * <p>Es el principal que queda en el {@code SecurityContext} y la única fuente de
 * verdad de {@code tenantId} y {@code role} de una petición: el path nunca la
 * sustituye (R-SEG-06).</p>
 *
 * <p><strong>Los identificadores son {@link UUID}, no {@code String}.</strong> El claim llega
 * como texto, pero se convierte en el límite: {@code sub} y {@code medicoId} tienen que ser
 * UUID canónicos y, si no lo son, el filtro responde {@code 401}. La razón es que
 * {@code usuario.id} y {@code organizacion_id} son columnas UUID, así que un valor con forma
 * inválida acabaría reventando como error interno ({@code 500}) en la capa de persistencia en
 * lugar de ser rechazado como credencial inválida.</p>
 *
 * <p>El {@code tenantId} admite además el slug de la organización, porque es lo que emite la
 * plataforma (S3, S11). Esa forma no llega hasta aquí tal cual: el filtro la resuelve a UUID
 * antes de construir el principal, y si no corresponde a ninguna organización rechaza la
 * petición con {@code 403}. Para el resto de la aplicación el tenant siempre es un UUID.</p>
 *
 * @param id       claim {@code sub}: identificador UUID del usuario
 * @param email    claim {@code email}; puede faltar en el token
 * @param role     claim {@code role}: {@code ADMIN}, {@code MEDICO} o {@code PACIENTE}
 * @param tenantId claim {@code tenantId}: UUID de la organización a la que pertenece el
 *                 usuario, ya resuelto desde el slug si el token lo traía así
 * @param medicoId claim {@code medicoId}: UUID opcional, solo presente para usuarios médicos
 */
public record AuthenticatedUser(
        UUID id,
        String email,
        String role,
        UUID tenantId,
        UUID medicoId) {

    /**
     * Autoridades de Spring Security derivadas del rol del token.
     *
     * @return lista con la única autoridad {@code ROLE_<role>}
     */
    public List<GrantedAuthority> autoridades() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
