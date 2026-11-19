package com.intoglobe.clinicas.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El slug de una organización derivado de su nombre (S4).
 *
 * <p>Es la clave con la que el servicio de identidad identifica a la clínica, así que tiene que ser
 * un texto estable, en minúsculas y sin nada que una URL o un claim no admitan: sin acentos, sin
 * espacios y sin signos. Dos clínicas pueden llamarse igual, y por eso el generador también sabe
 * componer la variante con sufijo sin pasarse nunca de los 60 caracteres de la columna.</p>
 *
 * <p>Prueba unitaria pura: no hay base de datos ni Spring. La búsqueda de un slug libre —la que
 * consulta la tabla— vive en {@code OrganizacionService} y se ejercita en las pruebas de la API.</p>
 */
class GeneradorDeSlugTest {

    @Test
    @DisplayName("deriva un slug en minúsculas, sin acentos ni espacios")
    void derivaMinusculasSinAcentosNiEspacios() {
        assertThat(GeneradorDeSlug.aPartirDe("Clínica San Rafael")).isEqualTo("clinica-san-rafael");
        assertThat(GeneradorDeSlug.aPartirDe("CONSULTORIO MÉDICO ÁNGEL")).isEqualTo("consultorio-medico-angel");
    }

    @Test
    @DisplayName("admite dígitos y colapsa los separadores en un solo guion")
    void admiteDigitosYColapsaSeparadores() {
        assertThat(GeneradorDeSlug.aPartirDe("  Clínica   2  "))
                .as("los espacios de los extremos y los repetidos no llegan al slug")
                .isEqualTo("clinica-2");
        assertThat(GeneradorDeSlug.aPartirDe("Clínica del Valle - Sede 3"))
                .isEqualTo("clinica-del-valle-sede-3");
        assertThat(GeneradorDeSlug.aPartirDe("A.B.C."))
                .as("los puntos y el guion final no quedan pegados al slug")
                .isEqualTo("a-b-c");
    }

    @Test
    @DisplayName("cae en un slug utilizable cuando el nombre no aporta letras latinas")
    void caeEnUnSlugUtilizableCuandoNoHayLetrasLatinas() {
        assertThat(GeneradorDeSlug.aPartirDe("日本語")).isEqualTo("clinica");
        assertThat(GeneradorDeSlug.aPartirDe("---")).isEqualTo("clinica");
        assertThat(GeneradorDeSlug.aPartirDe(null)).isEqualTo("clinica");
    }

    @Test
    @DisplayName("nunca supera los 60 caracteres de la columna")
    void nuncaSuperaLaLongitudDeLaColumna() {
        String largo = "Clínica ".repeat(30);

        String slug = GeneradorDeSlug.aPartirDe(largo);

        assertThat(slug).hasSize(GeneradorDeSlug.LONGITUD_MAXIMA);
        assertThat(slug).doesNotEndWith("-");
    }

    @Test
    @DisplayName("el sufijo distingue dos clínicas con el mismo nombre sin romper el máximo")
    void elSufijoDistingueSinRomperElMaximo() {
        assertThat(GeneradorDeSlug.conSufijo("clinica-san-rafael", 2)).isEqualTo("clinica-san-rafael-2");
        assertThat(GeneradorDeSlug.conSufijo("clinica-san-rafael", 12)).isEqualTo("clinica-san-rafael-12");

        String baseLarga = "c".repeat(GeneradorDeSlug.LONGITUD_MAXIMA);
        String conSufijo = GeneradorDeSlug.conSufijo(baseLarga, 2);

        assertThat(conSufijo).hasSize(GeneradorDeSlug.LONGITUD_MAXIMA);
        assertThat(conSufijo).endsWith("-2");
        assertThat(conSufijo).doesNotContain("--");
    }
}
