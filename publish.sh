#!/usr/bin/env bash
set -e
# ─── Sweet Crush publish script ───
# 1. Checks git repo is clean
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
require_cmd unzip

# Read version from pom.xml (strip -SNAPSHOT if present for the release tag)
POM_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)
[[ -z "${POM_VERSION}" ]] && die "Could not read version from pom.xml"
VERSION="${POM_VERSION%-SNAPSHOT}"
TAG="v${VERSION}"

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
COMMIT_LOG=$(git log ${LOG_RANGE} --no-merges --pretty=format:"- %s" 2>/dev/null || true)
if [[ -z "${COMMIT_LOG}" ]]; then
  COMMIT_LOG="- Initial release"
fi

# Build the release notes block
RELEASE_NOTES="${NOTES_HEADER}
${COMMIT_LOG}"

# Prepend to release.md (after the title line)
RELEASE_MD="${SCRIPT_DIR}/release.md"
if [[ -f "${RELEASE_MD}" ]]; then
  # Insert after the first heading line
  HEAD_LINE=$(head -1 "${RELEASE_MD}")
  TAIL_CONTENT=$(tail -n +2 "${RELEASE_MD}")
  cat > "${RELEASE_MD}" <<EOF
${HEAD_LINE}

${RELEASE_NOTES}
${TAIL_CONTENT}
EOF
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
echo "Running mvn install deploy..."
mvn install deploy

# 5. Create GitHub release with distribution zips attached
echo "Creating GitHub release ${TAG}..."
gh release create "${TAG}" \
  --title "Sweet Crush ${VERSION}" \
  --notes "${RELEASE_NOTES}" \
  "${RELEASE_FILES[@]}"

echo ""
echo "Published Sweet Crush ${TAG} successfully!"
