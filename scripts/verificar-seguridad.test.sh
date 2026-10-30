#!/bin/sh
# Pruebas de scripts/verificar-seguridad.sh (R-SEG-03, R-SEG-11).
#
# No tiene dependencias: solo sh y las herramientas POSIX. Cada caso monta un
# repositorio de mentira en un directorio temporal (mktemp -d), copia allí la
# compuerta y un fixture .java, y la ejecuta desde un cwd distinto al del
# repositorio para probar que la compuerta resuelve su raíz por sí misma.
#
# Uso: sh scripts/verificar-seguridad.test.sh
# Sale 0 si todos los casos pasan, 1 si alguno falla.

set -u

DIR_SCRIPT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
RAIZ=$(dirname -- "$DIR_SCRIPT")
COMPUERTA="$DIR_SCRIPT/verificar-seguridad.sh"

casos=0
fallos=0

ok() {
    casos=$((casos + 1))
    printf 'ok %s - %s\n' "$casos" "$1"
}

no_ok() {
    casos=$((casos + 1))
    fallos=$((fallos + 1))
    printf 'NO OK %s - %s\n' "$casos" "$1"
    printf '       salida de la compuerta (código %s):\n' "${CODIGO:-?}"
    printf '%s\n' "${SALIDA:-<vacía>}" | sed 's/^/       | /'
}

# contiene <texto>: verdadero si $SALIDA contiene el texto.
contiene() {
    case ${SALIDA:-} in
        *"$1"*) return 0 ;;
        *) return 1 ;;
    esac
}

# preparar <nombre-del-caso>: crea $CASO/repo con la compuerta ya copiada y
# deja $CASO/repo en REPO.
preparar() {
    CASO="$TMP/caso-$1"
    REPO="$CASO/repo"
    mkdir -p "$REPO/scripts" "$REPO/backend/src/main/java/com/intoglobe/clinicas/demo"
    cp "$COMPUERTA" "$REPO/scripts/verificar-seguridad.sh" || {
        printf 'no se pudo copiar la compuerta: %s\n' "$COMPUERTA" >&2
        exit 1
    }
}

# fixture <contenido-heredoc>: escribe el archivo Java del caso.
fixture() {
    cat > "$REPO/backend/src/main/java/com/intoglobe/clinicas/demo/CadenaDeFiltros.java"
}

# ejecutar <cwd>: corre la compuerta del caso y captura salida y código.
ejecutar() {
    SALIDA=$(cd "$1" && sh "$REPO/scripts/verificar-seguridad.sh" 2>&1)
    CODIGO=$?
}

esperar_exito() {
    if [ "$CODIGO" -eq 0 ]; then
        ok "$1"
    else
        no_ok "$1"
    fi
}

esperar_fallo() {
    if [ "$CODIGO" -ne 0 ]; then
        ok "$1"
    else
        no_ok "$1"
    fi
}

esperar_contiene() {
    if contiene "$2"; then
        ok "$1"
    else
        no_ok "$1 (falta '$2')"
    fi
}

TMP=$(mktemp -d) || exit 1
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

printf '# Pruebas de la compuerta de seguridad (R-SEG-11)\n'
printf '# Compuerta: %s\n\n' "$COMPUERTA"

# ---------------------------------------------------------------------------
# 1. Código del estilo actual: las tres ocurrencias permitidas pasan.
# ---------------------------------------------------------------------------
preparar estilo-actual
fixture <<'JAVA'
package com.intoglobe.clinicas.demo;

class CadenaDeFiltros {
    void cadena(HttpSecurity http, Object manager) {
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(RUTA_ERROR).permitAll()
                .requestMatchers(RUTAS_PUBLICAS).permitAll()
                .anyRequest().access(manager));
    }
}
JAVA
ejecutar /
esperar_exito 'estilo actual (DispatcherType.ERROR, RUTA_ERROR, RUTAS_PUBLICAS) pasa desde cwd=/'
esperar_contiene 'estilo actual: informe de éxito' 'OK'
esperar_contiene 'estilo actual: cuenta las 3 ocurrencias' 'validadas 3 ocurrencias'

# ---------------------------------------------------------------------------
# 2. anyRequest().permitAll() rompe la Regla A.
# ---------------------------------------------------------------------------
preparar anyrequest
fixture <<'JAVA'
package com.intoglobe.clinicas.demo;

class CadenaDeFiltros {
    void cadena(HttpSecurity http) {
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .requestMatchers(RUTA_ERROR).permitAll()
                .anyRequest().permitAll());
    }
}
JAVA
ejecutar "$TMP"
esperar_fallo 'anyRequest().permitAll() falla la compuerta'
esperar_contiene 'anyRequest().permitAll(): nombra la Regla A' 'Regla A'
esperar_contiene 'anyRequest().permitAll(): informa file:line' 'CadenaDeFiltros.java:7'
esperar_contiene 'anyRequest().permitAll(): cita el ADR' 'ADR-008'

# ---------------------------------------------------------------------------
# 3. Una ruta nueva con permitAll() rompe la Regla B.
# ---------------------------------------------------------------------------
preparar ruta-nueva
fixture <<'JAVA'
package com.intoglobe.clinicas.demo;

class CadenaDeFiltros {
    void cadena(HttpSecurity http) {
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(RUTA_ERROR).permitAll()
                .requestMatchers(RUTAS_PUBLICAS).permitAll()
                .requestMatchers("/api/v1/abierto").permitAll()
                .anyRequest().access(manager));
    }
}
JAVA
ejecutar "$TMP"
esperar_fallo 'requestMatchers("/api/v1/abierto").permitAll() falla la compuerta'
esperar_contiene 'ruta nueva: nombra la Regla B' 'Regla B'
esperar_contiene 'ruta nueva: informa file:line' 'CadenaDeFiltros.java:9'
esperar_contiene 'ruta nueva: muestra el texto infractor' '/api/v1/abierto'
esperar_contiene 'ruta nueva: cita el ADR' 'ADR-008'

# ---------------------------------------------------------------------------
# 4. Los comentarios que mencionan permitAll() se ignoran.
# ---------------------------------------------------------------------------
preparar comentarios
fixture <<'JAVA'
package com.intoglobe.clinicas.demo;

/**
 * El {@code permitAll()} de documentación se conserva a propósito.
 * Y {@code anyRequest().permitAll()} está prohibido (ADR-008).
 *
 * <p>La primera regla es {@code dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()}.</p>
 */
class CadenaDeFiltros {
    // .requestMatchers("/api/v1/abierto").permitAll()
    /*
     * .anyRequest().permitAll()
     * .requestMatchers("/api/v1/otro").permitAll()
     */
    void cadena(HttpSecurity http) {
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .requestMatchers(RUTA_ERROR).permitAll() // .anyRequest().permitAll() sería un error
                .anyRequest().access(manager));
    }
}
JAVA
ejecutar "$TMP"
esperar_exito 'comentarios con permitAll() (prosa, // y /* */) no fallan la compuerta'
esperar_contiene 'comentarios: solo cuenta la ocurrencia real' 'validadas 1 ocurrencias'

# ---------------------------------------------------------------------------
# 5. Los archivos reales del backend pasan tal como están hoy.
# ---------------------------------------------------------------------------
CASO="$TMP/caso-archivos-reales"
REPO="$CASO/repo"
mkdir -p "$REPO/scripts" "$REPO/backend/src/main/java/com/intoglobe/clinicas/security"
cp "$COMPUERTA" "$REPO/scripts/verificar-seguridad.sh"
cp "$RAIZ/backend/src/main/java/com/intoglobe/clinicas/security/SecurityConfig.java" \
   "$REPO/backend/src/main/java/com/intoglobe/clinicas/security/SecurityConfig.java"
cp "$RAIZ/backend/src/main/java/com/intoglobe/clinicas/security/AuthKeyStartupConfig.java" \
   "$REPO/backend/src/main/java/com/intoglobe/clinicas/security/AuthKeyStartupConfig.java"
ejecutar "$TMP"
esperar_exito 'SecurityConfig + AuthKeyStartupConfig reales (Javadoc con permitAll() en prosa) pasan'
esperar_contiene 'archivos reales: cuenta las 3 ocurrencias reales' 'validadas 3 ocurrencias'

# ---------------------------------------------------------------------------
# 6. --help no ejecuta la revisión y sale 0.
# ---------------------------------------------------------------------------
preparar ayuda
fixture <<'JAVA'
package com.intoglobe.clinicas.demo;

class CadenaDeFiltros {
    void cadena(HttpSecurity http) {
        http.authorizeHttpRequests(autorizacion -> autorizacion
                .anyRequest().permitAll());
    }
}
JAVA
SALIDA=$(cd "$TMP" && sh "$REPO/scripts/verificar-seguridad.sh" --help 2>&1)
CODIGO=$?
esperar_exito '--help sale 0'
esperar_contiene '--help: describe la Regla A' 'Regla A'
esperar_contiene '--help: describe la Regla B' 'Regla B'

# ---------------------------------------------------------------------------
# 7. Sin árbol de fuentes la compuerta falla en vez de pasar en silencio.
# ---------------------------------------------------------------------------
CASO="$TMP/caso-sin-fuentes"
REPO="$CASO/repo"
mkdir -p "$REPO/scripts"
cp "$COMPUERTA" "$REPO/scripts/verificar-seguridad.sh"
ejecutar "$TMP"
esperar_fallo 'sin backend/src/main/java la compuerta falla (no pasa en silencio)'
esperar_contiene 'sin fuentes: explica qué falta' 'backend/src/main/java'

printf '\n# Resultado: %s casos, %s fallos\n' "$casos" "$fallos"
if [ "$fallos" -ne 0 ]; then
    exit 1
fi
printf '# Todas las pruebas de la compuerta de seguridad pasaron\n'
