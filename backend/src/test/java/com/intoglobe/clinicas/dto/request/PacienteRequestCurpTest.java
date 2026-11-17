package com.intoglobe.clinicas.dto.request;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El borde de la CURP en {@link PacienteRequest}: opcional, canónica y estructuralmente validada
 * (S3 y S4).
 *
 * <p>Comprueba las dos mitades de la regla en el punto donde el cliente la encuentra: una CURP
 * ausente, vacía o en blanco es «no se registró» y no produce error; una CURP presente se canoniza
 * antes de validarse, así que las minúsculas se aceptan; y solo se rechaza lo que no puede ser una
 * CURP por su forma. El dígito verificador no se comprueba a propósito: eso es del registro
 * nacional y queda fuera de alcance.</p>
 *
 * <p>La validación se observa por su efecto público —el conjunto de violaciones que reporta el
 * validador de Jakarta— y nunca por el valor interno del DTO.</p>
 */
class PacienteRequestCurpTest {

    private final jakarta.validation.Validator validador =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static PacienteRequest con(String curp) {
        return new PacienteRequest("María López Ramírez", null, LocalDate.of(1985, 3, 14), "F",
                null, null, curp, null, null, null, null, null);
    }

    /** Indica si la petición viola alguna restricción declarada sobre el campo {@code curp}. */
    private boolean invalida(PacienteRequest peticion) {
        return validador.validate(peticion).stream()
                .anyMatch(violacion -> violacion.getPropertyPath().toString().equals("curp"));
    }

    @Test
    @DisplayName("la CURP es opcional: nula, vacía o en blanco no es un error (S2)")
    void opcionalNuloVacioYEnBlanco() {
        assertThat(invalida(con(null))).isFalse();
        assertThat(con("").curp()).isNull();
        assertThat(invalida(con(""))).isFalse();
        assertThat(con("   ").curp()).isNull();
        assertThat(invalida(con("   "))).isFalse();
    }

    @Test
    @DisplayName("las minúsculas y los espacios se canonizan antes de validar (S3)")
    void minusculasConEspaciosSeCanonizan() {
        assertThat(con("  sasc850101mdfrrn01 ").curp()).isEqualTo("SASC850101MDFRRN01");
        assertThat(invalida(con("sasc850101mdfrrn01"))).isFalse();
    }

    @Test
    @DisplayName("una estructura que no puede ser una CURP se rechaza con 400 (S4)")
    void estructuraInvalidaSeRechaza() {
        assertThat(invalida(con("SASC850101MDFRRN"))).isTrue();
        assertThat(invalida(con("SASC850101MDFRRN011"))).isTrue();
        assertThat(invalida(con("SASC850101XDFRRN01"))).isTrue();
        assertThat(invalida(con("SASC85010AMDFRRN01"))).isTrue();
        assertThat(invalida(con("SASC850101MDFRRN0!"))).isTrue();
        assertThat(invalida(con("SASC8501"))).isTrue();
    }

    @Test
    @DisplayName("el dígito verificador no se comprueba: la validación es estructural (S4)")
    void elDigitoVerificadorNoSeComprueba() {
        assertThat(invalida(con("SASC850101MDFRRN09"))).isFalse();
    }
}
