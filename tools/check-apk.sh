#!/usr/bin/env bash
# Vérifie un APK « prod » release : permissions, débogage, sauvegarde, absence de démo.
# NON EXÉCUTÉ à ce stade (aucun APK n'a pu être produit) : voir TESTS.md.
#
# Usage : tools/check-apk.sh app/build/outputs/apk/prod/release/app-prod-release*.apk
# Requiert aapt2 (build-tools du SDK Android) et unzip dans le PATH.
set -euo pipefail
APK="${1:?indique le chemin du fichier APK prod}"
fail=0
err() { echo "ÉCHEC : $*" >&2; fail=1; }

ALLOWED="android.permission.POST_NOTIFICATIONS android.permission.RECEIVE_BOOT_COMPLETED android.permission.USE_BIOMETRIC android.permission.CAMERA"
PERMS=$(aapt2 dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
for p in $PERMS; do
  case "$p" in
    *.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) continue ;; # permission interne signature, ajoutée par androidx.core
  esac
  echo " $ALLOWED " | grep -q " $p " || err "permission hors liste blanche : $p"
done
echo "$PERMS" | grep -q "android.permission.INTERNET" && err "INTERNET présent"

BADGING=$(aapt2 dump badging "$APK")
echo "$BADGING" | grep -q "application-debuggable" && err "APK débogable"
XML=$(aapt2 dump xmltree "$APK" --file AndroidManifest.xml)
echo "$XML" | grep -q 'android:allowBackup.*=false' || err "allowBackup n'est pas à false"
echo "$XML" | grep -q 'android:dataExtractionRules' || err "dataExtractionRules absent"
echo "$BADGING" | grep -q "package: name='app.mespapiers'" || err "identifiant d'application inattendu"

# Aucun code ni donnée de démonstration : marqueur des données fictives et classes demo.
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" -d "$TMP"
if grep -rlaE "MESPAPIERS-DEMO-FICTIF|DemoData" "$TMP" >/dev/null; then err "élément de démonstration présent dans l'APK prod"; fi

[ "$fail" -eq 0 ] && echo "OK : $APK respecte les contrôles prod." || exit 1
