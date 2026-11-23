#!/usr/bin/env python3
"""The build's checks of its repositories and of the MiMa baseline, for the Makefile and build.mill.

Run as `python3 -I -B scripts/release.py <command> ...`, so that nothing from the current
directory or the environment is imported, and no bytecode is written next to the scripts (-I
does not stop that). Every command exits non-zero, with one line per
problem on stderr, when anything is not exactly as expected.

  pins     [--own "A B"] POM...
           Print "artifactId version" for every dev.taihei dependency in the POMs. A dependency
           on one of the --own artifacts (this library's other modules, released in the same
           bundle) is not listed; it must have the depending POM's own version.
  no-repos JSON
           Check that Mill's `show {modules}.repositories` output names no repository.
  resolvers --url URL --resolving FILE [--modules "M N"] JSON
           Check that Mill's `show __.resolverOrder` output lists, for every module, exactly
           Coursier's defaults (the local Ivy repository, then Maven Central) followed by URL,
           and that every module in FILE, Mill's `resolve __.resolvedMvnDeps` output, reports it.
  build-repos --url URL FILE...
           Check that the build files name URL exactly once, as build.mill's `val repoUrl`, add it
           exactly once with MavenRepository(release.repoUrl), and name no other repository.
  resolved --workspace DIR --url URL --central URL --api URL --owner O --family "A B"
           --key FILE --fingerprint FPR JSON...
           Check every file in Mill's `show` output of resolved classpaths: each comes from
           Maven Central or URL in the Coursier cache, only dev.taihei comes from URL, and every
           dev.taihei file (and the POM beside it) is in the SHA256SUMS of its version's immutable
           GitHub Release, or, without such a release, comes from Central with a good signature
           by the release key.
  released --central URL --api URL --repo OWNER/NAME --artifact A VERSION
           Exit 0 when Maven Central has VERSION of artifact A (its POM answers 200) or the tag
           vVERSION has a published, immutable GitHub Release in the repository (asked without a
           token), and 3 when Central answers 404 and the tag has no GitHub Release: the version
           is spent, tagged and never released. A draft, which is what a failed release run
           leaves, is invisible without a token (the API answers 404), so it counts as spent too.
           Any other answer, a redirect from Central or a release that is not immutable included,
           exits 1. The MiMa baseline passes over a spent tag only.
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

# A path in a SHA256SUMS is a Maven path: no "..", no leading slash, nothing a shell or a URL
# would read differently.
SAFE_PATH = re.compile(r"^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")
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
    # Every module that resolves dependencies must report where from, published or not: a module
    # without resolverOrder (one that does not mix in build.mill's TaiheiRepositories) is refused,
    # so a new one cannot drop out of this check unseen.
    with open(args.resolving) as f:
        tasks = [line.strip() for line in f if line.strip()]
    if not tasks:
        problems.append(f"{args.resolving}: no module resolves dependencies, so nothing was checked")
    suffix = ".resolvedMvnDeps"
    for task in tasks:
        if not task.endswith(suffix) or len(task) == len(suffix):
            problems.append(f"{args.resolving}: '{task}' is not a resolvedMvnDeps task")
        elif f"{task[: -len(suffix)]}.resolverOrder" not in shown:
            problems.append(f"{task[: -len(suffix)]} resolves dependencies but does not report its repositories (resolverOrder)")
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


# ---------------------------------------------------------------- what the build resolved

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


def published_release(url, where):
    """The GitHub Release at url (an API /releases/tags/ URL), None when it answers 404.

    Anything other than a published, immutable release is refused: a draft or prerelease, an
    absent field, or a body that is not a JSON object.
    """
    code, body = http_get(url, MAX_JSON, accept="application/vnd.github+json")
    if code == 404:
        return None
    try:
        release = json.loads(body)
    except ValueError:
        raise Refused(f"{url}: not JSON")
    if (not isinstance(release, dict) or release.get("immutable") is not True
            or release.get("draft") is not False or release.get("prerelease") is not False):
        raise Refused(f"{where}: the GitHub Release is not published and immutable")
    return release


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
        where = f"{self.owner}/{library} v{version}"
        release = published_release(f"{self.api}/repos/{self.owner}/{library}/releases/tags/v{version}", where)
        if release is None:
            return None
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


# Exit status of released for a spent version: tagged, and without a GitHub Release, so never
# released (ADR 0014); the MiMa baseline passes over it. Any other failure is 1 and stops the build.
SPENT = 3


def released(args):
    central = args.central.rstrip("/")
    check_url(central + "/")
    pom = f"{central}/dev/taihei/{args.artifact}/{args.version}/{args.artifact}-{args.version}.pom"
    code, _ = http_get(pom, MAX_FILE, follow=False)
    if code == 200:
        print(f"{args.version} is on Maven Central")
        return
    # Not on Central: the tag's GitHub Release decides, never whether a site serves it yet.
    check_url(args.api.rstrip("/") + "/")
    url = f"{args.api.rstrip('/')}/repos/{args.repo}/releases/tags/v{args.version}"
    if published_release(url, f"{args.repo} v{args.version}") is None:
        print(f"[release] v{args.version} has no GitHub Release in {args.repo} and is not on Maven Central: spent, passed over", file=sys.stderr)
        sys.exit(SPENT)
    print(f"{args.version} has an immutable GitHub Release")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("pins")
    p.add_argument("--own", default="")
    p.add_argument("poms", nargs="+")
    n = sub.add_parser("no-repos")
    n.add_argument("json")
    r = sub.add_parser("resolvers")
    r.add_argument("--url", required=True)
    r.add_argument("--resolving", required=True)
    r.add_argument("--modules", default="")
    r.add_argument("json")
    br = sub.add_parser("build-repos")
    br.add_argument("--url", required=True)
    br.add_argument("files", nargs="*")
    rs = sub.add_parser("resolved")
    for opt in ("--workspace", "--url", "--central", "--api", "--owner", "--family", "--key", "--fingerprint"):
        rs.add_argument(opt, required=True)
    rs.add_argument("json", nargs="+")
    rl = sub.add_parser("released")
    for opt in ("--central", "--api", "--repo", "--artifact"):
        rl.add_argument(opt, required=True)
    rl.add_argument("version")
    args = parser.parse_args()
    commands = {"pins": pins, "no-repos": no_repos, "resolvers": resolvers, "build-repos": build_repos,
                "resolved": resolved, "released": released}
    try:
        commands[args.command](args)
    except Refused as e:
        fail([str(e)])


if __name__ == "__main__":
    main()
