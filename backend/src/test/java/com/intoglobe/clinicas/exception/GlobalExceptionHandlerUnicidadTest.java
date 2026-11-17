package com.intoglobe.clinicas.exception;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La traducción de los errores de unicidad por organización en
 * {@link GlobalExceptionHandler}: correo de una cuenta y CURP de un paciente (S5).
 *
 * <p>La rama que atiende {@code DataIntegrityViolationException} es la única de todo el traductor, y
 * su discriminación depende del <em>texto</em> del error: reconoce el nombre de la restricción en la
 * cadena de causas, porque el nombre viaja en el mensaje de las dos bases que usa el proyecto
 * —PostgreSQL y H2 en modo PostgreSQL—. Por eso esta prueba usa los mensajes reales de las dos y
 * comprueba el otro lado de la regla: cualquier otra violación de integridad se relanza intacta y no
 * se convierte en un 409 de negocio.</p>
 *
 * <p>Se observa la respuesta pública del traductor —estado y mensaje del cuerpo— y, en el caso de
 * las violaciones ajenas, la identidad de la excepción relanzada.</p>
 */
class GlobalExceptionHandlerUnicidadTest {

    private final GlobalExceptionHandler manejador = new GlobalExceptionHandler();

    private final HttpServletRequest peticion = mock(HttpServletRequest.class);

    {
        when(peticion.getRequestURI()).thenReturn("/api/v1/pacientes");
    }

    /** Error de integridad con el mensaje que pondría el proveedor de persistencia. */
    private static DataIntegrityViolationException violacion(String detalle) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(detalle));
    }

    @Test
    @DisplayName("la CURP repetida responde 409 con su mensaje, en las dos bases (S5)")
    void laCurpResponde409ConSuMensaje() {
        for (String mensaje : new String[] {
                "Unique index or primary key violation: \"public.paciente_organizacion_id_curp_key INDEX …\"",
                "duplicate key value violates unique constraint \"paciente_organizacion_id_curp_key\""}) {
            var respuesta = manejador.manejarUnicidadDuplicadaEnCarrera(violacion(mensaje), peticion);

            assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
            assertThat(respuesta.getBody().mensaje())
                    .as("el mensaje nombra el motivo: qué chocó y por qué el mismo dato sí puede "
                            + "existir en otra organización")
                    .contains("CURP")
                    .contains("organización");
        }
    }

    @Test
    @DisplayName("el correo de una cuenta conserva su mensaje propio")
    void elCorreoConservaSuMensaje() {
        var respuesta = manejador.manejarUnicidadDuplicadaEnCarrera(
                violacion("duplicate key value violates unique constraint "
                        + "\"usuario_organizacion_id_email_key\""),
                peticion);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
        assertThat(respuesta.getBody().mensaje()).isEqualTo(
                "Ya existe una cuenta con ese correo en esta organización. El correo es único dentro "
                        + "de la organización, aunque el mismo correo pueda tener cuentas independientes "
                        + "en otras organizaciones");
    }

    @Test
    @DisplayName("cualquier otra violación de integridad se relanza intacta")
    void otraViolacionSeRelanzaIntacta() {
        for (String detalle : new String[] {
                "violates foreign key constraint \"cita_paciente_id_fkey\"",
                "unique constraint \"usuario_auth_user_id_key\"",
                "violates check constraint \"paciente_sexo_check\""}) {
            var excepcion = violacion(detalle);

            assertThatThrownBy(() -> manejador.manejarUnicidadDuplicadaEnCarrera(excepcion, peticion))
                    .as("traducirla en bloque a 409 convertiría en conflicto de negocio cualquier "
                            + "fallo de integridad de cualquier módulo")
                    .isSameAs(excepcion);
        }
    }
}
