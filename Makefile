SHELL = /bin/bash

# A version is released by the family's release workflow (ADR 0014): the maintainer's signed
# v-tag starts .github/workflows/release.yml, which calls the release repository's open.yml at a
# pinned commit. Its build job runs make validate, make test-java-17 and make publish-dry-run in
# a fresh checkout of the tag, with no secret; it signs, keeps the bundle on an immutable GitHub
# Release and publishes it to the family's repository in a job that runs no code of this
# repository. Nothing here releases anything.

# The published artifacts, which repo-guard does not count as pins of this library.
ARTIFACTS = taihei-tsuji_3 taihei-tsuji_sjs1_3 taihei-tsuji-tools_3 taihei-tsuji-zio-json_3 taihei-tsuji-zio-json_sjs1_3
# The name Mill gives a release's bundle, and the folder open.yml takes it from.
BUNDLE_PREFIX = dev.taihei-taihei-tsuji
# Plain assignments, not ?=: an environment variable of the same name does not point the checks
# at another repository or key. A command-line override still does (the tests use one).
# The family's Maven repository: the one repository the build adds after Maven Central, the same
# string as release.repoUrl in build.mill (repo-guard checks that they agree). It answers 404
# for a directory, so every check asks for a file.
REPO_URL = https://taiheiframework.github.io/repo/
CENTRAL = https://repo1.maven.org/maven2
GITHUB_API = https://api.github.com
# The family's libraries: a dev.taihei artifact taihei-<name>... is released in the repository
# OWNER/<name>, on an immutable GitHub Release of its tag (repo-guard checks the files the build
# resolves against it).
OWNER = taiheiframework
FAMILY = tsuji mihon
# The release key; repo-guard checks signatures with its public key alone.
RELEASE_KEY = 5026B2996DDB697EC4630BCF14A733E2A507702F
RELEASE_KEY_FILE = $(CURDIR)/keys/release-key.asc
# Where publish-dry-run writes the bundle (Mill empties it before each run). A named bundle is in
# its own subdirectory, <bundle name>/, in Maven layout.
DRY_RUN_DIR = $(CURDIR)/out/mill.javalib.SonatypeCentralPublishModule/publishAll.dest/repository

# The build definition decides what is built and where it resolves from: repo-guard reads every
# tracked build file.
BUILD_FILES = '*.mill' '*.mill.yaml' '*.mill.scala' '*.sc' mill-build .mill-version .mill-jvm-opts .mill-opts .mill-repositories .config ':(exclude)out'
PUBLISHED = tsuji.js.publishArtifacts tsuji.jvm.publishArtifacts tsuji.tools.publishArtifacts ziojson.js.publishArtifacts ziojson.jvm.publishArtifacts

# Needs URL. The HTTP status of a GET of $$URL without credentials, without following a redirect
# (a redirect is an answer that stops a check), 000 when there is no answer.
HTTP_CODE = { curl -q -s --proto '=https' --max-time 30 -o /dev/null -w '%{http_code}' "$$URL" || true; }

.PHONY: clean fix fmt lint compile prepare build test test-java-17 mima no-zio zio-json-newest jsoniter-candidates repo-guard release-test validate deps \
        publishLocal publish-dry-run

# ---------------------------------------------------------------------------
# Build (cross-platform: __ covers the JVM and Scala.js modules)
# ---------------------------------------------------------------------------

clean:
	./mill clean

fix:
	./mill __.fix

fmt:
	./mill __.reformat

# What CI checks: fails on a file scalafmt or scalafix would change, and changes nothing. Both
# check, since where they disagree on a line the one that ran last wins a rewrite.
lint:
	./mill __.checkFormat
	./mill __.fix --check

compile:
	./mill __.compile

prepare: fix fmt compile

build: prepare test

# __.test.testForked resolves to both tsuji.jvm.test and tsuji.js.test
# (JS tests run under Node).
test:
	./mill __.test.testForked

# Binary compatibility with the baseline release (release.mimaBaseline: the newest v-tag before
# HEAD that is on Maven Central or has an immutable GitHub Release, passing over a spent one, a
# tag with neither, or MIMA_BASELINE). Any other answer fails. A break fails here; a major sets
# MIMA_BASELINE=none.
mima:
	@set -eo pipefail; BASELINE=$$(./mill show release.mimaBaseline | tr -d '\n "' | sed 's/^null$$/none/'); \
	echo "[mima] baseline: $$BASELINE"
	./mill __.mimaReportBinaryIssues

# An open repository resolves from Coursier's defaults (the local Ivy repository, then Maven
# Central), then the family's repository for dev.taihei alone, and nothing else (ADR 0004,
# section 5), so anyone can clone and build it without credentials, a pin moves only to a
# released version, and nothing but a released dev.taihei file comes from REPO_URL:
# 1. no Coursier setting in the environment adds a repository, and the build files name REPO_URL
#    once, as build.mill's release.repoUrl, add it once, after Coursier's defaults, and name no
#    other repository (release.py build-repos);
# 2. Mill's own `repositories`, which would come before Central, is empty, and every module
#    resolves from exactly Coursier's defaults followed by REPO_URL, which build.mill asks for
#    dev.taihei alone; a module that resolves dependencies without reporting its repositories
#    (build.mill's TaiheiRepositories) is refused, published or not;
# 3. every dev.taihei dependency of a published module is on Maven Central or on REPO_URL. A
#    dependency on another module of this library is the same release, and needs the same
#    version;
# 4. every file the build resolved came from Maven Central or REPO_URL, nothing but dev.taihei is
#    in the Coursier cache of REPO_URL, and every dev.taihei file, with its POM, is in the
#    SHA256SUMS of its version's immutable GitHub Release and signed by the release key, or, for
#    a version without a release (before this route, or a security fix before its advisory),
#    comes from Central with a good signature by the release key (release.py resolved). The
#    GitHub API is asked without a token.
# Each check asks for a file, never a directory, and a redirect stops it. The text scan reads
# tracked build files: the release workflow builds a fresh checkout of the tag, and a Coursier
# mirror file in the user's home is outside this repository. CI on a clean runner, with no
# credentials, stays the proof that the build needs none.
# The resolved classpaths repo-guard reads (Mill's own tasks; a module lists those it has).
RESOLVED = resolvedMvnDeps,resolvedRunMvnDeps,scalaCompilerClasspath,scalacPluginClasspath,scalaDocClasspath
RESOLVED := $(RESOLVED),scalaDocPluginClasspath,semanticDbPluginClasspath,resolvedMimaPreviousArtifacts
RESOLVED := $(RESOLVED),scalaJSToolsClasspath,scalaJSWorkerClasspath,scalaJSLinkerClasspath,scalaJSJsEnvMvnDeps
RESOLVED := $(RESOLVED),scalafixToolClasspath,pgpWorkerClasspath,scalaCompilerBridge
RESOLVED := $(RESOLVED),resolvedSemanticDbJavaPluginMvnDeps,annotationProcessorsResolvedMvnDeps
repo-guard:
	@set -euo pipefail; \
	if [ -n "$${COURSIER_REPOSITORIES:-}$${COURSIER_MIRRORS:-}$${COURSIER_CREDENTIALS:-}" ]; then \
		echo "[repo-guard] COURSIER_REPOSITORIES, COURSIER_MIRRORS or COURSIER_CREDENTIALS is set: this build resolves from Maven Central and $(REPO_URL) only, unset them"; exit 1; fi; \
	LIST=$$(git ls-files -- $(BUILD_FILES) .github); mapfile -t FILES <<< "$$LIST"; \
	python3 -I -B scripts/release.py build-repos --url "$(REPO_URL)" "$${FILES[@]}"; \
	MODULES="$(PUBLISHED)"; MODULES="$${MODULES//.publishArtifacts/}"; \
	T=$$(mktemp -d "$(CURDIR)/.repo-guard.XXXXXX"); trap 'rm -rf "$$T"' EXIT; \
	./mill show "{$${MODULES// /,}}.repositories" > "$$T/repositories.json"; \
	python3 -I -B scripts/release.py no-repos "$$T/repositories.json"; \
	./mill show '__.resolverOrder' > "$$T/resolvers.json"; \
	./mill resolve '__.resolvedMvnDeps' > "$$T/resolving"; \
	python3 -I -B scripts/release.py resolvers --url "$(REPO_URL)" --resolving "$$T/resolving" --modules "$$MODULES" "$$T/resolvers.json"; \
	./mill show "{$${MODULES// /,}}.pom" > "$$T/poms.json"; \
	python3 -I -B -c 'import json, sys; [print(v.split(":", 3)[3]) for v in json.load(open(sys.argv[1])).values()]' "$$T/poms.json" > "$$T/poms"; \
	mapfile -t POMS < "$$T/poms"; \
	python3 -I -B scripts/release.py pins --own "$(ARTIFACTS)" "$${POMS[@]}" | sort -u > "$$T/pins"; \
	while read -r A V; do \
		URL="$(CENTRAL)/dev/taihei/$$A/$$V/$$A-$$V.pom"; CODE=$$($(HTTP_CODE)); \
		case "$$CODE" in \
			200) echo "[repo-guard] dev.taihei:$$A:$$V is on Maven Central"; continue;; \
			404) ;; \
			*) echo "[repo-guard] cannot tell whether dev.taihei:$$A:$$V is on Maven Central (HTTP $$CODE)"; exit 1;; \
		esac; \
		URL="$(REPO_URL)dev/taihei/$$A/$$V/$$A-$$V.pom"; CODE=$$($(HTTP_CODE)); \
		case "$$CODE" in \
			200) echo "[repo-guard] dev.taihei:$$A:$$V is on $(REPO_URL)";; \
			404) echo "[repo-guard] dev.taihei:$$A:$$V is neither on Maven Central nor on $(REPO_URL): a pin moves only to a released version"; exit 1;; \
			*) echo "[repo-guard] cannot tell whether dev.taihei:$$A:$$V is on $(REPO_URL) (HTTP $$CODE)"; exit 1;; \
		esac; \
	done < "$$T/pins"; \
	./mill show '__.{$(RESOLVED)}' > "$$T/resolved.json"; \
	python3 -I -B scripts/release.py resolved --workspace "$(CURDIR)" --url "$(REPO_URL)" --central "$(CENTRAL)" --api "$(GITHUB_API)" \
		--owner "$(OWNER)" --family "$(FAMILY)" --key "$(RELEASE_KEY_FILE)" --fingerprint "$(RELEASE_KEY)" "$$T/resolved.json" | sed 's/^/[repo-guard] /'; \
	echo "[repo-guard] Maven Central, then $(REPO_URL), and nothing else"

# The tests of scripts/release.py, repo-guard, publish-dry-run and test-java-17, against stand-ins
# for Maven Central, the family's repository, the GitHub API, a JDK and Mill on localhost. No real
# registry, no token and no signing key is involved. Then the mill launcher's pinned SHA-256: a tampered or
# unpinned Mill, downloaded or already cached, is refused.
release-test:
	@bash scripts/test-release.sh
	@bash scripts/test-mill-launcher

# No dev.zio module in the published POMs or in anything they bring (ADR 0006, section 6; build.mill's
# NoZio), but for taihei-tsuji-zio-json, whose POM names zio-json at its pin and nothing else of
# dev.zio. Elsewhere ZIO stays in the tests.
no-zio:
	./mill __.noZio

# taihei-tsuji-zio-json's tests against another zio-json (ADR 0006): before a release, the
# newest on Central, since a user's build may evict the pin upward. The tests check that they ran
# against the version asked for. Without ZIO_JSON_TEST_VERSION it asks Maven Central for the newest
# x.y.z release that both zio-json artifacts have, and stops when Central cannot tell. The recipe
# reads the version from the environment, where make also puts a command-line value, so it is not
# pasted into the shell line; make itself still expands a command-line value as it exports it, so
# only a run that passes no variable, as the plain target needs none, is free of that.
ZIO_JSON_ARTIFACTS = zio-json_3 zio-json_sjs1_3
zio-json-newest:
	@set -euo pipefail; \
	V="$${ZIO_JSON_TEST_VERSION:-}"; \
	if [ -z "$$V" ]; then \
		V=$$(python3 -I -B scripts/release.py newest --central "$(CENTRAL)" --group dev.zio $(ZIO_JSON_ARTIFACTS)) \
			|| { echo "[zio-json-newest] cannot tell the newest zio-json on Maven Central; ZIO_JSON_TEST_VERSION=x.y.z names one"; exit 1; }; \
		echo "[zio-json-newest] the newest zio-json on Maven Central is $$V"; \
	else \
		echo "[zio-json-newest] ZIO_JSON_TEST_VERSION names zio-json $$V; Maven Central is not asked"; \
	fi; \
	ZIO_JSON_TEST_VERSION="$$V" ./mill '{ziojson.jvm,ziojson.js}.test.zioJsonTested' + '{ziojson.jvm,ziojson.js}.test.testForked'

# The J2 reading (ADR 0006) a move of the jsoniter-scala-core pin needs: every release of the
# pin's major above build.mill's V.jsoniter on Maven Central, with its publication date, whether
# it has been there 14 days or more, its licence, its release-notes link, and its jar and POM
# checked against the committed public key of the jsoniter-scala release key, in a temporary gpg
# home, and the notes of its GitHub Release, printed as marked, escaped upstream text (release.py
# candidates). It reads Central and the GitHub API over https, without credentials or redirects,
# and fails when it cannot read a version in full. It takes no variable, so a permission profile
# can allow exactly `make jsoniter-candidates`; that rule is narrow only while the agent cannot
# edit this Makefile or scripts/ and runs in the launcher's environment (MAKEFLAGS can set
# variables too), so the first line names the Central, the GitHub API and the key file actually used.
JSONITER_GROUP = com.github.plokhotnyuk.jsoniter-scala
JSONITER_ARTIFACT = jsoniter-scala-core_3
JSONITER_REPO = plokhotnyuk/jsoniter-scala
JSONITER_KEY = FDB76691F8E8BCFD47AF384C2E0AE1A6C673B7AA
JSONITER_KEY_FILE = $(CURDIR)/keys/jsoniter-scala.asc
jsoniter-candidates:
	@set -euo pipefail; \
	PIN=$$(sed -n -E 's/^ *val jsoniter *= *"([^"]*)" *$$/\1/p' build.mill); \
	[ -n "$$PIN" ] || { echo "[jsoniter-candidates] build.mill has no val jsoniter = \"x.y.z\""; exit 1; }; \
	[ -f "$(JSONITER_KEY_FILE)" ] || { echo "[jsoniter-candidates] $(JSONITER_KEY_FILE) is missing: commit the public key of $(JSONITER_KEY)"; exit 1; }; \
	echo "[jsoniter-candidates] $(JSONITER_GROUP):$(JSONITER_ARTIFACT) above the pin $$PIN on $(CENTRAL), key $(JSONITER_KEY_FILE), notes of $(JSONITER_REPO) on $(GITHUB_API)"; \
	python3 -I -B scripts/release.py candidates --central "$(CENTRAL)" --group "$(JSONITER_GROUP)" --artifact "$(JSONITER_ARTIFACT)" \
		--pin "$$PIN" --key "$(JSONITER_KEY_FILE)" --fingerprint "$(JSONITER_KEY)" --api "$(GITHUB_API)" --repo "$(JSONITER_REPO)"

# Checks and never rewrites: run make fix and make fmt first, so unformatted code fails here
# and in the release run instead of passing reformatted.
validate: repo-guard release-test lint compile test mima no-zio

# The JVM tests on Java 17, the oldest Java the library supports, where locale data and the JDK's
# behaviour can differ from the newest JDK: TAIHEI_TEST_JAVA_HOME names that JDK (Mill runs on its
# own). CI and the release workflow run it; it refuses a JDK that is not 17, and checks that each
# test module forks on it.
test-java-17:
	@set -euo pipefail; \
	if [ -z "$${TAIHEI_TEST_JAVA_HOME:-}" ]; then echo "[test-java-17] set TAIHEI_TEST_JAVA_HOME to a JDK 17"; exit 1; fi; \
	JV=$$("$$TAIHEI_TEST_JAVA_HOME/bin/java" -version 2>&1); echo "$$JV"; \
	grep -q 'version "17\.' <<< "$$JV" || { echo "[test-java-17] $$TAIHEI_TEST_JAVA_HOME is not a JDK 17"; exit 1; }; \
	for M in tsuji.jvm.test tsuji.tools.test ziojson.jvm.test; do \
		./mill show "$$M.javaHome" | grep -qF "$$TAIHEI_TEST_JAVA_HOME" || { echo "[test-java-17] $$M does not fork on $$TAIHEI_TEST_JAVA_HOME"; exit 1; }; \
	done; \
	./mill tsuji.jvm.test.testForked + tsuji.tools.test.testForked + ziojson.jvm.test.testForked

deps:
	./mill mill.scalalib.Dependency/showUpdates

# ---------------------------------------------------------------------------
# Publishing (namespace dev.taihei): the dry run the release workflow builds its bundle with
# ---------------------------------------------------------------------------

# A release number in ~/.ivy2/local shadows the published artifact for every build on the machine
# (Coursier reads it first). So a publishLocal at a release tag on a clean tree is refused; a
# SNAPSHOT publishLocal stays.
publishLocal:
	@if [ -z "$$(git status --porcelain | grep -v '^??')" ] && git describe --exact-match --tags --match 'v[0-9]*' HEAD >/dev/null 2>&1; then \
	  echo "[publishLocal] refused: HEAD is release tag $$(git describe --exact-match --tags HEAD); release it instead"; \
	  exit 1; \
	fi
	./mill __.publishLocal

# The bundle Maven Central would receive, written to DRY_RUN_DIR instead of uploaded: the files
# the release workflow signs and publishes. Mill names a bundle only for a release version, so a
# SNAPSHOT dry run writes it unnamed. Mill's bundle mode signs, so the caller hands it a key in
# MILL_PGP_SECRET_BASE64 and MILL_PGP_PASSPHRASE: the release workflow makes a throwaway one, and
# never the release key. Only MILL_TESTS_PUBLISH_DRY_RUN, a test hook of Mill's, keeps this from
# uploading; Mill, which wants Central credentials before it looks at the hook, gets a placeholder
# that is no token, so a Mill that no longer honours the hook fails to authenticate instead of
# publishing. Without a daemon, which would keep the key for as long as it lives.
publish-dry-run:
	@set -euo pipefail; \
	VERSION=$$(./mill --no-daemon show release.gitVersion | tr -d '"'); \
	case "$$VERSION" in *-SNAPSHOT) BUNDLE=();; *) BUNDLE=(--bundleName "$(BUNDLE_PREFIX)-$$VERSION");; esac; \
	MILL_TESTS_PUBLISH_DRY_RUN=1 MILL_SONATYPE_USERNAME=dry-run-no-upload MILL_SONATYPE_PASSWORD=dry-run-no-upload \
		./mill --no-daemon mill.javalib.SonatypeCentralPublishModule/ "$${BUNDLE[@]}"; \
	echo "[publish-dry-run] bundle in $(DRY_RUN_DIR)"
