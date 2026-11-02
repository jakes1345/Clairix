#!/usr/bin/env bash
# Opens, or adds a comment to, the one open issue that tracks a failing workflow, so a red
# scheduled run is seen by someone instead of only by whoever happens to open the Actions
# tab. One issue per title: an open issue whose title is exactly $FAILURE_TITLE gets a
# comment with the run; otherwise one is created. Closed issues are left alone, so closing
# the issue once a run succeeds again is the whole reset.
#
# Needs the GitHub CLI and jq (both on ubuntu-latest), GH_TOKEN with issues: write, and the
# variables GitHub Actions sets. $FAILURE_MEANS, if set, is a sentence on what the failure
# costs users, put in the issue body.
#
#   FAILURE_TITLE="Work zones publish failing" FAILURE_MEANS="..." .github/scripts/report-failure.sh
set -euo pipefail

: "${FAILURE_TITLE:?set FAILURE_TITLE to the fixed issue title}"
: "${GH_TOKEN:?set GH_TOKEN to a token that can write issues}"
: "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_SERVER_URL:?}" "${GITHUB_WORKFLOW:?}"
: "${GITHUB_SHA:?}" "${GITHUB_EVENT_NAME:?}"

run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
body="Run [$GITHUB_RUN_ID]($run_url) of the **$GITHUB_WORKFLOW** workflow failed on $(date -u '+%Y-%m-%d %H:%M UTC') (started by \`$GITHUB_EVENT_NAME\`, commit ${GITHUB_SHA:0:12}, attempt ${GITHUB_RUN_ATTEMPT:-1})."
if [ -n "${FAILURE_MEANS:-}" ]; then
  body="$body

$FAILURE_MEANS"
fi
body="$body

The failing step and its log are in the run. Close this issue once a run has succeeded again; a later failure opens a new one."

# Every open issue, exact title only; pull requests share the endpoint and are left out.
number=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues?state=open&per_page=100" \
  | jq -rs --arg t "$FAILURE_TITLE" \
      '[.[] | select(type == "array") | .[] | select(.pull_request == null and .title == $t) | .number] | first // empty')

if [ -n "$number" ]; then
  gh issue comment "$number" --repo "$GITHUB_REPOSITORY" --body "$body" > /dev/null
  echo "Added the run to issue #$number ($FAILURE_TITLE)."
else
  url=$(gh issue create --repo "$GITHUB_REPOSITORY" --title "$FAILURE_TITLE" --body "$body")
  echo "Opened $url ($FAILURE_TITLE)."
fi
