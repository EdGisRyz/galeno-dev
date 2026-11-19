package com.intoglobe.clinicas.validation;

/**
 * Patrones de formato compartidos por los DTO de entrada de la API.
 *
 * <p>Son las reglas de <em>forma</em> de los datos que el usuario teclea en los formularios:
 * «texto sin dígitos», «teléfono con formato» y la forma del correo de una cuenta. Viven en un
 * solo sitio porque las usan varios módulos —médico, paciente, consultorio y usuario— y una copia
 * por módulo es una oportunidad de que una
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

    /**
     * Correo de una <strong>cuenta de usuario</strong> (identificador de inicio de sesión): una
     * parte local, una {@code @}, un dominio y un punto con al menos dos letras después
     * ({@code algo@dominio.mx}).
     *
     * <p><strong>Es estructural, no la RFC completa.</strong> Comprueba que el dato pueda ser la
     * credencial de alguien: una cuenta creada con {@code juan@} nace sin que nadie pueda iniciar
     * sesión con ella. Que la dirección exista de verdad, o que su parte local respete la longitud
     * que impone una norma, es competencia del servicio de identidad, que es quien autentica; este
     * borde solo rechaza lo que no puede ser un correo.</p>
     *
     * <p><strong>Se aplica solo al correo de la cuenta, no al del paciente.</strong>
     * {@code paciente.email} es un dato de contacto opcional y sigue sin regla de formato
     * (decisión de producto, 2026-11-05): si está mal, no se puede escribir, pero la ficha sigue
     * siendo válida y el paciente se atiende igual. Aquí el dato es el identificador con el que se
     * entra al sistema, así que un valor sin forma de correo es una cuenta inutilizable.</p>
     *
     * <p><strong>Acepta mayúsculas a propósito.</strong> La normalización a minúsculas de las cuentas
     * ocurre después de validar, en {@code Usuario#normalizarEmail(String)} y en el servicio, de modo
     * que lo que se valida es el valor tal como llegó: rechazar mayúsculas dejaría fuera correos
     * válidos que el sistema guarda en minúsculas.</p>
     *
     * <p><strong>Los espacios de los extremos no se aceptan aquí porque el DTO ya los recortó.</strong>
     * {@code UsuarioRequest} aplica {@code Usuario#recortarEmail(String)} en su constructor compacto,
     * que corre antes de Bean Validation: un correo pegado con un espacio invisible se valida ya
     * recortado y se guarda así. El patrón sigue rechazando un espacio interior
     * ({@code juan @dominio.com}), que sí forma parte del dato.</p>
     *
     * <p>El punto de la parte local se admite porque {@code laura.mendez@example.com} es un correo
     * real, y el {@code +} porque {@code nombre+etiqueta@dominio.com.mx} también lo es. Los dominios
     * internacionales en forma no ASCII quedan fuera: no hay ninguno guardado y no es la regla que
     * este borde necesita.</p>
     *
     * <p>Acepta la cadena vacía porque el correo de la cuenta es obligatorio y quien lo rechaza es
     * {@code @NotBlank}, con su mensaje propio: si el patrón también lo rechazara, la respuesta
     * llevaría dos motivos para el mismo campo en un orden que no se controla.</p>
     */
    public static final String CORREO_CUENTA =
            "^$|^[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$";

    private PatronesDeFormato() {
    }
}
