SHELL = /bin/bash

# taihei-i18n 4.x, released by the family's release workflow (ADR 0014) as its own family entry:
# the maintainer's signed v4.x.y tag starts .github/workflows/release.yml, which calls the release
# repository's open.yml at a pinned commit. Its build job runs make validate, make test-java-17
# and make publish-dry-run in a fresh checkout of the tag, with no secret; it signs and publishes
# in a job that runs no code of this repository. Nothing here releases anything.

# The name Mill gives a release's bundle, and the folder open.yml takes it from.
BUNDLE_PREFIX = dev.taihei-taihei-i18n
# Where publish-dry-run writes the bundle (Mill empties it before each run). A named bundle is in
# its own subdirectory, <bundle name>/, in Maven layout.
DRY_RUN_DIR = $(CURDIR)/out/mill.javalib.SonatypeCentralPublishModule/publishAll.dest/repository

.PHONY: clean fix fmt compile prepare build test test-java-17 mima validate deps publishLocal publish-dry-run

# ---------------------------------------------------------------------------
# Build (cross-platform: __ covers the JVM and Scala.js modules)
# ---------------------------------------------------------------------------

clean:
	./mill clean

fix:
	./mill __.fix

fmt:
	./mill __.reformat

compile:
	./mill __.compile

prepare: fix fmt compile

build: prepare test

# __.test.testForked resolves to both i18n.jvm.test and i18n.js.test
# (JS tests run under Node).
test:
	./mill __.test.testForked

# Binary compatibility with the baseline release (release.mimaBaseline: the newest v-tag before HEAD
# that is on Maven Central, passing over a spent one, or MIMA_BASELINE). A break fails here; a
# major sets MIMA_BASELINE=none.
mima:
	@echo "[mima] baseline: $$(./mill show release.mimaBaseline | tr -d '\n "' | sed 's/^null$$/none/')"
	./mill __.mimaReportBinaryIssues

validate: fix fmt compile test mima

# The JVM tests on Java 17, the oldest Java the library supports, where locale data and the JDK's
# behaviour can differ from the newest JDK: TAIHEI_TEST_JAVA_HOME names that JDK (Mill runs on its
# own). The release workflow runs it; it refuses a JDK that is not 17, and checks that the test
# module forks on it.
test-java-17:
	@set -euo pipefail; \
	if [ -z "$${TAIHEI_TEST_JAVA_HOME:-}" ]; then echo "[test-java-17] set TAIHEI_TEST_JAVA_HOME to a JDK 17"; exit 1; fi; \
	JV=$$("$$TAIHEI_TEST_JAVA_HOME/bin/java" -version 2>&1); echo "$$JV"; \
	grep -q 'version "17\.' <<< "$$JV" || { echo "[test-java-17] $$TAIHEI_TEST_JAVA_HOME is not a JDK 17"; exit 1; }; \
	./mill show i18n.jvm.test.javaHome | grep -qF "$$TAIHEI_TEST_JAVA_HOME" || { echo "[test-java-17] i18n.jvm.test does not fork on $$TAIHEI_TEST_JAVA_HOME"; exit 1; }; \
	./mill i18n.jvm.test.testForked

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
