#!/usr/bin/env python3
"""Release helpers for the Makefile: one signed bundle, kept on an immutable GitHub Release, served
by the family's Maven repository and taken unchanged to Maven Central by the release train.

Run as `python3 -I scripts/release.py <command> ...`, so that nothing from the current
directory or the environment is imported. Every command exits non-zero, with one line per
problem on stderr, when anything is not exactly as expected.

  bundle   --source DIR --dest DIR --group G --version V --artifacts "A B" --name NAME [--pom-only "A"]
           Check the signed tree Mill wrote (DIR, Maven layout), copy it to DEST/repository,
           and write the ledger DEST/SHA256SUMS, the deterministic zip DEST/NAME.zip and its
           SHA-256 in DEST/NAME.zip.sha256. DEST must not exist.
  verify   --dest DIR
           Check that DEST/repository holds exactly the files in DEST/SHA256SUMS, with those
           hashes, and that the zip still matches DEST/NAME.zip.sha256. Prints the ledger's
           paths, POMs last.
  pins     [--own "A B"] POM...
           Print "artifactId version" for every dev.taihei dependency in the POMs. A dependency
           on one of the --own artifacts (this library's other modules, released in the same
           bundle) is not listed; it must have the depending POM's own version.
  no-repos JSON
           Check that Mill's `show {modules}.repositories` output names no repository.
  resolvers --url URL [--modules "M N"] JSON
           Check that Mill's `show __.resolverOrder` output lists, for every module, exactly
           Coursier's defaults (the local Ivy repository, then Maven Central) followed by URL.
  build-repos --url URL FILE...
           Check that the build files name URL exactly once, as build.mill's `val repoUrl`, add it
           exactly once with MavenRepository(release.repoUrl), and name no other repository.
  resolved --workspace DIR --url URL --central URL --api URL --owner O --family "A B"
           --key FILE --fingerprint FPR JSON...
           Check every file in Mill's `show` output of resolved classpaths (C12): each comes from
           Maven Central or URL in the Coursier cache, only dev.taihei comes from URL, and every
           dev.taihei file (and the POM beside it) is in the SHA256SUMS of its version's immutable
           GitHub Release, or, without such a release, comes from Central with a good signature
           by the release key.
  fetch    --base URL --group G --version V --artifacts "A B" [--pom-only "A"] --dest DIR
           Download a released version's files from a Maven repository (Central, for a security
           fix's GitHub Release), every required file and the optional ones it has.
  released --central URL --url URL --artifact A VERSION
           Exit 0 when Maven Central or URL has VERSION of artifact A (its POM answers 200, asked
           of Central first); fail, naming it, when both answer 404, and on any other answer.
           The MiMa baseline: a release tag that neither serves is never passed over.
  same     DIR DIR
           Check that two Maven trees hold the same files with the same bytes.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import ssl
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

# A path in the ledger is a Maven path: no "..", no leading slash, nothing a shell or a URL
# would read differently.
SAFE_PATH = re.compile(r"^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")
# What Mill's SonatypeCentralPublishModule writes per module: these four files, each with a
# signature and two checksums. Checksums of a signature are allowed, nothing else.
MAIN_SUFFIXES = (".jar", "-sources.jar", "-javadoc.jar", ".pom")
SIDE_SUFFIXES = ("", ".asc", ".md5", ".sha1")
OPTIONAL_SUFFIXES = (".asc.md5", ".asc.sha1")
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def fail(problems):
    for p in problems:
        print(f"[release] {p}", file=sys.stderr)
    sys.exit(1)


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def tree(root):
    """Every regular file under root, as sorted relative paths; refuses anything else."""
    if os.path.islink(root) or not os.path.isdir(root):
        fail([f"{root}: not a directory"])
    found, problems = [], []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for name in dirnames:
            if os.path.islink(os.path.join(dirpath, name)):
                problems.append(f"{os.path.join(dirpath, name)}: a symlink")
        for name in filenames:
            full = os.path.join(dirpath, name)
            mode = os.lstat(full).st_mode
            if not stat.S_ISREG(mode):
                problems.append(f"{full}: not a regular file")
                continue
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if not SAFE_PATH.match(rel) or ".." in rel.split("/"):
                problems.append(f"{rel}: not a plain Maven path")
            found.append(rel)
    if problems:
        fail(problems)
    return sorted(found)


def expected_files(group, version, artifacts, pom_only=()):
    required, optional = set(), set()
    for a in artifacts:
        base = f"{group.replace('.', '/')}/{a}/{version}/{a}-{version}"
        # A POM-only module (a relocation, a bill of materials) publishes its POM alone.
        for main in (".pom",) if a in pom_only else MAIN_SUFFIXES:
            for side in SIDE_SUFFIXES:
                required.add(base + main + side)
            for side in OPTIONAL_SUFFIXES:
                optional.add(base + main + side)
    return required, optional


def check_pom(path, group, artifact, version):
    problems = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return [f"{path}: not XML ({e})"]

    def text(*names):
        node = root
        for n in names:
            node = node.find(POM_NS + n) if node is not None else None
        return (node.text or "").strip() if node is not None else ""

    for field, want in (("groupId", group), ("artifactId", artifact), ("version", version)):
        if text(field) != want:
            problems.append(f"{path}: {field} is '{text(field)}', not '{want}'")
    # What Central refuses a component without.
    for names in (("name",), ("description",), ("url",), ("scm", "url"), ("scm", "connection")):
        if not text(*names):
            problems.append(f"{path}: no {'/'.join(names)}")
    licenses = root.find(POM_NS + "licenses")
    if licenses is None or not any(
        "Apache" in ((l.findtext(POM_NS + "name") or "") + (l.findtext(POM_NS + "url") or ""))
        for l in licenses.findall(POM_NS + "license")
    ):
        problems.append(f"{path}: no Apache licence")
    developers = root.find(POM_NS + "developers")
    if developers is None or not developers.findall(POM_NS + "developer"):
        problems.append(f"{path}: no developer")
    # A POM that names a repository makes its users resolve from it, before or besides Central:
    # a published POM names none, wherever it sits.
    for name in ("repositories", "pluginRepositories"):
        if root.find(f".//{POM_NS}{name}") is not None:
            problems.append(f"{path}: names {name}; a published POM names no repository")
    return problems


def write_ledger(repo, files, ledger):
    with open(ledger, "w", encoding="ascii", newline="\n") as f:
        for rel in files:
            f.write(f"{digest(os.path.join(repo, rel), 'sha256')}  {rel}\n")


def write_zip(repo, files, target):
    # Stored, not deflated: the jars are compressed already, and stored entries do not depend
    # on the zlib build, so the same tree gives the same bytes on any machine.
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as z:
        for rel in files:
            info = zipfile.ZipInfo(rel, date_time=ZIP_TIME)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_STORED
            with open(os.path.join(repo, rel), "rb") as f:
                z.writestr(info, f.read())


def read_ledger(ledger):
    entries, problems = {}, []
    with open(ledger, encoding="ascii") as f:
        for n, line in enumerate(f, 1):
            m = re.match(r"^([0-9a-f]{64})  (\S+)\n$", line)
            if not m or not SAFE_PATH.match(m.group(2)) or ".." in m.group(2).split("/"):
                problems.append(f"{ledger}:{n}: not a ledger line")
                continue
            if m.group(2) in entries:
                problems.append(f"{ledger}:{n}: {m.group(2)} twice")
            entries[m.group(2)] = m.group(1)
    if problems:
        fail(problems)
    if list(entries) != sorted(entries):
        fail([f"{ledger}: not sorted by path"])
    return entries


def bundle(args):
    artifacts = args.artifacts.split()
    if os.path.lexists(args.dest):
        fail([f"{args.dest} exists already: a bundle is built once"])
    files = tree(args.source)
    pom_only = args.pom_only.split()
    if any(a not in artifacts for a in pom_only):
        fail([f"--pom-only names an artifact that is not in --artifacts: {args.pom_only}"])
    required, optional = expected_files(args.group, args.version, artifacts, pom_only)
    problems = [f"missing from the bundle: {p}" for p in sorted(required - set(files))]
    problems += [f"not expected in the bundle: {p}" for p in files if p not in required | optional]
    for rel in files:
        full = os.path.join(args.source, rel)
        for algorithm in ("md5", "sha1"):
            side = f"{full}.{algorithm}"
            if os.path.isfile(side):
                with open(side, encoding="ascii", errors="replace") as f:
                    recorded = f.read().split()
                if not recorded or recorded[0].lower() != digest(full, algorithm):
                    problems.append(f"{rel}.{algorithm} does not match {rel}")
    for a in artifacts:
        pom = os.path.join(args.source, args.group.replace(".", "/"), a, args.version, f"{a}-{args.version}.pom")
        if os.path.isfile(pom):
            problems += check_pom(pom, args.group, a, args.version)
    if problems:
        fail(problems)

    repo = os.path.join(args.dest, "repository")
    os.makedirs(args.dest, mode=0o755)
    for rel in files:
        target = os.path.join(repo, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(os.path.join(args.source, rel), "rb") as src, open(target, "xb") as dst:
            dst.write(src.read())
        os.chmod(target, 0o644)
    if tree(repo) != files:
        fail([f"{repo}: the copy differs from {args.source}"])
    write_ledger(repo, files, os.path.join(args.dest, "SHA256SUMS"))
    zip_path = os.path.join(args.dest, f"{args.name}.zip")
    write_zip(repo, files, zip_path)
    with open(f"{zip_path}.sha256", "w", encoding="ascii", newline="\n") as f:
        f.write(f"{digest(zip_path, 'sha256')}  {args.name}.zip\n")
    print(f"{len(files)} files, {sum(1 for p in files if p.endswith('.asc'))} signatures")


def verify(args):
    ledger = read_ledger(os.path.join(args.dest, "SHA256SUMS"))
    repo = os.path.join(args.dest, "repository")
    files = tree(repo)
    problems = [f"not in the ledger: {p}" for p in files if p not in ledger]
    problems += [f"missing: {p}" for p in ledger if p not in files]
    problems += [f"changed: {p}" for p in ledger if p in files and digest(os.path.join(repo, p), "sha256") != ledger[p]]
    sums = [n for n in os.listdir(args.dest) if n.endswith(".zip.sha256")]
    if len(sums) != 1:
        problems.append(f"{args.dest}: needs exactly one .zip.sha256")
    else:
        with open(os.path.join(args.dest, sums[0]), encoding="ascii") as f:
            m = re.match(r"^([0-9a-f]{64})  (\S+\.zip)\n$", f.read())
        if not m or m.group(2) != sums[0][: -len(".sha256")]:
            problems.append(f"{sums[0]}: not a checksum line for its zip")
        else:
            z = os.path.join(args.dest, m.group(2))
            if not os.path.isfile(z) or os.path.islink(z) or digest(z, "sha256") != m.group(1):
                problems.append(f"{m.group(2)} does not match {sums[0]}")
            else:
                with zipfile.ZipFile(z) as zf:
                    names = zf.namelist()
                    if names != list(ledger):
                        problems.append(f"{m.group(2)}: entries differ from the ledger")
                    else:
                        for n in names:
                            if hashlib.sha256(zf.read(n)).hexdigest() != ledger[n]:
                                problems.append(f"{m.group(2)}: {n} differs from the ledger")
    if problems:
        fail(problems)
    # The upload order: every file first, each module's POM last, so a registry never sees a
    # POM whose files are not there yet.
    for p in [p for p in ledger if not p.endswith(".pom")] + [p for p in ledger if p.endswith(".pom")]:
        print(p)


def pins(args):
    own = set(args.own.split())
    for path in args.poms:
        root = ET.parse(path).getroot()
        for dep in root.iter(POM_NS + "dependency"):
            if (dep.findtext(POM_NS + "groupId") or "").strip() == "dev.taihei":
                artifact = (dep.findtext(POM_NS + "artifactId") or "").strip()
                version = (dep.findtext(POM_NS + "version") or "").strip()
                if artifact in own:
                    # Another module of this library: it is released with this one, in the same
                    # bundle, so it is never a pin on an earlier release.
                    if version != (root.findtext(POM_NS + "version") or "").strip():
                        fail([f"{path}: dev.taihei dependency '{artifact}' '{version}' is not this release's own version"])
                    continue
                if not re.match(r"^[a-z0-9_.-]+$", artifact) or not re.match(r"^[0-9]+\.[0-9]+\.[0-9]+$", version):
                    fail([f"{path}: dev.taihei dependency '{artifact}' '{version}' is not a released version"])
                print(artifact, version)


def no_repos(args):
    with open(args.json) as f:
        shown = json.load(f)
    if not isinstance(shown, dict) or not shown:
        fail([f"{args.json}: no modules shown"])
    named = {k: v for k, v in shown.items() if v}
    if named:
        fail([f"{k} names {v}: Mill's own repositories come before Maven Central, so they stay empty" for k, v in named.items()])


# ---------------------------------------------------------------- the repositories of the build

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
# Coursier's local Ivy repository, its first default: Mill lists it as this pattern.
IVY_LOCAL = re.compile(r"^ivy:file:/.+/\.ivy2/local/\[organisation\]/\[module\]/.*$")


def check_url(url):
    u = urllib.parse.urlparse(url)
    if u.scheme != "https" or not u.netloc or not url.endswith("/") or u.query or u.fragment or u.params:
        fail([f"'{url}' is not an https URL ending in /"])
    if u.hostname and u.hostname.lower() == "maven.pkg.github.com":
        fail([f"'{url}' is GitHub Packages, which needs a token to read"])


def resolvers(args):
    check_url(args.url)
    with open(args.json) as f:
        shown = json.load(f)
    if not isinstance(shown, dict) or not shown:
        fail([f"{args.json}: no modules shown"])
    # The family's repository is asked for dev.taihei only (build.mill's TaiheiOnly).
    want = [MAVEN_CENTRAL, f"{args.url.rstrip('/')} (dev.taihei only)"]
    problems = [f"{m} does not report its repositories (resolverOrder)" for m in args.modules.split() if f"{m}.resolverOrder" not in shown]
    for module, repos in sorted(shown.items()):
        if not isinstance(repos, list) or len(repos) != 3 or not IVY_LOCAL.match(str(repos[0])) or [str(r) for r in repos[1:]] != want:
            problems.append(f"{module} resolves from {repos}, not Coursier's defaults followed by {args.url} for dev.taihei only")
    if problems:
        fail(problems)


# A line of a build file that names, adds or configures a repository, or where Coursier keeps
# its cache or reads its configuration. Only the lines of the override in build.mill may; every
# other match is refused, also in a build header (`//| mill-repositories:`) or a YAML build file.
REPO_WORDS = re.compile(
    r"maven\.pkg\.github\.com|http://|MavenRepository|IvyRepository|reposFromStrings|"
    r"def\s+repositories\b|repositoriesTask|defaultRepositories|COURSIER_|coursier\.(repositories|mirrors|cache|config)|"
    r"millRepositories|mill-repositories|resolvers|(^|\s)repositories\s*:",
    re.IGNORECASE,
)
REPO_LINES = (
    "final case class TaiheiOnly(underlying: coursier.maven.MavenRepository) extends coursier.core.Repository {",
    "override def repositoriesTask: Task[Seq[coursier.Repository]] = Task.Anon {",
    "coursierConfigModule().defaultRepositories() :+ TaiheiOnly(coursier.maven.MavenRepository(release.repoUrl))",
    "repositoriesTask().map {",
    "case m: coursier.maven.MavenRepository => m.root",
    "case i: coursier.ivy.IvyRepository     => s\"ivy:${i.pattern.string}\"",
)


def comment_only(code):
    """Whether a stripped line is a comment and nothing else. A build header line (`//|`) is
    configuration Mill reads, not a comment, and code after a closed block comment is code."""
    if code.startswith("//|"):
        return False
    if code.startswith(("//", "#")):
        return True
    if code.startswith(("/*", "*")):
        return "*/" not in code or not code.split("*/", 1)[1].strip()
    return False


def build_repos(args):
    check_url(args.url)
    problems, declared, mentions = [], 0, 0
    for path in args.files:
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
        for n, line in enumerate(lines, 1):
            code = line.strip()
            if args.url in line:
                mentions += 1
                if path == "build.mill" and code == f'val repoUrl = "{args.url}"':
                    declared += 1
                else:
                    problems.append(f"{path}:{n}: names {args.url} outside build.mill's val repoUrl")
            elif code.startswith("val repoUrl") or code.startswith("def repoUrl"):
                problems.append(f"{path}:{n}: repoUrl is not '{args.url}', the Makefile's REPO_URL")
            # A comment adds no repository; code that names one must be the override itself.
            if REPO_WORDS.search(line) and not comment_only(code):
                if not (path == "build.mill" and code in REPO_LINES):
                    problems.append(f"{path}:{n}: names a repository: {code}")
    if declared != 1 or mentions != 1:
        problems.append(f"the build files must name {args.url} exactly once, as build.mill's val repoUrl ({mentions} found)")
    if problems:
        fail(problems)


# ---------------------------------------------------------------- what the build resolved (C12)

TIMEOUT = 60
MAX_SUMS = 1 << 20
MAX_JSON = 1 << 20
MAX_ASC = 1 << 16
MAX_FILE = 200 << 20
BAD_STATUS = ("BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "NO_PUBKEY", "KEYEXPIRED", "KEYREVOKED")
REF = re.compile(r"^q?ref:(?:v\d+:)?[0-9a-f]+:(/.+)$")


class Refused(Exception):
    pass


class HttpsOnly(urllib.request.HTTPRedirectHandler):
    """Follows a redirect only to https (a release asset redirects to GitHub's storage)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise Refused(f"{req.full_url}: redirect to {newurl} refused, https only")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Refused(f"{req.full_url}: HTTP {code}, a redirect to {newurl}; refused")


def http_get(url, limit, follow=True, accept=None):
    """(status, body) for a GET without credentials: 200 with the body, 404 with none; any other
    answer, a body over `limit` or a network error is Refused."""
    if urllib.parse.urlparse(url).scheme != "https":
        raise Refused(f"{url}: only https")
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl.create_default_context()), HttpsOnly() if follow else NoRedirect()
    )
    headers = {"User-Agent": "taihei-repo-guard"}
    if accept:
        headers["Accept"] = accept
    try:
        with opener.open(urllib.request.Request(url, headers=headers), timeout=TIMEOUT) as r:
            body = r.read(limit + 1)
            if len(body) > limit:
                raise Refused(f"{url}: more than {limit} bytes")
            return r.status, body
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return 404, b""
        hint = " (GitHub's limit for requests without a token; wait an hour)" if e.code in (403, 429) and "api" in url else ""
        raise Refused(f"{url}: HTTP {e.code}{hint}")
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise Refused(f"{url}: {getattr(e, 'reason', e)}")


class Keyring:
    """A gpgv keyring holding the pinned key and nothing else, in a private temporary home."""

    def __init__(self, key_file, fingerprint):
        self.fingerprint = fingerprint.upper()
        self.home = tempfile.mkdtemp(prefix="taihei-gpg-")
        os.chmod(self.home, 0o700)
        self.keyring = os.path.join(self.home, "pinned.gpg")
        shown = subprocess.run(
            ["gpg", "--homedir", self.home, "--batch", "--no-tty", "--with-colons", "--show-keys", key_file],
            capture_output=True, env=self.env(), timeout=TIMEOUT,
        )
        lines = shown.stdout.decode("ascii", "replace").splitlines()
        primaries = [i for i, l in enumerate(lines) if l.startswith("pub:")]
        if shown.returncode != 0 or len(primaries) != 1:
            self.close()
            raise Refused(f"{key_file}: does not hold exactly one key")
        fprs = [l.split(":")[9] for l in lines[primaries[0] + 1 :] if l.startswith("fpr:")][:1]
        if fprs != [self.fingerprint]:
            self.close()
            raise Refused(f"{key_file}: is not the key {self.fingerprint}")
        with open(key_file, "rb") as armored, open(self.keyring, "wb") as out:
            r = subprocess.run(
                ["gpg", "--homedir", self.home, "--batch", "--no-tty", "--dearmor"],
                stdin=armored, stdout=out, stderr=subprocess.PIPE, env=self.env(), timeout=TIMEOUT,
            )
        if r.returncode != 0:
            self.close()
            raise Refused(f"{key_file}: cannot be dearmored")

    def env(self):
        return {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "LC_ALL": "C", "GNUPGHOME": self.home}

    def verify(self, signature, data_path, name):
        """None, or why `signature` is not exactly one good signature of the file by the pinned key."""
        sig_path = os.path.join(self.home, "s.asc")
        with open(sig_path, "wb") as f:
            f.write(signature)
        r = subprocess.run(
            ["gpgv", "--homedir", self.home, "--keyring", self.keyring, "--status-fd", "1", sig_path, data_path],
            capture_output=True, env=self.env(), timeout=TIMEOUT,
        )
        status = [l.split() for l in r.stdout.decode("ascii", "replace").splitlines() if l.startswith("[GNUPG:] ")]
        codes = [x[1] for x in status if len(x) > 1]
        bad = [c for c in codes if c in BAD_STATUS]
        if r.returncode != 0 or bad:
            return f"{name}: the signature does not verify ({', '.join(bad) or 'exit ' + str(r.returncode)})"
        valid = [x for x in status if len(x) > 2 and x[1] == "VALIDSIG"]
        if codes.count("GOODSIG") != 1 or len(valid) != 1 or codes.count("NEWSIG") > 1:
            return f"{name}: not exactly one good signature"
        if valid[0][-1].upper() != self.fingerprint:
            return f"{name}: signed by {valid[0][-1]}, not by {self.fingerprint}"
        return None

    def close(self):
        shutil.rmtree(self.home, ignore_errors=True)


def shown_paths(value):
    """Every file path in Mill's `show` output: PathRefs are "qref:v1:<hash>:/path" strings, at any
    depth (resolvedMimaPreviousArtifacts pairs a dependency with one)."""
    if isinstance(value, str):
        m = REF.match(value)
        return [m.group(1)] if m else []
    if isinstance(value, list):
        return [p for v in value for p in shown_paths(v)]
    if isinstance(value, dict):
        return [p for v in value.values() for p in shown_paths(v)]
    return []


def cache_key(url):
    """The part of a Coursier cache path that names a repository: https/<host><path>/."""
    u = urllib.parse.urlparse(url.rstrip("/") + "/")
    return f"/https/{u.netloc}{u.path}"


class Releases:
    """The SHA256SUMS of each version's immutable GitHub Release, asked once per version."""

    def __init__(self, api, owner, family):
        self.api, self.owner, self.family, self.seen = api.rstrip("/"), owner, family, {}

    def library(self, artifact):
        names = [f for f in self.family if artifact.startswith((f"taihei-{f}_", f"taihei-{f}-"))]
        return max(names, key=len) if names else None

    def sums(self, library, version):
        """{path: sha256} of the release v<version> of <owner>/<library>, or None without one."""
        key = (library, version)
        if key not in self.seen:
            self.seen[key] = self.fetch(library, version)
        return self.seen[key]

    def fetch(self, library, version):
        url = f"{self.api}/repos/{self.owner}/{library}/releases/tags/v{version}"
        code, body = http_get(url, MAX_JSON, accept="application/vnd.github+json")
        if code == 404:
            return None
        try:
            release = json.loads(body)
        except ValueError:
            raise Refused(f"{url}: not JSON")
        where = f"{self.owner}/{library} v{version}"
        if release.get("immutable") is not True or release.get("draft") is not False or release.get("prerelease") is not False:
            raise Refused(f"{where}: the GitHub Release is not published and immutable")
        assets = [a for a in release.get("assets", []) if isinstance(a, dict) and a.get("name") == "SHA256SUMS"]
        if len(assets) != 1 or not isinstance(assets[0].get("browser_download_url"), str):
            raise Refused(f"{where}: the GitHub Release has no single SHA256SUMS")
        code, body = http_get(assets[0]["browser_download_url"], MAX_SUMS)
        if code != 200:
            raise Refused(f"{where}: its SHA256SUMS answers HTTP {code}")
        entries = {}
        for n, line in enumerate(body.decode("ascii", "replace").splitlines(), 1):
            m = re.match(r"^([0-9a-f]{64})  (\S+)$", line)
            if not m or m.group(2) in entries:
                raise Refused(f"{where}: SHA256SUMS line {n} is not a ledger line")
            entries[m.group(2)] = m.group(1)
        return entries


def resolved(args):
    for url in (args.url, args.central.rstrip("/") + "/", args.api.rstrip("/") + "/"):
        check_url(url)
    out = os.path.join(os.path.realpath(args.workspace), "out")
    paths = set()
    for j in args.json:
        with open(j) as f:
            paths.update(shown_paths(json.load(f)))
    if not paths:
        fail(["the build resolved no files: nothing to check, so something is wrong"])
    roots = {"central": cache_key(args.central), "repo": cache_key(args.url)}
    problems, taihei, central, cache_roots = [], [], 0, set()
    for path in sorted(paths):
        hits = [(name, path.index(k)) for name, k in roots.items() if k in path]
        if not hits and "/https/" not in path and os.path.realpath(path).startswith(out + os.sep):
            continue  # this build's own output in out/ (a module another module depends on)
        if len(hits) != 1:
            problems.append(f"{path}: not in the Coursier cache of Maven Central or {args.url}")
            continue
        name, at = hits[0]
        cache_roots.add(path[:at])
        rel = path[at + len(roots[name]) :]
        if not SAFE_PATH.match(rel) or ".." in rel.split("/"):
            problems.append(f"{path}: not a plain Maven path")
            continue
        if rel.startswith("dev/taihei/"):
            taihei.append((name, path, rel))
        elif name == "repo":
            problems.append(f"{path}: only dev.taihei comes from {args.url}")
        else:
            central += 1
    if len(cache_roots) > 1:
        problems.append(f"files from more than one Coursier cache: {sorted(cache_roots)}")
    # Whatever the family's repository ever served into this cache is dev.taihei: anything else
    # there came from it outside the resolutions shown here.
    for root in cache_roots:
        site = root + roots["repo"]
        for dirpath, _, filenames in os.walk(site):
            for name in filenames:
                rel = os.path.relpath(os.path.join(dirpath, name), site).replace(os.sep, "/")
                if not rel.startswith("dev/taihei/"):
                    problems.append(f"{os.path.join(dirpath, name)}: only dev.taihei comes from {args.url}")
    if problems:
        fail(problems)

    releases = Releases(args.api, args.owner, args.family.split())
    keyring = None
    by_sums = by_signature = 0
    try:
        checked = set()
        for name, path, rel in taihei:
            parts = rel.split("/")
            if len(parts) != 5:
                problems.append(f"{path}: not dev/taihei/<artifact>/<version>/<file>")
                continue
            artifact, version, file = parts[2], parts[3], parts[4]
            pom = f"{artifact}-{version}.pom"
            todo = [(path, rel)]
            # The POM beside a jar decides the jar's dependencies: it is checked too.
            sibling = os.path.join(os.path.dirname(path), pom)
            if file != pom and os.path.isfile(sibling):
                todo.append((sibling, "/".join(parts[:4] + [pom])))
            library = releases.library(artifact)
            if library is None or not re.match(r"^[0-9]+\.[0-9]+\.[0-9]+$", version):
                problems.append(f"{path}: dev.taihei:{artifact}:{version} is not a released version of a family library ({args.family})")
                continue
            try:
                sums = releases.sums(library, version)
            except Refused as e:
                problems.append(str(e))
                continue
            for p, r in todo:
                if p in checked:
                    continue
                checked.add(p)
                if os.path.islink(p) or not os.path.isfile(p):
                    problems.append(f"{p}: not a regular file")
                elif sums is not None:
                    where = f"{args.owner}/{library} v{version}"
                    if r not in sums:
                        problems.append(f"{p}: not in the SHA256SUMS of {where}")
                    elif digest(p, "sha256") != sums[r]:
                        problems.append(f"{p}: differs from the SHA256SUMS of {where}")
                    elif f"{r}.asc" not in sums:
                        problems.append(f"{p}: the SHA256SUMS of {where} lists no signature for it")
                    else:
                        # The release's own list, and the release key's signature too: GitHub
                        # write access alone does not make a file pass.
                        base = args.url if name == "repo" else args.central.rstrip("/") + "/"
                        try:
                            code, asc = http_get(f"{base}{r}.asc", MAX_ASC, follow=False)
                        except Refused as e:
                            problems.append(str(e))
                            continue
                        if code != 200 or hashlib.sha256(asc).hexdigest() != sums[f"{r}.asc"]:
                            problems.append(f"{p}: its signature is not served as the SHA256SUMS of {where} lists it (HTTP {code})")
                            continue
                        if keyring is None:
                            keyring = Keyring(args.key, args.fingerprint)
                        reason = keyring.verify(asc, p, p)
                        if reason:
                            problems.append(reason)
                        else:
                            by_sums += 1
                elif name != "central":
                    problems.append(f"{p}: served by {args.url}, but {args.owner}/{library} has no immutable release v{version}")
                else:
                    # A version from before the route, or a security fix before its advisory: on
                    # Central, without a GitHub Release. Its signature is the proof.
                    try:
                        code, asc = http_get(f"{args.central.rstrip('/')}/{r}.asc", MAX_ASC)
                    except Refused as e:
                        problems.append(str(e))
                        continue
                    if code != 200:
                        problems.append(f"{p}: Maven Central has no signature for it (HTTP {code})")
                        continue
                    if keyring is None:
                        keyring = Keyring(args.key, args.fingerprint)
                    reason = keyring.verify(asc, p, p)
                    if reason:
                        problems.append(reason)
                    else:
                        by_signature += 1
    except Refused as e:
        problems.append(str(e))
    finally:
        if keyring is not None:
            keyring.close()
    if problems:
        fail(problems)
    if central + by_sums + by_signature == 0:
        fail(["no resolved file was checked: nothing came from a Coursier cache of Maven Central or the repository"])
    print(f"{central} files from Maven Central; {by_sums} dev.taihei files match their release's SHA256SUMS and signature, {by_signature} verified by signature alone")


# ---------------------------------------------------------------- a security fix's files from Central


def fetch(args):
    if os.path.lexists(args.dest):
        fail([f"{args.dest} exists already"])
    check_url(args.base.rstrip("/") + "/")
    artifacts = args.artifacts.split()
    required, optional = expected_files(args.group, args.version, artifacts, args.pom_only.split())
    problems, got = [], {}
    for rel in sorted(required | optional):
        try:
            code, body = http_get(f"{args.base.rstrip('/')}/{rel}", MAX_FILE, follow=False)
        except Refused as e:
            problems.append(str(e))
            continue
        if code == 200:
            got[rel] = body
        elif rel in required:
            problems.append(f"{rel}: HTTP {code} from {args.base}")
    if problems:
        fail(problems)
    for rel, body in got.items():
        target = os.path.join(args.dest, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "xb") as f:
            f.write(body)
    print(f"{len(got)} files from {args.base}")


def released(args):
    for name, base in (("Maven Central", args.central.rstrip("/")), (args.url, args.url.rstrip("/"))):
        check_url(base + "/")
        url = f"{base}/dev/taihei/{args.artifact}/{args.version}/{args.artifact}-{args.version}.pom"
        code, _ = http_get(url, MAX_FILE, follow=False)
        if code == 200:
            print(f"{args.version} is on {name}")
            return
    fail([f"v{args.version} is neither on Maven Central nor on {args.url}: publish it (make release-github, make publish-repo), or set MIMA_BASELINE"])


def same(args):
    a, b = tree(args.a), tree(args.b)
    problems = [f"only in {args.a}: {p}" for p in a if p not in b] + [f"only in {args.b}: {p}" for p in b if p not in a]
    problems += [f"differs: {p}" for p in a if p in b and digest(os.path.join(args.a, p), "sha256") != digest(os.path.join(args.b, p), "sha256")]
    if problems:
        fail(problems)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("bundle")
    for opt in ("--source", "--dest", "--group", "--version", "--artifacts", "--name"):
        b.add_argument(opt, required=True)
    b.add_argument("--pom-only", default="")
    v = sub.add_parser("verify")
    v.add_argument("--dest", required=True)
    p = sub.add_parser("pins")
    p.add_argument("--own", default="")
    p.add_argument("poms", nargs="+")
    n = sub.add_parser("no-repos")
    n.add_argument("json")
    r = sub.add_parser("resolvers")
    r.add_argument("--url", required=True)
    r.add_argument("--modules", default="")
    r.add_argument("json")
    br = sub.add_parser("build-repos")
    br.add_argument("--url", required=True)
    br.add_argument("files", nargs="*")
    rs = sub.add_parser("resolved")
    for opt in ("--workspace", "--url", "--central", "--api", "--owner", "--family", "--key", "--fingerprint"):
        rs.add_argument(opt, required=True)
    rs.add_argument("json", nargs="+")
    fe = sub.add_parser("fetch")
    for opt in ("--base", "--group", "--version", "--artifacts", "--dest"):
        fe.add_argument(opt, required=True)
    fe.add_argument("--pom-only", default="")
    rl = sub.add_parser("released")
    for opt in ("--central", "--url", "--artifact"):
        rl.add_argument(opt, required=True)
    rl.add_argument("version")
    sm = sub.add_parser("same")
    sm.add_argument("a")
    sm.add_argument("b")
    args = parser.parse_args()
    commands = {"bundle": bundle, "verify": verify, "pins": pins, "no-repos": no_repos, "resolvers": resolvers,
                "build-repos": build_repos, "resolved": resolved, "fetch": fetch, "released": released, "same": same}
    try:
        commands[args.command](args)
    except Refused as e:
        fail([str(e)])


if __name__ == "__main__":
    main()
