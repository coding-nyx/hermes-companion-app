"""Git workspace and diff inspector provider for Hermes Companion."""

from __future__ import annotations

import os
import subprocess


def _run_git(repo_dir: str, args: list[str]) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["git"] + args,
            cwd=repo_dir,
            capture_output=True,
            text=True,
            check=False,
        )
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError:
        return 127, "", "git executable not found"
    except Exception as exc:
        return 1, "", str(exc)


def get_git_status(repo_dir: str) -> dict:
    rc, out, err = _run_git(repo_dir, ["status", "--porcelain=v1", "-b"])
    if rc != 0:
        return {"ok": False, "error": err or "git_status_failed"}

    lines = out.splitlines()
    branch = "main"
    tracking = ""
    ahead = 0
    behind = 0
    staged: list[str] = []
    modified: list[str] = []
    untracked: list[str] = []

    if lines and lines[0].startswith("## "):
        header = lines[0][3:].strip()
        # e.g. "main...origin/main [ahead 1, behind 2]"
        if "..." in header:
            parts = header.split("...", 1)
            branch = parts[0].strip()
            rest = parts[1]
            if " " in rest:
                tracking = rest.split(" ")[0].strip()
                if "[" in rest and "]" in rest:
                    bracket = rest.split("[", 1)[1].split("]", 1)[0]
                    for token in bracket.split(","):
                        token = token.strip()
                        if token.startswith("ahead "):
                            ahead = int(token.split(" ")[1])
                        elif token.startswith("behind "):
                            behind = int(token.split(" ")[1])
            else:
                tracking = rest.strip()
        else:
            branch = header

    for line in lines[1:]:
        if len(line) < 3:
            continue
        index_code = line[0]
        worktree_code = line[1]
        filepath = line[3:].strip()
        if " -> " in filepath:
            filepath = filepath.split(" -> ", 1)[1]

        if index_code in ("M", "A", "D", "R", "C"):
            staged.append(filepath)
        if worktree_code in ("M", "D"):
            modified.append(filepath)
        if index_code == "?" and worktree_code == "?":
            untracked.append(filepath)

    return {
        "ok": True,
        "branch": branch,
        "tracking": tracking,
        "ahead": ahead,
        "behind": behind,
        "staged_files": staged,
        "modified_files": modified,
        "untracked_files": untracked,
    }


def get_git_diff(repo_dir: str, file_path: str | None = None, staged: bool = False) -> dict:
    args = ["diff", "--no-color"]
    if staged:
        args.append("--cached")
    if file_path:
        args.extend(["--", file_path])

    rc, out, err = _run_git(repo_dir, args)
    if rc != 0:
        return {"ok": False, "error": err or "git_diff_failed"}

    # Also compute stats
    stat_args = ["diff", "--numstat"]
    if staged:
        stat_args.append("--cached")
    if file_path:
        stat_args.extend(["--", file_path])

    _, stat_out, _ = _run_git(repo_dir, stat_args)
    files_changed = 0
    insertions = 0
    deletions = 0
    for line in stat_out.splitlines():
        parts = line.split()
        if len(parts) >= 3:
            files_changed += 1
            if parts[0].isdigit():
                insertions += int(parts[0])
            if parts[1].isdigit():
                deletions += int(parts[1])

    return {
        "ok": True,
        "raw_diff": out,
        "files_changed": files_changed,
        "insertions": insertions,
        "deletions": deletions,
    }


def get_git_branches(repo_dir: str) -> dict:
    rc, out, err = _run_git(repo_dir, ["branch", "-a", "--no-color"])
    if rc != 0:
        return {"ok": False, "error": err or "git_branches_failed"}

    branches: list[str] = []
    current = ""
    for line in out.splitlines():
        trimmed = line.strip()
        if not trimmed:
            continue
        if trimmed.startswith("* "):
            current = trimmed[2:].strip()
            branches.append(current)
        else:
            branches.append(trimmed)

    return {"ok": True, "current": current, "branches": branches}


def stage_git_file(repo_dir: str, file_path: str, stage: bool = True) -> dict:
    if stage:
        args = ["add", file_path]
    else:
        args = ["restore", "--staged", file_path]
    rc, _, err = _run_git(repo_dir, args)
    if rc != 0:
        return {"ok": False, "error": err or "git_stage_failed"}
    return {"ok": True}


def commit_git(repo_dir: str, message: str) -> dict:
    if not message.strip():
        return {"ok": False, "error": "commit message required"}
    rc, out, err = _run_git(repo_dir, ["commit", "-m", message])
    if rc != 0:
        return {"ok": False, "error": err or "git_commit_failed"}
    return {"ok": True, "output": out.strip()}
