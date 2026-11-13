# Security policy

This is the security policy of Taihei Tsuji (`dev.taihei::taihei-tsuji`), and of taihei-i18n
(`dev.taihei::taihei-i18n`), the name of its 4.x line. It follows the policy of the Taihei
Framework.

## Reporting a vulnerability

Please report security problems privately to **security@taihei.dev**. Do not open a public
issue, pull request or discussion.

Include the library and version, a description of the problem and how it can be exploited, and, if
you can, a minimal reproduction.

## What happens next

- We acknowledge your report within five working days.
- We investigate, keep you informed, and agree on a disclosure date with you.
- We release a fix for every supported version line and publish an advisory on GitHub before the
  details become public. We credit you in the advisory unless you prefer otherwise.

## Supported versions

The latest major version receives security fixes. When a new major version is released, the
previous major version receives security fixes for 12 months after the new one ships.

| Line | Artifact | Security fixes |
| --- | --- | --- |
| 6.x | `dev.taihei::taihei-tsuji` | yes, the latest major |
| 5.0.x | `dev.taihei::taihei-tsuji` | yes, for 12 months after 6.0.0 |
| 4.0.x | `dev.taihei::taihei-i18n` | yes, until 2027-10-24, 12 months after Tsuji 5.0.0 |
| 3.x and older | `com.edoframework::edo-i18n`, `fish.genius::genius-i18n` | no: move to a supported line |

The policy that holds is the `SECURITY.md` on this repository's default branch; a copy in a
release or on a maintenance branch may be older. Published advisories are on this repository's
Security tab.

## Verifying releases

Every release published to Maven Central or to the Taihei Framework's own Maven repository is
signed by the Taihei Framework release key, UID
`Taihei Framework Releases <releases@taihei.dev>`, fingerprint
`5026 B299 6DDB 697E C463  0BCF 14A7 33E2 A507 702F`, published on keyserver.ubuntu.com and
keys.openpgp.org. A release may be signed by a signing subkey of that key. Fetch the key again
from either keyserver before you verify, because a subkey added later is not in a copy imported
earlier, and check that the signing key's primary key fingerprint is the one above (`gpg --verify`
shows it, or `gpg --list-keys --with-subkey-fingerprints`). The key also carries the UID
`Edo Framework Releases <releases@edoframework.com>`, from before the family was renamed.

From Taihei Tsuji 5.0.1 and taihei-i18n 4.0.3 on, every release is also kept on an immutable GitHub Release of its tag in
this repository, with `SHA256SUMS`, the SHA-256 of every file it holds. Where a version can be
downloaded from, Maven Central or the Taihei Framework's own Maven repository, is in the README and
the release notes: not every version is on Maven Central.
