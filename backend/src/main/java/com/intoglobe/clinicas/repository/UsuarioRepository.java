package com.intoglobe.clinicas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intoglobe.clinicas.model.RolUsuario;
import com.intoglobe.clinicas.model.Usuario;

/**
 * Acceso a datos de {@link Usuario}.
 *
 * <p>Toda consulta propia se declara con nombre en español y {@code @Query} JPQL explícito;
 * está prohibido depender de la derivación de nombres de Spring Data. Los métodos heredados de
 * {@link JpaRepository} ({@code findById}, {@code save}, {@code existsById}, ...) siguen siendo
 * válidos.</p>
 */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    /**
     * Lista las cuentas de una organización —activas e inactivas— ordenadas por correo
     * ascendente.
     *
     * <p>Es el listado del módulo, y devuelve las dos cosas a propósito: la baja es lógica, así
     * que ocultar las inactivas dejaría al administrador sin forma de reactivar a quien vuelve,
     * y la única salida visible sería crear una cuenta nueva con el mismo correo, que la
     * restricción de unicidad rechaza. El orden es parte del contrato: sin orden estable la
     * respuesta deja de ser reproducible y el cliente tendría que ordenar por su cuenta. Filtra
     * por {@code u.organizacion.id} para apoyarse en el índice {@code idx_usuario_org} de V1.</p>
     *
     * @param organizacionId organización dueña de las cuentas
     * @return todas las cuentas de esa organización, ordenadas por correo ascendente
     */
    @Query("SELECT u FROM Usuario u WHERE u.organizacion.id = :orgId ORDER BY u.email")
    List<Usuario> listarPorOrganizacion(@Param("orgId") UUID organizacionId);

    /**
     * Busca la cuenta de una organización por correo.
     *
     * <p>El correo debe llegar <strong>ya normalizado</strong> a minúsculas
     * ({@link Usuario#normalizarEmail(String)}): es la misma forma en la que la entidad lo
     * guarda, así que la comparación es exacta y puede apoyarse en
     * {@code usuario_organizacion_id_email_key}. Normalizar en la consulta con {@code lower()}
     * daría el mismo resultado pero renunciaría al índice.</p>
     *
     * <p>Se usa para comprobar que un correo no esté ocupado antes de guardar (el servicio debe
     * responder 409 en vez de dejar que el choque contra la restricción de unicidad llegue al
     * cliente como error de integridad) y para el flujo de identidad que resuelve la cuenta de
     * una persona a partir de su correo.</p>
     *
     * @param organizacionId organización en cuyo espacio de correos se busca
     * @param email          correo normalizado a minúsculas
     * @return la cuenta con ese correo en esa organización, o vacío si no existe allí
     */
    @Query("SELECT u FROM Usuario u WHERE u.organizacion.id = :orgId AND u.email = :email")
    Optional<Usuario> buscarPorOrganizacionYEmail(
            @Param("orgId") UUID organizacionId, @Param("email") String email);

    /**
     * Cuenta las cuentas activas de una organización que tienen un rol dado.
     *
     * <p>Solo se usa con {@link RolUsuario#ADMIN}, para la regla de la última administradora:
     * dar de baja —o retirarle el rol— a la última cuenta {@code ADMIN} activa dejaría a la
     * organización sin nadie que pueda administrar cuentas desde la interfaz. El conteo excluye
     * a las cuentas inactivas porque una administradora dada de baja no puede administrar nada.
     * Se filtra por organización, nunca globalmente: cada clínica tiene su propia
     * administración.</p>
     *
     * @param organizacionId organización en la que se cuenta
     * @param rol            rol a contar
     * @return número de cuentas activas de esa organización con ese rol
     */
    @Query("SELECT COUNT(u) FROM Usuario u WHERE u.organizacion.id = :orgId AND u.rol = :rol"
            + " AND u.activo = true")
    long contarActivosPorOrganizacionYRol(
            @Param("orgId") UUID organizacionId, @Param("rol") RolUsuario rol);
}
