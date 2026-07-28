# NaviSync — build di APK Android e binario Linux. `just` senza argomenti elenca le ricette.

debug_apk := "app/build/outputs/apk/debug/app-debug.apk"
release_apk := "app/build/outputs/apk/release/app-release.apk"
dist := "desktop/build/install/navisync"

default:
    @just --list

# APK di debug: firmato con la debug key, pronto da installare
debug:
    ./gradlew assembleDebug
    @ls -lh {{debug_apk}}

# APK di release firmato. Password dal env RELEASE_STORE_PASSWORD, altrimenti la chiede.
release:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ -z "${RELEASE_STORE_PASSWORD:-}" ]]; then
        read -rsp "Password keystore (navisync-release.keystore): " RELEASE_STORE_PASSWORD
        echo
        export RELEASE_STORE_PASSWORD
    fi
    ./gradlew assembleRelease
    # Senza password Gradle produce app-release-unsigned.apk: meglio accorgersene subito.
    if [[ ! -f {{release_apk}} ]]; then
        echo "APK firmato non trovato: password o alias sbagliati?" >&2
        exit 1
    fi
    ls -lh {{release_apk}}

# Installa l'APK di debug sul device collegato (lo builda se manca)
install: debug
    adb install -r {{debug_apk}}

# Installa l'APK di release. Disinstalla prima la debug: le firme sono diverse.
install-release: release
    adb install -r {{release_apk}}

# Versione dichiarata in app/build.gradle.kts
version:
    @grep -E 'version(Code|Name)' app/build.gradle.kts

# Firma e certificato dell'APK di release
verify:
    #!/usr/bin/env bash
    set -euo pipefail
    apksigner=$(ls -d "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
    "$apksigner" verify --print-certs {{release_apk}}

# --------------------------------------------------------------- Linux (TUI)

# Compila il binario Linux in desktop/build/install/navisync
desktop:
    ./gradlew -q :desktop:installDist
    @echo "{{dist}}/bin/navisync"

# Avvia l'interfaccia a terminale. Non uso `gradle run`: non aggancia una vera tty.
tui: desktop
    @{{dist}}/bin/navisync

# Sync headless con la config salvata (quello che metti in cron/systemd)
sync: desktop
    @{{dist}}/bin/navisync --sync

# Mette navisync nel PATH come symlink alla build (il launcher risolve il link da sé)
desktop-install: desktop
    mkdir -p ~/.local/bin
    ln -sf {{justfile_directory()}}/{{dist}}/bin/navisync ~/.local/bin/navisync
    @echo "navisync -> ~/.local/bin/navisync"

# --------------------------------------------------------------- comune

# Compila tutto senza produrre artefatti: giro veloce di controllo
check:
    ./gradlew compileDebugKotlin :desktop:compileKotlin

clean:
    ./gradlew clean
