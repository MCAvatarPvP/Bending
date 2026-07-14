#!/usr/bin/env bash
set -euo pipefail

remote="${PK_GITHUB_REMOTE:?PK_GITHUB_REMOTE is required}"
branch="${PK_GITHUB_BRANCH:?PK_GITHUB_BRANCH is required}"
release_directory="${PK_RELEASE_DIRECTORY:?PK_RELEASE_DIRECTORY is required}"
message="${PK_RELEASE_MESSAGE:?PK_RELEASE_MESSAGE is required}"

git fetch "$remote" "$branch"

remote_ref="$remote/$branch"
if ! git merge-base --is-ancestor "$remote_ref" HEAD; then
    echo "Local history has diverged from $remote_ref. Pull/rebase before publishing." >&2
    exit 3
fi

git add -- "$release_directory"
if git diff --cached --quiet -- "$release_directory"; then
    echo "The jars are unchanged; no publishing commit was needed."
else
    git commit -m "$message" -- "$release_directory"
fi

git push "$remote" "HEAD:$branch"
echo "Published $release_directory to $remote/$branch using Git authentication."
