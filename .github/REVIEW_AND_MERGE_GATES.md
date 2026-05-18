# Automated Review And Merge Gates

This repository uses three layers of pull request protection:

1. `CODEOWNERS` requests human ownership review from `@msugas-giga` and `@sgil`.
2. GitHub Copilot code review is configured through repository instructions and
   the `main quality gates` ruleset.
3. The Android Quality workflow is required before `main` can be updated.

## Automated Agent Review

`AGENTS.md` is the canonical repo-wide guidance for AI coding agents.
`.github/copilot-instructions.md` and `.github/instructions/*.instructions.md`
adapt that guidance for GitHub Copilot code review.

GitHub documents
[repository-wide Copilot code-review instructions](https://docs.github.com/en/copilot/customizing-copilot/adding-repository-custom-instructions-for-github-copilot)
in `.github/copilot-instructions.md`, and path-specific instructions in
`.github/instructions/**/*.instructions.md`.

Recommended repository setting:

1. Open repository **Settings**.
2. Go to **Code and automation** > **Rules** > **Rulesets**.
3. Use the `main quality gates` branch ruleset.
4. Enable **Automatically request Copilot code review**.
5. Keep **Review new pushes** off initially to reduce noise and premium-request
   usage. Authors can manually re-request review after meaningful updates.
6. Keep **Review draft pull requests** off initially; drafts should stay cheap
   while authors are still shaping the change.

## Required Checks

`main` must require these status checks from the Android Quality workflow:

- `Android build, lint, test, and package`
- `Sample backend typecheck`
- `Repository hygiene`
- `Dependency review`

Because required checks must appear on every pull request, the Android Quality
workflow intentionally runs on every pull request and every push to `main`.

## Ruleset Source

The desired repository ruleset is tracked in
`.github/rulesets/main-quality-gates.json`. Apply it with:

```bash
gh api \
  --method POST \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2026-03-10" \
  repos/GigaML/AndroidGigaSDKLivekit/rulesets \
  --input .github/rulesets/main-quality-gates.json
```

If the ruleset already exists, update it instead:

```bash
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2026-03-10" \
  repos/GigaML/AndroidGigaSDKLivekit/rulesets/<RULESET_ID> \
  --input .github/rulesets/main-quality-gates.json
```

The GitHub token applying this must have repository Administration write access.
The ruleset should be applied after the workflow version on `main` runs for all
pull requests; otherwise docs-only PRs can be blocked by required checks that
never start.

Reference: GitHub's repository ruleset API documents
[`required_status_checks`](https://docs.github.com/en/rest/repos/rules?apiVersion=2026-03-10#create-a-repository-ruleset)
and the `copilot_code_review` rule type used by this ruleset.
