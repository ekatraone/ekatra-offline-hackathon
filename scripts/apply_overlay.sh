#!/usr/bin/env bash
# Apply the Ekatra Offline event work on top of a checkout of the PRE-EVENT code.
#
#   scripts/apply_overlay.sh --backend <dir> [--android <dir>] [--force]
#
# <dir> must be a checkout of the pinned pre-event base (see PINNED BASES below), either the
# private commit itself or the published sanitised snapshot whose commit subject names it.
# The script (1) copies the event-authored files from backend/ and android/ into
# the checkout, (2) applies patches/<codebase>/*.patch in numeric order with
# `git apply`, and (3) removes the files listed in patches/<codebase>/*.deletes.
# It never touches git history or remotes of the target directory.
set -euo pipefail

# PINNED BASES (pre-event, last commit before 2026-10-26T16:24:00Z)
BACKEND_BASE=63964970   # ekatra-platform-pvt, "Fix admin UI breakages found in audit", 2026-09-30
ANDROID_BASE=87a7fa3    # ekatra-asmaa, "Add Gemma 4 E2B and LFM2.5 models ...", 2026-09-30

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR=""; ANDROID_DIR=""; FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backend) BACKEND_DIR="$2"; shift 2;;
    --android) ANDROID_DIR="$2"; shift 2;;
    --force) FORCE=1; shift;;
    -h|--help) sed -n '2,10p' "$0"; exit 0;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$BACKEND_DIR$ANDROID_DIR" ] || { echo "give --backend and/or --android" >&2; exit 2; }

check_base() { # dir pin name
  if git -C "$1" rev-parse --git-dir >/dev/null 2>&1; then
    head="$(git -C "$1" rev-parse HEAD)"
    subject="$(git -C "$1" log -1 --format=%s HEAD)"
    case "$head" in
      $2*) ;;
      *) case "$subject" in
           "Pre-event base snapshot of "*" at $2 (sanitised)") continue_ok=1;;
           *) continue_ok=0;;
         esac
         if [ "$continue_ok" = 1 ]; then :
         elif [ "$FORCE" = 1 ]; then echo "warning: $3 HEAD is ${head:0:8}, expected $2" >&2
         else echo "$3 checkout is at ${head:0:8} but the overlay is pinned to $2 (use --force to try anyway)" >&2; exit 1; fi;;
    esac
  else
    echo "note: $1 is not a git checkout; cannot verify it is at base $2" >&2
  fi
}

apply_one() { # name dir
  local name="$1" dir
  dir="$(cd "$2" && pwd)"
  echo "== $name -> $dir"
  # 1. patches first (pre-existing files), checked all together before writing anything is not
  #    possible for sequential patches, so apply in order and stop at the first failure.
  local n=0
  for p in "$HERE/patches/$name"/*; do
    [ -e "$p" ] || continue
    case "$p" in
      *.patch)  git -C "$dir" apply --whitespace=nowarn --binary "$p" || { echo "FAILED to apply $(basename "$p")" >&2; exit 1; }; n=$((n+1));;
      *.deletes) while IFS= read -r f; do [ -n "$f" ] || continue; rm -f -- "$dir/$f"; (cd "$dir" && rmdir -p "$(dirname "$f")" 2>/dev/null) || true; done < "$p";;
    esac
  done
  # 2. event-authored files
  cp -R "$HERE/$name/." "$dir/"
  echo "   applied $n patches, copied $(cd "$HERE/$name" && find . -type f | wc -l | tr -d ' ') new files"
}

[ -n "$BACKEND_DIR" ] && { check_base "$BACKEND_DIR" "$BACKEND_BASE" backend; apply_one backend "$BACKEND_DIR"; }
[ -n "$ANDROID_DIR" ] && { check_base "$ANDROID_DIR" "$ANDROID_BASE" android; apply_one android "$ANDROID_DIR"; }
echo "done"
