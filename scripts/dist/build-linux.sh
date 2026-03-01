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

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "build-linux.sh must be run on Linux." >&2
  exit 1
fi

APP_JAR="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar"
DIST_ROOT="${TARGET_DIR}/dist"
INPUT_LIB_DIR="${DIST_ROOT}/input/lib"
ARCH="$(uname -m)"
BUNDLE_NAME="${ARTIFACT_ID}-linux-${ARCH}"
BUNDLE_DIR="${DIST_ROOT}/${BUNDLE_NAME}"
APP_DIR="${BUNDLE_DIR}/app"
APP_LIB_DIR="${APP_DIR}/lib"
RUNTIME_DIR="${BUNDLE_DIR}/runtime"
ZIP_FILE="${DIST_ROOT}/${BUNDLE_NAME}.zip"

JAVA_BIN="${JAVA_HOME:-}/bin/java"
JDEPS_BIN="${JAVA_HOME:-}/bin/jdeps"
JLINK_BIN="${JAVA_HOME:-}/bin/jlink"
ZIP_BIN="$(command -v zip || true)"

if [[ ! -x "${JAVA_BIN}" ]]; then
  JAVA_BIN="$(command -v java)"
fi
if [[ ! -x "${JDEPS_BIN}" ]]; then
  JDEPS_BIN="$(command -v jdeps)"
fi
if [[ ! -x "${JLINK_BIN}" ]]; then
  JLINK_BIN="$(command -v jlink)"
fi

if [[ -z "${JAVA_BIN}" || -z "${JDEPS_BIN}" || -z "${JLINK_BIN}" || -z "${ZIP_BIN}" ]]; then
  echo "Missing required tools (java, jdeps, jlink, zip)." >&2
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

rm -rf "${BUNDLE_DIR}" "${ZIP_FILE}"
mkdir -p "${APP_LIB_DIR}"

cp "${APP_JAR}" "${APP_DIR}/sweet-crush.jar"
mapfile -t INPUT_JARS < <(find "${INPUT_LIB_DIR}" -maxdepth 1 -type f -name "*.jar" | sort)
if [[ ${#INPUT_JARS[@]} -eq 0 ]]; then
  echo "No dependency jars found in ${INPUT_LIB_DIR}" >&2
  exit 1
fi
cp "${INPUT_JARS[@]}" "${APP_LIB_DIR}/"
if [[ -d "${BASE_DIR}/tracks" ]]; then
  cp -R "${BASE_DIR}/tracks" "${BUNDLE_DIR}/tracks"
fi

mapfile -t LIB_JARS < <(find "${APP_LIB_DIR}" -maxdepth 1 -type f -name "*.jar" | sort)
CLASSPATH=""
if [[ ${#LIB_JARS[@]} -gt 0 ]]; then
  CLASSPATH="$(IFS=:; echo "${LIB_JARS[*]}")"
fi

JDEPS_ARGS=(--ignore-missing-deps --recursive --multi-release "${JAVA_RELEASE}" --print-module-deps)
if [[ -n "${CLASSPATH}" ]]; then
  JDEPS_ARGS+=(--class-path "${CLASSPATH}")
fi
MODULES="$("${JDEPS_BIN}" "${JDEPS_ARGS[@]}" "${APP_DIR}/sweet-crush.jar")"
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

cat > "${BUNDLE_DIR}/sweet-crush.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
APP_HOME="\$(cd "\$(dirname "\$0")" && pwd)"
exec "\${APP_HOME}/runtime/bin/java" -Dsun.java2d.uiScale=2.0 -cp "\${APP_HOME}/app/sweet-crush.jar:\${APP_HOME}/app/lib/*" ${MAIN_CLASS} "\$@"
EOF
chmod +x "${BUNDLE_DIR}/sweet-crush.sh"

(cd "${DIST_ROOT}" && "${ZIP_BIN}" -qry "${BUNDLE_NAME}.zip" "${BUNDLE_NAME}")
echo "Created Linux distribution: ${ZIP_FILE}"
