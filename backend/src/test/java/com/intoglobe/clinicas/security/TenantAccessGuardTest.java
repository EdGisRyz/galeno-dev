package com.intoglobe.clinicas.security;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link TenantAccessGuard}: la regla D5 corregida —el
 * {@code organizacionId} del path debe coincidir con el {@code tenantId} del claim, sin
 * excepción de rol— y su comportamiento fail-closed.
 *
 * <p>Se prueba sin contexto de Spring a propósito: el guard no consulta el rol ni la base de
 * datos, solo el principal del {@code SecurityContext}, así que lo que aquí se fija es su
 * contrato. El efecto HTTP ({@code 403} con {@code ErrorResponse}) se ejerce en
 * {@link AislamientoYMatrizRbacTest} y en los tests MVC de los tres controladores.</p>
 */
class TenantAccessGuardTest {

    private static final UUID TENANT = UUID.fromString("3f1c2b7a-8d4e-4a9b-9c1f-2e6d5a7b8c90");
    private static final UUID OTRO_TENANT = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final UUID USUARIO = UUID.fromString("9f0d2c4a-1b2e-4c7d-9a11-5b7f3e2d1a01");

    private final TenantAccessGuard guardia = new TenantAccessGuard();

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("tenantActual devuelve el tenantId del claim, no otro valor de la petición")
    void tenantActual_shouldReturnTheClaimTenant() {
        autenticar("ADMIN", TENANT);

        assertThat(guardia.tenantActual()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("exigirTenantDeLaRuta devuelve el tenant del claim cuando el path coincide")
    void exigirTenantDeLaRuta_shouldReturnTheClaimTenant_whenThePathMatches() {
        autenticar("ADMIN", TENANT);

        assertThat(guardia.exigirTenantDeLaRuta(TENANT)).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("exigirTenantDeLaRuta deniega cuando el path es de otro tenant, también para ADMIN")
    void exigirTenantDeLaRuta_shouldDeny_whenThePathIsFromAnotherTenant() {
        autenticar("ADMIN", TENANT);

        assertThatThrownBy(() -> guardia.exigirTenantDeLaRuta(OTRO_TENANT))
                .as("no existe un rol de plataforma: ADMIN tampoco puede operar sobre otro tenant")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("exigirTenantDeLaRuta deniega cuando el path no trae organización")
    void exigirTenantDeLaRuta_shouldDeny_whenThePathHasNoOrganization() {
        autenticar("MEDICO", TENANT);

        assertThatThrownBy(() -> guardia.exigirTenantDeLaRuta(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Sin principal del contrato de claims falla cerrado en lugar de dejar pasar la petición")
    void usuarioActual_shouldDeny_whenThereIsNoAuthenticatedUser() {
        assertThatThrownBy(guardia::usuarioActual)
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(guardia::tenantActual)
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardia.exigirTenantDeLaRuta(TENANT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Un principal ajeno al contrato de claims también se deniega")
    void usuarioActual_shouldDeny_whenThePrincipalIsNotAnAuthenticatedUser() {
        var autenticacion = new UsernamePasswordAuthenticationToken("otro-principal", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(autenticacion);

        assertThatThrownBy(guardia::usuarioActual)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Un principal anónimo se deniega en los tres métodos, no se trata como autenticado")
    void usuarioActual_shouldDeny_whenThePrincipalIsAnonymous() {
        // AnonymousAuthenticationToken se considera "autenticado" por Spring Security: es el
        // principal que la cadena publica por defecto en una petición sin credenciales cuando
        // las anónimas están habilitadas. Por eso se prueba explícitamente que la guardia no
        // lo confunde con un usuario del contrato de claims.
        var anonimo = new AnonymousAuthenticationToken("clave-de-prueba", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(anonimo.isAuthenticated())
                .as("el punto del caso es que Spring lo marca como autenticado")
                .isTrue();
        SecurityContextHolder.getContext().setAuthentication(anonimo);

        assertThatThrownBy(guardia::usuarioActual)
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(guardia::tenantActual)
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardia.exigirTenantDeLaRuta(TENANT))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Publica en el contexto un principal del contrato de claims, como lo haría
     * {@code JwtAuthenticationFilter}.
     *
     * @param rol    rol del token
     * @param tenant organización del token
     */
    private static void autenticar(String rol, UUID tenant) {
        var usuario = new AuthenticatedUser(USUARIO, "ana.torres@clinicasanrafael.test", rol, tenant, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(usuario, null, usuario.autoridades()));
    }
}
