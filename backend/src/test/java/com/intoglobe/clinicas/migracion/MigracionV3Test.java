package com.intoglobe.clinicas.migracion;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas negativas de la V3 y la V4: lo que la base tiene que <em>impedir</em>.
 *
 * La suite de la aplicación nunca las ejerce, porque siempre inserta datos coherentes. Y la clave
 * foránea compuesta de la V3 ya estuvo mal escrita una vez sin que ningún test lo notara: comparaba
 * la organización contra el id del médico. Por eso estas comprobaciones existen aparte.
 *
 * Se monta la base igual que en {@link MigracionV2Test}, con un pool: H2 ata las restricciones a la
 * sesión que las creó y Flyway cierra la conexión que usa cuando no hay pool.
 */
class MigracionV3Test {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private HikariDataSource fuente;
    private Connection conexion;

    @BeforeEach
    void migrarBaseLimpia() throws SQLException {
        String nombre = "v3_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(String.format(PLANTILLA_URL, nombre));
        configuracion.setUsername("sa");
        configuracion.setPassword("");
        configuracion.setMaximumPoolSize(2);
        configuracion.setPoolName(nombre);
        fuente = new HikariDataSource(configuracion);

        Flyway.configure()
                .dataSource(fuente)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        conexion = fuente.getConnection();
    }

    @AfterEach
    void cerrarTodo() throws SQLException {
        conexion.close();
        fuente.close();
    }

    @Test
    @DisplayName("rechaza un usuario que apunta a un médico de otra organización")
    void medicoDeOtraOrganizacionEsRechazado() throws SQLException {
        String propia = crearOrganizacion();
        String ajena = crearOrganizacion();
        String medicoAjeno = crearMedico(ajena);

        assertThatThrownBy(() -> ejecutar(insertarUsuario(propia, "ajeno@correo.mx", "MEDICO", medicoAjeno)))
                .as("la clave compuesta por organización tiene que impedirlo, no solo el servicio")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23506"));
    }

    @Test
    @DisplayName("rechaza un usuario con rol MEDICO sin fila de médico")
    void medicoSinFilaDeMedicoEsRechazado() throws SQLException {
        String organizacion = crearOrganizacion();

        assertThatThrownBy(() -> ejecutar(insertarUsuario(organizacion, "suelto@correo.mx", "MEDICO", null)))
                .as("sin fila de médico no puede tener agenda ni aparecer en el directorio")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23513"));
    }

    @Test
    @DisplayName("acepta un administrador con médico asignado, porque la regla es de una sola dirección")
    void administradorConMedicoEsAceptado() throws SQLException {
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);

        assertThatCode(() -> ejecutar(insertarUsuario(organizacion, "duenio@correo.mx", "ADMIN", medico)))
                .as("el dueño de la clínica puede atender: solo se exige en el sentido del rol MEDICO")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("la tabla de unión rechaza un consultorio de otra organización")
    void vinculoEntreOrganizacionesEsRechazado() throws SQLException {
        String propia = crearOrganizacion();
        String ajena = crearOrganizacion();
        String medico = crearMedico(propia);
        String consultorioAjeno = crearConsultorio(ajena);

        assertThatThrownBy(() -> ejecutar("INSERT INTO medico_consultorio (medico_id, consultorio_id, organizacion_id)"
                + " VALUES ('" + medico + "', '" + consultorioAjeno + "', '" + propia + "')"))
                .as("las dos claves comparten la organización: si no coinciden, no se puede satisfacer")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23506"));
    }

    // ─────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────

    private String crearOrganizacion() throws SQLException {
        String id = UUID.randomUUID().toString();
        ejecutar("INSERT INTO organizacion (id, nombre, plan) VALUES ('"
                + id + "', 'Organización de prueba', 'SOLO')");
        return id;
    }

    private String crearConsultorio(String organizacionId) throws SQLException {
        String id = UUID.randomUUID().toString();
        ejecutar("INSERT INTO consultorio (id, organizacion_id, nombre) VALUES ('"
                + id + "', '" + organizacionId + "', 'Consultorio de prueba')");
        return id;
    }

    private String crearMedico(String organizacionId) throws SQLException {
        String id = UUID.randomUUID().toString();
        ejecutar("INSERT INTO medico (id, organizacion_id, consultorio_id, nombre) VALUES ('"
                + id + "', '" + organizacionId + "', '" + crearConsultorio(organizacionId)
                + "', 'Médico de prueba')");
        return id;
    }

    private String insertarUsuario(String organizacionId, String correo, String rol, String medicoId) {
        return "INSERT INTO usuario (organizacion_id, email, rol, medico_id, auth_user_id) VALUES ('"
                + organizacionId + "', '" + correo + "', '" + rol + "', "
                + (medicoId == null ? "NULL" : "'" + medicoId + "'")
                + ", '" + UUID.randomUUID() + "')";
    }

    private void ejecutar(String sql) throws SQLException {
        try (Statement sentencia = conexion.createStatement()) {
            sentencia.execute(sql);
        }
    }

    /** El código viaja en la excepción encadenada, no siempre en el primer nivel. */
    private static org.assertj.core.api.AbstractStringAssert<?> assertThatSqlState(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLException sql) {
                return org.assertj.core.api.Assertions.assertThat(sql.getSQLState());
            }
        }
        return org.assertj.core.api.Assertions.assertThat((String) null);
    }
}
