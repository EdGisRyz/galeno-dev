package com.intoglobe.clinicas.validation;

/**
 * Patrones de formato compartidos por los DTO de entrada de la API.
 *
 * <p>Son las reglas de <em>forma</em> de los datos que el usuario teclea en los formularios:
 * «texto sin dígitos» y «teléfono con formato». Viven en un solo sitio porque las usan varios
 * módulos —médico, paciente y consultorio— y una copia por módulo es una oportunidad de que una
 * acepte lo que otra rechaza, sin que nadie sepa cuál manda. Aquí hay <strong>una</strong>
 * definición por regla; los DTO las referencian y no escriben expresiones propias.</p>
 *
 * <p>Son {@code public static final String} porque {@code @Pattern(regexp = …)} exige una
 * constante de compilación: un método o un {@code static final Pattern} no sirven como valor de la
 * anotación.</p>
 *
 * <p><strong>Espejo en el frontend.</strong> La pantalla valida antes de enviar con las mismas
 * reglas, escritas otra vez porque son otro lenguaje. La copia del cliente está en
 * {@code frontend/src/lib/patronesDeFormato.ts} y este archivo es su contraparte; los dos tienen
 * que cambiar juntos. Ese es el fallo silencioso de este diseño: una regla que solo se ajusta en un
 * lado deja al formulario aceptando lo que la API rechaza (o al revés).</p>
 *
 * <p><strong>Aceptan la cadena vacía los patrones marcados como opcionales</strong> ({@code ^$|…}),
 * porque los campos opcionales de un formulario pueden llegar en blanco; {@code @Pattern} ya se
 * salta {@code null} por diseño. {@link #TEXTO_SIN_DIGITOS} no incluye esa alternativa: sus campos
 * obligatorios ya están cubiertos por {@code @NotBlank}, que es quien tiene el mensaje propio para
 * el valor vacío.</p>
 */
public final class PatronesDeFormato {

    /**
     * Texto sin dígitos: letras Unicode —con acentos, eñe y diéresis—, marcas de acentuación
     * combinadas, espacios, punto, guion y apóstrofo.
     *
     * <p>Admite la cadena en blanco a propósito: en un campo obligatorio es {@code @NotBlank} quien
     * la rechaza, con su propio mensaje, y si el patrón también la rechazara la respuesta llevaría
     * dos motivos para el mismo campo en un orden que no se controla.</p>
     */
    public static final String TEXTO_SIN_DIGITOS = "^[\\p{L}\\p{M} .,'\\-]*$";

    /**
     * Lo mismo que {@link #TEXTO_SIN_DIGITOS} aceptando además la cadena vacía, para los campos
     * opcionales: una especialidad, una ocupación o un parentesco sin registrar.
     */
    public static final String TEXTO_SIN_DIGITOS_OPCIONAL = "^$|" + TEXTO_SIN_DIGITOS;

    /**
     * Nombre de un local —hoy solo el consultorio—: admite dígitos, pero exige al menos una letra.
     *
     * <p>{@code Consultorio 2} y {@code Sala 3} son nombres reales de una clínica y el patrón de las
     * personas los rechazaba; {@code 454545}, {@code 123} y {@code ---} no son nombres y siguen sin
     * entrar. Se aplica <strong>solo</strong> al nombre del consultorio: en el nombre de un médico o
     * de un paciente un número no es parte del nombre.</p>
     *
     * <p>La anticipación {@code (?=.*[\p{L}])} es la que exige la letra; el blanco tiene su propia
     * alternativa ({@code ^ *$}) para que un valor en blanco lo rechace {@code @NotBlank} con su
     * mensaje y no este patrón. Sin ella, {@code "   "} violaría las dos reglas y la respuesta
     * llevaría dos motivos para el mismo campo, en un orden que no se controla.</p>
     */
    public static final String NOMBRE_DE_CONSULTORIO = "^$|^ *$|^(?=.*[\\p{L}])[\\p{L}\\p{M} .,'\\-0-9]*$";

    /**
     * Teléfono: dígitos, con un {@code +} inicial opcional, espacios, guiones y paréntesis; de 7 a 20
     * caracteres en total.
     *
     * <p>La anticipación {@code (?=.{7,20}$)} acota el largo total, de modo que el máximo incluye el
     * {@code +} —{@code +52 55 5555 9876} son 16— y un número de 6 caracteres no llega al
     * mínimo. La segunda anticipación, {@code (?=.*[0-9])}, exige al menos un dígito: siete
     * caracteres de paréntesis, espacios o guiones no son un teléfono, así que el mínimo de largo no
     * basta por sí solo. Acepta la cadena vacía porque el teléfono es opcional en los tres
     * módulos.</p>
     *
     * <p>Los paréntesis se admiten porque {@code (951) 111 1111} es como se escribe un fijo, y
     * pueden abrir el número: de ahí {@code [0-9(]} como primer carácter. Los separadores son
     * <strong>espacio, guion y paréntesis literales</strong>, no {@code \s}: la clase de espacios de
     * Java y la de JavaScript no son la misma —la del navegador incluye espacios Unicode que la de
     * Java no—, y el espejo del cliente tiene que aceptar exactamente lo mismo. Un tabulador o un
     * salto de línea en un teléfono no es un separador válido en ninguno de los dos lados.</p>
     */
    public static final String TELEFONO = "^$|^(?=.{7,20}$)(?=.*[0-9])\\+?[0-9(][0-9() -]*$";

    /**
     * Cédula profesional: solo dígitos, entre 5 y 10. Acepta la cadena vacía porque es opcional y
     * sustituye al {@code @Size(max = 30)} de la columna: el patrón ya es más estricto, y declarar
     * los dos produciría dos mensajes para el mismo dato.
     */
    public static final String CEDULA_PROFESIONAL = "^$|^[0-9]{5,10}$";

    private PatronesDeFormato() {
    }
}
