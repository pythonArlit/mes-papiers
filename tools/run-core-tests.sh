#!/usr/bin/env bash
# Compile et exécute les tests JVM du module :core SANS Gradle ni accès réseau, à partir
# des bibliothèques livrées avec une distribution Gradle locale (compilateur Kotlin
# embarqué, JUnit 4, Bouncy Castle).
#
# Sert quand les dépôts Maven sont inaccessibles. La voie normale reste :
#   ./gradlew :core:test
#
# Usage : tools/run-core-tests.sh [répertoire de la distribution Gradle]
set -euo pipefail

GRADLE_HOME_DIR="${1:-${GRADLE_HOME:-/opt/gradle}}"
LIB="$GRADLE_HOME_DIR/lib"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT_DIR:-$ROOT/core/build/standalone}"

one() { ls "$@" 2>/dev/null | head -n 1; }
KOTLINC="$(one "$LIB"/kotlin-compiler-embeddable-*.jar)"
STDLIB="$(one "$LIB"/kotlin-stdlib-[0-9]*.jar)"
JUNIT="$(one "$LIB"/junit-4*.jar)"
HAMCREST="$(one "$LIB"/hamcrest-core-*.jar)"
BC="$(one "$LIB"/plugins/bcprov-jdk18on-*.jar)"
for f in "$KOTLINC" "$STDLIB" "$JUNIT" "$HAMCREST" "$BC"; do
  [ -n "$f" ] || { echo "Bibliothèque introuvable sous $LIB" >&2; exit 2; }
done
COMPILER_CP="$KOTLINC:$STDLIB:$(one "$LIB"/kotlin-script-runtime-*.jar):$(one "$LIB"/kotlin-reflect-*.jar):$(one "$LIB"/kotlinx-coroutines-core-jvm-*.jar):$(one "$LIB"/trove4j-*.jar):$(one "$LIB"/annotations-*.jar)"

echo "Kotlin : $(basename "$KOTLINC") | JUnit : $(basename "$JUNIT") | Bouncy Castle : $(basename "$BC")"
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"

kotlinc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror "$@"; }

echo "Compilation de core/src/main…"
kotlinc -cp "$STDLIB:$BC" -d "$OUT/main" "$ROOT/core/src/main/kotlin"
echo "Compilation de core/src/test…"
kotlinc -cp "$STDLIB:$BC:$JUNIT:$OUT/main" -d "$OUT/test" "$ROOT/core/src/test/kotlin"

CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
echo "Exécution : $CLASSES"
# shellcheck disable=SC2086
java -cp "$OUT/test:$OUT/main:$STDLIB:$BC:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES
