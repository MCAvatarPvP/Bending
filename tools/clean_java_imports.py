#!/usr/bin/env python3
"""Replace fully qualified Java types with ordinary imports when safe.

Examples:
    python tools/clean_java_imports.py --dry-run fabric/src/main/java
    python tools/clean_java_imports.py fabric/src/main/java/Foo.java
    python tools/clean_java_imports.py

With no paths, the script scans the main Java source roots of this repository.
Comments, strings, import declarations, and files with conflicting simple type
names are handled conservatively.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

DEFAULT_SOURCE_ROOTS = (
    Path("common/src/main/java"),
    Path("bukkit/src/main/java"),
    Path("fabric/src/main/java"),
)
IGNORED_DIRECTORIES = {".git", ".gradle", ".idea", "build", "out", "target"}

# Match a conventional package followed by its top-level type, leaving nested
# types in place. For example, java.util.Map.Entry becomes Map.Entry after
# importing java.util.Map. Requiring at least two lowercase package segments
# keeps ordinary member access from being mistaken for a qualified type.
QUALIFIED_TYPE = re.compile(
    r"(?<![\w$.])(?:[a-z_$][\w$]*\.){2,}[A-Z_$][\w$]*"
)
PACKAGE = re.compile(r"(?m)^[ \t]*package[ \t]+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)[ \t]*;")
IMPORT = re.compile(
    r"(?m)^[ \t]*import[ \t]+(?:(static)[ \t]+)?"
    r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$*][\w$*]*)*)[ \t]*;"
)
DECLARED_TYPE = re.compile(
    r"\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)\b"
)
TYPE_TOKEN = re.compile(r"\b([A-Z_$][\w$]*)\b")


@dataclass(frozen=True)
class CleanResult:
    text: str
    replacements: int
    added_imports: tuple[str, ...]
    skipped: tuple[str, ...]


def code_view(source: str) -> str:
    """Return source with comments and literals blanked, preserving offsets."""
    output = list(source)
    state = "code"
    index = 0
    length = len(source)

    def blank(position: int) -> None:
        if source[position] not in "\r\n":
            output[position] = " "

    while index < length:
        if state == "code":
            if source.startswith("//", index):
                blank(index)
                blank(index + 1)
                index += 2
                state = "line_comment"
                continue
            if source.startswith("/*", index):
                blank(index)
                blank(index + 1)
                index += 2
                state = "block_comment"
                continue
            if source.startswith('\"\"\"', index):
                for offset in range(3):
                    blank(index + offset)
                index += 3
                state = "text_block"
                continue
            if source[index] == '"':
                blank(index)
                index += 1
                state = "string"
                continue
            if source[index] == "'":
                blank(index)
                index += 1
                state = "character"
                continue
            index += 1
            continue

        if state == "line_comment":
            if source[index] in "\r\n":
                state = "code"
            else:
                blank(index)
            index += 1
            continue

        if state == "block_comment":
            if source.startswith("*/", index):
                blank(index)
                blank(index + 1)
                index += 2
                state = "code"
            else:
                blank(index)
                index += 1
            continue

        if state == "text_block":
            if source.startswith('\"\"\"', index):
                for offset in range(3):
                    blank(index + offset)
                index += 3
                state = "code"
            else:
                blank(index)
                index += 1
            continue

        # Ordinary string or character literal.
        if source[index] == "\\" and index + 1 < length:
            blank(index)
            blank(index + 1)
            index += 2
            continue
        terminator = '"' if state == "string" else "'"
        blank(index)
        if source[index] == terminator:
            state = "code"
        index += 1

    return "".join(output)


def add_imports(source: str, imports: Iterable[str]) -> str:
    additions = sorted(set(imports))
    if not additions:
        return source

    newline = "\r\n" if "\r\n" in source else "\n"
    lines = source.splitlines(keepends=True)
    import_lines: list[tuple[int, bool]] = []
    package_line: int | None = None

    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("package ") and stripped.endswith(";"):
            package_line = index
        match = re.match(r"^[ \t]*import[ \t]+(?:(static)[ \t]+)?", line)
        if match:
            import_lines.append((index, match.group(1) is not None))

    ordinary = [index for index, is_static in import_lines if not is_static]
    if ordinary:
        # Place each new import relative to existing ordinary imports. This
        # preserves comments and blank-line groupings instead of rebuilding the
        # entire import section.
        for name in additions:
            current_imports: list[tuple[int, str]] = []
            for index, line in enumerate(lines):
                match = re.match(
                    r"^[ \t]*import[ \t]+(?!static[ \t]+)"
                    r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$*][\w$*]*)*)[ \t]*;",
                    line,
                )
                if match:
                    current_imports.append((index, match.group(1)))
            later = next((index for index, imported in current_imports if imported > name), None)
            insertion = later if later is not None else current_imports[-1][0] + 1
            lines.insert(insertion, f"import {name};{newline}")
        return "".join(lines)

    static = [index for index, is_static in import_lines if is_static]
    insertion = static[0] if static else (package_line + 1 if package_line is not None else 0)
    block: list[str] = []
    if insertion > 0 and lines[insertion - 1].strip():
        block.append(newline)
    block.extend(f"import {name};{newline}" for name in additions)
    if insertion >= len(lines) or lines[insertion].strip():
        block.append(newline)
    lines[insertion:insertion] = block
    return "".join(lines)


def clean_source(source: str) -> CleanResult:
    view = code_view(source)
    import_matches = list(IMPORT.finditer(view))
    package_match = PACKAGE.search(view)
    current_package = package_match.group(1) if package_match else ""

    explicit_imports: dict[str, str] = {}
    for match in import_matches:
        if match.group(1):
            continue
        imported = match.group(2)
        if not imported.endswith(".*"):
            explicit_imports[imported.rsplit(".", 1)[-1]] = imported

    # Imports are not usage sites and must never become invalid statements such
    # as "import Map;".
    usage_view = list(view)
    for match in import_matches:
        for index in range(match.start(), match.end()):
            if usage_view[index] not in "\r\n":
                usage_view[index] = " "
    usage_view_text = "".join(usage_view)

    declared_names = {match.group(1) for match in DECLARED_TYPE.finditer(usage_view_text)}
    occurrences: dict[str, list[tuple[int, int]]] = {}
    for match in QUALIFIED_TYPE.finditer(usage_view_text):
        occurrences.setdefault(match.group(0), []).append(match.span())

    # Adding an import can silently rebind a simple name that was previously
    # supplied by the current package or a wildcard import. Blank every fully
    # qualified occurrence, then conservatively preserve qualification when the
    # same simple name is already used elsewhere in the file.
    unqualified_view = list(usage_view_text)
    for spans in occurrences.values():
        for start, end in spans:
            for index in range(start, end):
                if unqualified_view[index] not in "\r\n":
                    unqualified_view[index] = " "
    unqualified_names = {match.group(1) for match in TYPE_TOKEN.finditer("".join(unqualified_view))}

    replacements: list[tuple[int, int, str]] = []
    required_imports: set[str] = set()
    skipped: list[str] = []
    claimed_names = dict(explicit_imports)

    for qualified_name in sorted(occurrences):
        simple_name = qualified_name.rsplit(".", 1)[-1]
        conflicting_import = claimed_names.get(simple_name)
        if conflicting_import is not None and conflicting_import != qualified_name:
            skipped.append(
                f"{qualified_name}: {simple_name} is already imported from {conflicting_import}"
            )
            continue
        if simple_name in declared_names:
            skipped.append(f"{qualified_name}: {simple_name} is declared in this file")
            continue
        if simple_name in unqualified_names and explicit_imports.get(simple_name) != qualified_name:
            skipped.append(f"{qualified_name}: {simple_name} is already used unqualified in this file")
            continue

        claimed_names[simple_name] = qualified_name
        package_name = qualified_name.rsplit(".", 1)[0]
        needs_import = (
                package_name != current_package
                and package_name != "java.lang"
                and explicit_imports.get(simple_name) != qualified_name
        )
        if needs_import:
            required_imports.add(qualified_name)
        for start, end in occurrences[qualified_name]:
            replacements.append((start, end, simple_name))

    cleaned = source
    for start, end, simple_name in sorted(replacements, reverse=True):
        cleaned = cleaned[:start] + simple_name + cleaned[end:]
    cleaned = add_imports(cleaned, required_imports)
    return CleanResult(
        text=cleaned,
        replacements=len(replacements),
        added_imports=tuple(sorted(required_imports)),
        skipped=tuple(skipped),
    )


def java_files(paths: Iterable[Path]) -> Iterable[Path]:
    seen: set[Path] = set()
    for supplied_path in paths:
        path = supplied_path.resolve()
        candidates = [path] if path.is_file() else path.rglob("*.java") if path.is_dir() else []
        for candidate in candidates:
            if candidate.suffix != ".java":
                continue
            try:
                relative_parts = candidate.relative_to(path if path.is_dir() else path.parent).parts
            except ValueError:
                relative_parts = candidate.parts
            if any(part in IGNORED_DIRECTORIES for part in relative_parts):
                continue
            resolved = candidate.resolve()
            if resolved not in seen:
                seen.add(resolved)
                yield resolved


def read_source(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as source_file:
        return source_file.read()


def write_source(path: Path, source: str) -> None:
    with path.open("w", encoding="utf-8", newline="") as source_file:
        source_file.write(source)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="Java files or directories to scan")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="show files that would change without writing")
    mode.add_argument("--check", action="store_true", help="do not write and exit 1 when changes are needed")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = args.paths or [path for path in DEFAULT_SOURCE_ROOTS if path.exists()]
    if not paths:
        print("No Java source paths were found.", file=sys.stderr)
        return 2

    changed_files = 0
    total_replacements = 0
    for path in java_files(paths):
        original = read_source(path)
        result = clean_source(original)
        if result.text == original:
            continue
        changed_files += 1
        total_replacements += result.replacements
        action = "Would update" if args.dry_run or args.check else "Updated"
        try:
            display_path = path.relative_to(Path.cwd())
        except ValueError:
            display_path = path
        print(
            f"{action} {display_path}: {result.replacements} replacement(s), "
            f"{len(result.added_imports)} import(s) added"
        )
        for reason in result.skipped:
            print(f"  skipped {reason}")
        if not args.dry_run and not args.check:
            write_source(path, result.text)

    verb = "need updates" if args.dry_run or args.check else "updated"
    print(f"{changed_files} file(s) {verb}; {total_replacements} qualified reference(s) found.")
    return 1 if args.check and changed_files else 0


if __name__ == "__main__":
    raise SystemExit(main())
