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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La ficha administrativa del paciente en el esquema: las seis columnas que la V8 agrega a
 * {@code paciente} y la unicidad de la CURP dentro de la organización.
 *
 * <p>La suite de la aplicación solo inserta filas correctas, así que no ejerce el rechazo del
 * duplicado: por eso la comprobación vive aquí, donde la base se monta igual que en
 * {@link MigracionV7Test} —con un pool, porque H2 ata las restricciones a la sesión que las creó y
 * Flyway cierra la conexión que usa cuando no hay pool—.</p>
 *
 * <p>El caso que un índice mal puesto rompería es el de los pacientes <em>sin</em> CURP: registrar
 * a alguien sin CURP —urgencia, menor, extranjero— es normal, y dos de esas filas en la misma
 * organización no pueden colisionar entre sí.</p>
 */
class MigracionV8Test {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** Nombre explícito de la restricción de unicidad que la V8 agrega. */
    private static final String RESTRICCION_CURP_UNICA = "paciente_organizacion_id_curp_key";

    /** Las seis columnas de la ficha, con su tipo declarado en la migración. */
    private static final List<String> COLUMNAS_DE_LA_FICHA = List.of(
            "curp", "ocupacion", "domicilio", "nombre_responsable", "telefono_responsable", "parentesco");

    private HikariDataSource fuente;
    private Connection conexion;

    @BeforeEach
    void prepararBaseLimpia() throws SQLException {
        String nombre = "v8_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(String.format(PLANTILLA_URL, nombre));
        configuracion.setUsername("sa");
        configuracion.setPassword("");
        configuracion.setMaximumPoolSize(4);
        configuracion.setPoolName(nombre);
        fuente = new HikariDataSource(configuracion);
        conexion = fuente.getConnection();
    }

    @AfterEach
    void cerrarTodo() throws SQLException {
        conexion.close();
        fuente.close();
    }

    @Test
    @DisplayName("la V8 agrega las seis columnas de la ficha, todas nulables")
    void laFichaExisteEnElEsquemaYEsNulable() throws SQLException {
        migrar();

        assertThat(columnasDeLaFicha())
                .as("la ficha se guarda en la tabla del paciente, no en una tabla aparte")
                .containsExactlyInAnyOrderElementsOf(COLUMNAS_DE_LA_FICHA);
        assertThat(COLUMNAS_DE_LA_FICHA)
                .allSatisfy(columna -> assertThat(nulabilidadDe(columna))
                        .as("«%s» es opcional: un paciente puede registrarse sin ese dato", columna)
                        .isEqualTo("YES"));
    }

    @Test
    @DisplayName("la V8 declara la unicidad de la CURP por organización sobre (organizacion_id, curp)")
    void laUnicidadDeLaCurpEsPorOrganizacion() throws SQLException {
        migrar();

        assertThat(constraintUnico(RESTRICCION_CURP_UNICA))
                .as("la restricción debe existir con nombre explícito y ser UNIQUE")
                .isEqualTo("UNIQUE");
        assertThat(columnasDeLaRestriccion(RESTRICCION_CURP_UNICA))
                .as("la clave es la pareja organización-CURP: el mismo paciente puede estar en dos clínicas")
                .containsExactly("organizacion_id", "curp");
    }

    @Test
    @DisplayName("rechaza una CURP repetida en la misma organización")
    void laCurpRepetidaEnLaOrganizacionEsRechazada() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Primero", "SASC850101MDFRRN01"));

        assertThatThrownBy(() -> ejecutar(
                insertarPaciente(organizacion, "Paciente Repetido", "SASC850101MDFRRN01")))
                .as("la base es la última frontera: dos altas simultáneas no pueden colar el duplicado")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23505"));
    }

    @Test
    @DisplayName("acepta la misma CURP en otra organización: son dos clínicas distintas")
    void laMismaCurpEnOtraOrganizacionEsAceptada() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        String otraOrganizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Primero", "SASC850101MDFRRN01"));

        assertThatCode(() -> ejecutar(
                insertarPaciente(otraOrganizacion, "Paciente Segundo", "SASC850101MDFRRN01")))
                .as("la unicidad es por organización, nunca entre organizaciones")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("acepta dos pacientes sin CURP en la misma organización")
    void dosPacientesSinCurpConvivenEnLaOrganizacion() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();

        assertThatCode(() -> {
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Uno", null));
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Dos", null));
        })
                .as("los nulos no colisionan en una restricción única: registrar sin CURP es normal")
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────

    /** Aplica todas las migraciones, hasta la última. */
    private void migrar() {
        Flyway.configure()
                .dataSource(fuente)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Nombres de las columnas de la ficha presentes en {@code paciente}.
     *
     * @return las columnas de la ficha que existen hoy en la tabla
     * @throws SQLException si la consulta falla
     */
    private List<String> columnasDeLaFicha() throws SQLException {
        List<String> encontradas = new ArrayList<>();
        for (String columna : COLUMNAS_DE_LA_FICHA) {
            if (!valoresDe("SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'paciente' AND column_name = '" + columna + "'").isEmpty()) {
                encontradas.add(columna);
            }
        }
        return encontradas;
    }

    /**
     * Nulabilidad declarada de una columna de {@code paciente}.
     *
     * @param columna nombre de la columna
     * @return {@code YES} o {@code NO} según {@code information_schema}
     * @throws SQLException si la consulta falla
     */
    private String nulabilidadDe(String columna) throws SQLException {
        var valores = valoresDe("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'paciente' AND column_name = '" + columna + "'");
        return valores.isEmpty() ? null : valores.get(0);
    }

    /**
     * Tipo de restricción declarado para un nombre, si existe.
     *
     * @param restriccion nombre de la restricción
     * @return el tipo declarado ({@code UNIQUE}, {@code PRIMARY KEY}, ...) o {@code null}
     * @throws SQLException si la consulta falla
     */
    private String constraintUnico(String restriccion) throws SQLException {
        var valores = valoresDe("SELECT constraint_type FROM information_schema.table_constraints"
                + " WHERE table_name = 'paciente' AND constraint_name = '" + restriccion + "'");
        return valores.isEmpty() ? null : valores.get(0);
    }

    /**
     * Columnas que cubre una restricción, en su orden declarado.
     *
     * @param restriccion nombre de la restricción
     * @return las columnas en orden
     * @throws SQLException si la consulta falla
     */
    private List<String> columnasDeLaRestriccion(String restriccion) throws SQLException {
        return valoresDe("SELECT column_name FROM information_schema.key_column_usage"
                + " WHERE table_name = 'paciente' AND constraint_name = '" + restriccion + "'"
                + " ORDER BY ordinal_position");
    }

    private String crearOrganizacion() throws SQLException {
        String id = UUID.randomUUID().toString();
        ejecutar("INSERT INTO organizacion (id, nombre, plan) VALUES ('"
                + id + "', 'Organización de prueba', 'SOLO')");
        return id;
    }

    /**
     * Sentencia de alta de un paciente con la ficha mínima.
     *
     * @param organizacionId organización dueña
     * @param nombre         nombre del paciente
     * @param curp           CURP, o {@code null} para dejarla en nulo
     * @return la sentencia {@code INSERT} lista para ejecutar
     */
    private String insertarPaciente(String organizacionId, String nombre, String curp) {
        return "INSERT INTO paciente (id, organizacion_id, nombre, curp) VALUES ('"
                + UUID.randomUUID() + "', '" + organizacionId + "', '" + nombre + "', "
                + (curp == null ? "NULL" : "'" + curp + "'") + ")";
    }

    /**
     * Lee una única columna de todas las filas de la consulta.
     *
     * @param sql consulta que devuelve una sola columna
     * @return los valores leídos, en el orden de la consulta
     * @throws SQLException si la consulta falla
     */
    private List<String> valoresDe(String sql) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (Statement sentencia = conexion.createStatement();
                var filas = sentencia.executeQuery(sql)) {
            while (filas.next()) {
                valores.add(filas.getString(1));
            }
        }
        return valores;
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
