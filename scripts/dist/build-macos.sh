#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <base_dir> <target_dir> <artifact_id> <version> <main_class>" >&2
  exit 1
fi

BASE_DIR=$1
TARGET_DIR=$2
ARTIFACT_ID=$3
VERSION=$4
MAIN_CLASS=$5
JAVA_RELEASE=25

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "build-macos.sh must be run on macOS." >&2
  exit 1
fi

APP_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar"
DIST_ROOT="${TARGET_DIR}/dist"
INPUT_LIB_DIR="${DIST_ROOT}/input/lib"
ARCH="$(uname -m)"
BUNDLE_NAME="${ARTIFACT_ID}-macos-${ARCH}"
BUNDLE_DIR="${DIST_ROOT}/${BUNDLE_NAME}"
INPUT_DIR="${DIST_ROOT}/input/macos"
RUNTIME_DIR="${DIST_ROOT}/runtime/macos"
ZIP_FILE="${DIST_ROOT}/${BUNDLE_NAME}.zip"

JDEPS_BIN="${JAVA_HOME:-}/bin/jdeps"
JLINK_BIN="${JAVA_HOME:-}/bin/jlink"
JPACKAGE_BIN="${JAVA_HOME:-}/bin/jpackage"

if [[ ! -x "${JDEPS_BIN}" ]]; then
  JDEPS_BIN="$(command -v jdeps)"
fi
if [[ ! -x "${JLINK_BIN}" ]]; then
  JLINK_BIN="$(command -v jlink)"
fi
if [[ ! -x "${JPACKAGE_BIN}" ]]; then
  JPACKAGE_BIN="$(command -v jpackage)"
fi

if [[ -z "${JDEPS_BIN}" || -z "${JLINK_BIN}" || -z "${JPACKAGE_BIN}" ]]; then
  echo "Missing required tools (jdeps, jlink, jpackage)." >&2
  exit 1
fi
if [[ ! -f "${APP_JAR}" ]]; then
  echo "Application jar not found: ${APP_JAR}" >&2
  exit 1
fi
if [[ ! -d "${INPUT_LIB_DIR}" ]]; then
  echo "Runtime dependency folder not found: ${INPUT_LIB_DIR}" >&2
  exit 1
fi

rm -rf "${BUNDLE_DIR}" "${INPUT_DIR}" "${RUNTIME_DIR}" "${ZIP_FILE}"
mkdir -p "${INPUT_DIR}" "${RUNTIME_DIR}"

cp "${APP_JAR}" "${INPUT_DIR}/sweet-crush.jar"
mapfile -t INPUT_JARS < <(find "${INPUT_LIB_DIR}" -maxdepth 1 -type f -name "*.jar" | sort)
if [[ ${#INPUT_JARS[@]} -eq 0 ]]; then
  echo "No dependency jars found in ${INPUT_LIB_DIR}" >&2
  exit 1
fi
cp "${INPUT_JARS[@]}" "${INPUT_DIR}/"

mapfile -t LIB_JARS < <(find "${INPUT_DIR}" -maxdepth 1 -type f -name "*.jar" ! -name "sweet-crush.jar" | sort)
CLASSPATH=""
if [[ ${#LIB_JARS[@]} -gt 0 ]]; then
  CLASSPATH="$(IFS=:; echo "${LIB_JARS[*]}")"
fi

JDEPS_ARGS=(--ignore-missing-deps --recursive --multi-release "${JAVA_RELEASE}" --print-module-deps)
if [[ -n "${CLASSPATH}" ]]; then
  JDEPS_ARGS+=(--class-path "${CLASSPATH}")
fi
MODULES="$("${JDEPS_BIN}" "${JDEPS_ARGS[@]}" "${INPUT_DIR}/sweet-crush.jar")"
MODULES="${MODULES//[$'\r\n\t ']/}"
if [[ -z "${MODULES}" ]]; then
  MODULES="java.base,java.desktop"
fi
if [[ ",${MODULES}," != *",java.desktop,"* ]]; then
  MODULES="${MODULES},java.desktop"
fi
if [[ ",${MODULES}," != *",jdk.unsupported,"* ]]; then
  MODULES="${MODULES},jdk.unsupported"
fi

"${JLINK_BIN}" \
  --add-modules "${MODULES}" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output "${RUNTIME_DIR}"

mkdir -p "${BUNDLE_DIR}"
"${JPACKAGE_BIN}" \
  --type app-image \
  --name sweet-crush \
  --dest "${BUNDLE_DIR}" \
  --input "${INPUT_DIR}" \
  --main-jar sweet-crush.jar \
  --main-class "${MAIN_CLASS}" \
  --runtime-image "${RUNTIME_DIR}" \
  --java-options "-Dsun.java2d.uiScale=2.0"

if [[ -d "${BASE_DIR}/tracks" ]]; then
  cp -R "${BASE_DIR}/tracks" "${BUNDLE_DIR}/tracks"
fi

cat > "${BUNDLE_DIR}/sweet-crush.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec "${APP_HOME}/sweet-crush.app/Contents/MacOS/sweet-crush" "$@"
EOF
chmod +x "${BUNDLE_DIR}/sweet-crush.sh"

if command -v ditto >/dev/null 2>&1; then
  (cd "${DIST_ROOT}" && ditto -c -k --sequesterRsrc --keepParent "${BUNDLE_NAME}" "${BUNDLE_NAME}.zip")
else
  (cd "${DIST_ROOT}" && zip -qry "${BUNDLE_NAME}.zip" "${BUNDLE_NAME}")
fi
echo "Created macOS distribution: ${ZIP_FILE}"
