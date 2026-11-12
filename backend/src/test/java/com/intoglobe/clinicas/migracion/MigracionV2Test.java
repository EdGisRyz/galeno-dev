package com.intoglobe.clinicas.migracion;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migración V2: usuario respaldado por el servicio de autenticación, rol de recepción y slug.
 *
 * Se prueba sobre H2 en modo PostgreSQL, igual que la suite de la aplicación, porque el esquema lo
 * crea Flyway en los dos entornos: una sentencia que solo entienda un motor rompería el arranque del
 * otro. No se levanta el contexto de Spring: esto verifica el esquema, no el comportamiento de la
 * aplicación.
 *
 * <p>
 * Las bases se montan con un pool y no con conexiones sueltas, y no es un detalle cosmético: H2
 * asocia cada restricción a la sesión que la creó y cachea comparadores ligados a ella. Flyway
 * cierra la conexión que usa, así que con un {@code DriverDataSource} la sesión muere y la primera
 * inserción posterior falla con «Check constraint invalid», un mensaje que no describe la causa.
 * Con un pool, la conexión vuelve al pool y la sesión sigue viva, que es lo que ocurre en la
 * aplicación real.
 */
class MigracionV2Test {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final String UBICACION_MIGRACIONES = "classpath:db/migration";

    private HikariDataSource fuente;
    private Connection conexion;

    private static String urlDe(String nombre) {
        return String.format(PLANTILLA_URL, nombre);
    }

    private static HikariDataSource poolDe(String nombre) {
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(urlDe(nombre));
        configuracion.setUsername("sa");
        configuracion.setPassword("");
        configuracion.setMaximumPoolSize(2);
        configuracion.setPoolName(nombre);
        return new HikariDataSource(configuracion);
    }

    private static Flyway flywayDe(HikariDataSource fuente, String versionObjetivo) {
        var configuracion = Flyway.configure()
                .dataSource(fuente)
                .locations(UBICACION_MIGRACIONES);
        if (versionObjetivo != null) {
            configuracion.target(MigrationVersion.fromVersion(versionObjetivo));
        }
        return configuracion.load();
    }

    /** Base nueva migrada a la última versión, con su pool vivo durante toda la prueba. */
    @BeforeEach
    void migrarBaseLimpia() throws SQLException {
        String nombre = "v2_" + UUID.randomUUID().toString().replace("-", "");
        fuente = poolDe(nombre);
        flywayDe(fuente, null).migrate();
        conexion = fuente.getConnection();
    }

    @AfterEach
    void cerrarTodo() throws SQLException {
        conexion.close();
        fuente.close();
    }

    // ─────────────────────────────────────────
    // Esquema resultante (S1, S11)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("deja el usuario sin contraseña, con identificador de autenticación y con slug de organización")
    void esquemaResultante() throws SQLException {
        assertThat(columnas(conexion, "usuario"))
                .as("la contraseña no puede quedarse: las credenciales viven en el servicio de autenticación")
                .doesNotContain("password_hash")
                .contains("auth_user_id");

        assertThat(esNulable(conexion, "usuario", "auth_user_id"))
                .as("cada usuario tiene que poder atribuirse a una cuenta del servicio")
                .isFalse();

        assertThat(columnas(conexion, "organizacion")).contains("slug");
        assertThat(esNulable(conexion, "organizacion", "slug"))
                .as("nulable a propósito: un valor inventado no coincidiría con el del servicio")
                .isTrue();

        assertThat(nombresDeRestricciones(conexion, "usuario"))
                .as("la unicidad global del correo ya no existe y las nuevas están declaradas")
                .doesNotContain("usuario_email_key")
                .contains("usuario_organizacion_id_email_key", "usuario_auth_user_id_key");
    }

    // ─────────────────────────────────────────
    // Correo único por organización (S2)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("acepta el mismo correo en dos organizaciones y lo rechaza dentro de una")
    void correoUnicoPorOrganizacion() throws SQLException {
        String primera = crearOrganizacion(null);
        String segunda = crearOrganizacion(null);

        ejecutar(conexion, insertarUsuario(primera, "compartido@correo.mx", "ADMIN"));

        assertThatCode(() -> ejecutar(conexion, insertarUsuario(segunda, "compartido@correo.mx", "ADMIN")))
                .as("el mismo correo en otra clínica es otra cuenta: así lo identifica el servicio")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> ejecutar(conexion, insertarUsuario(primera, "compartido@correo.mx", "MEDICO")))
                .as("repetido dentro de la misma organización es un error")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(codigoSql(error)).isEqualTo("23505"));
    }

    // ─────────────────────────────────────────
    // Identificador de autenticación único por organización (S1)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("acepta el mismo identificador de autenticación en dos organizaciones y lo rechaza dentro de una")
    void identificadorDeAutenticacionUnicoPorOrganizacion() throws SQLException {
        String primera = crearOrganizacion(null);
        String segunda = crearOrganizacion(null);
        String identificador = UUID.randomUUID().toString();

        ejecutar(conexion, insertarUsuario(primera, "uno@correo.mx", "ADMIN", identificador));
        ejecutar(conexion, insertarUsuario(segunda, "dos@correo.mx", "ADMIN", identificador));

        assertThatThrownBy(() -> ejecutar(conexion, insertarUsuario(primera, "tres@correo.mx", "ADMIN", identificador)))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(codigoSql(error)).isEqualTo("23505"));
    }

    // ─────────────────────────────────────────
    // Rol de recepción, aditivo (S9)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("admite los cuatro roles y sigue rechazando uno inventado")
    void rolDeRecepcionEsAditivo() throws SQLException {
        String organizacion = crearOrganizacion(null);

        for (String rol : List.of("ADMIN", "MEDICO", "PACIENTE", "RECEPCIONISTA")) {
            String correo = rol.toLowerCase() + "@correo.mx";
            assertThatCode(() -> ejecutar(conexion, insertarUsuario(organizacion, correo, rol)))
                    .as("el rol %s tiene que estar admitido", rol)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> ejecutar(conexion, insertarUsuario(organizacion, "otro@correo.mx", "BOGUS")))
                .as("la lista de roles sigue siendo cerrada")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(codigoSql(error)).isEqualTo("23513"));
    }

    // ─────────────────────────────────────────
    // Slug de organización (S11)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("permite varias organizaciones sin slug y rechaza el slug repetido")
    void slugUnicoYNulosCompatibles() {
        assertThatCode(() -> {
            crearOrganizacion(null);
            crearOrganizacion(null);
            crearOrganizacion("clinica-central");
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> crearOrganizacion("clinica-central"))
                .as("dos organizaciones no pueden compartir slug: es la clave de comparación del token")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(codigoSql(error)).isEqualTo("23505"));
    }

    // ─────────────────────────────────────────
    // Precondición y orden de las sentencias (S1)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("con filas en usuario se detiene y deja la tabla como estaba")
    void seDetieneSinMutarCuandoLaTablaNoEstaVacia() throws SQLException {
        String nombre = "neg_" + UUID.randomUUID().toString().replace("-", "");

        try (HikariDataSource pool = poolDe(nombre)) {
            // Solo hasta V1: simula una base que todavía no migró y que ya tiene usuarios.
            flywayDe(pool, "1").migrate();

            try (Connection base = pool.getConnection()) {
                ejecutar(base, "INSERT INTO organizacion (id, nombre, plan) VALUES ('"
                        + UUID.randomUUID() + "', 'Organización previa', 'SOLO')");
                ejecutar(base, "INSERT INTO usuario (organizacion_id, email, rol, password_hash)"
                        + " SELECT id, 'anterior@correo.mx', 'ADMIN', 'hash' FROM organizacion");

                assertThatThrownBy(() -> flywayDe(pool, null).migrate())
                        .as("no hay de dónde derivar un identificador de autenticación: tiene que fallar")
                        .isInstanceOf(FlywayException.class);

                /*
                 * Y lo que importa de verdad: el fallo no dejó la tabla a medio migrar. La
                 * sentencia que puede fallar va antes que cualquier otra, justamente para esto.
                 */
                assertThat(columnas(base, "usuario"))
                        .as("la tabla conserva su forma original")
                        .contains("password_hash")
                        .doesNotContain("auth_user_id");
                assertThat(columnas(base, "organizacion"))
                        .as("la organización tampoco se tocó")
                        .doesNotContain("slug");
            }
        }
    }

    // ─────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────

    private String crearOrganizacion(String slug) throws SQLException {
        String id = UUID.randomUUID().toString();
        String valorSlug = slug == null ? "NULL" : "'" + slug + "'";
        ejecutar(conexion, "INSERT INTO organizacion (id, nombre, plan, slug) VALUES ('"
                + id + "', 'Organización de prueba', 'SOLO', " + valorSlug + ")");
        return id;
    }

    private String insertarUsuario(String organizacionId, String correo, String rol) {
        return insertarUsuario(organizacionId, correo, rol, UUID.randomUUID().toString());
    }

    private String insertarUsuario(String organizacionId, String correo, String rol, String authUserId) {
        return "INSERT INTO usuario (organizacion_id, email, rol, auth_user_id) VALUES ('"
                + organizacionId + "', '" + correo + "', '" + rol + "', '" + authUserId + "')";
    }

    private static void ejecutar(Connection conexion, String sql) throws SQLException {
        try (Statement sentencia = conexion.createStatement()) {
            sentencia.execute(sql);
        }
    }

    private static List<String> columnas(Connection conexion, String tabla) throws SQLException {
        return consultar(conexion,
                "SELECT column_name FROM information_schema.columns WHERE lower(table_name) = '"
                        + tabla + "'");
    }

    private static List<String> nombresDeRestricciones(Connection conexion, String tabla) throws SQLException {
        return consultar(conexion,
                "SELECT constraint_name FROM information_schema.table_constraints WHERE lower(table_name) = '"
                        + tabla + "'");
    }

    private static boolean esNulable(Connection conexion, String tabla, String columna) throws SQLException {
        List<String> resultado = consultar(conexion,
                "SELECT is_nullable FROM information_schema.columns WHERE lower(table_name) = '"
                        + tabla + "' AND lower(column_name) = '" + columna + "'");
        assertThat(resultado)
                .as("la columna %s.%s tiene que existir", tabla, columna)
                .hasSize(1);
        return "YES".equalsIgnoreCase(resultado.get(0));
    }

    private static List<String> consultar(Connection conexion, String sql) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (Statement sentencia = conexion.createStatement();
                ResultSet resultado = sentencia.executeQuery(sql)) {
            while (resultado.next()) {
                valores.add(resultado.getString(1).toLowerCase());
            }
        }
        return valores;
    }

    /** El código viaja en la excepción encadenada, no siempre en el primer nivel. */
    private static String codigoSql(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
