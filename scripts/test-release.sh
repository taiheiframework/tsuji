#!/usr/bin/env bash
# Tests of the build's checks that never touch a real registry (make release-test, part of make
# validate): scripts/release.py, and repo-guard, publish-dry-run and test-java-17 in the Makefile,
# run against scripts/fake_servers.py on localhost, which stands in for Maven Central, the
# family's Maven repository and the GitHub API. Files are signed by throwaway keys, never the
# release key. A throwaway git repository holds a copy of the Makefile and scripts/; where a target
# starts Mill or a JDK, a stand-in answers and records what it was asked. Nothing leaves
# localhost. The mill launcher has its own tests, scripts/test-mill-launcher. One line per check;
# exits non-zero when any check fails.
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

# released: the MiMa baseline's check. A version on Central, or with an immutable GitHub Release,
# is released (exit 0); one on neither Central nor GitHub is spent (exit 3), which the baseline
# passes over; any other answer stops it (exit 1). A site that does not serve a file yet decides
# nothing. Asked without a token, GitHub answers 404 for a draft, so a draft is spent; the draft
# case below guards a caller that would see one.
rel_json() { # VERSION IMMUTABLE DRAFT: the stand-in API's GitHub Release of tag vVERSION
  mkdir -p "$S/api/repos/$OWNER/$LIB/releases/tags"
  printf '{"tag_name": "v%s", "immutable": %s, "draft": %s, "prerelease": false}\n' "$1" "$2" "$3" > "$S/api/repos/$OWNER/$LIB/releases/tags/v$1"
}
RL() { R released --central "$CENTRAL" --api "$API" --repo "$OWNER/$LIB" --artifact "$DA" "$@"; }
mkdir -p "$S/central/dev/taihei/$DA/6.6.6"; printf 'pom' > "$S/central/dev/taihei/$DA/6.6.6/$DA-6.6.6.pom"
rel_json 6.6.7 true false; rel_json 6.6.9 false false; rel_json 6.6.10 true true
printf '{"tag_name": "v6.6.11", "immutable": true, "prerelease": false}\n' > "$S/api/repos/$OWNER/$LIB/releases/tags/v6.6.11"
expect "released: a version on Central" 0 "on Maven Central" RL 6.6.6
: > "$W/log"
expect "released: a version with an immutable GitHub Release, which no site need serve yet" 0 "has an immutable GitHub Release" RL 6.6.7
! grep -q 'auth=present' "$W/log" && pass "released: asks Central and the API without credentials" || fail "released: sent credentials"
RL 6.6.8 >/dev/null 2>&1
[ $? = 3 ] && pass "released: a tag with no GitHub Release and nothing on Central is spent, exit 3" || fail "released: a spent version does not exit 3"
expect "released: and says so, naming it" 1 "v6.6.8 has no GitHub Release" RL 6.6.8
mkdir -p "$S/site/dev/taihei/$DA/6.6.8"; printf 'pom' > "$S/site/dev/taihei/$DA/6.6.8/$DA-6.6.8.pom"
RL 6.6.8 >/dev/null 2>&1
[ $? = 3 ] && pass "released: a file on the site does not make a version without a GitHub Release released" || fail "released: the site decided"
expect "released: a GitHub Release that is not immutable stops it" 1 "not published and immutable" RL 6.6.9
expect "released: a draft, if the API ever shows one, stops it" 1 "not published and immutable" RL 6.6.10
expect "released: a GitHub Release that does not say it is no draft stops it" 1 "not published and immutable" RL 6.6.11
R released --central "$BASE/moved" --api "$API" --repo "$OWNER/$LIB" --artifact "$DA" 6.6.6 >/dev/null 2>&1
[ $? = 1 ] && pass "released: a redirect is no answer, exit 1, never spent" || fail "released: a redirect did not exit 1"
expect "released: and names it" 1 "HTTP 301" R released --central "$BASE/moved" --api "$API" --repo "$OWNER/$LIB" --artifact "$DA" 6.6.6

# newest: the newest x.y.z release that Central lists for every artifact asked, for
# make zio-json-newest. Anything it cannot read stops it, so the tests never run against a guess.
meta() { # GROUP DIRECTORY ARTIFACT VERSION...: Central's maven-metadata.xml in GROUP/DIRECTORY, naming ARTIFACT
  local g="$1" d="$S/central/${1//.//}/$2" a="$3"; shift 3; mkdir -p "$d"
  { printf '<?xml version="1.0" encoding="UTF-8"?>\n<metadata><groupId>%s</groupId><artifactId>%s</artifactId><versioning><versions>' "$g" "$a"
    printf '<version>%s</version>' "$@"; printf '</versions></versioning></metadata>\n'; } > "$d/maven-metadata.xml"
}
NW() { R newest --central "$CENTRAL" --group org.example "$@"; }
meta org.example lib_3 lib_3 0.7.1 0.7.9 0.7.43 0.7.44 0.8.0-RC1 1.0.0-M1 0.7.45-SNAPSHOT
meta org.example lib_sjs1_3 lib_sjs1_3 0.7.1 0.7.9 0.7.43 0.8.0-RC1
meta org.example pre_3 pre_3 0.8.0-RC1 1.0.0-M1
meta org.example other_3 other_3 0.6.0
expect "newest: the newest x.y.z, compared as numbers, not text" 0 "0.7.44" NW lib_3
[ "$(NW lib_3 2>/dev/null)" = 0.7.44 ] && pass "newest: prints the version alone" || fail "newest: printed '$(NW lib_3 2>&1)'"
[ "$(NW lib_3 lib_sjs1_3 2>/dev/null)" = 0.7.43 ] && pass "newest: the newest that every artifact has" || fail "newest: not the common version: '$(NW lib_3 lib_sjs1_3 2>&1)'"
expect "newest: only milestones and candidates is no answer" 1 "no x.y.z release" NW pre_3
expect "newest: artifacts with no version in common is no answer" 1 "no x.y.z release" NW lib_3 other_3
expect "newest: an artifact Central does not list stops it" 1 "HTTP 404" NW lib_3 missing_3
printf 'not xml' > "$S/central/org/example/other_3/maven-metadata.xml"
expect "newest: an answer that is not XML stops it" 1 "not XML" NW other_3
meta org.example other_3 lib_3 0.6.0
expect "newest: the metadata of another artifact stops it" 1 "not the maven-metadata.xml of other_3" NW other_3
expect "newest: a redirect stops it" 1 "HTTP 301" R newest --central "$BASE/moved" --group org.example lib_3
expect "newest: a group that is no Maven group is refused" 1 "not a Maven group" R newest --central "$CENTRAL" --group ../x lib_3
expect "newest: an artifact with .. is refused" 1 "not a Maven group" NW ..
expect "newest: Central over http is refused" 1 "not an https URL" R newest --central "http://localhost:$PORT/central" --group org.example lib_3

# candidates: the J2 reading of every release above a pin, for make jsoniter-candidates. The
# stand-in Central dates a file by its modification time; a version it cannot read in full fails it.
dated() { python3 -I -B -c 'import os, sys, time; t = 0 if sys.argv[2] == "none" else time.time() - int(sys.argv[2]) * 86400; os.utime(sys.argv[1], (t, t))' "$1" "$2"; }
cand_release() { # VERSION DAYS-AGO|none [LICENCE-XML]: a release of $CG:$CA on the stand-in Central, signed by the stand-in key
  local v="$1" d="$S/central/${CG//.//}/$CA/$1"; mkdir -p "$d"
  printf '<?xml version="1.0"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>%s</artifactId><version>%s</version>%s<scm><url>https://github.com/example/cand</url></scm></project>\n' \
    "$CA" "$v" "${3-<licenses><license><name>MIT</name></license></licenses>}" > "$d/$CA-$v.pom"
  printf 'jar %s\n' "$v" > "$d/$CA-$v.jar"
  sign "$KEY" "$d/$CA-$v.pom"; sign "$KEY" "$d/$CA-$v.jar"; dated "$d/$CA-$v.pom" "$2"
}
CG=org.example.cand; CA=core_3; CJ="$S/central/org/example/cand/$CA/2.10.0/$CA-2.10.0.jar"
meta "$CG" "$CA" "$CA" 2.7.0 2.8.0 2.10.0 2.9.0 2.11.0-RC1 3.0.0
# Only the versions above the pin have files: reading any other would fail with HTTP 404.
cand_release 2.9.0 15; cand_release 2.10.0 13
CAND() { R candidates --central "$CENTRAL" --group "$CG" --artifact "$CA" --key "$W/release-key.asc" --fingerprint "$KEY" "$@"; }
expect "candidates: the newest release on Central for 14 days or more" 0 "newest on Central for 14 days or more: 2.9.0" CAND --pin 2.8.0
[ "$(CAND --pin 2.8.0 2>/dev/null | grep -E '^[0-9]' | cut -d' ' -f1 | tr '\n' ' ')" = "2.9.0 2.10.0 " ] \
  && pass "candidates: the releases of the pin's major above it, in order of their numbers" || fail "candidates: listed $(CAND --pin 2.8.0 2>&1 | tr '\n' ';')"
expect "candidates: 15 days on Central is J2" 0 "2.9.0  on Central since $(date -u -d '15 days ago' +%Y-%m-%d) (15 days)  J2 yes" CAND --pin 2.8.0
expect "candidates: 13 days on Central is not" 0 "2.10.0  on Central since $(date -u -d '13 days ago' +%Y-%m-%d) (13 days)  J2 no" CAND --pin 2.8.0
expect "candidates: the signature, the licence and the release notes" 0 "jar and POM signed by $KEY  licence MIT  notes https://github.com/example/cand/releases/tag/v2.9.0" CAND --pin 2.8.0
expect "candidates: none on Central for 14 days" 0 "none above 2.9.0 has been on Central for 14 days or more" CAND --pin 2.9.0
expect "candidates: nothing above the pin" 0 "lists no 2.x release of $CG:$CA above the pin 2.10.0" CAND --pin 2.10.0
sign "$OTHER" "$CJ"
expect "candidates: a jar signed by another key stops it" 1 "$CA-2.10.0.jar: the signature does not verify" CAND --pin 2.8.0
expect "candidates: and still lists the others" 1 "2.9.0  on Central since" CAND --pin 2.8.0
sign "$KEY" "$CJ"; printf 'x' >> "$CJ"
expect "candidates: a jar that is not the one signed stops it" 1 "the signature does not verify (BADSIG" CAND --pin 2.8.0
cand_release 2.10.0 13; rm "$CJ.asc"
expect "candidates: a jar without a signature on Central stops it" 1 "$CA-2.10.0.jar.asc: HTTP 404" CAND --pin 2.8.0
cand_release 2.10.0 13 ''
expect "candidates: a POM that names no licence stops it" 1 "names no licence" CAND --pin 2.8.0
cand_release 2.10.0 none
expect "candidates: a POM without a date stops it" 1 "no Last-Modified date" CAND --pin 2.8.0
cand_release 2.10.0 13
expect "candidates: a key file that is not the pinned key stops it" 1 "is not the key $OTHER" CAND --pin 2.8.0 --fingerprint "$OTHER"
expect "candidates: a pin that is not x.y.z is refused" 1 "is not an x.y.z version" CAND --pin 2.8
expect "candidates: a redirect stops it" 1 "HTTP 301" R candidates --central "$BASE/moved" --group "$CG" --artifact "$CA" --pin 2.8.0 --key "$W/release-key.asc" --fingerprint "$KEY"
expect "candidates: Central over http is refused" 1 "not an https URL" R candidates --central "http://localhost:$PORT/central" --group "$CG" --artifact "$CA" --pin 2.8.0 --key "$W/release-key.asc" --fingerprint "$KEY"
DEAD=$(python3 -I -B -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
expect "candidates: Central unreachable stops it" 1 "maven-metadata.xml" R candidates --central "https://localhost:$DEAD/central" --group "$CG" --artifact "$CA" --pin 2.8.0 --key "$W/release-key.asc" --fingerprint "$KEY"

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
  "show "*".javaHome") echo "\"\${STANDIN_JAVA_HOME:-/nowhere}\"";;
  "show __."*) cat "$RESOLVED_JSON";;
  *".testForked"*) echo "tests zio-json=\${ZIO_JSON_TEST_VERSION-unset} \$*" >> "$MLOG.tests";;
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

# test-java-17: the JDK TAIHEI_TEST_JAVA_HOME names must be a 17, and every test module must fork
# on it, before any test runs. Stand-in JDKs print only their version.
for v in 17.0.9 21.0.1; do
  mkdir -p "$W/jdk-$v/bin"
  printf '#!/bin/sh\necho '"'"'openjdk version "%s" 2025-01-01'"'"' >&2\n' "$v" > "$W/jdk-$v/bin/java"; chmod +x "$W/jdk-$v/bin/java"
done
rm -f "$MLOG.tests"
expect "test-java-17 refuses without TAIHEI_TEST_JAVA_HOME" 1 "set TAIHEI_TEST_JAVA_HOME" env -u TAIHEI_TEST_JAVA_HOME "${M[@]}" test-java-17
expect "test-java-17 refuses a JDK that is not 17" 1 "is not a JDK 17" env TAIHEI_TEST_JAVA_HOME="$W/jdk-21.0.1" "${M[@]}" test-java-17
expect "test-java-17 refuses a test module that does not fork on it" 1 "does not fork on" \
  env TAIHEI_TEST_JAVA_HOME="$W/jdk-17.0.9" STANDIN_JAVA_HOME=/elsewhere "${M[@]}" test-java-17
[ ! -e "$MLOG.tests" ] && pass "test-java-17: and no test ran after a refusal" || fail "test-java-17 ran tests after a refusal"
expect "test-java-17 runs the tests on a JDK 17 every module forks on" 0 "" \
  env TAIHEI_TEST_JAVA_HOME="$W/jdk-17.0.9" STANDIN_JAVA_HOME="$W/jdk-17.0.9" "${M[@]}" test-java-17
grep -q '\.testForked' "$MLOG.tests" 2>/dev/null && pass "test-java-17: the tests ran" || fail "test-java-17: no test ran"

# zio-json-newest: with no version given it runs the zio-json tests against the newest release
# both zio-json artifacts have on Central; a version given in the environment or on the command
# line is used as it is, and Central is not asked; when Central cannot tell, no test runs.
ZJ="ziojson.jvm,ziojson.js"
for a in zio-json_3 zio-json_sjs1_3; do meta dev.zio "$a" "$a" 0.7.3 0.7.10 0.8.0-RC1; done
ran_with() { grep -qF "tests zio-json=$1 {$ZJ}.test.zioJsonTested + {$ZJ}.test.testForked" "$MLOG.tests" 2>/dev/null; }
rm -f "$MLOG.tests"
expect "zio-json-newest finds the newest zio-json on Central itself" 0 "the newest zio-json on Maven Central is 0.7.10" env -u ZIO_JSON_TEST_VERSION "${M[@]}" zio-json-newest
ran_with 0.7.10 && pass "zio-json-newest: and runs the tests against it" || fail "zio-json-newest: the tests ran with: $(cat "$MLOG.tests" 2>/dev/null)"
rm -f "$MLOG.tests"; : > "$W/log"
expect "zio-json-newest takes a version from the environment, and says so" 0 "ZIO_JSON_TEST_VERSION names zio-json 0.7.3" env ZIO_JSON_TEST_VERSION=0.7.3 "${M[@]}" zio-json-newest
ran_with 0.7.3 && pass "zio-json-newest: the tests ran against the environment's version" || fail "zio-json-newest: the tests ran with: $(cat "$MLOG.tests" 2>/dev/null)"
! grep -q 'maven-metadata' "$W/log" && pass "zio-json-newest: a version given does not ask Central" || fail "zio-json-newest: asked Central for a version it was given"
rm -f "$MLOG.tests"
expect "zio-json-newest takes a version from make's command line" 0 "" env -u ZIO_JSON_TEST_VERSION "${M[@]}" zio-json-newest ZIO_JSON_TEST_VERSION=0.7.3
ran_with 0.7.3 && pass "zio-json-newest: the tests ran against the command line's version" || fail "zio-json-newest: the tests ran with: $(cat "$MLOG.tests" 2>/dev/null)"
rm -f "$MLOG.tests" "$S/central/dev/zio/zio-json_sjs1_3/maven-metadata.xml"
expect "zio-json-newest stops when Central cannot tell" 1 "cannot tell the newest zio-json" env -u ZIO_JSON_TEST_VERSION "${M[@]}" zio-json-newest
[ ! -e "$MLOG.tests" ] && pass "zio-json-newest: and no test ran" || fail "zio-json-newest ran tests without a version"

# jsoniter-candidates: the J2 reading of jsoniter-scala-core_3 above build.mill's pin, with the
# Makefile's coordinates; only the key is the stand-in's.
CG=$(from_makefile JSONITER_GROUP); CA=$(from_makefile JSONITER_ARTIFACT)
[ "$CG:$CA" = "com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3" ] && pass "jsoniter-candidates reads jsoniter-scala-core_3" || fail "jsoniter-candidates reads $CG:$CA"
meta "$CG" "$CA" "$CA" 2.41.7 2.42.0 2.42.1 2.43.0
cand_release 2.42.1 20; cand_release 2.43.0 2
JC=("${M[@]}" "JSONITER_KEY=$KEY" "JSONITER_KEY_FILE=$G/keys/release-key.asc")
{ override "$SITE"; printf '  val jsoniter      = "2.42.0"\n'; } > "$G/build.mill"
expect "jsoniter-candidates reads the pin from build.mill" 0 "above the pin 2.42.0 on Maven Central" "${JC[@]}" jsoniter-candidates
expect "jsoniter-candidates: and gives the J2 reading" 0 "newest on Central for 14 days or more: 2.42.1" "${JC[@]}" jsoniter-candidates
expect "jsoniter-candidates stops without the committed key" 1 "keys/none.asc is missing" "${M[@]}" jsoniter-candidates "JSONITER_KEY_FILE=$G/keys/none.asc"
rm "$S/central/${CG//.//}/$CA/2.43.0/$CA-2.43.0.pom.asc"
expect "jsoniter-candidates fails when a version cannot be read" 1 "$CA-2.43.0.pom.asc: HTTP 404" "${JC[@]}" jsoniter-candidates
override "$SITE" > "$G/build.mill"
expect "jsoniter-candidates stops without a pin in build.mill" 1 "has no val jsoniter" "${JC[@]}" jsoniter-candidates

grep -q '^PUT\|^POST' "$W/log" && fail "something was uploaded to a stand-in server" || pass "nothing was ever uploaded"
[ "$FAILED" = 0 ] && echo "[release-test] all checks passed" || echo "[release-test] FAILED"
exit "$FAILED"
