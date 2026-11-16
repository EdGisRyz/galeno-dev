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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cierre que la V7 hace sobre lo que la V6 dejó abierto: {@code organizacion_id} y
 * {@code consultorio_id} de {@code horario_medico} pasan a ser obligatorias.
 *
 * <p>La V6 las dejó nulables porque la entidad todavía no las mapeaba y el código insertaba
 * bloques sin consultorio; esta migración las exige cuando el código ya las escribe siempre. La
 * suite de la aplicación solo inserta filas correctas, así que no ejerce el rechazo: por eso la
 * comprobación vive aquí, donde la base se monta igual que en {@link MigracionV3Test} —con un
 * pool, porque H2 ata las restricciones a la sesión que las creó y Flyway cierra la conexión que
 * usa cuando no hay pool—.</p>
 */
class MigracionV7Test {

    private static final String PLANTILLA_URL =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private HikariDataSource fuente;
    private Connection conexion;

    @BeforeEach
    void prepararBaseLimpia() throws SQLException {
        String nombre = "v7_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(String.format(PLANTILLA_URL, nombre));
        configuracion.setUsername("sa");
        configuracion.setPassword("");
        // La prueba sostiene su propia conexión mientras Flyway abre las suyas para migrar a la
        // V6, sembrar el caso intermedio y volver a migrar: una conexión más que la de las
        // pruebas que migran de una sola vez.
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
    @DisplayName("rechaza un bloque sin consultorio: la columna es obligatoria desde la V7")
    void bloqueSinConsultorioEsRechazado() throws SQLException {
        migrar(null);
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);

        assertThatThrownBy(() -> ejecutar(insertarHorario(medico, null, null)))
                .as("sin consultorio el bloque no dice dónde se dicta: la V7 lo cierra")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23502"));
    }

    @Test
    @DisplayName("rechaza un bloque con consultorio pero sin organización: las dos columnas son obligatorias")
    void bloqueSinOrganizacionEsRechazado() throws SQLException {
        migrar(null);
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);

        assertThatThrownBy(() -> ejecutar(insertarHorario(medico, consultorioDelMedico(medico), null)))
                .as("la organización es la mitad de las dos claves compuestas de la V6")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23502"));
    }

    @Test
    @DisplayName("acepta un bloque en un consultorio asignado al médico, con la organización del médico")
    void bloqueConConsultorioAsignadoEsAceptado() throws SQLException {
        migrar(null);
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);

        assertThatCode(() -> ejecutar(insertarHorario(medico, consultorioDelMedico(medico), organizacion)))
                .as("con el par asignado y la organización del médico, el bloque es válido")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rechaza un bloque en un consultorio que no está asignado al médico")
    void bloqueEnConsultorioNoAsignadoEsRechazado() throws SQLException {
        migrar(null);
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);
        String otroConsultorio = crearConsultorio(organizacion);

        assertThatThrownBy(() -> ejecutar(insertarHorario(medico, otroConsultorio, organizacion)))
                .as("la regla «primero asignar, después agendar» también vive en la base")
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThatSqlState(error).isEqualTo("23506"));
    }

    /**
     * La reposición de la V7 no puede mover un bloque que ya tenía consultorio: si solo falta la
     * organización, se repone esa columna y el consultorio se respeta. Es la diferencia entre
     * «completar lo que falta» y «devolver todo al principal», y en una agenda con un día en otro
     * consultorio esa diferencia es un cambio de datos silencioso.
     */
    @Test
    @DisplayName("la V7 repone la organización sin mover de consultorio un bloque que ya lo tenía")
    void bloqueSinOrganizacionConservaSuConsultorio() throws SQLException {
        migrar("6");
        String organizacion = crearOrganizacion();
        String medico = crearMedico(organizacion);
        String principal = consultorioDelMedico(medico);
        String secundario = crearConsultorio(organizacion);
        asignar(medico, secundario, organizacion);
        // Estado intermedio real: el bloque ya está en el consultorio secundario, pero se escribió
        // cuando el servicio todavía no escribía la organización.
        ejecutar("INSERT INTO horario_medico (medico_id, organizacion_id, consultorio_id, dia_semana,"
                + " hora_inicio, hora_fin, duracion_consulta) VALUES ('" + medico + "', NULL, '"
                + secundario + "', 4, '15:00:00', '19:00:00', 30)");

        migrar(null);

        assertThat(consultorioDelBloque(medico))
                .as("la V7 no puede devolver al principal un bloque que ya se había movido")
                .isEqualTo(secundario)
                .isNotEqualTo(principal);
        assertThat(organizacionDelBloque(medico))
                .as("la organización que faltaba sí se repone, tomada del médico")
                .isEqualTo(organizacion);
    }

    // ─────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────

    /**
     * Aplica las migraciones hasta la versión indicada, o hasta la última si es {@code null}.
     *
     * <p>Las pruebas que necesitan un estado intermedio migran primero a la V6, siembran el caso y
     * recién entonces aplican la V7.</p>
     *
     * @param version versión destino, por ejemplo {@code 6}; {@code null} para la última
     */
    private void migrar(String version) {
        var configuracion = Flyway.configure()
                .dataSource(fuente)
                .locations("classpath:db/migration");
        if (version != null) {
            configuracion = configuracion.target(version);
        }
        configuracion.load().migrate();
    }

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

    /**
     * Crea un médico con su consultorio principal ya asignado, como lo deja el alta del médico.
     *
     * @param organizacionId organización dueña del médico
     * @return el identificador del médico creado
     */
    private String crearMedico(String organizacionId) throws SQLException {
        String id = UUID.randomUUID().toString();
        String consultorio = crearConsultorio(organizacionId);
        ejecutar("INSERT INTO medico (id, organizacion_id, consultorio_id, nombre) VALUES ('"
                + id + "', '" + organizacionId + "', '" + consultorio + "', 'Médico de prueba')");
        asignar(id, consultorio, organizacionId);
        return id;
    }

    private String consultorioDelMedico(String medicoId) throws SQLException {
        return valorDe("SELECT consultorio_id FROM medico WHERE id = '" + medicoId + "'");
    }

    /**
     * Consultorio del único bloque de horario del médico.
     *
     * @param medicoId médico dueño del bloque
     * @return el identificador del consultorio del bloque
     * @throws SQLException si la consulta falla
     */
    private String consultorioDelBloque(String medicoId) throws SQLException {
        return valorDe("SELECT consultorio_id FROM horario_medico WHERE medico_id = '" + medicoId + "'");
    }

    /**
     * Organización del único bloque de horario del médico.
     *
     * @param medicoId médico dueño del bloque
     * @return el identificador de la organización del bloque
     * @throws SQLException si la consulta falla
     */
    private String organizacionDelBloque(String medicoId) throws SQLException {
        return valorDe("SELECT organizacion_id FROM horario_medico WHERE medico_id = '" + medicoId + "'");
    }

    /**
     * Asigna un consultorio al médico, con la organización del médico.
     *
     * @param medicoId       médico al que se asigna
     * @param consultorioId  consultorio asignado
     * @param organizacionId organización dueña de los dos
     * @throws SQLException si la escritura falla
     */
    private void asignar(String medicoId, String consultorioId, String organizacionId) throws SQLException {
        ejecutar("INSERT INTO medico_consultorio (medico_id, consultorio_id, organizacion_id) VALUES ('"
                + medicoId + "', '" + consultorioId + "', '" + organizacionId + "')");
    }

    /**
     * Lee una única columna de una única fila.
     *
     * @param sql consulta que devuelve una fila y una columna
     * @return el valor leído
     * @throws SQLException si la consulta falla
     */
    private String valorDe(String sql) throws SQLException {
        try (Statement sentencia = conexion.createStatement();
                var filas = sentencia.executeQuery(sql)) {
            filas.next();
            return filas.getString(1);
        }
    }

    private String insertarHorario(String medicoId, String consultorioId, String organizacionId) {
        return "INSERT INTO horario_medico (medico_id, organizacion_id, consultorio_id, dia_semana,"
                + " hora_inicio, hora_fin, duracion_consulta) VALUES ('"
                + medicoId + "', "
                + (organizacionId == null ? "NULL" : "'" + organizacionId + "'") + ", "
                + (consultorioId == null ? "NULL" : "'" + consultorioId + "'")
                + ", 1, '09:00:00', '10:00:00', 30)";
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
