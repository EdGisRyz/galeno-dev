package com.intoglobe.clinicas.security;

import java.util.Collection;
import java.util.Locale;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Detector del reto {@code Basic} en una respuesta, para las pruebas de contrato de
 * {@code 401}.
 *
 * <p>Afirmar que la cabecera {@code WWW-Authenticate} no existe no demuestra nada: el
 * cliente puede recibir un reto {@code Bearer} legítimo y la aserción pasaría igual. Lo que
 * el contrato prohíbe es ofrecer el esquema {@code Basic}, porque este backend no tiene
 * contraseñas ni {@code UserDetailsService}. Por eso se examinan los valores que sí existen
 * y se busca un reto cuyo esquema sea {@code Basic}.</p>
 *
 * <p>El detector se prueba a sí mismo con un control positivo: contra la salida real de
 * {@code BasicAuthenticationEntryPoint} de Spring Security tiene que dar {@code true}.</p>
 */
final class RetosDeAutenticacion {

    private RetosDeAutenticacion() {
    }

    /**
     * Indica si la respuesta ofrece un reto {@code Basic}.
     *
     * @param respuesta respuesta HTTP a inspeccionar
     * @return {@code true} si alguna cabecera {@code WWW-Authenticate} anuncia el esquema Basic
     */
    static boolean ofreceRetoBasic(HttpServletResponse respuesta) {
        return ofreceRetoBasic(respuesta.getHeaders("WWW-Authenticate"));
    }

    /**
     * Indica si alguno de los valores de {@code WWW-Authenticate} anuncia el esquema Basic.
     *
     * <p>Un valor puede contener varios retos separados por comas y cada reto empieza por su
     * esquema; se compara el esquema, no una subcadena cualquiera, para no confundir un
     * {@code Bearer realm="Basic zone"} con un reto Basic real.</p>
     *
     * @param valores valores de la cabecera {@code WWW-Authenticate}
     * @return {@code true} si algún reto usa el esquema Basic
     */
    static boolean ofreceRetoBasic(Collection<String> valores) {
        if (valores == null) {
            return false;
        }
        for (String valor : valores) {
            if (valor == null) {
                continue;
            }
            for (String reto : valor.split(",")) {
                String[] partes = reto.trim().split("\\s+");
                if (partes.length > 0 && "basic".equals(partes[0].toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
