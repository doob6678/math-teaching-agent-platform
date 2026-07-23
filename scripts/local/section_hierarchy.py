"""Repair recoverable parent-heading context in extracted textbook sections.

Vision extraction sometimes reads a child label (for example ``2.6.1``) on the
last line of one page and its parent heading (``2.6 双曲线及其方程``) at the
top of the next.  The child text is correct, but loses the subject phrase that
both lexical and semantic retrieval need.  This module restores only a numbered
parent that exists as a real nearby heading; it never invents labels from a
query or from a topic dictionary.
"""

from __future__ import annotations

import re
from collections import defaultdict
from typing import Any


# A parent heading may begin on the next PDF page when an OCR page boundary
# splits a printed chapter title from its first child block.  The named setting
# makes this structural assumption visible to callers instead of burying it in
# matching logic.
DEFAULT_PARENT_HEADING_PAGE_GAP = 1
SECTION_NUMBER_PATTERN = re.compile(r"(?<![\d.])(?P<number>\d+(?:\.\d+)+)(?![\d.])")
NUMERIC_ONLY_TITLE_PATTERN = re.compile(r"^\d+(?:\.\d+)+$")
# This mirrors a standard textbook definition sentence: a mathematical object
# is introduced with “称为 X”.  The captured name is source-derived evidence,
# not a curated list of textbook topics.
DEFINITION_LABEL_PATTERN = re.compile(r"称为(?P<label>[\u4e00-\u9fff]{2,12})(?=[，,。；;]|$)")


def section_number(value: Any) -> str:
    """Return the first dotted section number from a title or path component."""
    match = SECTION_NUMBER_PATTERN.search(str(value or ""))
    return match.group("number") if match else ""


def parent_number(value: str) -> str:
    """Return the immediate dotted parent of a section number, if it has one."""
    pieces = value.split(".")
    return ".".join(pieces[:-1]) if len(pieces) > 2 else ""


def page_number(row: dict[str, Any]) -> int:
    """Read page number defensively so malformed source rows are left untouched."""
    try:
        return int(row.get("page_no") or 0)
    except (TypeError, ValueError):
        return 0


def chapter_path(row: dict[str, Any]) -> list[str]:
    """Normalize path storage to nonempty display strings."""
    source = row.get("chapter_path")
    values = source if isinstance(source, list) else [source]
    return [str(value).strip() for value in values if str(value).strip()]


def child_section_number(row: dict[str, Any]) -> str:
    """Find the deepest numbered node already present on a section row."""
    numbers = [section_number(value) for value in [*chapter_path(row), row.get("section_title")]]
    numbered = [value for value in numbers if value]
    return max(numbered, key=lambda value: len(value.split(".")), default="")


def parent_heading_candidates(rows: list[dict[str, Any]]) -> dict[str, list[tuple[int, str]]]:
    """Index only real heading rows that can authoritatively supply parent text."""
    candidates: dict[str, list[tuple[int, str]]] = defaultdict(list)
    for row in rows:
        if str(row.get("chunk_type") or "") != "section_heading":
            continue
        title = str(row.get("section_title") or "").strip()
        number = section_number(title)
        if number and title:
            candidates[number].append((page_number(row), title))
    return candidates


def nearest_parent_heading(
    candidates: dict[str, list[tuple[int, str]]],
    number: str,
    child_page: int,
    max_page_gap: int,
) -> str:
    """Choose the nearest genuine parent heading inside the explicit page bound."""
    parent = parent_number(number)
    eligible = [
        (abs(parent_page - child_page), parent_page, title)
        for parent_page, title in candidates.get(parent, [])
        if child_page > 0 and abs(parent_page - child_page) <= max_page_gap
    ]
    return min(eligible)[2] if eligible else ""


def enrich_chapter_paths(
    rows: list[dict[str, Any]],
    max_parent_heading_page_gap: int = DEFAULT_PARENT_HEADING_PAGE_GAP,
) -> int:
    """Insert known immediate parents before child labels and return changed-row count.

    The same parent is applied to all blocks belonging to one child path, so
    prose, formula, figure-caption, and heading blocks remain in one section
    hierarchy.  Existing explicit parent labels are preserved verbatim.
    """
    if max_parent_heading_page_gap < 0:
        raise ValueError("max_parent_heading_page_gap must be non-negative")
    candidates = parent_heading_candidates(rows)
    changed = 0
    for row in rows:
        number = child_section_number(row)
        if not parent_number(number):
            continue
        parent_title = nearest_parent_heading(candidates, number, page_number(row), max_parent_heading_page_gap)
        if not parent_title:
            continue
        path = chapter_path(row)
        parent = parent_number(number)
        if any(section_number(component) == parent for component in path):
            continue
        child_indexes = [index for index, component in enumerate(path) if section_number(component) == number]
        insertion_index = child_indexes[-1] if child_indexes else len(path)
        row["chapter_path"] = [*path[:insertion_index], parent_title, *path[insertion_index:]]
        changed += 1
    return changed


def definition_label(text: Any) -> str:
    """Extract a directly named mathematical object from a definition sentence."""
    match = DEFINITION_LABEL_PATTERN.search(str(text or ""))
    return match.group("label") if match else ""


def enrich_definition_titles(rows: list[dict[str, Any]]) -> int:
    """Give numeric-only section labels a source-derived ``对象定义`` suffix.

    The original OCR title remains in ``source_section_title``.  Only groups
    with an explicit definition sentence are changed, and the derived title is
    applied consistently to every block on the same page/numbered child node.
    This makes a definition discoverable while preserving the underlying text
    and allowing the UI to display the original title when needed.
    """
    grouped: dict[tuple[str, int, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        number = child_section_number(row)
        title = str(row.get("section_title") or "").strip()
        if number and NUMERIC_ONLY_TITLE_PATTERN.fullmatch(title):
            grouped[(str(row.get("doc_id") or ""), page_number(row), number)].append(row)

    changed = 0
    for group_rows in grouped.values():
        label = next((definition_label(row.get("text")) for row in group_rows if definition_label(row.get("text"))), "")
        if not label:
            continue
        for row in group_rows:
            original_title = str(row.get("section_title") or "").strip()
            row.setdefault("source_section_title", original_title)
            row["section_title"] = f"{original_title} {label}定义"
            if str(row.get("chunk_type") or "") == "section_prose":
                row["chunk_type"] = "section_definition"
            changed += 1
    return changed
