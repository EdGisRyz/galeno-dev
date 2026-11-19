package com.intoglobe.clinicas.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Deriva el {@code slug} de una organización a partir de su nombre.
 *
 * <p>El slug es la clave con la que el servicio de identidad identifica a la clínica en el claim
 * {@code tenantId}; la columna {@code organizacion.slug} es {@code VARCHAR(60)} y única en toda la
 * plataforma ({@code organizacion_slug_key}, migración V2). Por eso el texto que sale de aquí tiene
 * que ser: minúsculas, sin acentos, sin espacios y sin nada que una URL o un claim no admitan.</p>
 *
 * <p><strong>Esta clase solo compone el texto.</strong> Que el slug esté libre lo decide
 * {@link OrganizacionService}, que consulta la tabla, y la última palabra la tiene la restricción
 * única de la base. La separación importa: aquí no hay repositorio y por eso la regla de forma se
 * puede probar sin contexto de Spring.</p>
 *
 * <p><strong>Los acentos se descomponen, no se borran.</strong> {@link Normalizer.Form#NFKD} separa
 * cada letra acentuada en su letra base más una marca combinada, y las marcas se descartan: así
 * {@code Clínica} produce {@code clinica} y no {@code clnica}, que es lo que daría eliminar los
 * caracteres no ASCII a secas.</p>
 */
public final class GeneradorDeSlug {

    /** Longitud de {@code organizacion.slug} en la migración V2. */
    public static final int LONGITUD_MAXIMA = 60;

    /** Slug cuando el nombre no aporta ninguna letra latina ni dígito. */
    private static final String SLUG_POR_DEFECTO = "clinica";

    private GeneradorDeSlug() {
    }

    /**
     * Compone el slug base de un nombre.
     *
     * @param nombre nombre de la organización, tal como llegó del cliente; puede ser {@code null}
     * @return slug en minúsculas, sin acentos, con guiones simples y de longitud 1..60
     */
    public static String aPartirDe(String nombre) {
        String sinAcentos = Normalizer.normalize(nombre == null ? "" : nombre, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String slug = sinAcentos
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return SLUG_POR_DEFECTO;
        }
        return recortar(slug, LONGITUD_MAXIMA);
    }

    /**
     * Compone la variante con sufijo de un slug base, para cuando el base ya está tomado.
     *
     * <p>El sufijo se reserva <strong>antes</strong> de recortar el base, de modo que
     * {@code base-12} quepa completo en los 60 caracteres y el sufijo nunca se pierda: si se
     * recortara después, dos clínicas con un nombre muy largo colisionarían justo en el texto que
     * las distingue.</p>
     *
     * @param base   slug base devuelto por {@link #aPartirDe(String)}
     * @param numero número de la variante; a partir de 2
     * @return slug base con {@code -numero} al final, de longitud 1..60
     */
    public static String conSufijo(String base, int numero) {
        String sufijo = "-" + numero;
        String recortado = recortar(base, LONGITUD_MAXIMA - sufijo.length());
        return recortado + sufijo;
    }

    /**
     * Recorta a un máximo sin dejar un guion colgando al final.
     *
     * @param texto  texto a recortar
     * @param maximo longitud máxima permitida
     * @return el texto, recortado si hacía falta
     */
    private static String recortar(String texto, int maximo) {
        if (texto.length() <= maximo) {
            return texto;
        }
        return texto.substring(0, maximo).replaceAll("-+$", "");
    }
}
