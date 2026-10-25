#!/usr/bin/env bash
# Tests of the release machinery that never touch a real registry (make release-test, part of
# make validate): scripts/release.py, and publish-github, verify-github, release-abort and the
# embargo in the Makefile, run against scripts/fake_registry.py on localhost. A throwaway git
# repository holds a copy of the Makefile and scripts/, tagged v9.9.9, with a synthetic bundle
# signed by throwaway keys (no Mill, never the release key): what is tested is what the targets do
# with a bundle, its signatures, the tokens and the registry's answers. Where a target starts Mill,
# a stand-in answers and records which credential variables reached it. The mill launcher's
# download check runs against a stand-in curl. The Central check asks repo1.maven.org about version
# 9.9.9, so the test needs the network. One line per check; exits non-zero when any check fails.
set -uo pipefail
# make hands its command-line variables to every make below it, through MAKEFLAGS and as
# environment variables: release-prepare TYPE=... EMBARGO=... runs make validate, and so this
# script, and the make calls here would inherit them (an EMBARGO=0 from outside answers the very
# question one check asks). TYPE, EMBARGO and VERSION are the ones the Makefile reads from there;
# COURSIER_CONFIG_DIR it reads from the environment. The release credentials and the launcher's
# version variables are cleared too, so that only what a check sets reaches the targets.
unset MAKEFLAGS MFLAGS MAKEOVERRIDES MAKELEVEL TYPE EMBARGO VERSION RELEASE_ENV COURSIER_CONFIG_DIR \
  MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD MILL_PGP_SECRET_BASE64 MILL_PGP_PASSPHRASE \
  MILL_VERSION DEFAULT_MILL_VERSION MILL_TESTS_PUBLISH_DRY_RUN

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/taihei-release-test.XXXXXX")
SERVER=""; WATCH=""
cleanup() { [ -n "$WATCH" ] && kill "$WATCH" 2>/dev/null; [ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null; rm -rf "$W"; }
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
ARTIFACTS=$(make -s -C "$REPO" --no-print-directory -f - print-artifacts <<'EOF' 2>/dev/null
include Makefile
print-artifacts:
	@echo $(ARTIFACTS)
EOF
)
PREFIX=$(make -s -C "$REPO" --no-print-directory -f - print-prefix <<'EOF' 2>/dev/null
include Makefile
print-prefix:
	@echo $(BUNDLE_PREFIX)
EOF
)
PUBLISHED=$(make -s -C "$REPO" --no-print-directory -f - print-published <<'EOF' 2>/dev/null
include Makefile
print-published:
	@echo $(PUBLISHED)
EOF
)
[ -n "$ARTIFACTS" ] && [ -n "$PREFIX" ] && [ -n "$PUBLISHED" ] || { echo "cannot read ARTIFACTS, BUNDLE_PREFIX and PUBLISHED from the Makefile"; exit 1; }

# --- a synthetic tree in the layout Mill writes -----------------------------------------------
SRC="$W/src"
python3 -I - "$SRC" "$GROUP" "$V" $ARTIFACTS <<'EOF'
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
KEY=$(gpg --list-keys --with-colons "release@example.invalid" | awk -F: '$1 == "fpr" { print $10; exit }')
OTHER=$(gpg --list-keys --with-colons "other@example.invalid" | awk -F: '$1 == "fpr" { print $10; exit }')
sign() { rm -f "$2.asc"; gpg --batch --quiet --pinentry-mode loopback --passphrase '' --local-user "$1" --armor --detach-sign -o "$2.asc" "$2" 2>/dev/null; }
while IFS= read -r f; do sign "$KEY" "$f"; done < <(find "$SRC" -type f \( -name '*.jar' -o -name '*.pom' \) | sort)

R() { python3 -I "$REPO/scripts/release.py" "$@"; }

# --- scripts/release.py -----------------------------------------------------------------------
expect "bundle: a complete tree becomes a bundle" 0 "files" R bundle --source "$SRC" --dest "$W/b1" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
R bundle --source "$SRC" --dest "$W/b2" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n >/dev/null 2>&1
cmp -s "$W/b1/n.zip" "$W/b2/n.zip" && cmp -s "$W/b1/SHA256SUMS" "$W/b2/SHA256SUMS" && pass "bundle: the same tree gives the same zip and ledger" || fail "bundle: not deterministic"
expect "bundle: an existing bundle is refused" 1 "built once" R bundle --source "$SRC" --dest "$W/b1" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
expect "verify: an untouched bundle passes" 0 "" R verify --dest "$W/b1"
[ "$(R verify --dest "$W/b1" | tail -n 1)" = "$(R verify --dest "$W/b1" | grep '\.pom$' | tail -n 1)" ] && pass "verify: POMs come last in the upload order" || fail "verify: upload order"
first_jar() { find "$1" -name "*-$V.jar" | sort | head -n 1; }
cp -r "$SRC" "$W/s3"; rm "$(first_jar "$W/s3").asc"
expect "bundle: a missing signature is refused" 1 "missing from the bundle" R bundle --source "$W/s3" --dest "$W/b3" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s4"; echo 00 > "$(first_jar "$W/s4").md5"
expect "bundle: a wrong checksum is refused" 1 "does not match" R bundle --source "$W/s4" --dest "$W/b4" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s5"; touch "$(dirname "$(first_jar "$W/s5")")/extra.txt"
expect "bundle: an extra file is refused" 1 "not expected in the bundle" R bundle --source "$W/s5" --dest "$W/b5" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
cp -r "$SRC" "$W/s6"; P6=$(find "$W/s6" -name "*-$V.pom" | sort | head -n 1); sed -i.bak 's#<description>test</description>##' "$P6"; rm "$P6.bak"
md5=$(python3 -I -c 'import hashlib,sys; print(hashlib.md5(open(sys.argv[1],"rb").read()).hexdigest())' "$P6"); echo "$md5" > "$P6.md5"
sha1=$(python3 -I -c 'import hashlib,sys; print(hashlib.sha1(open(sys.argv[1],"rb").read()).hexdigest())' "$P6"); echo "$sha1" > "$P6.sha1"
expect "bundle: a POM without what Central needs is refused" 1 "no description" R bundle --source "$W/s6" --dest "$W/b6" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name n
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
expect "no-repos: a module naming one is refused" 1 "Maven Central only" R no-repos "$W/some.json"
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

# --- the Makefile targets against the fake registry ----------------------------------------
G="$W/repo"; mkdir -p "$G/scripts"
cp "$REPO/Makefile" "$G/"; cp "$REPO/scripts/release.py" "$G/scripts/"
git -C "$G" init -q; git -C "$G" add -A
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -qm test
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -a "v$V" -m test
B="$G/.release-bundles/$PREFIX-$V"
mkdir -p "$G/.release-bundles"; R bundle --source "$SRC" --dest "$B" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name "$PREFIX-$V" >/dev/null
COUNT=$(wc -l < "$B/SHA256SUMS" | tr -d ' ')

openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/key.pem" -out "$W/cert.pem" -days 1 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1 || { echo "openssl cannot make a test certificate"; exit 1; }
read -r PA PB < <(python3 -I -c 'import socket
s=[socket.socket() for _ in range(2)]
[x.bind(("127.0.0.1", 0)) for x in s]
print(*[x.getsockname()[1] for x in s])')
mkdir -p "$W/served"
python3 -I "$HERE/fake_registry.py" "$W/served" "$PA" "$PB" "$W/cert.pem" "$W/key.pem" "$W/log" & SERVER=$!
for _ in $(seq 50); do curl -s --cacert "$W/cert.pem" -o /dev/null "https://localhost:$PA/" && break; sleep 0.1; done
: > "$W/log"

( umask 077
  printf "# test\nTAIHEI_PACKAGES_PUBLISH_USER='test-user'\nTAIHEI_PACKAGES_PUBLISH_TOKEN='ghp_testpublishtoken'\n" > "$W/publish.env"
  printf "taihei.host=maven.pkg.github.com\ntaihei.username=test-user\ntaihei.password=ghp_testreadtoken\ntaihei.https-only=true\n" > "$W/credentials.properties" )
export CURL_CA_BUNDLE="$W/cert.pem"
M=(make -C "$G" --no-print-directory "GITHUB_PACKAGES_URL=https://localhost:$PA/taiheiframework/test"
   "PUBLISH_ENV=$W/publish.env" "READ_CREDENTIALS=$W/credentials.properties" "RELEASE_KEY=$KEY"
   "RELEASE_ENV=$W/no-release-env")

# The signatures, checked by publish-github before the first upload (as by release-bundle).
use_bundle() { rm -rf "$B"; R bundle --source "$1" --dest "$B" --group $GROUP --version $V --artifacts "$ARTIFACTS" --name "$PREFIX-$V" >/dev/null; }
cp -r "$SRC" "$W/u1"; J1=$(first_jar "$W/u1"); printf -- '-----BEGIN PGP SIGNATURE-----\nnot one\n-----END PGP SIGNATURE-----\n' > "$J1.asc"
use_bundle "$W/u1"; expect "publish-github refuses a signature that does not verify" 1 "does not verify" "${M[@]}" publish-github
cp -r "$SRC" "$W/u2"; J2=$(first_jar "$W/u2"); sign "$OTHER" "$J2"
use_bundle "$W/u2"; expect "publish-github refuses a file signed by another key" 1 "not signed by the release key" "${M[@]}" publish-github
cp -r "$SRC" "$W/u3"; J3=$(first_jar "$W/u3"); printf x >> "$J3"
python3 -I -c 'import hashlib,sys; d=open(sys.argv[1],"rb").read(); open(sys.argv[1]+".md5","w").write(hashlib.md5(d).hexdigest()); open(sys.argv[1]+".sha1","w").write(hashlib.sha1(d).hexdigest())' "$J3"
use_bundle "$W/u3"; expect "publish-github refuses a file changed after it was signed" 1 "does not verify" "${M[@]}" publish-github
use_bundle "$SRC"
expect "publish-github refuses another number of signatures" 1 "not 9" "${M[@]}" publish-github SIGNATURES=9

echo "$V" > "$G/.release-embargo"
expect "publish-github refuses a version under embargo" 1 "under embargo" "${M[@]}" publish-github
expect "release-github refuses a version under embargo" 1 "under embargo" "${M[@]}" release-github
rm "$G/.release-embargo"
printf '{"newVersion":"%s","head":"x","embargo":true}\n' "$V" > "$G/.release-state.json"
expect "publish-github refuses while an embargoed release is prepared" 1 "under embargo" "${M[@]}" publish-github
rm "$G/.release-state.json"
chmod 644 "$W/publish.env"
expect "publish-github refuses a token file others can read" 1 "mode 600" "${M[@]}" publish-github
chmod 600 "$W/publish.env"
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m after
expect "publish-github refuses a HEAD that is not the release tag" 1 "not at a release tag" "${M[@]}" publish-github
git -C "$G" reset -q --hard "v$V"
echo "# edited" >> "$G/Makefile"
expect "publish-github refuses tracked changes at the tag" 1 "tracked changes" "${M[@]}" publish-github
git -C "$G" checkout -q Makefile
[ ! -e "$B/UPLOAD_STARTED" ] && ! grep -q '^registry PUT' "$W/log" && pass "no refusal uploaded anything" || fail "a refusal uploaded to the registry"

# Every command line, while the upload runs: the token is never an argument.
( while [ ! -e "$W/stop" ]; do
    grep -l -a 'ghp_[t]estpublish' /proc/[0-9]*/cmdline 2>/dev/null >> "$W/argv"
    grep -l -a 'upload-fil[e]' /proc/[0-9]*/cmdline 2>/dev/null >> "$W/curls"
  done ) & WATCH=$!
expect "publish-github uploads the bundle" 0 "$COUNT files uploaded" "${M[@]}" publish-github
touch "$W/stop"; wait "$WATCH" 2>/dev/null; WATCH=""
[ -s "$W/curls" ] && pass "the watcher saw curl's command lines" || fail "the watcher saw no curl at all (no /proc?)"
[ ! -s "$W/argv" ] && pass "the publish token was never on a command line" || fail "the publish token was on a command line"
[ "$(grep -c "^registry PUT .* auth=test-user:publish$" "$W/log")" = "$COUNT" ] && pass "every PUT carried the publish token" || fail "PUTs"
[ "$(grep '^registry PUT' "$W/log" | tail -n "$(echo $ARTIFACTS | wc -w)" | grep -vc '\.pom ')" = 0 ] && pass "the POMs went last" || fail "the POMs did not go last"
diff <(cd "$B/repository" && find . -type f | sort | xargs sha256sum) <(cd "$W/served/taiheiframework/test" && find . -type f | sort | xargs sha256sum) >/dev/null \
  && pass "the registry holds exactly the bundle's files" || fail "the registry holds other files"
expect "publish-github never uploads a version twice" 1 "never uploaded again" "${M[@]}" publish-github
mv "$B/UPLOAD_STARTED" "$W/started"; : > "$W/log"
expect "without the marker, files already on GitHub Packages stop the upload" 1 "the version is there already" "${M[@]}" publish-github
[ "$(grep -c '^registry PUT' "$W/log")" = 0 ] && pass "and nothing was uploaded" || fail "PUTs went out"
printf "taihei.host=maven.pkg.github.com\ntaihei.username=test-user\ntaihei.password=ghp_testreadblind\n" > "$W/blind.properties"
: > "$W/log"
expect "a second upload the registry refuses stops at once" 1 "HTTP 409" "${M[@]}" publish-github "READ_CREDENTIALS=$W/blind.properties"
[ "$(grep -c '^registry PUT' "$W/log")" = 1 ] && pass "no PUT followed the refusal" || fail "PUTs went on after the refusal"
mv "$W/started" "$B/UPLOAD_STARTED"
printf 'not json' > "$G/.release-state.json"
expect "an unreadable release state refuses, since it may hold an embargo" 1 "cannot be read" "${M[@]}" publish-github
rm "$G/.release-state.json"
mkdir -p "$G/.release-bundles/tracked"; echo x > "$G/.release-bundles/tracked/f"
git -C "$G" add -f .release-bundles/tracked/f; git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false commit -qm tracked
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -f -a "v$V" -m test >/dev/null
expect "a committed bundle directory is refused" 1 "are tracked" "${M[@]}" verify-github
git -C "$G" tag -d "v$V" >/dev/null; git -C "$G" reset -q --hard HEAD~1; rm -rf "$G/.release-bundles/tracked"
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -a "v$V" -m test

# make publish, the direct Central upload, signs again: only for an embargoed version, and never
# for one GitHub Packages has.
expect "publish refuses a version that is not under embargo" 1 "is not under embargo" "${M[@]}" publish
echo "$V" > "$G/.release-embargo"
expect "publish refuses a version GitHub Packages has" 1 "on GitHub Packages, not 404" "${M[@]}" publish
rm "$G/.release-embargo"
expect "release-prepare wants the embargo decided" 1 "never a default" "${M[@]}" release-prepare TYPE=patch

: > "$W/log"
expect "verify-github reads every file back unchanged" 0 "all $COUNT files" "${M[@]}" verify-github
[ "$(grep -c "^registry GET .* auth=test-user:read$" "$W/log")" = "$COUNT" ] && pass "every read used the read token" || fail "reads"
[ "$(grep -c '^storage GET .* auth=none$' "$W/log")" = "$COUNT" ] && pass "the token never followed the redirect" || fail "the token followed the redirect"
J=$(first_jar "$W/served"); cp "$J" "$W/jar"; printf x >> "$J"
expect "verify-github reports a changed file as an incident" 1 "SECURITY INCIDENT" "${M[@]}" verify-github
cp "$W/jar" "$J"; rm "$J"
expect "verify-github reports a missing file" 1 "MISSING" "${M[@]}" verify-github
cp "$W/jar" "$J"
printf "a.host=maven.pkg.github.com\na.username=u\na.password=p\nb.host=maven.pkg.github.com\n" > "$W/two.properties"
expect "verify-github wants one read entry for the host" 1 "exactly one entry" "${M[@]}" verify-github "READ_CREDENTIALS=$W/two.properties"

printf '{"currentVersion":"0.0.0","newVersion":"%s","releaseType":"patch","branch":"main","head":"x","embargo":false}\n' "$V" > "$G/.release-state.json"
expect "release-abort undoes nothing once files reached GitHub Packages" 1 "the version is final" "${M[@]}" release-abort
git -C "$G" rev-parse -q --verify "refs/tags/v$V" >/dev/null && [ -d "$B" ] && pass "the tag and the bundle stay" || fail "release-abort removed the tag or the bundle"
rm "$B/UPLOAD_STARTED"
expect "release-abort removes a bundle that never left the machine" 0 "never left this machine" "${M[@]}" release-abort
[ ! -e "$B" ] && pass "the bundle is gone" || fail "the bundle stayed"

printf '%s\n1.2.3\n' "$V" > "$G/.release-embargo"
expect "embargo-lift lifts one version" 0 "no longer under embargo" "${M[@]}" embargo-lift "VERSION=$V"
[ "$(cat "$G/.release-embargo")" = "1.2.3" ] && pass "and keeps the others" || fail "embargo-lift changed the other versions"
expect "embargo-lift refuses a version that is not under embargo" 1 "not under embargo" "${M[@]}" embargo-lift "VERSION=$V"

# --- the targets that start Mill, against a stand-in ----------------------------------------
# The stand-in answers the guards, and records which Central token variables reached each Mill
# call before the publishing one (in $MLOG.pre), and for the publishing command which credential
# variables reached it (their names, never their values), its arguments, and whether the Central
# marker was there when it started. STANDIN_VERSION, when set, is the version it reports. Mill 1.3.1 signs in its own JVM; only an added --useGpgCli
# would hand the passphrase to a gpg command line, so the arguments must be exactly these.
MLOG="$W/mill-env"
cat > "$G/mill" <<EOF
#!/usr/bin/env bash
case "\$*" in
  shutdown|"resolve __.publishArtifacts")
    for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD; do
      if [ -n "\${!v+x}" ]; then echo "\$1 \$v set"; else echo "\$1 \$v unset"; fi
    done >> "$MLOG.pre"
    [ "\$1" = shutdown ] || printf '%s\n' $PUBLISHED;;
  "--no-daemon show release.gitVersion")
    for v in MILL_SONATYPE_USERNAME MILL_SONATYPE_PASSWORD; do
      if [ -n "\${!v+x}" ]; then echo "show \$v set"; else echo "show \$v unset"; fi
    done > "$MLOG.show"
    echo "\"\${STANDIN_VERSION:-$V}\"";;
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

# make publish marks the Central upload before Mill starts, as publish-github does for GitHub
# Packages, so that it never runs twice for a version and release-abort does not undo it.
# release-abort above removed the tag; the release is at it again.
git -C "$G" -c user.name=Test -c user.email=test@example.invalid -c tag.gpgsign=false tag -a "v$V" -m test
rm -rf "$W/served/taiheiframework"; echo "$V" > "$G/.release-embargo"; rm -f "$MLOG"
expect "publish refuses when Mill would publish another version than the tag's" 1 "not the tag's" env STANDIN_VERSION=9.9.8 "${M[@]}" publish "RELEASE_ENV=$W/release.env"
[ ! -e "$B.CENTRAL_UPLOAD_STARTED" ] && [ ! -e "$MLOG" ] \
  && pass "and it wrote no Central marker and started no upload" || fail "publish marked or started a Central upload for another version than the tag's"
expect "publish uploads an embargoed version to Central" 0 "to Maven Central" env MILL_TESTS_PUBLISH_DRY_RUN=1 "${M[@]}" publish "RELEASE_ENV=$W/release.env"
[ -s "$B.CENTRAL_UPLOAD_STARTED" ] && saw "marker before mill" \
  && pass "publish marked the Central upload before Mill started" || fail "publish did not mark the Central upload before Mill"
saw "$ARGS" && pass "publish: Mill gets the bundle name and no other argument" || fail "publish: Mill's arguments: $(grep '^args' "$MLOG")"
saw "MILL_SONATYPE_USERNAME set" && saw "MILL_TESTS_PUBLISH_DRY_RUN unset" \
  && pass "publish gives Mill the Central token, and no dry-run switch, not even an inherited one" || fail "publish: Mill's environment"
rm -f "$MLOG"
expect "publish never starts the Central upload of a version twice" 1 "started before" "${M[@]}" publish "RELEASE_ENV=$W/release.env"
[ ! -e "$MLOG" ] && pass "and Mill did not run" || fail "Mill ran for a second Central upload"
rm -f "$G/.release-embargo"
expect "release-bundle never signs again a version signed for Central" 1 "never signed again" "${M[@]}" release-bundle
expect "publish-github never uploads a version signed for Central" 1 "never signed again" "${M[@]}" publish-github
printf '{"currentVersion":"0.0.0","newVersion":"%s","releaseType":"patch","branch":"main","head":"x","embargo":true}\n' "$V" > "$G/.release-state.json"
expect "release-abort undoes nothing once the Central upload started" 1 "may be final there" "${M[@]}" release-abort
git -C "$G" rev-parse -q --verify "refs/tags/v$V" >/dev/null && [ -e "$G/.release-state.json" ] \
  && pass "the tag and the release state stay" || fail "release-abort removed the tag or the state"
rm -f "$G/.release-state.json" "$B.CENTRAL_UPLOAD_STARTED"

[ "$FAILED" = 0 ] && echo "[release-test] all checks passed" || echo "[release-test] FAILED"
exit "$FAILED"
