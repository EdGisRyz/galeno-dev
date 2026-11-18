package com.intoglobe.clinicas.model;

/**
 * Nombres de los días de la semana según la convención que usa el esquema.
 *
 * <p>La convención es la de {@code Date.getDay()} de JavaScript —{@code 0=Domingo … 6=Sábado}—
 * y está fijada en el comentario de la columna {@code dia_semana} (migración V5). Vive acá para
 * que los mensajes que el usuario lee nombren el día con su palabra y no con su número interno:
 * «día 4» no le dice nada a quien atiende el mostrador, «jueves» sí.</p>
 *
 * <p>Una sola copia de los nombres alcanza: dos listas de días que se desincronicen producirían
 * mensajes contradictorios sobre el mismo bloque.</p>
 */
public final class DiasDeLaSemana {

    private static final String[] NOMBRES = {
            "domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"
    };

    private DiasDeLaSemana() {
    }

    /**
     * Devuelve el nombre del día para un valor de la convención.
     *
     * <p>Un valor fuera de rango no se corrige: se describe como tal, con el número incluido,
     * porque entonces el dato sí es lo que hay que revisar.</p>
     *
     * @param dia número de día, {@code 0=Domingo … 6=Sábado}
     * @return el nombre del día, o una descripción del valor fuera de la convención
     */
    public static String nombre(int dia) {
        return dia >= 0 && dia < NOMBRES.length
                ? NOMBRES[dia]
                : "día " + dia + " fuera de la convención";
    }
}
