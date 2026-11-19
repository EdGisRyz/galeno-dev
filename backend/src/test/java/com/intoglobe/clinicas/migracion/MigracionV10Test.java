package com.intoglobe.clinicas.migracion;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
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
 * La V10 agrega el nombre y el teléfono del trabajador a {@code usuario}, y comprueba de paso lo que
 * la V10 <strong>no</strong> necesita migrar: el CHECK del plan ya admitía {@code ENTERPRISE} desde
 * la V1.
 *
 * <p>Las dos columnas son <strong>nulables</strong> a propósito: las cuentas que ya existen no tienen
 * nombre ni teléfono y no hay de dónde derivarlos; y una fila sin ellos sigue siendo válida, porque
 * ninguno identifica a la cuenta —ése es el correo— ni participa en una unicidad. La obligación de
 * traer el nombre la impone el contrato del registro público —el DTO del alta— y no la tabla: una
 * columna NOT NULL habría exigido inventar un valor para cada fila existente, que es exactamente el
 * tipo de dato inventado que el proyecto prohíbe.</p>
 *
 * <p>La base se monta igual que en las pruebas de V2, V8 y V9 —con un pool, porque H2 ata las
 * restricciones a la sesión que las creó y Flyway cierra la conexión que usa— y las sentencias se
 * ejecutan por {@code Statement}: la prueba mira el esquema, no el camino del servicio.</p>
 */
class MigracionV10Test {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final String UBICACION_MIGRACIONES = "classpath:db/migration";

    /** Nombre del CHECK del plan, declarado en la V1. */
    private static final String RESTRICCION_PLAN = "organizacion_plan_check";

    private HikariDataSource fuente;
    private Connection conexion;

    private static HikariDataSource poolDe(String nombre) {
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(String.format(PLANTILLA_URL, nombre));
        configuracion.setUsername("sa");
        configuracion.setPassword("");
        configuracion.setMaximumPoolSize(2);
        configuracion.setPoolName(nombre);
        return new HikariDataSource(configuracion);
    }

    /** Base nueva migrada a la última versión, con su pool vivo durante toda la prueba. */
    @BeforeEach
    void migrarBaseLimpia() throws SQLException {
        String nombre = "v10_" + UUID.randomUUID().toString().replace("-", "");
        fuente = poolDe(nombre);
        Flyway.configure().dataSource(fuente).locations(UBICACION_MIGRACIONES).load().migrate();
        conexion = fuente.getConnection();
    }

    @AfterEach
    void cerrarTodo() throws SQLException {
        conexion.close();
        fuente.close();
    }

    @Test
    @DisplayName("usuario.nombre y usuario.telefono existen, son nulables y tienen la longitud declarada")
    void lasDosColumnasNuevasExistenYNacenNulables() throws SQLException {
        assertThat(columnas(conexion, "usuario"))
                .as("el nombre del administrador se persiste desde el registro y el teléfono queda listo")
                .contains("nombre", "telefono");

        assertThat(esNulable(conexion, "usuario", "nombre"))
                .as("nulable: las cuentas que ya existen no tienen nombre y no se inventa")
                .isTrue();
        assertThat(esNulable(conexion, "usuario", "telefono"))
                .as("nulable: el registro público no pide teléfono y las cuentas anteriores no lo tienen")
                .isTrue();

        assertThat(longitudMaxima(conexion, "usuario", "nombre"))
                .as("el mismo máximo que el nombre de una organización y de un local")
                .isEqualTo(120);
        assertThat(longitudMaxima(conexion, "usuario", "telefono"))
                .as("el mismo tipo que el teléfono de médico y de paciente")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("una cuenta sin nombre ni teléfono sigue siendo válida y con ellos los guarda")
    void unaCuentaSinNombreNiTelefonoSigueSiendoValida() throws SQLException {
        String organizacion = crearOrganizacion("Clinica V10 Sin Nombre");

        assertThatCode(() -> ejecutar(conexion,
                insertarUsuario(organizacion, "sin.nombre@correo.mx", null, null)))
                .as("las cuentas anteriores a las columnas no se rompen")
                .doesNotThrowAnyException();

        assertThatCode(() -> ejecutar(conexion,
                insertarUsuario(organizacion, "con.datos@correo.mx", "Administración", "+52 55 5555 1234")))
                .doesNotThrowAnyException();

        assertThat(valoresDeTexto(conexion,
                "SELECT nombre FROM usuario WHERE email = 'con.datos@correo.mx'"))
                .containsExactly("Administración");
        assertThat(valoresDeTexto(conexion,
                "SELECT telefono FROM usuario WHERE email = 'con.datos@correo.mx'"))
                .containsExactly("+52 55 5555 1234");
    }

    @Test
    @DisplayName("el CHECK del plan ya admitía ENTERPRISE en la V1: la V10 no lo vuelve a declarar")
    void elCheckDelPlanYaAdmiteEnterprise() throws SQLException {
        assertThat(nombresDeRestricciones(conexion, "organizacion"))
                .as("el CHECK del plan viene de la V1 y no se reemplaza")
                .contains(RESTRICCION_PLAN);

        for (String plan : List.of("SOLO", "CLINICA", "ENTERPRISE")) {
            assertThatCode(() -> ejecutar(conexion, insertarOrganizacion("Clinica " + plan, plan)))
                    .as("el plan %s tiene que ser válido en la base", plan)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> ejecutar(conexion, insertarOrganizacion("Clinica Inventada", "GOLD")))
                .as("un plan fuera del contrato sigue rechazándose")
                .isInstanceOf(SQLException.class);
    }

    private String crearOrganizacion(String nombre) throws SQLException {
        String id = UUID.randomUUID().toString();
        ejecutar(conexion, "INSERT INTO organizacion (id, nombre, plan, activo) VALUES ('"
                + id + "', '" + nombre + "', 'CLINICA', true)");
        return id;
    }

    private static String insertarOrganizacion(String nombre, String plan) {
        return "INSERT INTO organizacion (id, nombre, plan, activo) VALUES ('"
                + UUID.randomUUID() + "', '" + nombre + "', '" + plan + "', true)";
    }

    private static String insertarUsuario(String organizacionId, String email, String nombre,
            String telefono) {
        StringBuilder columnas = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        if (nombre != null) {
            columnas.append(", nombre");
            valores.append(", '").append(nombre).append("'");
        }
        if (telefono != null) {
            columnas.append(", telefono");
            valores.append(", '").append(telefono).append("'");
        }
        return "INSERT INTO usuario (id, organizacion_id, email, rol, auth_user_id, activo"
                + columnas + ") VALUES ('" + UUID.randomUUID() + "', '" + organizacionId + "', '" + email
                + "', 'ADMIN', '" + UUID.randomUUID() + "', true" + valores + ")";
    }

    private static void ejecutar(Connection conexion, String sql) throws SQLException {
        try (Statement sentencia = conexion.createStatement()) {
            sentencia.execute(sql);
        }
    }

    private static List<String> columnas(Connection conexion, String tabla) throws SQLException {
        return valoresDeTexto(conexion,
                "SELECT column_name FROM information_schema.columns WHERE lower(table_name) = '"
                        + tabla + "'");
    }

    private static List<String> nombresDeRestricciones(Connection conexion, String tabla) throws SQLException {
        return valoresDeTexto(conexion,
                "SELECT constraint_name FROM information_schema.table_constraints WHERE lower(table_name) = '"
                        + tabla + "'");
    }

    private static boolean esNulable(Connection conexion, String tabla, String columna) throws SQLException {
        List<String> resultado = valoresDeTexto(conexion,
                "SELECT is_nullable FROM information_schema.columns WHERE lower(table_name) = '"
                        + tabla + "' AND lower(column_name) = '" + columna + "'");
        assertThat(resultado)
                .as("la columna %s.%s tiene que existir", tabla, columna)
                .hasSize(1);
        return "YES".equalsIgnoreCase(resultado.get(0));
    }

    private static int longitudMaxima(Connection conexion, String tabla, String columna) throws SQLException {
        List<String> resultado = valoresDeTexto(conexion,
                "SELECT character_maximum_length FROM information_schema.columns WHERE lower(table_name) = '"
                        + tabla + "' AND lower(column_name) = '" + columna + "'");
        assertThat(resultado).hasSize(1);
        return Integer.parseInt(resultado.get(0));
    }

    private static List<String> valoresDeTexto(Connection conexion, String sql) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (Statement sentencia = conexion.createStatement();
                ResultSet resultado = sentencia.executeQuery(sql)) {
            while (resultado.next()) {
                valores.add(resultado.getString(1));
            }
        }
        return valores;
    }
}
