# Contributing to Clairix

Pull requests are welcome. A security problem is not a pull request or an
issue: write to security@clairix.app first, as [SECURITY.md](SECURITY.md)
says, so it can be fixed before it is public. For a bug or an idea, open an
issue from one of the templates.

[docs/PROCESS.md](docs/PROCESS.md) says how a pull request is reviewed and
merged into `main` and which checks have to pass. This file covers the two
things every commit has to carry: a sign-off from a person, and a word on
any AI tool that helped write it.

## Sign off every commit

Every commit in a pull request ends with a line like this one:

```
Signed-off-by: Your Name <you@example.org>
```

It has to come from a person, under the name you are known by and an address
that reaches you. By adding it you certify the Developer Certificate of
Origin, below, for that commit: in short, that you wrote it or otherwise have
the right to submit it, under the licence of the files it changes. That
licence is GPL-3.0-only for almost everything, with the additional permission
for Google Play services set out in the [README](README.md#license);
`comms-worker/voprf/` is MIT, as its `Cargo.toml` says.

`git commit -s` adds the line. For commits you have already made,
`git commit --amend -s --no-edit` signs off the last one and
`git rebase --signoff origin/main` every commit on your branch (then push the
branch again with `--force-with-lease`).

`.github/workflows/dco.yml` fails a pull request in which any commit, merge
commits aside, has no `Signed-off-by` from a person. It does not count a
sign-off from a GitHub App (a name ending in `[bot]`, or a
`[bot]@users.noreply.github.com` address), from GitHub's own address, or from
the addresses Claude and Copilot commit with; the rule covers every other
tool too, whether or not the check knows its address. Commits that a GitHub
App makes in a pull request it opened itself (Dependabot's dependency
updates) are not checked; the person who approves that pull request answers
for them. The check starts with the first pull request opened against a
`main` that already has it: the pull request that added it, and the commits
made before the rule existed, were not checked, and signing off on them is
the maintainer's.

The Developer Certificate of Origin, from https://developercertificate.org:

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## AI assistance

You may use AI tools to write code, tests, documentation or commit messages.
These rules apply when you do:

1. **Say so in the commit.** A commit any part of which a tool wrote names
   the tool and model in a trailer:

   ```
   Assisted-by: Claude Code (claude-opus-5-5)
   ```

   A `Co-Authored-By:` trailer naming the tool, which Claude Code adds by
   itself, says the same thing and is fine.
2. **A person signs off, never the tool.** Your `Signed-off-by` certifies the
   Developer Certificate of Origin for the whole commit, including what the
   tool wrote: you have read and understood all of it, you have the right to
   submit it under the licence of the files it changes, and as far as you can
   tell it does not copy code that you could not submit yourself. A tool is
   not a person and cannot certify anything, so a commit signed off only by a
   tool fails the check.

   The maintainer's own pull requests are the exception: one the repository
   owner opens from a branch of this repository (not a fork) is not checked,
   because the maintainer answers for every change they merge, including what a
   tool wrote for them. Every other contributor signs off as above.
3. **A tool may be the commit's author.** Some tools commit under their own
   name (Claude Code commits as `Claude <noreply@anthropic.com>`). That
   records truthfully who wrote the change and is allowed, but the commit
   still needs your `Signed-off-by` before it can merge:
   `git rebase --signoff origin/main` adds it to every commit on the branch.
4. **Say in the pull request which parts a tool wrote**, so the reviewer
   knows where to look hardest.
5. **Never give a tool a secret**: not the asset signing seed, the release
   keystore or its passwords, a relay's enrollment or admin secret, nor
   anything else that would let its holder sign or act as the project. A
   session that keeps a transcript keeps the secret too
   ([tools/signing/README.md](tools/signing/README.md)).

## Before you open a pull request

- Run the tests for what you changed: [docs/audit/BUILD-AND-TEST.md](docs/audit/BUILD-AND-TEST.md)
  shows how to build every part and run every suite, and CI runs them on the
  pull request.
- Write what is true of the code after your change. A sentence in the app,
  the README or on the website that your change makes false is a bug, and
  the copy stays plain and specific, for a reader who may be frightened.
