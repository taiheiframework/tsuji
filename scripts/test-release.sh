#!/usr/bin/env bash
# Tests of the build's checks that never touch a real registry (make release-test, part of make
# validate): scripts/release.py, and repo-guard and publish-dry-run in the Makefile, run against
# scripts/fake_servers.py on localhost, which stands in for Maven Central, the family's Maven
# repository and the GitHub API. Files are signed by throwaway keys, never the release key. A
# throwaway git repository holds a copy of the Makefile and scripts/; where a target starts Mill, a
# stand-in answers and records what it was asked. Nothing leaves localhost. The mill launcher has
# its own tests, scripts/test-mill-launcher. One line per check; exits non-zero when any check
# fails.
set -uo pipefail
# make hands its command-line variables to every make below it, through MAKEFLAGS and as
# environment variables, and the make calls here would inherit them. COURSIER_* the Makefile reads
# from the environment. The publishing credentials and the launcher's version variables are
# cleared too, so that only what a check sets reaches the targets.
unset MAKEFLAGS MFLAGS MAKEOVERRIDES MAKELEVEL COURSIER_CONFIG_DIR \
  COURSIER_REPOSITORIES COURSIER_MIRRORS COURSIER_CREDENTIALS \
  MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD MILL_PGP_SECRET_BASE64 MILL_PGP_PASSPHRASE \
  MILL_VERSION DEFAULT_MILL_VERSION MILL_TESTS_PUBLISH_DRY_RUN
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/taihei-release-test.XXXXXX")
SERVER=""
cleanup() { [ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null; rm -rf "$W"; }
trap cleanup EXIT

FAILED=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILED=1; }
# expect NAME 0|1 PATTERN cmd...: the command succeeds (0) or fails (1), and prints PATTERN.
expect() {
  local name="$1" want="$2" pattern="$3" out rc; shift 3
  out=$("$@" 2>&1); rc=$?
  if { [ "$want" = 0 ] && [ "$rc" = 0 ]; } || { [ "$want" != 0 ] && [ "$rc" != 0 ]; }; then
    if [ -z "$pattern" ] || grep -qF -- "$pattern" <<<"$out"; then pass "$name"; return; fi
    fail "$name: the output lacks '$pattern'"
  else
    fail "$name: exit $rc"
  fi
  printf '%s\n' "$out" | tail -n 6 | sed 's/^/     /'
}

V=9.9.9
from_makefile() {
  make -s -C "$REPO" --no-print-directory -f - print <<EOF 2>/dev/null
include Makefile
print:
	@echo \$($1)
EOF
}
ARTIFACTS=$(from_makefile ARTIFACTS)
PREFIX=$(from_makefile BUNDLE_PREFIX)
PUBLISHED=$(from_makefile PUBLISHED)
FAMILY=$(from_makefile FAMILY)
OWNER=$(from_makefile OWNER)
LIBRARY=${PREFIX##*-}
[ -n "$ARTIFACTS" ] && [ -n "$PREFIX" ] && [ -n "$PUBLISHED" ] && [ -n "$FAMILY" ] && [ -n "$OWNER" ] \
  || { echo "cannot read ARTIFACTS, BUNDLE_PREFIX, PUBLISHED, FAMILY and OWNER from the Makefile"; exit 1; }
MODULES=${PUBLISHED//.publishArtifacts/}

# Two throwaway signing keys, in a keyring of their own (a short path: gpg-agent's socket may
# live there), standing in for the release key and for some other key.
export GNUPGHOME; GNUPGHOME=$(mktemp -d /tmp/trt-gpg.XXXXXX); chmod 700 "$GNUPGHOME"
trap 'gpgconf --kill gpg-agent >/dev/null 2>&1; rm -rf "$GNUPGHOME"; cleanup' EXIT
for who in release other; do
  gpg --batch --quiet --passphrase '' --pinentry-mode loopback --quick-gen-key "Test $who <$who@example.invalid>" ed25519 sign never 2>/dev/null \
    || { echo "gpg cannot make a test key"; exit 1; }
done
KEY=$(gpg --list-keys --with-colons "release@example.invalid" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }')
OTHER=$(gpg --list-keys --with-colons "other@example.invalid" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }')
gpg --batch --quiet --armor --export "$KEY" > "$W/release-key.asc" 2>/dev/null
sign() { rm -f "$2.asc"; gpg --batch --quiet --pinentry-mode loopback --passphrase '' --local-user "$1" --armor --detach-sign -o "$2.asc" "$2" 2>/dev/null; }

R() { python3 -I -B "$REPO/scripts/release.py" "$@"; }
sha() { python3 -I -B -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"; }

# --- the stand-in servers, on one port ---------------------------------------------------------
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/key.pem" -out "$W/cert.pem" -days 1 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1 || { echo "openssl cannot make a test certificate"; exit 1; }
PORT=$(python3 -I -B -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
S="$W/served"; mkdir -p "$S/central" "$S/site" "$S/api" "$S/download"
python3 -I -B "$HERE/fake_servers.py" "$S" "$PORT" "$W/cert.pem" "$W/key.pem" "$W/log" & SERVER=$!
export CURL_CA_BUNDLE="$W/cert.pem" SSL_CERT_FILE="$W/cert.pem"
for _ in $(seq 50); do curl -s -o /dev/null "https://localhost:$PORT/" && break; sleep 0.1; done
: > "$W/log"
BASE="https://localhost:$PORT"
SITE="$BASE/site/"
CENTRAL="$BASE/central"
API="$BASE/api"

# --- scripts/release.py -----------------------------------------------------------------------
printf '{"a.repositories": [], "b.repositories": []}' > "$W/none.json"
printf '{"a.repositories": ["https://maven.pkg.github.com/x"]}' > "$W/some.json"
expect "no-repos: modules without repositories pass" 0 "" R no-repos "$W/none.json"
expect "no-repos: a module naming one is refused" 1 "stay empty" R no-repos "$W/some.json"
deps_pom() {
  printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>%s</dependencies></project>\n' "$2" > "$1"
}
dep() { printf '<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></dependency>' "$1" "$2" "$3"; }
deps_pom "$W/p1.pom" "$(dep dev.taihei taihei-tsuji_3 5.0.0)$(dep dev.zio zio-json_3 1.1.1)"
[ "$(R pins "$W/p1.pom")" = "taihei-tsuji_3 5.0.0" ] && pass "pins: a dev.taihei dependency is listed, others are not" || fail "pins: $(R pins "$W/p1.pom" 2>&1)"
deps_pom "$W/p2.pom" "$(dep dev.taihei taihei-tsuji_3 5.0.1-SNAPSHOT)"
expect "pins: a SNAPSHOT pin is refused" 1 "not a released version" R pins "$W/p2.pom"
deps_pom "$W/p3.pom" "$(dep dev.taihei taihei-tsuji_3 '${tsuji.version}')"
expect "pins: a property for a version is refused" 1 "not a released version" R pins "$W/p3.pom"
own_pom() {
  printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>dev.taihei</groupId><artifactId>taihei-x-tools_3</artifactId><version>%s</version><dependencies>%s</dependencies></project>\n' "$2" "$3" > "$1"
}
own_pom "$W/p5.pom" 5.0.1-SNAPSHOT "$(dep dev.taihei taihei-x_3 5.0.1-SNAPSHOT)$(dep dev.taihei taihei-tsuji_3 5.0.0)"
[ "$(R pins --own "taihei-x_3 taihei-x-tools_3" "$W/p5.pom")" = "taihei-tsuji_3 5.0.0" ] && pass "pins: a module of this library at the same version is not a pin" || fail "pins --own: $(R pins --own "taihei-x_3 taihei-x-tools_3" "$W/p5.pom" 2>&1)"
own_pom "$W/p6.pom" 5.0.1 "$(dep dev.taihei taihei-x_3 5.0.0)"
expect "pins: a module of this library at another version is refused" 1 "not this release's own version" R pins --own "taihei-x_3 taihei-x-tools_3" "$W/p6.pom"
expect "pins: without --own, a module of this library is a pin like any other" 1 "not a released version" R pins "$W/p5.pom"
deps_pom "$W/p4.pom" "$(dep dev.zio zio_3 2.1.27)"
[ -z "$(R pins "$W/p4.pom")" ] && pass "pins: a POM without dev.taihei dependencies lists none" || fail "pins: listed a foreign dependency"

# resolvers: every module, exactly Coursier's defaults (local Ivy, Central) and then the site.
IVY='ivy:file:/home/u/.ivy2/local/[organisation]/[module]/(scala_[scalaVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]'
resolvers_json() { python3 -I -B -c 'import json, sys; print(json.dumps({m + ".resolverOrder": sys.argv[2:] for m in sys.argv[1].split()}))' "$@"; }
ONLY="${SITE%/} (dev.taihei only)"
resolving() { printf '%s.resolvedMvnDeps\n' "$@"; }
resolving a > "$W/rs-a"
resolvers_json "a b" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$W/r1.json"
expect "resolvers: the defaults followed by the site pass" 0 "" R resolvers --resolving "$W/rs-a" --url "$SITE" --modules "a b" "$W/r1.json"
expect "resolvers: a module that does not report is refused" 1 "does not report" R resolvers --resolving "$W/rs-a" --url "$SITE" --modules "a b c" "$W/r1.json"
resolving a b fixture > "$W/rs-abf"
expect "resolvers: a module that resolves but does not report is refused" 1 "fixture resolves dependencies but does not report" R resolvers --resolving "$W/rs-abf" --url "$SITE" "$W/r1.json"
: > "$W/rs-none"
expect "resolvers: a list of resolving modules that is empty is refused" 1 "no module resolves" R resolvers --resolving "$W/rs-none" --url "$SITE" "$W/r1.json"
printf 'a.compile\n' > "$W/rs-bad"
expect "resolvers: a list that is not of resolvedMvnDeps tasks is refused" 1 "not a resolvedMvnDeps task" R resolvers --resolving "$W/rs-bad" --url "$SITE" "$W/r1.json"
resolvers_json "a" "$IVY" "$ONLY" https://repo1.maven.org/maven2 > "$W/r2.json"
expect "resolvers: the site before Central is refused" 1 "not Coursier's defaults followed by" R resolvers --resolving "$W/rs-a" --url "$SITE" "$W/r2.json"
resolvers_json "a" "$IVY" https://repo1.maven.org/maven2 "$ONLY" https://example.invalid/maven > "$W/r3.json"
expect "resolvers: one more repository is refused" 1 "not Coursier's defaults followed by" R resolvers --resolving "$W/rs-a" --url "$SITE" "$W/r3.json"
resolvers_json "a" "$IVY" https://repo1.maven.org/maven2 > "$W/r4.json"
expect "resolvers: without the site is refused" 1 "not Coursier's defaults followed by" R resolvers --resolving "$W/rs-a" --url "$SITE" "$W/r4.json"
resolvers_json "a" "$IVY" https://repo1.maven.org/maven2 "${SITE%/}" > "$W/r5.json"
expect "resolvers: the site for every group, not dev.taihei only, is refused" 1 "for dev.taihei only" R resolvers --resolving "$W/rs-a" --url "$SITE" "$W/r5.json"
expect "resolvers: an http URL is refused" 1 "not an https URL" R resolvers --resolving "$W/rs-a" --url "http://localhost/site/" "$W/r1.json"
expect "resolvers: GitHub Packages is refused" 1 "GitHub Packages" R resolvers --resolving "$W/rs-a" --url "https://maven.pkg.github.com/x/y/" "$W/r1.json"

# build-repos: the build files name the site once, as build.mill's val repoUrl, add it once
# after the defaults, and name no other repository.
BR="$W/br"; mkdir -p "$BR"
override() { printf '%s\n' \
  "  val repoUrl = \"$1\"" \
  '  // Mill'"'"'s own repositories would put it first.' \
  '  override def repositoriesTask: Task[Seq[coursier.Repository]] = Task.Anon {' \
  '    coursierConfigModule().defaultRepositories() :+ TaiheiOnly(coursier.maven.MavenRepository(release.repoUrl))' \
  '  }'; }
build_repos() { (cd "$BR" && python3 -I -B "$REPO/scripts/release.py" build-repos --url "$SITE" build.mill "$@"); }
override "$SITE" > "$BR/build.mill"
expect "build-repos: the one override passes" 0 "" build_repos
override "https://example.invalid/repo/" > "$BR/build.mill"
expect "build-repos: a repoUrl that is not REPO_URL is refused" 1 "the Makefile's REPO_URL" build_repos
for line in '  def repositories = Task { Seq("https://example.invalid/maven") }' \
            '  val r = coursier.maven.MavenRepository("https://example.invalid/maven")' \
            '  def x = Seq("https://maven.pkg.github.com/taiheiframework/x")' \
            '  def x = Seq("http://example.invalid/maven")' \
            '//| mill-repositories: ["https://example.invalid/maven"]' \
            '/**/ override def repositoriesTask = Task.Anon { Seq.empty }' \
            '  def x = coursierConfigModule().defaultRepositories() :+ coursier.maven.MavenRepository(release.repoUrl)' \
            "  def x = \"$SITE\""; do
  { override "$SITE"; printf '%s\n' "$line"; } > "$BR/build.mill"
  expect "build-repos: refuses: ${line# }" 1 "" build_repos
done
override "$SITE" > "$BR/build.mill"; printf 'env:\n  COURSIER_REPOSITORIES: https://example.invalid/maven\n' > "$BR/ci.yml"
expect "build-repos: a workflow that sets Coursier's repositories is refused" 1 "names a repository" build_repos ci.yml
printf 'repositories:\n- https://example.invalid/maven\n' > "$BR/build.mill.yaml"
expect "build-repos: a YAML build file that names repositories is refused" 1 "names a repository" build_repos build.mill.yaml
printf -- '-Dcoursier.cache=/tmp/x\n' > "$BR/.mill-jvm-opts"
expect "build-repos: Mill options that move Coursier's cache are refused" 1 "names a repository" build_repos .mill-jvm-opts

# resolved: a stand-in Coursier cache, with a release of the library on the stand-in API.
# Another library of the family than this one, so that a pin on it is a pin, not a module of ours.
LIB=""; for f in $FAMILY; do [ "$f" != "$LIBRARY" ] && { LIB=$f; break; }; done
[ -n "$LIB" ] || { echo "FAMILY names no library besides $LIBRARY"; exit 1; }
DA="taihei-${LIB}_3"; DV=8.8.8
CACHE="$W/cache/v1"
SITEC="$CACHE/https/localhost:$PORT/site"; CENTC="$CACHE/https/localhost:$PORT/central"
mkdir -p "$SITEC/dev/taihei/$DA/$DV" "$CENTC/org/x/x/1.0" "$CENTC/dev/taihei/$DA/7.7.7" "$W/ws/out"
mkdir -p "$S/site/dev/taihei/$DA/$DV"
for f in "$DA-$DV.jar" "$DA-$DV.pom"; do
  printf 'resolved %s\n' "$f" > "$SITEC/dev/taihei/$DA/$DV/$f"; sign "$KEY" "$SITEC/dev/taihei/$DA/$DV/$f"
  cp "$SITEC/dev/taihei/$DA/$DV/$f.asc" "$S/site/dev/taihei/$DA/$DV/"
done
cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar" "$W/site.jar.ok"
restore_jar() { cp "$W/site.jar.ok" "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar"; }
printf 'x' > "$CENTC/org/x/x/1.0/x-1.0.jar"
printf 'own' > "$W/ws/out/own.jar"
for f in "$DA-7.7.7.jar" "$DA-7.7.7.pom"; do
  printf 'old %s\n' "$f" > "$CENTC/dev/taihei/$DA/7.7.7/$f"
  mkdir -p "$S/central/dev/taihei/$DA/7.7.7"; cp "$CENTC/dev/taihei/$DA/7.7.7/$f" "$S/central/dev/taihei/$DA/7.7.7/"
  sign "$KEY" "$S/central/dev/taihei/$DA/7.7.7/$f"
done
release_api() { # LIBRARY VERSION IMMUTABLE: a GitHub Release whose SHA256SUMS lists the resolved files
  mkdir -p "$S/api/repos/$OWNER/$1/releases/tags" "$S/download/$1/v$2"
  ( cd "$SITEC" && for f in dev/taihei/$DA/$2/*; do printf '%s  %s\n' "$(sha "$f")" "$f"; done ) > "$S/download/$1/v$2/SHA256SUMS"
  printf '{"tag_name": "v%s", "immutable": %s, "draft": false, "prerelease": false, "assets": [{"name": "SHA256SUMS", "browser_download_url": "%s/download/%s/v%s/SHA256SUMS"}]}\n' \
    "$2" "$3" "$BASE" "$1" "$2" > "$S/api/repos/$OWNER/$1/releases/tags/v$2"
}
release_api "$LIB" "$DV" true
ref() { printf '"qref:v1:0123abcd:%s"' "$1"; }
cp_json() { local IFS=,; printf '{"m.resolvedRunMvnDeps": [%s], "m.resolvedMimaPreviousArtifacts": [["dev.taihei:x:1", %s]]}\n' "$*" "$(ref "$W/ws/out/own.jar")"; }
resolved() { R resolved --workspace "$W/ws" --url "$SITE" --central "$CENTRAL" --api "$API" --owner "$OWNER" --family "$FAMILY" \
  --key "$W/release-key.asc" --fingerprint "$KEY" "$@"; }
cp_json "$(ref "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar")" "$(ref "$CENTC/org/x/x/1.0/x-1.0.jar")" > "$W/c1.json"
: > "$W/log"
expect "resolved: a dev.taihei jar and its POM from the site, in their release's SHA256SUMS and signed, pass" 0 "2 dev.taihei files match" resolved "$W/c1.json"
! grep -q 'auth=present' "$W/log" && pass "resolved: asks the API and the downloads without credentials" || fail "resolved: sent credentials"
cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom" "$W/pom.bak"; printf 'x' >> "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom"
expect "resolved: a POM beside the jar that differs from the release is refused" 1 "differs from the SHA256SUMS" resolved "$W/c1.json"
cp "$W/pom.bak" "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom"
printf 'x' >> "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar"
expect "resolved: a jar that differs from the release is refused" 1 "differs from the SHA256SUMS" resolved "$W/c1.json"
release_api "$LIB" "$DV" true
expect "resolved: a jar in its release's SHA256SUMS but not signed by the release key is refused" 1 "does not verify" resolved "$W/c1.json"
restore_jar; release_api "$LIB" "$DV" true
cp "$S/site/dev/taihei/$DA/$DV/$DA-$DV.jar.asc" "$W/site.asc.ok"; sign "$OTHER" "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar"
cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar.asc" "$S/site/dev/taihei/$DA/$DV/"; release_api "$LIB" "$DV" true
expect "resolved: a release whose signature is by another key is refused" 1 "does not verify" resolved "$W/c1.json"
cp "$W/site.asc.ok" "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar.asc"; cp "$W/site.asc.ok" "$S/site/dev/taihei/$DA/$DV/$DA-$DV.jar.asc"; release_api "$LIB" "$DV" true
printf 'x' >> "$S/site/dev/taihei/$DA/$DV/$DA-$DV.jar.asc"
expect "resolved: a served signature that is not the release's is refused" 1 "not served as the SHA256SUMS" resolved "$W/c1.json"
cp "$W/site.asc.ok" "$S/site/dev/taihei/$DA/$DV/$DA-$DV.jar.asc"
grep -v '\.jar\.asc$' "$S/download/$LIB/v$DV/SHA256SUMS" > "$W/sums"; cp "$W/sums" "$S/download/$LIB/v$DV/SHA256SUMS"
expect "resolved: a release that lists no signature for a file is refused" 1 "lists no signature" resolved "$W/c1.json"
release_api "$LIB" "$DV" true
printf 'extra\n' > "$SITEC/dev/taihei/$DA/$DV/$DA-$DV-sources.jar"
cp_json "$(ref "$SITEC/dev/taihei/$DA/$DV/$DA-$DV-sources.jar")" > "$W/c2.json"
expect "resolved: a file the release does not list is refused" 1 "not in the SHA256SUMS" resolved "$W/c2.json"
release_api "$LIB" "$DV" false
expect "resolved: a release that is not immutable is refused" 1 "not published and immutable" resolved "$W/c1.json"
rm "$S/api/repos/$OWNER/$LIB/releases/tags/v$DV"
expect "resolved: a dev.taihei file from the site without a release is refused" 1 "has no immutable release" resolved "$W/c1.json"
release_api "$LIB" "$DV" true
mkdir -p "$SITEC/org/x/x/1.0"; printf 'x' > "$SITEC/org/x/x/1.0/x-1.0.jar"
cp_json "$(ref "$SITEC/org/x/x/1.0/x-1.0.jar")" > "$W/c3.json"
expect "resolved: anything but dev.taihei from the site is refused" 1 "only dev.taihei comes from" resolved "$W/c3.json"
expect "resolved: and so is anything but dev.taihei in the site's cache, resolved or not" 1 "only dev.taihei comes from" resolved "$W/c1.json"
rm -rf "$SITEC/org"
WSC="$W/ws/.cc/v1/https/localhost:$PORT/site/org/x/x/1.0"; mkdir -p "$WSC"; printf 'x' > "$WSC/x-1.0.jar"
cp_json "$(ref "$WSC/x-1.0.jar")" > "$W/c3w.json"
expect "resolved: a Coursier cache inside the workspace is checked like any other" 1 "only dev.taihei comes from" resolved "$W/c3w.json"
rm -rf "$W/ws/.cc"
OTHERC="$CACHE/https/example.invalid/maven/org/x/x/1.0"; mkdir -p "$OTHERC"; printf 'x' > "$OTHERC/x-1.0.jar"
cp_json "$(ref "$OTHERC/x-1.0.jar")" > "$W/c4.json"
expect "resolved: a file from another repository is refused" 1 "not in the Coursier cache of Maven Central" resolved "$W/c4.json"
IVYF="$W/ivy2/local/dev.taihei/$DA/$DV/jars"; mkdir -p "$IVYF"; printf 'x' > "$IVYF/$DA.jar"
cp_json "$(ref "$IVYF/$DA.jar")" > "$W/c5.json"
expect "resolved: a file from the local Ivy repository is refused" 1 "not in the Coursier cache" resolved "$W/c5.json"
cp_json "$(ref "$CENTC/dev/taihei/$DA/7.7.7/$DA-7.7.7.jar")" > "$W/c6.json"
expect "resolved: a version on Central without a release passes by its signature, and so does its POM" 0 "2 verified by signature" resolved "$W/c6.json"
sign "$OTHER" "$S/central/dev/taihei/$DA/7.7.7/$DA-7.7.7.pom"
expect "resolved: a signature by another key is refused" 1 "does not verify" resolved "$W/c6.json"
sign "$KEY" "$S/central/dev/taihei/$DA/7.7.7/$DA-7.7.7.pom"
printf 'x' >> "$CENTC/dev/taihei/$DA/7.7.7/$DA-7.7.7.jar"
expect "resolved: a Central file that does not match its signature is refused" 1 "does not verify" resolved "$W/c6.json"
mkdir -p "$SITEC/dev/taihei/taihei-stranger_3/1.0.0"; printf 'x' > "$SITEC/dev/taihei/taihei-stranger_3/1.0.0/taihei-stranger_3-1.0.0.jar"
cp_json "$(ref "$SITEC/dev/taihei/taihei-stranger_3/1.0.0/taihei-stranger_3-1.0.0.jar")" > "$W/c7.json"
expect "resolved: a dev.taihei artifact of no family library is refused" 1 "not a released version of a family library" resolved "$W/c7.json"
printf '{"m.x": []}\n' > "$W/c8.json"
expect "resolved: nothing resolved at all is refused" 1 "resolved no files" resolved "$W/c8.json"
cp_json > "$W/c11.json"
expect "resolved: only the build's own output, so nothing checked, is refused" 1 "no resolved file was checked" resolved "$W/c11.json"
expect "resolved: a key file that is not the pinned key is refused" 1 "is not the key $OTHER" \
  R resolved --workspace "$W/ws" --url "$SITE" --central "$CENTRAL" --api "$API" --owner "$OWNER" --family "$FAMILY" \
  --key "$W/release-key.asc" --fingerprint "$OTHER" "$W/c6.json"
CACHE2="$W/cache2/v1/https/localhost:$PORT/central/org/y/y/1.0"; mkdir -p "$CACHE2"; printf 'y' > "$CACHE2/y-1.0.jar"
cp_json "$(ref "$CENTC/org/x/x/1.0/x-1.0.jar")" "$(ref "$CACHE2/y-1.0.jar")" > "$W/c9.json"
mkdir -p "$CENTC/dev/taihei/$DA/$DV"; cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar" "$CENTC/dev/taihei/$DA/$DV/"; printf 'x' >> "$CENTC/dev/taihei/$DA/$DV/$DA-$DV.jar"
cp_json "$(ref "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar")" "$(ref "$CENTC/dev/taihei/$DA/$DV/$DA-$DV.jar")" > "$W/c10.json"
expect "resolved: the same file from Central and from the site is checked in each" 1 "$CENTC/dev/taihei/$DA/$DV/$DA-$DV.jar: differs" resolved "$W/c10.json"
rm -rf "$CENTC/dev/taihei/$DA/$DV"
expect "resolved: files from two Coursier caches are refused" 1 "more than one Coursier cache" resolved "$W/c9.json"

# released: the MiMa baseline's check. A version on neither Central nor the site is spent (exit
# 3), which the baseline passes over; any other answer stops it (exit 1).
mkdir -p "$S/central/dev/taihei/$DA/6.6.6" "$S/site/dev/taihei/$DA/6.6.7"
printf 'pom' > "$S/central/dev/taihei/$DA/6.6.6/$DA-6.6.6.pom"; printf 'pom' > "$S/site/dev/taihei/$DA/6.6.7/$DA-6.6.7.pom"
expect "released: a version on Central" 0 "on Maven Central" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.6
expect "released: a version on the site" 0 "on $SITE" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.7
R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.8 >/dev/null 2>&1
[ $? = 3 ] && pass "released: a version on neither is spent, exit 3" || fail "released: a version on neither does not exit 3"
expect "released: and says so, naming it" 1 "v6.6.8 is neither on Maven Central nor on" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.8
R released --central "$CENTRAL" --url "$BASE/moved/" --artifact "$DA" 6.6.8 >/dev/null 2>&1
[ $? = 1 ] && pass "released: a redirect is no answer, exit 1, never spent" || fail "released: a redirect did not exit 1"
expect "released: and names it" 1 "HTTP 301" R released --central "$CENTRAL" --url "$BASE/moved/" --artifact "$DA" 6.6.7

# --- the Makefile targets, with a stand-in for Mill ---------------------------------------------
G="$W/repo"; mkdir -p "$G/scripts" "$G/keys"
cp "$REPO/Makefile" "$G/"; cp "$REPO/scripts/release.py" "$G/scripts/"; cp "$W/release-key.asc" "$G/keys/"
override "$SITE" > "$G/build.mill"
MLOG="$W/mill-env"

# The stand-in Mill answers repo-guard's questions and the dry run's. Every call is logged in
# $MLOG.calls with its arguments; for the publishing command it records which credential variables
# reached it (their names, never their values) and its arguments. STANDIN_VERSION, when set, is
# the version it reports. Mill 1.3.1 signs in its own JVM; only an added --useGpgCli would hand
# the passphrase to a gpg command line, so the arguments must be exactly these.
POMS_JSON="$W/poms.json"; RESOLVERS_JSON="$W/resolvers.json"; RESOLVED_JSON="$W/rg.json"; RESOLVING="$W/resolving"
cat > "$G/mill" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$MLOG.calls"
case "\$*" in
  "--no-daemon show release.gitVersion") echo "\"\${STANDIN_VERSION:-$V}\"";;
  "show "*".repositories") echo '{"m.repositories": []}';;
  "show __.resolverOrder") cat "$RESOLVERS_JSON";;
  "resolve __.resolvedMvnDeps") cat "$RESOLVING";;
  "show "*".pom") cat "$POMS_JSON";;
  "show __."*) cat "$RESOLVED_JSON";;
  "--no-daemon mill.javalib.SonatypeCentralPublishModule/"*)
    for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD MILL_PGP_SECRET_BASE64 MILL_PGP_PASSPHRASE MILL_TESTS_PUBLISH_DRY_RUN; do
      if [ "\${!v-}" = dry-run-no-upload ]; then echo "\$v placeholder"
      elif [ -n "\${!v+x}" ]; then echo "\$v set"; else echo "\$v unset"; fi
    done > "$MLOG"
    echo "args \$*" >> "$MLOG";;
  *) echo "stand-in mill: unexpected \$*" >&2; exit 1;;
esac
EOF
chmod +x "$G/mill"

git -C "$G" init -q; git -C "$G" symbolic-ref HEAD refs/heads/main; git -C "$G" add -A
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -qm test

M=(make -C "$G" --no-print-directory "REPO_URL=$SITE" "CENTRAL=$CENTRAL" "GITHUB_API=$API"
   "RELEASE_KEY=$KEY" "RELEASE_KEY_FILE=$G/keys/release-key.asc")

# repo-guard, end to end with the stand-in Mill: the pin check and the check of every file that
# was resolved. One published POM pins the stand-in library's version on the site.
resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$RESOLVERS_JSON"
resolving $MODULES > "$RESOLVING"
PIN="$W/pin.pom"; deps_pom "$PIN" "$(dep dev.taihei "$DA" "$DV")"
printf '{"m.pom": "qref:v1:0123abcd:%s"}\n' "$PIN" > "$POMS_JSON"
mkdir -p "$S/site/dev/taihei/$DA/$DV"; cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom" "$S/site/dev/taihei/$DA/$DV/"
printf '{"m.resolvedRunMvnDeps": [%s, %s]}\n' "$(ref "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar")" "$(ref "$CENTC/org/x/x/1.0/x-1.0.jar")" > "$RESOLVED_JSON"
release_api "$LIB" "$DV" true
expect "repo-guard passes a pin on the site and files that match their release" 0 "and nothing else" "${M[@]}" repo-guard
resolving $MODULES lib.fixture > "$RESOLVING"
expect "repo-guard refuses a module that resolves without reporting its repositories" 1 "lib.fixture resolves dependencies but does not report" "${M[@]}" repo-guard
resolving $MODULES > "$RESOLVING"
override "$BASE/moved/" > "$G/build.mill"; resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$BASE/moved (dev.taihei only)" > "$RESOLVERS_JSON"
expect "repo-guard refuses a pin answered with a redirect" 1 "HTTP 301" "${M[@]}" repo-guard "REPO_URL=$BASE/moved/"
override "$SITE" > "$G/build.mill"; resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$RESOLVERS_JSON"
rm "$S/site/dev/taihei/$DA/$DV/$DA-$DV.pom"
expect "repo-guard refuses a pin on neither Central nor the site" 1 "neither on Maven Central nor on" "${M[@]}" repo-guard
cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom" "$S/site/dev/taihei/$DA/$DV/"
printf 'x' >> "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar"
expect "repo-guard refuses a resolved file that differs from its release" 1 "differs from the SHA256SUMS" "${M[@]}" repo-guard
restore_jar
resolvers_json "$MODULES" "$ONLY" "$IVY" https://repo1.maven.org/maven2 > "$RESOLVERS_JSON"
expect "repo-guard refuses a module that asks the site before Central" 1 "not Coursier's defaults" "${M[@]}" repo-guard
resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$RESOLVERS_JSON"
expect "repo-guard refuses a REPO_URL that build.mill does not name" 1 "the Makefile's REPO_URL" "${M[@]}" repo-guard "REPO_URL=$BASE/other/"
expect "repo-guard refuses Coursier repositories from the environment" 1 "COURSIER_REPOSITORIES" env COURSIER_REPOSITORIES=https://example.invalid "${M[@]}" repo-guard

# publish-dry-run: Mill gets the key the caller gives, the dry-run switch, a placeholder for the
# Central token whatever the environment holds, and the bundle's name for a release version.
saw() { grep -qxF "$1" "$MLOG" 2>/dev/null; }
ARGS="args --no-daemon mill.javalib.SonatypeCentralPublishModule/ --bundleName $PREFIX-$V"
rm -f "$MLOG" "$MLOG.calls"
expect "publish-dry-run runs" 0 "bundle in" env MILL_PGP_SECRET_BASE64=dGVzdA== MILL_PGP_PASSPHRASE=test \
  MILL_SONATYPE_USERNAME=env-user MILL_SONATYPE_PASSWORD=env-token "${M[@]}" publish-dry-run
saw "MILL_SONATYPE_USERNAME placeholder" && saw "MILL_SONATYPE_PASSWORD placeholder" \
  && pass "publish-dry-run: Mill gets a placeholder, never a Central token from the environment" || fail "publish-dry-run: Mill saw the Central token, or no placeholder"
saw "MILL_PGP_SECRET_BASE64 set" && saw "MILL_PGP_PASSPHRASE set" && saw "MILL_TESTS_PUBLISH_DRY_RUN set" \
  && pass "publish-dry-run: Mill gets the caller's key and the dry-run switch" || fail "publish-dry-run: Mill lacks the key or the switch"
saw "$ARGS" && pass "publish-dry-run: Mill gets the bundle name and no other argument" || fail "publish-dry-run: Mill's arguments: $(grep '^args' "$MLOG")"
! grep -v '^--no-daemon ' "$MLOG.calls" >/dev/null && pass "publish-dry-run: no Mill call runs on a daemon, which would keep the key" \
  || fail "publish-dry-run: a Mill call may start a daemon: $(grep -v '^--no-daemon ' "$MLOG.calls" | tr '\n' ';')"
rm -f "$MLOG"
STANDIN_VERSION=$V.1-SNAPSHOT "${M[@]}" publish-dry-run >/dev/null 2>&1
saw "args --no-daemon mill.javalib.SonatypeCentralPublishModule/" && pass "publish-dry-run: a SNAPSHOT bundle gets no name" \
  || fail "publish-dry-run: a SNAPSHOT's arguments: $(grep '^args' "$MLOG")"

grep -q '^PUT\|^POST' "$W/log" && fail "something was uploaded to a stand-in server" || pass "nothing was ever uploaded"
[ "$FAILED" = 0 ] && echo "[release-test] all checks passed" || echo "[release-test] FAILED"
exit "$FAILED"
