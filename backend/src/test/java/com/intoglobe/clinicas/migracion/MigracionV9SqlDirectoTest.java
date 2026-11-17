package com.intoglobe.clinicas.migracion;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * La CURP en mayúsculas impuesta por el esquema, ejercida por SQL directo: lo que agrega la V9.
 *
 * <p>La V8 declara la unicidad {@code (organizacion_id, curp)}, pero compara cadenas: la forma
 * canónica de la CURP vivía solo en el código, así que una carga directa por SQL podía meter
 * {@code sasc850101mdfrrn01} al lado de {@code SASC850101MDFRRN01} y burlar la unicidad sin violar
 * ninguna restricción. La V9 cierra ese hueco con {@code CHECK (curp = upper(curp))}: la base no
 * depende del código para una regla que ya declara (S3).</p>
 *
 * <p>La base se monta igual que en {@link MigracionV8Test} —con un pool, porque H2 ata las
 * restricciones a la sesión que las creó y Flyway cierra la conexión que usa cuando no hay pool— y
 * las sentencias se ejecutan por {@code Statement}, no por JDBC de la aplicación: la prueba mira el
 * esquema, no el camino del servicio.</p>
 *
 * <p>El efecto aceptado de la V9 se comprueba aquí de forma explícita: la carga directa en
 * minúsculas ahora <em>falla</em> en vez de duplicar en silencio.</p>
 */
class MigracionV9SqlDirectoTest {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** Nombre explícito de la restricción de unicidad que la V8 agrega. */
    private static final String RESTRICCION_CURP_UNICA = "paciente_organizacion_id_curp_key";

    /** Nombre explícito del CHECK de mayúsculas que agrega la V9. */
    private static final String RESTRICCION_CURP_MAYUSCULAS = "paciente_curp_mayusculas_check";

    /** La V9 es la novena migración de la serie: V1 a V9. */
    private static final int VERSIONES_ESPERADAS = 9;

    /** Las seis columnas de la ficha, con su tipo declarado en la V8. */
    private static final List<String> COLUMNAS_DE_LA_FICHA = List.of(
            "curp", "ocupacion", "domicilio", "nombre_responsable", "telefono_responsable", "parentesco");

    /** CURP canónica de la fixture. */
    private static final String CURP = "SASC850101MDFRRN01";

    private HikariDataSource fuente;
    private Connection conexion;

    @BeforeEach
    void prepararBaseLimpia() throws SQLException {
        String nombre = "v9_" + UUID.randomUUID().toString().replace("-", "");
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
    @DisplayName("la V9 es la novena migración y la ficha del paciente sigue existiendo y nulable (S1)")
    void laSerieCompletaAplicaYLaFichaEsNulable() throws SQLException {
        var resultado = migrar();

        assertThat(resultado.migrationsExecuted)
                .as("la V9 se suma a la serie: modificar la V8 habría roto su suma de verificación "
                        + "en cualquier base donde ya corrió")
                .isEqualTo(VERSIONES_ESPERADAS);
        assertThat(columnasDeLaFicha())
                .as("la ficha se guarda en la tabla del paciente, no en una tabla aparte")
                .containsExactlyInAnyOrderElementsOf(COLUMNAS_DE_LA_FICHA);
        assertThat(COLUMNAS_DE_LA_FICHA)
                .allSatisfy(columna -> assertThat(nulabilidadDe(columna))
                        .as("«%s» es opcional: un paciente puede registrarse sin ese dato", columna)
                        .isEqualTo("YES"));
    }

    @Test
    @DisplayName("la V9 declara el CHECK de mayúsculas como restricción con nombre propio")
    void laV9DeclaraElCheckDeMayusculas() throws SQLException {
        migrar();

        assertThat(tipoDeRestriccion(RESTRICCION_CURP_MAYUSCULAS))
                .as("el nombre es el que una prueba y un operador pueden citar al diagnosticar")
                .isEqualTo("CHECK");
        assertThat(restriccionCorrespondiente(RESTRICCION_CURP_MAYUSCULAS)).isTrue();
        assertThat(tipoDeRestriccion(RESTRICCION_CURP_UNICA)).isEqualTo("UNIQUE");
    }

    @Test
    @DisplayName("rechaza la CURP en minúsculas cargada por SQL directo, junto a la canónica (S3)")
    void laCurpEnMinusculasPorSqlDirectoEsRechazada() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Canónico", CURP));

        var error = catchThrowable(() -> ejecutar(
                insertarPaciente(organizacion, "Paciente En Minúsculas", CURP.toLowerCase())));

        assertThat(error)
                .as("antes de la V9 esta fila entraba: la unicidad comparaba cadenas")
                .isInstanceOf(SQLException.class);
        assertThat(codigoSql(error)).isEqualTo("23513");
        assertThat(cadenaDeMensajes(error)).containsIgnoringCase(RESTRICCION_CURP_MAYUSCULAS);
        assertThat(curpsDe(organizacion))
                .as("la fila rechazada no deja rastro: la base queda como estaba")
                .containsExactly(CURP);
    }

    @Test
    @DisplayName("la CURP canónica sí se acepta con el CHECK puesto")
    void laCurpCanonicaSeAcepta() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();

        assertThatCode(() -> ejecutar(insertarPaciente(organizacion, "Paciente Canónico", CURP)))
                .doesNotThrowAnyException();
        assertThat(curpsDe(organizacion)).containsExactly(CURP);
    }

    @Test
    @DisplayName("la CURP repetida en la misma organización sigue dando 23505 con su nombre (S2)")
    void laCurpRepetidaEnLaOrganizacionEsRechazada() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Primero", CURP));

        var error = catchThrowable(() -> ejecutar(
                insertarPaciente(organizacion, "Paciente Repetido", CURP)));

        assertThat(codigoSql(error)).isEqualTo("23505");
        assertThat(cadenaDeMensajes(error)).containsIgnoringCase(RESTRICCION_CURP_UNICA);
    }

    @Test
    @DisplayName("la misma CURP en otra organización sigue aceptándose: son dos clínicas (S2)")
    void laMismaCurpEnOtraOrganizacionEsAceptada() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        String otraOrganizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Primero", CURP));

        assertThatCode(() -> ejecutar(
                insertarPaciente(otraOrganizacion, "Paciente Segundo", CURP)))
                .as("la unicidad es por organización, nunca entre organizaciones")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dos pacientes sin CURP conviven en la misma organización")
    void dosPacientesSinCurpConvivenEnLaOrganizacion() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();

        assertThatCode(() -> {
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Uno", null));
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Dos", null));
        })
                .as("los nulos no colisionan en una restricción única: registrar sin CURP es normal")
                .doesNotThrowAnyException();

        assertThat(curpsDe(organizacion)).containsExactly(null, null);
    }

    @Test
    @DisplayName("el CHECK no estorba a los nulos: conviven con una fila de CURP canónica")
    void losNulosSiguenPasandoElCheck() throws SQLException {
        migrar();
        String organizacion = crearOrganizacion();
        ejecutar(insertarPaciente(organizacion, "Paciente Con Curp", CURP));

        assertThatCode(() -> {
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Uno", null));
            ejecutar(insertarPaciente(organizacion, "Paciente Sin Curp Dos", null));
        })
                .as("una comparación con nulo nunca es falsa: el CHECK no puede estorbar al alta sin CURP")
                .doesNotThrowAnyException();
        assertThat(curpsDe(organizacion)).containsExactlyInAnyOrder(CURP, null, null);
    }

    // ─────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────

    /**
     * Aplica todas las migraciones, hasta la última.
     *
     * @return el resultado del {@code migrate()}, con el número de versiones aplicadas
     */
    private MigrateResult migrar() {
        return Flyway.configure()
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
     * @return el tipo declarado ({@code UNIQUE}, {@code CHECK}, ...) o {@code null}
     * @throws SQLException si la consulta falla
     */
    private String tipoDeRestriccion(String restriccion) throws SQLException {
        var valores = valoresDe("SELECT constraint_type FROM information_schema.table_constraints"
                + " WHERE table_name = 'paciente' AND constraint_name = '" + restriccion + "'");
        return valores.isEmpty() ? null : valores.get(0);
    }

    /**
     * Indica si la restricción aparece en el catálogo de {@code CHECK} del esquema.
     *
     * @param restriccion nombre de la restricción
     * @return {@code true} si el catálogo la lista entre las restricciones de comprobación
     * @throws SQLException si la consulta falla
     */
    private boolean restriccionCorrespondiente(String restriccion) throws SQLException {
        return !valoresDe("SELECT constraint_name FROM information_schema.check_constraints"
                + " WHERE constraint_name = '" + restriccion + "'").isEmpty();
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
     * CURP de todos los pacientes de una organización, en el orden de la tabla.
     *
     * @param organizacionId organización consultada
     * @return las CURP almacenadas, con {@code null} donde no se registró
     * @throws SQLException si la consulta falla
     */
    private List<String> curpsDe(String organizacionId) throws SQLException {
        return valoresDe("SELECT curp FROM paciente WHERE organizacion_id = '" + organizacionId + "'");
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
    private static String codigoSql(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Concatena los mensajes de toda la cadena de causas, que es donde viaja el nombre. */
    private static String cadenaDeMensajes(Throwable error) {
        var cadena = new StringBuilder();
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            cadena.append(actual.getMessage()).append(" || ");
            if (actual.getCause() == actual) {
                break;
            }
        }
        return cadena.toString();
    }
}
