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
 * <p><strong>Los identificadores son {@link UUID}, no {@code String}.</strong> El claim
 * llega como texto, pero se valida y se convierte en el límite: el filtro responde
 * {@code 401} si no es un UUID bien formado. La razón es que {@code usuario.id} y
 * {@code organizacion_id} son columnas UUID, así que un valor con forma inválida acabaría
 * reventando como error interno ({@code 500}) en la capa de persistencia en lugar de ser
 * rechazado como credencial inválida.</p>
 *
 * @param id       claim {@code sub}: identificador UUID del usuario
 * @param email    claim {@code email}; puede faltar en el token
 * @param role     claim {@code role}: {@code ADMIN}, {@code MEDICO} o {@code PACIENTE}
 * @param tenantId claim {@code tenantId}: UUID de la organización a la que pertenece el usuario
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
