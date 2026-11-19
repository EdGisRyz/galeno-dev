#!/bin/sh
# Compuerta de seguridad de CI (R-SEG-03, R-SEG-11).
#
# Recorre backend/src/main/java/**/*.java y falla si la cadena de filtros abre
# una ruta sin que nadie lo haya decidido:
#
#   Regla A  cualquier anyRequest().permitAll().
#   Regla B  cualquier permitAll() en una línea que no declare además uno de los
#            motivos permitidos: DispatcherType.ERROR, RUTA_ERROR,
#            RUTAS_PUBLICAS o RUTAS_PUBLICAS_DE_ONBOARDING.
#
# La Regla B es la estricta a propósito: abrir una ruta nueva obliga a escribir
# un motivo con nombre propio, que es un punto de revisión humano y no un
# descuido que pasa desapercibido.
#
# Los motivos se comparan por NOMBRE COMPLETO de constante y nunca por
# subcadena. Los nombres se contienen unos a otros —RUTAS_PUBLICAS_DE_ONBOARDING
# contiene RUTAS_PUBLICAS— así que una búsqueda por subcadena aceptaría la
# constante del onboarding y también cualquier invento con ese prefijo, sin que
# nadie hubiera decidido abrirlo. Comparar identificadores completos cierra esa
# puerta: el motivo tiene que ser exactamente uno de los cuatro.
#
# Los comentarios se ignoran antes de buscar: líneas que empiezan por *, /* o
# //, bloques /* ... */ repartidos en varias líneas y colas // dentro de una
# línea de código. Es indispensable: el Javadoc de SecurityConfig menciona
# permitAll() en prosa y una búsqueda ingenua fallaría el día uno.
#
# Solo herramientas POSIX (find, sort, awk). Sin dependencias. Funciona desde
# cualquier cwd: la raíz del repositorio se resuelve desde la ruta del script.
#
# Uso: sh scripts/verificar-seguridad.sh [--help]
# Sale 0 si pasa, 1 si hay infracciones, 2 si no puede revisar el árbol.

set -u

mostrar_ayuda() {
    cat <<'AYUDA'
Uso: sh scripts/verificar-seguridad.sh [--help]

Compuerta de seguridad de CI (R-SEG-03, R-SEG-11). Recorre todos los .java de
backend/src/main/java y falla si:

  Regla A  aparece anyRequest().permitAll().
  Regla B  aparece permitAll() en una línea que no declare además uno de los
           motivos permitidos: DispatcherType.ERROR, RUTA_ERROR,
           RUTAS_PUBLICAS o RUTAS_PUBLICAS_DE_ONBOARDING. Abrir una ruta nueva
           exige declararla en una de esas constantes: es un punto de revisión
           humano deliberado.

Los motivos se comparan por nombre completo de constante, no por subcadena:
RUTAS_PUBLICAS_DE_ONBOARDING contiene RUTAS_PUBLICAS y una subcadena aceptaría
cualquier invento con ese prefijo.

Los comentarios se ignoran: líneas que empiezan por *, /* o //, bloques
/* ... */ de varias líneas y colas // tras el código. El Javadoc de
SecurityConfig menciona permitAll() en prosa y no es una regla de autorización.

Sale 0 si pasa e informa cuántas ocurrencias revisó; 1 si hay infracciones;
2 si no encuentra el árbol de fuentes (para no pasar en silencio).

Referencia: docs/decisiones.md ADR-008, que documenta el cierre de permitAll().
AYUDA
}

for argumento in "$@"; do
    case $argumento in
        -h | --help)
            mostrar_ayuda
            exit 0
            ;;
        *)
            printf 'verificar-seguridad: opción no reconocida: %s\n' "$argumento" >&2
            mostrar_ayuda >&2
            exit 2
            ;;
    esac
done

DIR_SCRIPT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
RAIZ=$(dirname -- "$DIR_SCRIPT")
DIR_JAVA=backend/src/main/java

cd "$RAIZ" || exit 2

if [ ! -d "$DIR_JAVA" ]; then
    printf 'verificar-seguridad: no existe el árbol de fuentes %s\n' "$DIR_JAVA" >&2
    exit 2
fi

archivos=$(find "$DIR_JAVA" -type f -name '*.java' | sort)
if [ -z "$archivos" ]; then
    printf 'verificar-seguridad: no hay archivos .java en %s\n' "$DIR_JAVA" >&2
    exit 2
fi

# Los archivos se pasan a awk como argumentos. Partir la lista solo por saltos
# de línea evita romper rutas con espacios.
IFS_ANTERIOR=$IFS
IFS='
'
set -- $archivos
IFS=$IFS_ANTERIOR

awk '
# Devuelve la parte de la línea que es código, descartando comentarios. El
# estado vive en `estado` y cruza líneas para los bloques /* ... */; las cadenas
# se respetan para que un // dentro de un literal no se tome por comentario.
function solo_codigo(linea,   i, n, caracter, par, salida) {
    salida = ""
    n = length(linea)
    i = 1
    while (i <= n) {
        caracter = substr(linea, i, 1)
        par = substr(linea, i, 2)
        if (estado == "codigo") {
            if (par == "//") { estado = "linea"; i += 2; continue }
            if (par == "/*") { estado = "bloque"; i += 2; continue }
            if (caracter == "\"") { estado = "cadena" }
            salida = salida caracter
            i++
            continue
        }
        if (estado == "linea") { break }
        if (estado == "bloque") {
            if (par == "*/") { estado = "codigo"; i += 2; continue }
            i++
            continue
        }
        if (estado == "cadena") {
            if (caracter == "\\") {
                salida = salida caracter substr(linea, i + 1, 1)
                i += 2
                continue
            }
            if (caracter == "\"") { estado = "codigo" }
            salida = salida caracter
            i++
            continue
        }
        i++
    }
    if (estado == "linea") { estado = "codigo" }
    return salida
}

# anyRequest().permitAll(), sin importar el espaciado horizontal.
function abre_todo(codigo,   compacto) {
    compacto = codigo
    gsub(/[ \t]+/, "", compacto)
    return index(compacto, "anyRequest().permitAll") > 0
}

# Regla B: ¿la propia línea declara uno de los motivos permitidos?
#
# La comparación es por identificador completo, nunca por subcadena. Partir la
# línea en palabras separadas por todo lo que no sea letra, dígito o guion bajo
# deja cada identificador entero ("RUTAS_PUBLICAS_DE_ONBOARDING" no se parte) y
# permite comparar con ==. Se rechazan a propósito los nombres que solo
# contienen un motivo: MI_RUTA_ERROR, RUTAS_PUBLICAS_DE_MAS o
# RUTAS_PUBLICAS_DE_ONBOARDING_AMPLIADA no son decisiones de nadie.
#
# DispatcherType.ERROR es un acceso a miembro, así que sus dos identificadores
# tienen que aparecer seguidos: el punto es separador y no queda en los trozos.
function motivo_permitido(codigo,   trozos, cuantos, i) {
    cuantos = split(codigo, trozos, /[^A-Za-z0-9_]+/)
    for (i = 1; i <= cuantos; i++) {
        if (trozos[i] == "RUTA_ERROR") { return 1 }
        if (trozos[i] == "RUTAS_PUBLICAS") { return 1 }
        if (trozos[i] == "RUTAS_PUBLICAS_DE_ONBOARDING") { return 1 }
        if (trozos[i] == "ERROR" && i > 1 && trozos[i - 1] == "DispatcherType") { return 1 }
    }
    return 0
}

BEGIN { estado = "codigo"; total = 0; infracciones = 0 }

{
    codigo = solo_codigo($0)
    # Javadoc sin bloque abierto (línea suelta que arranca con *): comentario.
    if (codigo ~ /^[ \t]*\*/) { next }
    if (index(codigo, "permitAll") == 0) { next }

    total++
    regla_a = abre_todo(codigo)
    regla_b = !motivo_permitido(codigo)
    if (!regla_a && !regla_b) { next }

    infracciones++
    texto = $0
    sub(/^[ \t]+/, "", texto)
    sub(/[ \t]+$/, "", texto)
    ubicacion[infracciones] = FILENAME ":" FNR ": " texto
    es_a[infracciones] = regla_a
    es_b[infracciones] = regla_b
}

END {
    if (infracciones > 0) {
        printf "verificar-seguridad: FALLO - %d infraccion(es) en %d ocurrencia(s) de permitAll() revisadas\n", infracciones, total
        for (i = 1; i <= infracciones; i++) {
            printf "%s\n", ubicacion[i]
            if (es_a[i]) {
                printf "  Regla A: anyRequest().permitAll() abre toda la API. Prohibido (R-SEG-03, R-SEG-11; ver docs/decisiones.md ADR-008).\n"
            }
            if (es_b[i]) {
                printf "  Regla B: un permitAll() solo vale si la linea declara, por nombre completo, DispatcherType.ERROR, RUTA_ERROR, RUTAS_PUBLICAS o RUTAS_PUBLICAS_DE_ONBOARDING; abrir una ruta nueva exige revision humana (R-SEG-11; ver docs/decisiones.md ADR-008).\n"
            }
        }
        printf "  Referencia: docs/decisiones.md ADR-008.\n"
        exit 1
    }
    printf "verificar-seguridad: OK - validadas %d ocurrencias de permitAll() en backend/src/main/java (reglas A y B; ver docs/decisiones.md ADR-008)\n", total
    exit 0
}
' "$@"

exit $?
