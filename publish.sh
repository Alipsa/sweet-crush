#!/usr/bin/env bash
set -e
# ─── Sweet Crush publish script ───
# 1. Checks git repo is clean (and on main branch)
# 2. Downloads latest distribution artifacts from GitHub Actions
# 3. Generates release notes in release.md, commits and pushes
# 4. Runs mvn install deploy
# 5. Creates a GitHub release with distribution zips attached

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

# ─── Helpers ───

die() { echo "ERROR: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

# ─── Pre-flight checks ───

require_cmd git
require_cmd gh
require_cmd mvn

# Must release from main branch
CURRENT_BRANCH=$(git branch --show-current)
[[ "${CURRENT_BRANCH}" == "main" ]] || die "Releases must be made from the main branch (currently on: ${CURRENT_BRANCH})"

# ─── JDK 25 setup via SDKMAN ───

export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
[[ -s "${SDKMAN_DIR}/bin/sdkman-init.sh" ]] || die "SDKMAN not found at ${SDKMAN_DIR}"
source "${SDKMAN_DIR}/bin/sdkman-init.sh"
if command -v jdk25 &> /dev/null; then
  . jdk25
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
[[ "${JAVA_MAJOR}" == "25" ]] || die "JDK 25 is required but found Java ${JAVA_MAJOR}"

# Read version from pom.xml
POM_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)
[[ -z "${POM_VERSION}" ]] && die "Could not read version from pom.xml"
[[ "${POM_VERSION}" == *-SNAPSHOT ]] && die "The pom version is still ${POM_VERSION}, cannot publish a snapshot"
TAG="v${POM_VERSION}"

echo "Publishing Sweet Crush ${TAG}"

# 1. Check that the git repository is clean
if [[ -n "$(git status --porcelain)" ]]; then
  die "Git working directory is not clean. Commit or stash changes first."
fi

git fetch --tags origin

if git rev-parse "${TAG}" >/dev/null 2>&1; then
  die "Tag ${TAG} already exists. Bump the version in pom.xml first."
fi

# 2. Download latest distribution artifacts from GitHub Actions
echo "Downloading distribution artifacts..."
DIST_DIR="${SCRIPT_DIR}/target/release-dist"
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

ARTIFACTS=("sweet-crush-linux" "sweet-crush-macos" "sweet-crush-windows")
for artifact in "${ARTIFACTS[@]}"; do
  echo "  Downloading ${artifact}..."
  gh run download --name "${artifact}" --dir "${DIST_DIR}/${artifact}" || \
    die "Failed to download artifact: ${artifact}. Ensure the latest workflow run succeeded."
done

# Flatten: gh downloads each artifact into a subdirectory; collect the zips
RELEASE_FILES=()
for artifact in "${ARTIFACTS[@]}"; do
  mapfile -t zips < <(find "${DIST_DIR}/${artifact}" -name "*.zip" -type f)
  if [[ ${#zips[@]} -eq 0 ]]; then
    die "No zip file found in downloaded artifact: ${artifact}"
  fi
  for z in "${zips[@]}"; do
    mv "$z" "${DIST_DIR}/"
    RELEASE_FILES+=("${DIST_DIR}/$(basename "$z")")
  done
done

echo "Distribution files ready:"
printf "  %s\n" "${RELEASE_FILES[@]}"

# 3. Generate release notes from git history since last release
echo "Generating release notes..."

LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
if [[ -n "${LAST_TAG}" ]]; then
  LOG_RANGE="${LAST_TAG}..HEAD"
else
  LOG_RANGE="HEAD"
fi

TODAY=$(date +%Y-%m-%d)
NOTES_HEADER="## ${TAG}, ${TODAY}"

# Collect commit summaries (one-liners, skip merge commits)
COMMIT_LOG=$(git log "${LOG_RANGE}" --no-merges --pretty=format:"- %s" 2>/dev/null || true)
if [[ -z "${COMMIT_LOG}" ]]; then
  COMMIT_LOG="- Initial release"
fi

# Build the release notes block
RELEASE_NOTES="${NOTES_HEADER}
${COMMIT_LOG}"

# Prepend to release.md (after the title line)
RELEASE_MD="${SCRIPT_DIR}/release.md"
if [[ -f "${RELEASE_MD}" ]]; then
  HEAD_LINE=$(head -1 "${RELEASE_MD}")
  # Strip leading blank lines from existing content to ensure exactly one blank line between sections
  TAIL_CONTENT=$(tail -n +2 "${RELEASE_MD}" | sed '/./,$!d')
  if [[ -n "${TAIL_CONTENT}" ]]; then
    cat > "${RELEASE_MD}" <<EOF
${HEAD_LINE}

${RELEASE_NOTES}

${TAIL_CONTENT}
EOF
  else
    cat > "${RELEASE_MD}" <<EOF
${HEAD_LINE}

${RELEASE_NOTES}
EOF
  fi
else
  cat > "${RELEASE_MD}" <<EOF
# Sweet Crush Release History

${RELEASE_NOTES}
EOF
fi

echo "Updated release.md"

git add release.md
git commit -m "Release notes for ${TAG}"
git push origin main

# 4. Run mvn install deploy
echo "Running mvn install deploy -Prelease..."
mvn install deploy -Prelease

# 5. Create GitHub release with distribution zips attached
echo "Creating GitHub release ${TAG}..."
gh release create "${TAG}" \
  --title "Sweet Crush ${POM_VERSION}" \
  --notes "${RELEASE_NOTES}" \
  "${RELEASE_FILES[@]}"

echo ""
echo "Published Sweet Crush ${TAG} successfully!"
