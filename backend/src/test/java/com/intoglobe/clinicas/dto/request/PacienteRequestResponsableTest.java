package com.intoglobe.clinicas.dto.request;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla de dos campos de la ficha (S5): un parentesco exige el nombre del responsable.
 *
 * <p>Ninguna de las dos reglas de campo individual detecta el caso que la origina: `parentesco`
 * con un valor válido y `nombreResponsable` vacío guarda «qué es esa persona del paciente» sin
 * decir <em>quién</em> es. Por eso la regla es de clase y no de campo.</p>
 *
 * <p>La otra mitad importa igual: los dos campos vacíos son lo normal —un paciente que llega solo
 * al mostrador— y no pueden empezar a rechazarse. Tampoco se exige el camino inverso: un
 * responsable sin parentesco describe un caso real y sigue permitido.</p>
 *
 * <p>La validación se observa por su efecto público —las violaciones que reporta el validador de
 * Jakarta— y nunca por el valor interno del DTO.</p>
 */
class PacienteRequestResponsableTest {

    /** Nombre de la propiedad de clase que reporta la regla cruzada. */
    private static final String PROPIEDAD = "responsableInformadoCuandoHayParentesco";

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    private static PacienteRequest con(String nombreResponsable, String parentesco) {
        return new PacienteRequest("María López Ramírez", null, LocalDate.of(1985, 3, 14), "F",
                null, null, null, null, null, nombreResponsable, null, parentesco);
    }

    @Test
    @DisplayName("un parentesco sin nombre del responsable no se puede guardar (S5)")
    void parentescoSinResponsableSeRechaza() {
        for (String parentesco : new String[] {"Madre", "amigo", "Vecino"}) {
            var violaciones = validador.validate(con(null, parentesco));

            assertThat(violaciones)
                    .as("parentesco «%s» sin responsable: nada dice quién es esa persona", parentesco)
                    .anyMatch(violacion -> violacion.getPropertyPath().toString().equals(PROPIEDAD)
                            && violacion.getMessage().equals(
                                    "Si se registra un parentesco, el nombre del responsable es obligatorio."));
        }
    }

    @Test
    @DisplayName("un parentesco con responsable en blanco tampoco se guarda (S5)")
    void parentescoConResponsableEnBlancoSeRechaza() {
        // El blanco no es un nombre: `TEXTO_SIN_DIGITOS_OPCIONAL` lo deja pasar porque el campo es
        // opcional, así que es esta regla la que tiene que distinguir «vacío» de «escrito».
        assertThat(validador.validate(con("   ", "Madre")))
                .anyMatch(violacion -> violacion.getPropertyPath().toString().equals(PROPIEDAD));
    }

    @Test
    @DisplayName("sin ninguno de los dos campos el paciente se guarda igual (S5)")
    void sinParentescoNiResponsableSeAcepta() {
        assertThat(validador.validate(con(null, null))).isEmpty();
        assertThat(validador.validate(con("", ""))).isEmpty();
        assertThat(validador.validate(con("   ", "   "))).isEmpty();
    }

    @Test
    @DisplayName("con parentesco y responsable completos el paciente se guarda (S5)")
    void conParentescoYResponsableSeAcepta() {
        assertThat(validador.validate(con("Rosa Ramírez Silva", "Madre"))).isEmpty();
    }

    @Test
    @DisplayName("un responsable sin parentesco sigue permitido: la regla va en una sola dirección (S5)")
    void responsableSinParentescoSeAcepta() {
        assertThat(validador.validate(con("Rosa Ramírez Silva", null))).isEmpty();
    }
}
