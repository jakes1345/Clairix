# How a change reaches main, and a release

What a machine enforces, what the maintainer has to switch on for it to be
enforced, and the rule people follow for code an AI tool wrote. How to send a
change in the first place is in [CONTRIBUTING.md](../CONTRIBUTING.md).

## The rules on main

[`.github/rulesets/main.json`](../.github/rulesets/main.json) is a GitHub
ruleset for the default branch. Once applied (below):

- Every change arrives through a pull request. Pushing to main directly,
  force-pushing to it and deleting it are refused.
- History stays linear and pull requests are squash-merged: each pull request
  becomes one commit on main, which GitHub signs. GitHub cannot sign the
  commits a rebase merge makes, so the ruleset offers squash alone.
- Every commit carries a verified signature, and that includes the commits on
  a pull request's branch: an unsigned one there can stop GitHub
  squash-merging the pull request.
- Three checks must pass: `gate`, `dependency-review` and `secret-scan`
  (below).
- Every review conversation is resolved before merging.
- No approving review is required. Clairix has one maintainer, and GitHub
  lets nobody approve their own pull request, so a required review could
  never be given. When a second maintainer with write access joins, raise
  `required_approving_review_count` to 1 and set `require_code_owner_review`
  and `require_last_push_approval` to true; [CODEOWNERS](../.github/CODEOWNERS)
  already names the security, comms, crypto and relay code, and gets the new
  name.
- Nobody is exempt (`bypass_actors` is empty). In an emergency the
  maintainer can set the ruleset to Disabled under Settings → Rules →
  Rulesets, and sets it back to Active when it is over.
- Branches are not required to be up to date with main before merging: the
  emulator suite takes hours, and a release is checked on its own commit
  anyway (Releases, below).

### Applying it

The maintainer does this once, as a repository administrator:

1. Settings → General → Pull Requests: allow squash merging; turn off merge
   commits and rebase merging.
2. Settings → Rules → Rulesets → New ruleset → Import a ruleset, choose
   `.github/rulesets/main.json`, and create it. Or, with the GitHub CLI:
   `gh api --method POST repos/jakes1345/Clairix/rulesets --input .github/rulesets/main.json`
3. Sign your commits: `git config --global gpg.format ssh`,
   `git config --global user.signingkey ~/.ssh/id_ed25519.pub`,
   `git config --global commit.gpgsign true`, and add the same public key on
   GitHub under Settings → SSH and GPG keys as a signing key.
4. Commits made where nothing signs them, such as an AI agent's session, are
   signed again before their pull request can merge: on the branch,
   `git rebase --force-rebase --gpg-sign origin/main`, then
   `git push --force-with-lease`. Only main refuses a force push.
5. Settings → Code security: turn on Dependabot alerts and Dependabot
   security updates, and automatic dependency submission. GitHub reads no
   Gradle build file by itself, so without submission the app's libraries are
   missing from the dependency graph that alerts and `dependency-review` read.
6. Create the two environments and move the signing secrets into them
   (Signing keys, below).

## The checks

Most workflows run only when a pull request touches their files: the emulator
suite takes hours on four emulators. GitHub reports nothing at all for a
workflow that did not run, so a ruleset that required those jobs by name would
hold every other pull request forever ("Waiting for status to be reported").
The ruleset therefore requires `gate`, from
[`gate.yml`](../.github/workflows/gate.yml), which runs on every pull request,
waits until every other workflow that the pull request started on its head
commit has finished, and passes only if each of them succeeded. A workflow
added later is covered as soon as it runs on a pull request, with no change to
the ruleset. `dependency-review` and `secret-scan` run on every pull request
too and are required by name as well: keep those two job names, or change the
ruleset with them. Every other job can be renamed freely. A pull request is
checked by the workflows as it changes them, the gate's included, so a change
under `.github/` is read with that in mind.

Every workflow and its jobs, under the names GitHub shows as checks:

| Workflow | Jobs | Runs |
|---|---|---|
| Android APK (`android.yml`) | `build` (crypto crate and Tor tests, debug APKs, 16 KB pages, Kotlin unit tests, screenshots compared, Android Lint, detekt), `reproducible-build (first)`, `reproducible-build (second)`, `compare-builds` | Pull requests, and pushes to any branch but `release/v*`, touching `android/`, `comms-crypto/`, `relays/`, `tools/android/`, `tools/reproducible/`; by hand; called by Release |
| Emulator smoke test (`emulator.yml`) | `build`, then `smoke (<API level>)` for each level in its matrix (31, 33, 34 and 35 today) | Pull requests and pushes to main touching `android/`, `comms-crypto/`, `comms-worker/`; by hand |
| Relay (`relay.yml`) | `test` | Pull requests and pushes to main touching `comms-worker/` |
| Relay container (`relay-container.yml`) | `image` | The same, and every Monday |
| Relay list (`relays.yml`) | `check`, `publish` (main only) | Pull requests and pushes to main touching `relays/`, `tools/relays/` |
| Stalkerware list (`ioc.yml`) | `convert`, `publish` (main only), `alert` | Daily; pull requests and pushes to main touching `tools/ioc/` |
| Maps (`maps.yml`) | `plan`, `build (<region>)`, `sign`, `publish (<region>)`, `alert` | Daily; pushes to main touching `tools/graph-builder/` |
| Work zones (`workzones.yml`) | `aggregate`, `sign`, `publish`, `alert` | Every 15 minutes, as GitHub's schedule allows |
| Supply chain (`supply-chain.yml`) | `cargo-audit`, `npm-audit`, `dependency-review` (pull requests), `secret-scan`, `alert` | Every pull request, pushes to main, daily |
| Gate (`gate.yml`) | `gate` | Every pull request |
| Release (`release.yml`) | `checks / build`, `checks / reproducible-build (first)`, `checks / reproducible-build (second)`, `checks / compare-builds`, `emulator`, `sbom`, `release`, `verify / verify`, `alert` | A `v*` tag, a `release/v*` branch, or by hand |
| Verify a release (`verify-release.yml`) | `verify` | A published release; pull requests touching it or `tools/reproducible/` |

When Maps, Work zones, the Stalkerware list, Supply chain or a Release fails
outside a pull request, its `alert` job opens an issue, or adds the run to the
open one (`.github/scripts/report-failure.sh`).

## Releases

[`release.yml`](../.github/workflows/release.yml) signs nothing until three
jobs have passed on the commit being released:

- `checks` runs the whole Android APK workflow on it.
- `emulator` does not run the emulator suite a second time. It looks for an
  Emulator smoke test run, started by a push or by hand, that passed on a
  commit where `android/`, `comms-crypto/`, `comms-worker/`, `relays/` and the
  workflow are the same as on the release commit, and waits for such a run that
  is still going ([`wait_for_runs.py`](../.github/scripts/wait_for_runs.py)).
  A tag pushed right after its commit reached main waits for the run that push
  started. When there is none (the last change was to `relays/` alone, say,
  which the emulator workflow does not watch), the job fails and says so:
  start Emulator smoke test by hand on the release's ref, then re-run the
  release's failed jobs once it has passed.
- `sbom` makes the two CycloneDX bills of materials attached to every release.

Then `release` builds, signs and publishes, and `verify` rebuilds the release
from its tag on another machine and requires the published APKs to match.

## Signing keys

Two keys sign what phones install and download: the release keystore (the
secret `ANDROID_KEYSTORE_BASE64`, with `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`) and the asset signing key
(`ASSET_SIGNING_KEY`, [tools/signing](../tools/signing/README.md)). Every job
that reads one names a deployment environment, and the secrets belong in those
environments, not in the repository's own secrets, which any workflow run on
any branch can read:

| Environment | Jobs that name it | Its secrets | Deployment branches and tags |
|---|---|---|---|
| `release` | Release → `release` | the four keystore secrets and `ASSET_SIGNING_KEY` | branches `main` and `release/v*`, tags `v*` |
| `assets` | Maps → `sign`, Work zones → `sign`, Relay list → `publish`, Stalkerware list → `publish` | `ASSET_SIGNING_KEY` | branch `main` |

Under Settings → Environments, create both before the next run (GitHub
creates an environment the first time a job names it, with no rules), set the
deployment branches and tags above, and add the secrets there:

```sh
gh secret set ANDROID_KEYSTORE_BASE64 --env release --repo jakes1345/Clairix < release.keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --env release --repo jakes1345/Clairix
gh secret set ANDROID_KEY_ALIAS --env release --repo jakes1345/Clairix
gh secret set ANDROID_KEY_PASSWORD --env release --repo jakes1345/Clairix
```

then delete the repository secrets of the same names
(`gh secret delete NAME --repo jakes1345/Clairix`); an environment's secret
takes precedence, but the repository's copy stays readable by every workflow
until it is deleted. If you kept the asset signing seed offline, set it the
same way, once with `--env release` and once with `--env assets`. If it exists
only as the repository secret (`keygen.py --gh-secret` never shows it), it
cannot be read back: leave that secret in place until the key is next rotated
with `python3 tools/signing/keygen.py --force --gh-secret jakes1345/Clairix --gh-env release --gh-env assets`,
which, since phones accept only the key they were built with, also needs an
app release.

Required reviewers are the maintainer's choice. On `release` they make each
release wait for a click. On `assets` they hold every scheduled run, the daily
maps and stalkerware list and the work zones every 15 minutes, until someone
approves it, and phones get no new files in between. The deployment branch
rules alone already keep the keys from any job that is not running from the
refs listed.

## Dependencies

[`.github/dependabot.yml`](../.github/dependabot.yml) asks every Monday for
updates to the actions, both Rust crates, the relay's and the website's npm
trees, the app's Gradle libraries and plugins, and the Python tools: minor and
patch updates grouped into one pull request per directory, a major version on
its own. An update to `comms-worker/voprf` also changes `src/voprf.wasm`; rebuild
it with `comms-worker/scripts/build-voprf.sh` on the update's branch, or the
Relay workflow fails.

The Supply chain workflow audits the whole lockfiles every day, and on a push
to main or a pull request that changes one. A daily or main-branch audit that
finds an advisory fails and opens the "Supply chain check failing" issue; on a
pull request the audit only reports, and `dependency-review` is what fails a
pull request that brings in a vulnerable version. For an advisory with no
fixed version yet:

- Rust: add its id to `.cargo/audit.toml` at the repository root, with a
  comment saying why it does not apply and when to look again:
  `[advisories]` then `ignore = ["RUSTSEC-YYYY-NNNN"]`.
- npm: if a fixed version of the vulnerable package exists further down the
  tree, pin it with `overrides` in that `package.json`. npm audit has no
  ignore list, so until a fix exists the daily audit stays red and its issue
  open.
- `dependency-review`: an advisory read and found not to apply goes into
  `allow-ghsas` in `supply-chain.yml`, with the reason beside it.

The Python tools install with `--require-hashes`. To change a version, edit
the `.in` file beside the requirements file and compile it again in a fresh
virtual environment with pip-tools, under the Python the workflows use (3.12
for `tools/graph-builder`, 3.11 for `tools/signing` and
`tools/requirements-test`), with the command at the top of the compiled file:
`pip-compile --generate-hashes --no-emit-index-url --strip-extras --output-file=requirements.txt requirements.in`.
`tools/ioc/requirements.txt` lists its hashes by hand.

## Lint, detekt and screenshots

Android Lint and detekt fail on anything not listed in
`android/app/lint-baseline.xml` and `android/app/detekt-baseline.xml`, the
findings that were there when they were switched on. Fix those over time and
regenerate the lists only to drop what was fixed:
`./gradlew :app:updateLintBaselineFullSuiteDebug` and
`./gradlew :app:detektBaseline`, from `android/`.

detekt runs its default rules with the changes in `android/config/detekt.yml`,
each with its reason there: @Composable functions are exempt from the naming,
length, parameter-count and complexity rules, and the rules on line length,
numbers in code, early returns, loop jumps and functions per file are off,
since they reported this codebase's style rather than defects. A rule switched
off there is switched off for everyone; change that file in its own pull
request, with the reason beside the change.

The screenshot tests compare each screen with the PNGs in
`android/app/src/test/snapshots/images/`. When a screen changes on purpose,
record new ones on the machine that compares them: run Android APK by hand
(Actions → Android APK → Run workflow) with "record_screenshots", download its
`recorded-screenshots` artifact, put the PNGs in that directory, and commit
them. Rendering depends on the JDK and its fonts, so PNGs recorded on another
machine may not match.

## Code written with AI tools

Most of Clairix has been written with an AI model. That makes no difference to
what a change has to pass, and this rule makes sure a person has read it:

1. A person reads the whole diff before it merges, every line, tests and
   workflows included, in the pull request's file view or locally. A model's
   review, from another model or a review bot, helps; it is not the reading.
2. The pull request says which tool wrote the change and what the reader did
   besides reading it: ran the tests, tried it on a phone, checked it against
   the report or issue it answers.
3. A diff too large to read in one sitting is split into pull requests that
   are not.
4. Merging is the reader's statement that they read it; the pull request
   records who merged it.

No one outside the project has reviewed the code yet (README.md, Limits).
[`docs/audit/`](audit/README.md) is where an independent reviewer starts.
