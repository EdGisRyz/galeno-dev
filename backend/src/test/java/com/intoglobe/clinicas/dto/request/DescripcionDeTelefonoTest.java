package com.intoglobe.clinicas.dto.request;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.intoglobe.clinicas.validation.PatronesDeFormato;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La descripción de la API del teléfono enumera lo mismo que la regla (S7).
 *
 * <p>El mensaje de la pantalla y las notas de Swagger son dos textos distintos que describen la
 * misma expresión regular. Cuando uno enumera menos separadores de los que la regla acepta
 * —`espacios y guiones`, sin paréntesis—, la documentación de la API miente igual que el mensaje
 * de la pantalla: quien integra contra el servicio descarta un dato válido antes de enviarlo.</p>
 *
 * <p>La prueba no comprueba un texto escrito a mano: encuentra los campos que están validados con
 * {@link PatronesDeFormato#TELEFONO} y exige que su {@link Schema} nombre los paréntesis. Un campo
 * de teléfono nuevo nace obligado a documentarse completo, sin que nadie tenga que recordarlo.</p>
 */
class DescripcionDeTelefonoTest {

    /** Los tres cuerpos con teléfono. */
    private static final List<Class<?>> CUERPOS = List.of(
            PacienteRequest.class, MedicoRequest.class, ConsultorioRequest.class);

    @Test
    @DisplayName("todo campo validado como teléfono se documenta con paréntesis (S7)")
    void todoTelefonoSeDocumentaConParentesis() {
        List<String> documentados = new ArrayList<>();

        for (Class<?> cuerpo : CUERPOS) {
            for (String campo : camposDeTelefono(cuerpo)) {
                documentados.add(cuerpo.getSimpleName() + "." + campo);

                assertThat(descripcionDe(cuerpo, campo))
                        .as("%s.%s está validado con PatronesDeFormato.TELEFONO, que acepta paréntesis, "
                                + "así que su descripción tiene que enumerarlos", cuerpo.getSimpleName(), campo)
                        .contains("paréntesis");
            }
        }

        // Guarda de la propia prueba: si la reflexión no encontrara ningún teléfono, la aserción de
        // arriba pasaría sin haber leído nada.
        assertThat(documentados)
                .as("los tres cuerpos tienen al menos un teléfono documentado")
                .containsExactlyInAnyOrder(
                        "PacienteRequest.telefono",
                        "PacienteRequest.telefonoResponsable",
                        "MedicoRequest.telefono",
                        "ConsultorioRequest.telefono");
    }

    /** Campos del cuerpo validados con el patrón compartido de teléfono. */
    private static List<String> camposDeTelefono(Class<?> cuerpo) {
        List<String> campos = new ArrayList<>();
        for (Field campo : cuerpo.getDeclaredFields()) {
            if (esPatronDeTelefono(anotaciones(campo))) {
                campos.add(campo.getName());
            }
        }
        return campos;
    }

    private static boolean esPatronDeTelefono(List<Annotation> anotaciones) {
        return anotaciones.stream()
                .anyMatch(anotacion -> anotacion instanceof Pattern patron
                        && PatronesDeFormato.TELEFONO.equals(patron.regexp()));
    }

    /** Anotaciones del campo y de su accesor: un `record` reparte las anotaciones entre los dos. */
    private static List<Annotation> anotaciones(Field campo) {
        List<Annotation> anotaciones = new ArrayList<>(List.of(campo.getAnnotations()));
        try {
            Method accesor = campo.getDeclaringClass().getDeclaredMethod(campo.getName());
            anotaciones.addAll(List.of(accesor.getAnnotations()));
        } catch (NoSuchMethodException excepcion) {
            // No es un `record`: con las del campo alcanza.
        }
        return anotaciones;
    }

    private static String descripcionDe(Class<?> cuerpo, String nombre) {
        for (Annotation anotacion : anotaciones(campoDe(cuerpo, nombre))) {
            if (anotacion instanceof Schema schema) {
                return schema.description();
            }
        }
        return "";
    }

    private static Field campoDe(Class<?> cuerpo, String nombre) {
        try {
            return cuerpo.getDeclaredField(nombre);
        } catch (NoSuchFieldException excepcion) {
            throw new AssertionError("El campo " + nombre + " no existe en " + cuerpo.getSimpleName(), excepcion);
        }
    }
}
