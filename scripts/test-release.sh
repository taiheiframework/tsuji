#!/usr/bin/env bash
# Tests of the release machinery that never touch a real registry (make release-test, part of
# make validate): scripts/release.py, and repo-guard, release-preflight, release-github,
# publish-repo, verify-repo, central-bundle, publish, release-abort and the embargo in the
# Makefile, run against scripts/fake_servers.py on localhost, which stands in for Maven Central,
# the family's Maven repository and the GitHub API. A throwaway git repository, with a bare one as
# its origin, holds a copy of the Makefile and scripts/, tagged v9.9.9, with a synthetic bundle
# signed by throwaway keys (no Mill, never the release key): what is tested is what the targets do
# with a bundle, its signatures and the answers they get. Where a target starts Mill or gh, a
# stand-in answers and records what it was asked (and which credential variables reached Mill).
# The mill launcher's download check runs against a stand-in curl. Nothing leaves localhost. One
# line per check; exits non-zero when any check fails.
set -uo pipefail
# make hands its command-line variables to every make below it, through MAKEFLAGS and as
# environment variables: release-prepare TYPE=... EMBARGO=... runs make validate, and so this
# script, and the make calls here would inherit them (an EMBARGO=0 from outside answers the very
# question one check asks). TYPE, EMBARGO and VERSION are the ones the Makefile reads from there;
# COURSIER_* it reads from the environment. The release credentials and the launcher's version
# variables are cleared too, so that only what a check sets reaches the targets.
unset MAKEFLAGS MFLAGS MAKEOVERRIDES MAKELEVEL TYPE EMBARGO VERSION RELEASE_ENV COURSIER_CONFIG_DIR \
  COURSIER_REPOSITORIES COURSIER_MIRRORS COURSIER_CREDENTIALS \
  MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD MILL_PGP_SECRET_BASE64 MILL_PGP_PASSPHRASE \
  MILL_VERSION DEFAULT_MILL_VERSION MILL_TESTS_PUBLISH_DRY_RUN GH_TOKEN GITHUB_TOKEN

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
GROUP=dev.taihei
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
LIBRARY=$(from_makefile GITHUB_REPO); LIBRARY=${LIBRARY#*/}
[ -n "$ARTIFACTS" ] && [ -n "$PREFIX" ] && [ -n "$PUBLISHED" ] && [ -n "$FAMILY" ] && [ -n "$OWNER" ] && [ -n "$LIBRARY" ] \
  || { echo "cannot read ARTIFACTS, BUNDLE_PREFIX, PUBLISHED, FAMILY, OWNER and GITHUB_REPO from the Makefile"; exit 1; }
MODULES=${PUBLISHED//.publishArtifacts/}

# --- a synthetic tree in the layout Mill writes -----------------------------------------------
SRC="$W/src"
python3 -I -B - "$SRC" "$GROUP" "$V" $ARTIFACTS <<'EOF'
import hashlib, os, sys
src, group, v, artifacts = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
pom = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{g}</groupId><artifactId>{a}</artifactId><version>{v}</version>
  <name>{a}</name><description>test</description><url>https://example.invalid</url>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
  <scm><url>https://example.invalid</url><connection>scm:git:https://example.invalid</connection></scm>
  <developers><developer><id>t</id><name>Test</name></developer></developers>
</project>
"""
for a in artifacts:
    d = os.path.join(src, *group.split("."), a, v)
    os.makedirs(d)
    for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom"):
        f = os.path.join(d, f"{a}-{v}{suffix}")
        data = pom.format(g=group, a=a, v=v).encode() if suffix == ".pom" else os.urandom(64)
        open(f, "wb").write(data)
        open(f + ".md5", "w").write(hashlib.md5(data).hexdigest())
        open(f + ".sha1", "w").write(hashlib.sha1(data).hexdigest())
EOF

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
while IFS= read -r f; do sign "$KEY" "$f"; done < <(find "$SRC" -type f \( -name '*.jar' -o -name '*.pom' \) | sort)

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
expect "bundle: a complete tree becomes a bundle" 0 "files" R bundle --source "$SRC" --dest "$W/b1" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
R bundle --source "$SRC" --dest "$W/b2" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n >/dev/null 2>&1
cmp -s "$W/b1/n.zip" "$W/b2/n.zip" && cmp -s "$W/b1/SHA256SUMS" "$W/b2/SHA256SUMS" && pass "bundle: the same tree gives the same zip and ledger" || fail "bundle: not deterministic"
expect "bundle: an existing bundle is refused" 1 "built once" R bundle --source "$SRC" --dest "$W/b1" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
expect "verify: an untouched bundle passes" 0 "" R verify --dest "$W/b1"
[ "$(R verify --dest "$W/b1" | tail -n 1)" = "$(R verify --dest "$W/b1" | grep '\.pom$' | tail -n 1)" ] && pass "verify: POMs come last in the upload order" || fail "verify: upload order"
first_jar() { find "$1" -name "*-$V.jar" | sort | head -n 1; }
first_pom() { find "$1" -name "*-$V.pom" | sort | head -n 1; }
rehash() { python3 -I -B -c 'import hashlib,sys; d=open(sys.argv[1],"rb").read(); open(sys.argv[1]+".md5","w").write(hashlib.md5(d).hexdigest()); open(sys.argv[1]+".sha1","w").write(hashlib.sha1(d).hexdigest())' "$1"; }
cp -r "$SRC" "$W/s3"; rm "$(first_jar "$W/s3").asc"
expect "bundle: a missing signature is refused" 1 "missing from the bundle" R bundle --source "$W/s3" --dest "$W/b3" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s4"; echo 00 > "$(first_jar "$W/s4").md5"
expect "bundle: a wrong checksum is refused" 1 "does not match" R bundle --source "$W/s4" --dest "$W/b4" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s5"; touch "$(dirname "$(first_jar "$W/s5")")/extra.txt"
expect "bundle: an extra file is refused" 1 "not expected in the bundle" R bundle --source "$W/s5" --dest "$W/b5" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s6"; P6=$(first_pom "$W/s6"); sed -i.bak 's#<description>test</description>##' "$P6"; rm "$P6.bak"; rehash "$P6"
expect "bundle: a POM without what Central needs is refused" 1 "no description" R bundle --source "$W/s6" --dest "$W/b6" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
for tag in repositories pluginRepositories; do
  rm -rf "$W/s6r" "$W/b6r"; cp -r "$SRC" "$W/s6r"; P6=$(first_pom "$W/s6r")
  sed -i.bak "s#</developers>#</developers><$tag><repository><id>x</id><url>https://example.invalid/maven</url></repository></$tag>#" "$P6"; rm "$P6.bak"; rehash "$P6"
  expect "bundle: a POM that names $tag is refused" 1 "names $tag" R bundle --source "$W/s6r" --dest "$W/b6r" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
done
cp -r "$SRC" "$W/s7"; ln -s /etc/hostname "$(dirname "$(first_jar "$W/s7")")/link"
expect "bundle: a symlink is refused" 1 "not a regular file" R bundle --source "$W/s7" --dest "$W/b7" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
for change in changed extra zip; do
  rm -rf "$W/b8"; cp -r "$W/b1" "$W/b8"
  case $change in
    changed) printf x >> "$(first_jar "$W/b8/repository")";;
    extra) touch "$W/b8/repository/extra";;
    zip) printf x >> "$W/b8/n.zip";;
  esac
  expect "verify: a $change file in the kept bundle is refused" 1 "" R verify --dest "$W/b8"
done
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

# resolved (C12): a stand-in Coursier cache, with a release of the library on the stand-in API.
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

# released: the MiMa baseline's check; a release tag on neither Central nor the site fails.
mkdir -p "$S/central/dev/taihei/$DA/6.6.6" "$S/site/dev/taihei/$DA/6.6.7"
printf 'pom' > "$S/central/dev/taihei/$DA/6.6.6/$DA-6.6.6.pom"; printf 'pom' > "$S/site/dev/taihei/$DA/6.6.7/$DA-6.6.7.pom"
expect "released: a version on Central" 0 "on Maven Central" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.6
expect "released: a version on the site" 0 "on $SITE" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.7
expect "released: a version on neither fails, naming it" 1 "v6.6.8 is neither on Maven Central nor on" R released --central "$CENTRAL" --url "$SITE" --artifact "$DA" 6.6.8
expect "released: a redirect fails" 1 "HTTP 301" R released --central "$CENTRAL" --url "$BASE/moved/" --artifact "$DA" 6.6.7

# --- the mill launcher ----------------------------------------------------------------------
# A stand-in curl records its arguments and writes a file that is not Mill where -o points.
L="$W/launcher"; mkdir -p "$L/bin" "$L/cwd"
cat > "$L/bin/curl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$L/curl-args"
out=""; while [ \$# -gt 0 ]; do [ "\$1" = -o ] && out="\$2"; shift; done
[ -n "\$out" ] && printf 'not mill' > "\$out"
EOF
chmod +x "$L/bin/curl"
launch() { (cd "$L/cwd" && env PATH="$L/bin:$PATH" MILL_FINAL_DOWNLOAD_FOLDER="$L/dl" MILL_OUTPUT_DIR="$L/out" "$@" sh "$REPO/mill" --version); }
expect "launcher: a download that does not match the pinned SHA-256 is refused" 1 "not the pinned" launch MILL_VERSION=1.3.1
[ -s "$L/curl-args" ] && [ ! -e "$L/dl" ] && [ ! -e "$L/out/mill-temp-download" ] \
  && pass "launcher: and it is neither kept nor made executable" || fail "launcher: the refused download was kept"
grep -qF -- "--proto =https --proto-redir =https" "$L/curl-args" \
  && pass "launcher: curl may use HTTPS only, also after a redirect" || fail "launcher: curl's protocols are not limited: $(cat "$L/curl-args")"
: > "$L/curl-args"
expect "launcher: a version without a pinned SHA-256 is refused" 1 "No SHA-256 is pinned" launch MILL_VERSION=1.3.0
[ ! -s "$L/curl-args" ] && pass "launcher: and nothing is downloaded for it" || fail "launcher: curl ran for an unpinned version"

# --- the Makefile targets, with stand-ins for Mill and gh --------------------------------------
G="$W/repo"; mkdir -p "$G/scripts" "$G/keys"
cp "$REPO/Makefile" "$G/"; cp "$REPO/scripts/release.py" "$G/scripts/"; cp "$W/release-key.asc" "$G/keys/"
override "$SITE" > "$G/build.mill"
printf '# Release %s\n' "$V" > "$G/RELEASE_${V//./_}.md"
B="$G/.release-bundles/$PREFIX-$V"
MLOG="$W/mill-env"; GLOG="$W/gh-log"; GHS="$W/gh"; mkdir -p "$GHS" "$W/bin"

# The stand-in Mill answers the guards and repo-guard's questions, and records which Central token
# variables reached each Mill call before the publishing one (in $MLOG.pre), and for the
# publishing command which credential variables reached it (their names, never their values), its
# arguments, and whether the Central marker was there when it started. Every call is also logged
# in $MLOG.calls: its arguments, the names of the credential variables it saw, and whether any
# environment variable held the marker value cmdline-secret, which a check puts on make's command
# line (make hands it on in MAKEFLAGS). STANDIN_VERSION, when set, is the version it reports.
# Mill 1.3.1 signs in its own JVM; only an added --useGpgCli would hand the passphrase to a gpg
# command line, so the arguments must be exactly these.
POMS_JSON="$W/poms.json"; RESOLVERS_JSON="$W/resolvers.json"; RESOLVED_JSON="$W/rg.json"; RESOLVING="$W/resolving"
cat > "$G/mill" <<EOF
#!/usr/bin/env bash
SEEN=""; for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD MILL_PGP_SECRET_BASE64 MILL_PGP_PASSPHRASE; do
  [ -n "\${!v+x}" ] && SEEN="\$SEEN \$v"
done
LEAK=no; env | grep -qF cmdline-secret && LEAK=yes
echo "\$* |\${SEEN:- none} | leak \$LEAK" >> "$MLOG.calls"
case "\$*" in
  shutdown|"resolve __.publishArtifacts"|"--no-daemon resolve __.publishArtifacts")
    N=shutdown; [ "\$1" = shutdown ] || N=resolve
    for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD; do
      if [ -n "\${!v+x}" ]; then echo "\$N \$v set"; else echo "\$N \$v unset"; fi
    done >> "$MLOG.pre"
    [ "\$N" = shutdown ] || printf '%s\n' $PUBLISHED;;
  "--no-daemon show release.gitVersion")
    for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD; do
      if [ -n "\${!v+x}" ]; then echo "show \$v set"; else echo "show \$v unset"; fi
    done > "$MLOG.show"
    echo "\"\${STANDIN_VERSION:-$V}\"";;
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
    echo "args \$*" >> "$MLOG"
    if [ -e "$B.CENTRAL_UPLOAD_STARTED" ]; then echo "marker before mill" >> "$MLOG"; fi;;
  *) echo "stand-in mill: unexpected \$*" >&2; exit 1;;
esac
EOF
chmod +x "$G/mill"

# The stand-in gh logs every call. Its GitHub Releases live in $GHS: "releases" lists the tags
# and names that exist, "draft/" a draft's assets, "published" marks it published, "immutable"
# what the API reports (true by default). A dispatched deploy copies the bundle onto the stand-in
# site, unless "deploy-skip" exists, and shows as run 4242 beside an older run 4100 ("no-run": it
# never shows, "two-runs": another new one shows too); "run-fails" makes the run fail. "corrupt"
# changes a draft's zip, "corrupt-published" the zip downloaded once it is published.
cat > "$W/bin/gh" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$GLOG"
case "\$1 \$2" in
  "api --paginate") [ -e "$GHS/list-fails" ] && exit 1; cat "$GHS/releases" 2>/dev/null; exit 0;;
  "release create")
    mkdir -p "$GHS/draft"; shift 2
    while [ \$# -gt 0 ]; do case "\$1" in --repo|--title|--notes-file) [ "\$1" = --notes-file ] && cp "\$2" "$GHS/body.md"; shift 2;; --*) shift;; *) [ -f "\$1" ] && cp "\$1" "$GHS/draft/"; shift;; esac; done
    [ -e "$GHS/corrupt" ] && for z in "$GHS"/draft/*.zip; do printf x >> "\$z"; done
    printf 'v$V\n$V\n' >> "$GHS/releases"; exit 0;;
  "release download")
    dir=""; while [ \$# -gt 0 ]; do [ "\$1" = --dir ] && dir="\$2"; shift; done
    mkdir -p "\$dir"; cp "$GHS"/draft/* "\$dir/"
    [ -e "$GHS/published" ] && [ -e "$GHS/corrupt-published" ] && for z in "\$dir"/*.zip; do printf x >> "\$z"; done
    exit 0;;
  "release edit") touch "$GHS/published"; exit 0;;
  "api repos/"*)
    [ -e "$GHS/published" ] || exit 1
    IM=\$(cat "$GHS/immutable" 2>/dev/null || echo true)
    case "\$*" in *"--jq .immutable") echo "\$IM";; *) echo "\$IM false false";; esac; exit 0;;
  "workflow run")
    if [ ! -e "$GHS/deploy-skip" ]; then
      ( cd "$B/repository" && find . -type f | while read -r f; do mkdir -p "$S/site/\$(dirname "\$f")"; cp "\$f" "$S/site/\$f"; done )
      cat "$B/SHA256SUMS" >> "$S/site/SHA256SUMS"
    fi
    touch "$GHS/dispatched"; exit 0;;
  "run list")
    echo 4100; [ -e "$GHS/no-run" ] && exit 0
    [ -e "$GHS/dispatched" ] && echo 4242; [ -e "$GHS/dispatched" ] && [ -e "$GHS/two-runs" ] && echo 4243; exit 0;;
  "run watch") [ -e "$GHS/run-fails" ] && exit 1; exit 0;;
esac
echo "stand-in gh: unexpected \$*" >&2; exit 1
EOF
chmod +x "$W/bin/gh"

git -C "$G" init -q; git -C "$G" add -A
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -qm test
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -a "v$V" -m test
git init -q --bare "$W/origin.git"; git -C "$G" remote add origin "$W/origin.git"
mkdir -p "$G/.release-bundles"; R bundle --source "$SRC" --dest "$B" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name "$PREFIX-$V" >/dev/null
COUNT=$(wc -l < "$B/SHA256SUMS" | tr -d ' ')
ZIPSUM=$(cut -c1-64 "$B/$PREFIX-$V.zip.sha256")

M=(env PATH="$W/bin:$PATH" make -C "$G" --no-print-directory "REPO_URL=$SITE" "CENTRAL=$CENTRAL" "GITHUB_API=$API"
   "RELEASE_KEY=$KEY" "RELEASE_KEY_FILE=$G/keys/release-key.asc" "RELEASE_ENV=$W/no-release-env"
   "GITHUB_GIT=$W/origin.git" VERIFY_TRIES=2 VERIFY_WAIT=0 DISPATCH_WAIT=0)
gh_reset() { rm -rf "$GHS"; mkdir -p "$GHS"; : > "$GLOG"; }
gh_called() { grep -q -- "$1" "$GLOG"; }

# repo-guard, end to end with the stand-in Mill: the pin check and the C12 check of what was
# resolved. One published POM pins the stand-in library's version on the site.
resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$RESOLVERS_JSON"
resolving $MODULES > "$RESOLVING"
PIN="$W/pin.pom"; deps_pom "$PIN" "$(dep dev.taihei "$DA" "$DV")"
printf '{"m.pom": "qref:v1:0123abcd:%s"}\n' "$PIN" > "$POMS_JSON"
mkdir -p "$S/site/dev/taihei/$DA/$DV"; cp "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.pom" "$S/site/dev/taihei/$DA/$DV/"
printf '{"m.resolvedRunMvnDeps": [%s, %s]}\n' "$(ref "$SITEC/dev/taihei/$DA/$DV/$DA-$DV.jar")" "$(ref "$CENTC/org/x/x/1.0/x-1.0.jar")" > "$RESOLVED_JSON"
release_api "$LIB" "$DV" true
expect "repo-guard passes a pin on the site and files that match their release" 0 "and nothing else" "${M[@]}" repo-guard
# Every Mill call of a step that needs no credential, with a Central token and the release key
# exported in the shell and a value on make's command line: none of them may see either.
TOKENS=(MILL_SONATYPE_USERNAME=env-user MILL_SONATYPE_PASSWORD=env-token MILL_PGP_SECRET_BASE64=env-key MILL_PGP_PASSPHRASE=env-pass)
no_credential_reached_mill() {
  [ -s "$MLOG.calls" ] && ! grep -v '| none | leak no$' "$MLOG.calls" >/dev/null
}
rm -f "$MLOG.calls"
expect "repo-guard passes with credentials in the shell and on make's command line" 0 "and nothing else" env "${TOKENS[@]}" "${M[@]}" repo-guard MILL_PGP_PASSPHRASE=cmdline-secret
no_credential_reached_mill && pass "repo-guard: no Mill call sees a credential, neither from the shell nor from make's command line" \
  || fail "repo-guard: a Mill call saw a credential: $(grep -v '| none | leak no$' "$MLOG.calls" 2>/dev/null | tr '\n' ';')"
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
expect "repo-guard refuses a resolved file that differs from its release (C12)" 1 "differs from the SHA256SUMS" "${M[@]}" repo-guard
restore_jar
resolvers_json "$MODULES" "$ONLY" "$IVY" https://repo1.maven.org/maven2 > "$RESOLVERS_JSON"
expect "repo-guard refuses a module that asks the site before Central" 1 "not Coursier's defaults" "${M[@]}" repo-guard
resolvers_json "$MODULES" "$IVY" https://repo1.maven.org/maven2 "$ONLY" > "$RESOLVERS_JSON"
expect "repo-guard refuses a REPO_URL that build.mill does not name" 1 "the Makefile's REPO_URL" "${M[@]}" repo-guard "REPO_URL=$BASE/other/"
expect "repo-guard refuses Coursier repositories from the environment" 1 "COURSIER_REPOSITORIES" env COURSIER_REPOSITORIES=https://example.invalid "${M[@]}" repo-guard

# release-preflight: every file 404 on Central and on the site, no tag on origin, no release.
gh_reset
expect "preflight passes a version that is nowhere yet" 0 "has no GitHub Release for it" "${M[@]}" release-preflight
gh_called "api --paginate repos/" && pass "preflight lists the GitHub Releases, drafts included" || fail "preflight did not ask for the releases"
FIRST=$(echo $ARTIFACTS | cut -d' ' -f1)
for where in central site; do
  mkdir -p "$S/$where/dev/taihei/$FIRST/$V"; printf x > "$S/$where/dev/taihei/$FIRST/$V/$FIRST-$V-sources.jar"
  expect "preflight refuses a version whose file is on $where" 1 "released already" "${M[@]}" release-preflight
  rm -rf "$S/$where/dev/taihei/$FIRST"
done
expect "preflight stops on a redirect from the site" 1 "HTTP 301" "${M[@]}" release-preflight "REPO_URL=$BASE/moved/"
printf 'v%s\n%s\n' "$V" "$V" > "$GHS/releases"
expect "preflight refuses a version that has a GitHub Release, or a draft" 1 "exists already" "${M[@]}" release-preflight
gh_reset; touch "$GHS/list-fails"
expect "preflight stops when it cannot list the releases" 1 "cannot list" "${M[@]}" release-preflight
gh_reset
rm -f "$MLOG.calls"
expect "preflight passes with credentials in the shell and on make's command line" 0 "has no GitHub Release for it" \
  env "${TOKENS[@]}" "${M[@]}" release-preflight MILL_PGP_PASSPHRASE=cmdline-secret
no_credential_reached_mill && pass "preflight: no Mill call sees a credential, neither from the shell nor from make's command line" \
  || fail "preflight: a Mill call saw a credential: $(grep -v '| none | leak no$' "$MLOG.calls" 2>/dev/null | tr '\n' ';')"
! grep -v '^--no-daemon ' "$MLOG.calls" >/dev/null && pass "preflight: every Mill call runs without a daemon" \
  || fail "preflight: a Mill call may start a daemon: $(grep -v '^--no-daemon ' "$MLOG.calls" | tr '\n' ';')"
gh_reset

# The signatures, checked by release-github before anything is created (as by release-bundle).
use_bundle() { rm -rf "$B"; R bundle --source "$1" --dest "$B" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name "$PREFIX-$V" >/dev/null; }
cp -r "$SRC" "$W/u1"; J1=$(first_jar "$W/u1"); printf -- '-----BEGIN PGP SIGNATURE-----\nnot one\n-----END PGP SIGNATURE-----\n' > "$J1.asc"
use_bundle "$W/u1"; expect "release-github refuses a signature that does not verify" 1 "does not verify" "${M[@]}" release-github
cp -r "$SRC" "$W/u2"; J2=$(first_jar "$W/u2"); sign "$OTHER" "$J2"
use_bundle "$W/u2"; expect "release-github refuses a file signed by another key" 1 "not signed by the release key" "${M[@]}" release-github
cp -r "$SRC" "$W/u3"; J3=$(first_jar "$W/u3"); printf x >> "$J3"; rehash "$J3"
use_bundle "$W/u3"; expect "release-github refuses a file changed after it was signed" 1 "does not verify" "${M[@]}" release-github
use_bundle "$SRC"
expect "release-github refuses another number of signatures" 1 "not 9" "${M[@]}" release-github SIGNATURES=9

echo "$V" > "$G/.release-embargo"
expect "release-github refuses a version under embargo" 1 "under embargo" "${M[@]}" release-github
expect "publish-repo refuses a version under embargo" 1 "under embargo" "${M[@]}" publish-repo
rm "$G/.release-embargo"
printf '{"newVersion":"%s","head":"x","embargo":true}\n' "$V" > "$G/.release-state.json"
expect "release-github refuses while an embargoed release is prepared" 1 "under embargo" "${M[@]}" release-github
expect "publish-repo refuses while an embargoed release is prepared" 1 "under embargo" "${M[@]}" publish-repo
printf 'not json' > "$G/.release-state.json"
expect "an unreadable release state refuses, since it may hold an embargo" 1 "cannot be read" "${M[@]}" release-github
rm "$G/.release-state.json"
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m after
expect "release-github refuses a HEAD that is not the release tag" 1 "not at a release tag" "${M[@]}" release-github
git -C "$G" reset -q --hard "v$V"
echo "# edited" >> "$G/Makefile"
expect "release-github refuses tracked changes at the tag" 1 "tracked changes" "${M[@]}" release-github
git -C "$G" checkout -q Makefile
expect "release-github refuses before the tag is pushed" 1 "push main and the tag first" "${M[@]}" release-github
! gh_called "release create" && pass "no refusal created a release" || fail "a refusal created a release"

# release-abort: until the tag is pushed it undoes the preparation; from the push on, nothing.
printf '{"currentVersion":"0.0.0","newVersion":"%s","releaseType":"patch","branch":"main","head":"x","embargo":false}\n' "$V" > "$G/.release-state.json"
expect "release-abort removes a bundle whose tag was never pushed" 0 "never left this machine" "${M[@]}" release-abort
[ ! -e "$B" ] && ! git -C "$G" rev-parse -q --verify "refs/tags/v$V" >/dev/null && pass "the bundle and the local tag are gone" || fail "the bundle or the tag stayed"
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -a "v$V" -m test
use_bundle "$SRC"
git -C "$G" push -q origin "refs/tags/v$V" 2>/dev/null
gh_reset
expect "release-github refuses while main is not on GitHub" 1 "main is not on taiheiframework/" "${M[@]}" release-github
! gh_called "release create" && pass "and creates nothing" || fail "it created a release without main on origin"
git -C "$G" push -q origin HEAD:refs/heads/main 2>/dev/null
printf '{"currentVersion":"0.0.0","newVersion":"%s","releaseType":"patch","branch":"main","head":"x","embargo":false}\n' "$V" > "$G/.release-state.json"
expect "release-abort undoes nothing once the tag is on origin" 1 "the version is final" "${M[@]}" release-abort
git -C "$G" rev-parse -q --verify "refs/tags/v$V" >/dev/null && [ -d "$B" ] && [ -e "$G/.release-state.json" ] && pass "the tag, the bundle and the state stay" || fail "release-abort removed something"
expect "preflight refuses a version whose tag is on GitHub" 1 "already: the version is final" "${M[@]}" release-preflight

# release-github: a draft, read back and compared, then published and checked immutable.
gh_reset; printf 'v%s\n%s\n' "$V" "$V" > "$GHS/releases"
expect "release-github refuses when a release for the version exists" 1 "exists already" "${M[@]}" release-github
! gh_called "release create" && pass "and creates nothing" || fail "it created a release"
gh_reset; touch "$GHS/corrupt"
expect "release-github leaves a draft whose assets differ unpublished" 1 "the draft stays unpublished" "${M[@]}" release-github
! gh_called "release edit" && pass "and never publishes it" || fail "it published a draft whose assets differ"
gh_reset; echo false > "$GHS/immutable"
expect "release-github stops when GitHub does not report the release immutable" 1 "not report the release v$V as immutable" "${M[@]}" release-github
gh_reset; touch "$GHS/corrupt-published"
expect "release-github reads the published release back" 1 "the release's assets differ" "${M[@]}" release-github
gh_reset
expect "release-github publishes the bundle" 0 "record on the release ticket: $PREFIX-$V.zip SHA-256 $ZIPSUM" "${M[@]}" release-github
grep -q -- "release create v$V --repo .* --verify-tag --draft" "$GLOG" && [ "$(grep -n 'release create' "$GLOG" | cut -d: -f1)" -lt "$(grep -n 'release edit' "$GLOG" | cut -d: -f1)" ] \
  && pass "release-github creates a draft first and publishes it after the read-back" || fail "release-github's order: $(tr '\n' ';' < "$GLOG")"
grep -qF "$ZIPSUM" "$GHS/body.md" && grep -qF "$SITE" "$GHS/body.md" && pass "the release body names the zip's SHA-256 and the repository" || fail "the release body"
[ ! -e "$G/.release-state.json" ] && pass "release-github ends the release in progress" || fail "the release state stayed"

# publish-repo and verify-repo, against the stand-in site, without credentials.
: > "$W/log"
expect "publish-repo deploys the site and reads every file back" 0 "all $COUNT files of $V are served unchanged" "${M[@]}" publish-repo
gh_called "workflow run deploy.yml --repo taiheiframework/repo --ref main" && gh_called "run watch 4242" \
  && pass "publish-repo dispatches the deploy on main and waits for its run" || fail "publish-repo's gh calls: $(tr '\n' ';' < "$GLOG")"
[ "$(grep -c '^GET /site/' "$W/log")" -ge "$COUNT" ] && ! grep -q 'auth=present' "$W/log" \
  && pass "verify-repo read every file without credentials" || fail "verify-repo's reads"
J=$(first_jar "$S/site"); cp "$J" "$W/served.jar"; printf x >> "$J"
expect "verify-repo reports a changed file as an incident" 1 "SECURITY INCIDENT" "${M[@]}" verify-repo
cp "$W/served.jar" "$J"; rm "$J"
expect "verify-repo reports a file still missing after its tries" 1 "after 2 tries" "${M[@]}" verify-repo
cp "$W/served.jar" "$J"
cp "$S/site/SHA256SUMS" "$W/site-sums"; sed -i.bak '1d' "$S/site/SHA256SUMS"; rm -f "$S/site/SHA256SUMS.bak"
expect "verify-repo wants every ledger line in the site's SHA256SUMS" 1 "lines of the ledger" "${M[@]}" verify-repo
cp "$W/site-sums" "$S/site/SHA256SUMS"
expect "verify-repo follows no redirect" 1 "HTTP 301" "${M[@]}" verify-repo "REPO_URL=$BASE/moved/"
gh_called "run watch 4242" && ! gh_called "run watch 4100" && pass "publish-repo watches the run its dispatch started, not an older one" || fail "publish-repo watched another run"
echo false > "$GHS/immutable"
expect "publish-repo refuses a release GitHub does not report immutable" 1 "not published and immutable" "${M[@]}" publish-repo
rm "$GHS/immutable"; rm -f "$GHS/dispatched"; touch "$GHS/no-run"
expect "publish-repo stops when no run of the deploy appears" 1 "no run of" "${M[@]}" publish-repo
rm "$GHS/no-run" "$GHS/dispatched"; touch "$GHS/two-runs"
expect "publish-repo stops when more than one new run appears" 1 "more than one new run" "${M[@]}" publish-repo
rm "$GHS/two-runs" "$GHS/dispatched"
touch "$GHS/run-fails"
expect "publish-repo stops on a failed deploy run" 1 "failed" "${M[@]}" publish-repo
rm "$GHS"/published
expect "publish-repo refuses a version without a published release" 1 "make release-github first" "${M[@]}" publish-repo
gh_reset

# make publish, the direct Central upload, signs again: only for an embargoed version, and never
# for one the site serves.
expect "publish refuses a version that is not under embargo" 1 "is not under embargo" "${M[@]}" publish
echo "$V" > "$G/.release-embargo"
expect "publish refuses a version the site serves" 1 "not 404" "${M[@]}" publish
rm "$G/.release-embargo"
expect "release-prepare wants the embargo decided" 1 "never a default" "${M[@]}" release-prepare TYPE=patch

printf '%s\n1.2.3\n' "$V" > "$G/.release-embargo"
expect "embargo-lift lifts one version" 0 "no longer under embargo" "${M[@]}" embargo-lift "VERSION=$V"
[ "$(cat "$G/.release-embargo")" = "1.2.3" ] && pass "and keeps the others" || fail "embargo-lift changed the other versions"
[ -f "$B.EMBARGO_LIFTED" ] && pass "embargo-lift records the lift next to the bundle" || fail "embargo-lift wrote no lift"
rm -f "$B.EMBARGO_LIFTED"
expect "embargo-lift records a lift for a version not listed here, saying so" 0 "not listed under embargo here" "${M[@]}" embargo-lift "VERSION=$V"
rm -f "$G/.release-embargo" "$B.EMBARGO_LIFTED"

# A security fix's bundle comes from Central's files (C14): nothing is signed again.
rm -rf "$S/site/dev" "$B"
cp -r "$SRC/dev" "$S/central/"
expect "central-bundle wants the lift recorded first" 1 "make embargo-lift VERSION=$V first" "${M[@]}" central-bundle
"${M[@]}" embargo-lift "VERSION=$V" >/dev/null 2>&1
expect "central-bundle builds the bundle from Central's files" 0 "nothing was signed again" "${M[@]}" central-bundle
mv "$B.EMBARGO_LIFTED" "$W/lifted"
expect "release-github refuses a bundle from Central without a recorded lift, also with no embargo file" 1 "went to Maven Central first" "${M[@]}" release-github
expect "publish-repo refuses it too" 1 "went to Maven Central first" "${M[@]}" publish-repo
mv "$W/lifted" "$B.EMBARGO_LIFTED"
diff <(cd "$SRC" && find . -type f -name '*.asc' | sort | xargs sha256sum) <(cd "$B/repository" && find . -type f -name '*.asc' | sort | xargs sha256sum) >/dev/null \
  && pass "central-bundle keeps Central's signature bytes" || fail "central-bundle's signatures differ from Central's"
expect "central-bundle builds a bundle once" 1 "built once" "${M[@]}" central-bundle
rm -rf "$B"; CJ=$(first_jar "$S/central/dev"); cp "$CJ.asc" "$W/central.asc"; sign "$OTHER" "$CJ"
expect "central-bundle refuses a file on Central signed by another key" 1 "not signed by the release key" "${M[@]}" central-bundle
cp "$W/central.asc" "$CJ.asc"; rm -rf "$B.partial" "$B.download"
"${M[@]}" central-bundle >/dev/null 2>&1
rm -rf "$B"; DRY="$G/out/mill.javalib.SonatypeCentralPublishModule/publishAll.dest/repository/$PREFIX-$V"
mkdir -p "$DRY"; cp -r "$SRC/dev" "$DRY/"
expect "central-bundle compares Central's files with the local publish directory" 0 "byte-equal to the local publish directory" "${M[@]}" central-bundle
rm -rf "$B"; printf x >> "$(first_jar "$DRY")"
expect "central-bundle refuses files that differ from the local publish directory" 1 "differ from the local publish directory" "${M[@]}" central-bundle
[ ! -e "$B" ] && pass "and keeps no bundle" || fail "a bundle was kept"
rm -rf "$G/out" "$S/central/dev/taihei/$FIRST" "$B.partial" "$B.download"
expect "central-bundle refuses a version Central does not have" 1 "is not on Maven Central" "${M[@]}" central-bundle
rm -rf "$S/central/dev"

# --- the targets that start Mill to publish, against the stand-in --------------------------
( umask 077
  printf "export MILL_SONATYPE_USERNAME='test-central-user'\nexport MILL_SONATYPE_PASSWORD='test-central-token'\n" > "$W/release.env"
  printf "export MILL_PGP_SECRET_BASE64='dGVzdA=='\nexport MILL_PGP_PASSPHRASE='test'\n" >> "$W/release.env" )
saw() { grep -qxF "$1" "$MLOG" 2>/dev/null; }
ARGS="args --no-daemon mill.javalib.SonatypeCentralPublishModule/ --bundleName $PREFIX-$V"

rm -f "$MLOG"
expect "publish-dry-run runs with the release env" 0 "bundle in" "${M[@]}" publish-dry-run "RELEASE_ENV=$W/release.env"
saw "MILL_SONATYPE_USERNAME placeholder" && saw "MILL_SONATYPE_PASSWORD placeholder" \
  && grep -qxF "show MILL_SONATYPE_USERNAME unset" "$MLOG.show" && grep -qxF "show MILL_SONATYPE_PASSWORD unset" "$MLOG.show" \
  && pass "publish-dry-run: Mill gets a placeholder, never the Central token from the release env" || fail "publish-dry-run: a Mill saw the Central token, or no placeholder"
saw "MILL_PGP_SECRET_BASE64 set" && saw "MILL_PGP_PASSPHRASE set" && saw "MILL_TESTS_PUBLISH_DRY_RUN set" \
  && pass "publish-dry-run: Mill gets the release key and the dry-run switch" || fail "publish-dry-run: Mill lacks the key or the switch"
saw "$ARGS" && pass "publish-dry-run: Mill gets the bundle name and no other argument" || fail "publish-dry-run: Mill's arguments: $(grep '^args' "$MLOG")"
rm -f "$MLOG" "$MLOG.pre"
MILL_SONATYPE_USERNAME=env-user MILL_SONATYPE_PASSWORD=env-token "${M[@]}" publish-dry-run >/dev/null 2>&1
saw "MILL_SONATYPE_USERNAME placeholder" && saw "MILL_SONATYPE_PASSWORD placeholder" \
  && pass "publish-dry-run: nor a Central token from the environment" || fail "publish-dry-run: Mill saw the Central token from the environment"
grep -q "^shutdown " "$MLOG.pre" 2>/dev/null && grep -q "^resolve " "$MLOG.pre" && ! grep -q " set$" "$MLOG.pre" \
  && pass "publish-dry-run: no Mill call, shutdown and the module guard included, sees a Central token from the environment" \
  || fail "publish-dry-run: a Mill call before the dry run saw the Central token from the environment: $(grep ' set$' "$MLOG.pre" 2>/dev/null | tr '\n' ' ')"
rm -f "$MLOG" "$MLOG.calls"
"${M[@]}" publish-dry-run "RELEASE_ENV=$W/release.env" MILL_SONATYPE_PASSWORD=cmdline-secret >/dev/null 2>&1
saw "MILL_SONATYPE_PASSWORD placeholder" && ! grep -q 'leak yes$' "$MLOG.calls" \
  && pass "publish-dry-run: a value on make's command line reaches no Mill call" \
  || fail "publish-dry-run: a Mill call saw a value from make's command line: $(grep 'leak yes$' "$MLOG.calls" 2>/dev/null | tr '\n' ';')"
! grep -v -e '^--no-daemon ' -e '^shutdown ' "$MLOG.calls" >/dev/null && pass "publish-dry-run: no Mill call but shutdown runs on a daemon" \
  || fail "publish-dry-run: a Mill call may start a daemon: $(grep -v -e '^--no-daemon ' -e '^shutdown ' "$MLOG.calls" | tr '\n' ';')"
rm -f "$MLOG"; chmod 640 "$W/release.env"
expect "publish-dry-run refuses a release env others can read" 1 "mode 600" "${M[@]}" publish-dry-run "RELEASE_ENV=$W/release.env"
chmod 600 "$W/release.env"; ln -s "$W/release.env" "$W/link.env"
expect "publish-dry-run refuses a release env that is a link" 1 "not a link" "${M[@]}" publish-dry-run "RELEASE_ENV=$W/link.env"
ln -s "$W/missing.env" "$W/dangling.env"
expect "publish-dry-run refuses a release env that is a dangling link" 1 "not a link" "${M[@]}" publish-dry-run "RELEASE_ENV=$W/dangling.env"
[ ! -e "$MLOG" ] && pass "and Mill never ran for a refused release env" || fail "Mill ran although the release env was refused"
mkdir -p "$W/home"
env HOME="$W/home" RELEASE_ENV="$W/release.env" make -C "$G" --no-print-directory publish-dry-run >/dev/null 2>&1
saw "MILL_PGP_SECRET_BASE64 unset" \
  && pass "an environment variable RELEASE_ENV does not choose the file that is sourced" || fail "RELEASE_ENV from the environment was sourced"

# make publish marks the Central upload before Mill starts, so that it never runs twice for a
# version and release-abort does not undo it.
echo "$V" > "$G/.release-embargo"; rm -f "$MLOG"
expect "publish refuses when Mill would publish another version than the tag's" 1 "not the tag's" env STANDIN_VERSION=9.9.8 "${M[@]}" publish "RELEASE_ENV=$W/release.env"
[ ! -e "$B.CENTRAL_UPLOAD_STARTED" ] && [ ! -e "$MLOG" ] \
  && pass "and it wrote no Central marker and started no upload" || fail "publish marked or started a Central upload for another version than the tag's"
rm -f "$MLOG.calls"
expect "publish uploads an embargoed version to Central" 0 "to Maven Central" env MILL_TESTS_PUBLISH_DRY_RUN=1 "${TOKENS[@]}" "${M[@]}" publish "RELEASE_ENV=$W/release.env"
grep -q '^--no-daemon resolve __.publishArtifacts ' "$MLOG.calls" && ! grep -v -e '^--no-daemon ' -e '^shutdown ' "$MLOG.calls" >/dev/null \
  && pass "publish: with a token in the environment (CI), no Mill call but shutdown runs on a daemon, the module guard included" \
  || fail "publish: a Mill call may start a daemon that keeps the token: $(grep -v -e '^--no-daemon ' -e '^shutdown ' "$MLOG.calls" 2>/dev/null | tr '\n' ';')"
[ -s "$B.CENTRAL_UPLOAD_STARTED" ] && saw "marker before mill" \
  && pass "publish marked the Central upload before Mill started" || fail "publish did not mark the Central upload before Mill"
saw "$ARGS" && pass "publish: Mill gets the bundle name and no other argument" || fail "publish: Mill's arguments: $(grep '^args' "$MLOG")"
saw "MILL_SONATYPE_USERNAME set" && saw "MILL_TESTS_PUBLISH_DRY_RUN unset" \
  && pass "publish gives Mill the Central token, and no dry-run switch, not even an inherited one" || fail "publish: Mill's environment"
rm -f "$MLOG"
expect "publish never starts the Central upload of a version twice" 1 "started before" "${M[@]}" publish "RELEASE_ENV=$W/release.env"
[ ! -e "$MLOG" ] && pass "and Mill did not run" || fail "Mill ran for a second Central upload"
# The CI route: no release env file, every credential from the environment. publish must hand all
# of them to the publishing call, and run nothing but shutdown on a daemon.
rm -f "$B.CENTRAL_UPLOAD_STARTED" "$MLOG" "$MLOG.calls"
expect "publish takes the credentials from the environment when there is no release env (CI)" 0 "to Maven Central" \
  env MILL_TESTS_PUBLISH_DRY_RUN=1 "${TOKENS[@]}" "${M[@]}" publish
saw "MILL_SONATYPE_USERNAME set" && saw "MILL_SONATYPE_PASSWORD set" && saw "MILL_PGP_SECRET_BASE64 set" && saw "MILL_PGP_PASSPHRASE set" \
  && ! grep -v -e '^--no-daemon ' -e '^shutdown ' "$MLOG.calls" >/dev/null \
  && pass "publish: the publishing call gets all four from the environment, and no daemon sees them" \
  || fail "publish: on the CI route, Mill's environment or a daemon: $(cat "$MLOG" "$MLOG.calls" 2>/dev/null | tr '\n' ';')"
rm -f "$G/.release-embargo"
expect "release-bundle never signs again a version signed for Central" 1 "never signed again" "${M[@]}" release-bundle
expect "release-github sends a version that went to Central first to central-bundle" 1 "make central-bundle" "${M[@]}" release-github
printf '{"currentVersion":"0.0.0","newVersion":"%s","releaseType":"patch","branch":"main","head":"x","embargo":true}\n' "$V" > "$G/.release-state.json"
expect "release-abort undoes nothing once the Central upload started" 1 "may be final there" "${M[@]}" release-abort
git -C "$G" rev-parse -q --verify "refs/tags/v$V" >/dev/null && [ -e "$G/.release-state.json" ] \
  && pass "the tag and the release state stay" || fail "release-abort removed the tag or the state"
rm -f "$G/.release-state.json" "$B.CENTRAL_UPLOAD_STARTED"

# release-finalize runs repo-guard first (C12), before it commits or tags anything.
git -C "$G" tag -d "v$V" >/dev/null
printf '{"currentVersion":"9.9.8","newVersion":"9.9.10","releaseType":"patch","branch":"main","head":"%s","embargo":false}\n' "$(git -C "$G" rev-parse HEAD)" > "$G/.release-state.json"
HEAD_BEFORE=$(git -C "$G" rev-parse HEAD)
expect "release-finalize runs repo-guard first" 1 "[repo-guard] COURSIER_REPOSITORIES" env COURSIER_REPOSITORIES=https://example.invalid "${M[@]}" release-finalize
[ "$(git -C "$G" rev-parse HEAD)" = "$HEAD_BEFORE" ] && ! git -C "$G" rev-parse -q --verify "refs/tags/v9.9.10" >/dev/null \
  && pass "and a refusal there commits and tags nothing" || fail "release-finalize committed or tagged before repo-guard"
rm -f "$G/.release-state.json"

# release-abort undoes no notes commit that main on origin holds, also before the tag is pushed.
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m "Add release notes for version 9.9.10"
git -C "$G" push -q origin HEAD:refs/heads/main 2>/dev/null
printf '{"currentVersion":"9.9.9","newVersion":"9.9.10","releaseType":"patch","branch":"main","head":"x","embargo":false}\n' > "$G/.release-state.json"
NOTES_COMMIT=$(git -C "$G" rev-parse HEAD)
expect "release-abort undoes no notes commit that is on origin's main" 1 "would need a force push" "${M[@]}" release-abort
[ "$(git -C "$G" rev-parse HEAD)" = "$NOTES_COMMIT" ] && pass "and the notes commit stays" || fail "release-abort reset a pushed commit"
rm -f "$G/.release-state.json"

grep -q '^PUT\|^POST' "$W/log" && fail "something was uploaded to a stand-in server" || pass "nothing was ever uploaded"
[ "$FAILED" = 0 ] && echo "[release-test] all checks passed" || echo "[release-test] FAILED"
exit "$FAILED"
