import os
import json
import fnmatch
from typing import Iterable, List, Set, Optional, Tuple, Dict, Union

# ================================
# Config (example for your case)
# ================================
SRC_DIR = '.'
PACKAGE_PATH = 'package.json'
ONLY_TARGETS_WITHIN_INCLUDED_FOLDERS: bool = False
OUTPUT_FILE = 'output.txt'
MAX_CHARS = 250000

# ================================
# Target patterns
# ================================
EXCLUDE_DIRS = [
    '.git', 'lib', '.idea'
]
EXCLUDE_FILES = [
    '.gitignore', 'make_output.py', 'output.txt', 'build.xml', 'LICENSE', 'README.md', '.DS_Store'
]

INCLUDE_FILES: List[str] = ['*.*']
INCLUDE_FOLDERS: List[str] = []

BUZZWORD: Optional[Union[str, List[str]]] = None

# ================================
# Utilities
# ================================
def is_path_excluded(path: str) -> bool:
    parts = os.path.normpath(path).split(os.sep)
    return any(ex in parts for ex in EXCLUDE_DIRS)

def should_skip_file(path: str) -> bool:
    return os.path.basename(path) in EXCLUDE_FILES

def walk_dirs(root: str):
    """Yield (cur_root, dirs, files) with excluded dirs pruned."""
    for cur_root, dirs, files in os.walk(root, followlinks=False):
        # prune excluded dirs in-place (so we never descend into them)
        dirs[:] = [d for d in dirs if not is_path_excluded(os.path.join(cur_root, d))]
        if is_path_excluded(cur_root):
            continue
        yield cur_root, dirs, files

def walk_files(root: str):
    """Yield every file path under root, respecting excluded dirs."""
    for cur_root, dirs, files in walk_dirs(root):
        for f in files:
            yield os.path.join(cur_root, f)

def matches_any_pattern(rel_path: str, fname: str, patterns: Iterable[str]) -> bool:
    """Return True if file matches any of the provided patterns by:
       - exact basename equality, OR
       - fnmatch on the relative path from SRC_DIR, OR
       - fnmatch on the basename
    """
    for pat in patterns:
        if fname == pat or fnmatch.fnmatch(rel_path, pat) or fnmatch.fnmatch(fname, pat):
            return True
    return False

def folder_matches(d_basename: str, rel_dir_from_src: str, patterns: Iterable[str]) -> bool:
    """
    True if the directory matches any glob in patterns, checking both its basename
    and its path relative to SRC_DIR.
    """
    for pat in patterns or []:
        if fnmatch.fnmatch(d_basename, pat) or fnmatch.fnmatch(rel_dir_from_src, pat):
            return True
    return False

def _normalize_buzzwords(bz: Optional[Union[str, List[str]]]) -> List[str]:
    """Return a cleaned list of lowercase buzzwords (deduped), or [] if disabled."""
    if not bz:
        return []
    if isinstance(bz, str):
        # allow comma-separated strings OR a single phrase
        parts = [p.strip() for p in bz.split(',')] if ',' in bz else [bz.strip()]
    else:
        parts = [str(p).strip() for p in bz]
    # drop empties and lowercase for case-insensitive matching
    return sorted({p.lower() for p in parts if p})

def _file_contains_any_buzzword(path: str, buzzwords: List[str]) -> bool:
    """Case-insensitive substring check for any buzzword in file content."""
    if not buzzwords:
        return True  # no filter
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            content_lower = f.read().lower()
        return any(bw in content_lower for bw in buzzwords)
    except Exception:
        # unreadable files are treated as non-matches
        return False

def filter_by_buzzwords(files: Iterable[str], buzzwords: List[str]) -> List[str]:
    """Keep only files whose contents include any buzzword (if buzzwords provided)."""
    if not buzzwords:
        return sorted({os.path.normpath(f) for f in files})
    kept = []
    for f in files:
        if not os.path.isfile(f) or should_skip_file(f):
            continue
        if _file_contains_any_buzzword(f, buzzwords):
            kept.append(os.path.normpath(f))
    return sorted(set(kept))

# ================================
# Collectors (modular)
# ================================
def collect_by_INCLUDE_FILES(INCLUDE_FILES: Iterable[str]) -> Set[str]:
    """
    Include any file anywhere under SRC_DIR that matches ANY pattern in INCLUDE_FILES.
    """
    collected: Set[str] = set()
    patterns = list(INCLUDE_FILES or [])
    if not patterns:
        return collected

    for cur_root, _dirs, files in walk_dirs(SRC_DIR):
        for fname in files:
            fpath = os.path.join(cur_root, fname)
            if should_skip_file(fpath):
                continue
            rel = os.path.relpath(fpath, SRC_DIR)
            if matches_any_pattern(rel, fname, patterns):
                collected.add(os.path.normpath(fpath))

    return collected

def collect_all_inside_named_folders(folder_names: Iterable[str]) -> Set[str]:
    """
    Include ALL files nested inside ANY directory whose BASENAME or RELATIVE PATH
    matches one of folder_names (globs supported).
    """
    collected: Set[str] = set()
    patterns = list(folder_names or [])
    if not patterns:
        return collected

    for cur_root, dirs, _files in walk_dirs(SRC_DIR):
        for d in list(dirs):
            abs_dir = os.path.join(cur_root, d)
            rel_dir = os.path.relpath(abs_dir, SRC_DIR)
            if folder_matches(d, rel_dir, patterns):
                for f in walk_files(abs_dir):
                    if should_skip_file(f):
                        continue
                    collected.add(os.path.normpath(f))
    return collected

def collect_targets_within_named_folders(INCLUDE_FILES: Iterable[str], folder_names: Iterable[str]) -> Set[str]:
    """
    Include files that (a) are nested inside ANY directory whose BASENAME or RELATIVE PATH
    matches folder_names (globs supported), and (b) match ANY of INCLUDE_FILES patterns.
    """
    collected: Set[str] = set()
    patterns = list(INCLUDE_FILES or [])
    folder_globs = list(folder_names or [])

    if not patterns or not folder_globs:
        return collected

    for cur_root, dirs, _files in walk_dirs(SRC_DIR):
        for d in list(dirs):
            abs_dir = os.path.join(cur_root, d)
            rel_dir = os.path.relpath(abs_dir, SRC_DIR)
            if folder_matches(d, rel_dir, folder_globs):
                for f in walk_files(abs_dir):
                    if should_skip_file(f):
                        continue
                    rel = os.path.relpath(f, SRC_DIR)
                    fname = os.path.basename(f)
                    if matches_any_pattern(rel, fname, patterns):
                        collected.add(os.path.normpath(f))
    return collected

def collect_union(INCLUDE_FILES: Iterable[str], include_folder_names: Iterable[str]) -> List[str]:
    by_patterns = collect_by_INCLUDE_FILES(INCLUDE_FILES)
    by_folders = collect_all_inside_named_folders(include_folder_names)
    all_files = by_patterns | by_folders
    return sorted(all_files)

def collect_files(INCLUDE_FILES: Iterable[str],
                  include_folder_names: Optional[Iterable[str]],
                  only_within: bool) -> List[str]:
    """
    Dispatcher to honor ONLY_TARGETS_WITHIN_INCLUDED_FOLDERS flag.

    If include_folder_names is None or empty, traverse the entire SRC_DIR
    and return any file matching INCLUDE_FILES (ignoring folder limits).
    """
    if not include_folder_names:  # handles both None and []
        return sorted(collect_by_INCLUDE_FILES(INCLUDE_FILES))

    if only_within:
        return sorted(
            collect_targets_within_named_folders(INCLUDE_FILES, include_folder_names)
        )

    return collect_union(INCLUDE_FILES, include_folder_names)

# ================================
# Code line counting
# ================================
def count_code_lines(found_files: Iterable[str]) -> Tuple[int, int, Dict[str, Tuple[int, int]]]:
    """
    Returns:
      total_raw_lines: total physical lines across files
      total_non_empty_lines: total lines where line.strip() is truthy
      per_file: mapping path -> (raw_lines, non_empty_lines)
    """
    total_raw = 0
    total_non_empty = 0
    per_file: Dict[str, Tuple[int, int]] = {}

    for fpath in found_files:
        if not os.path.isfile(fpath):
            continue
        try:
            with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
                content = f.read()
        except Exception:
            # If unreadable, skip counting for this file but keep going
            continue

        lines = content.splitlines()
        raw = len(lines)
        non_empty = sum(1 for ln in lines if ln.strip())
        per_file[fpath] = (raw, non_empty)
        total_raw += raw
        total_non_empty += non_empty

    return total_raw, total_non_empty, per_file

# ================================
# Output helpers
# ================================
def parse_package_json(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            return data.get('dependencies', {}), data.get('devDependencies', {})
    except Exception:
        return None, None

def generate_tree(paths):
    tree = {}
    for p in paths:
        rel = os.path.relpath(p, SRC_DIR)
        cur = tree
        for part in rel.split(os.sep):
            cur = cur.setdefault(part, {})
    lines = []
    def fmt(node, prefix=''):
        items = sorted(node.keys())
        for i, k in enumerate(items):
            last = i == len(items) - 1
            lines.append(prefix + ('└── ' if last else '├── ') + k)
            if node[k]:
                fmt(node[k], prefix + ('    ' if last else '│   '))
    fmt(tree)
    return '\n'.join(lines) if lines else 'No files found.'

def append_files(found_files, prelude_text='', output_file=OUTPUT_FILE):
    total_chars = 0
    base, ext = os.path.splitext(output_file)
    part = 1
    current_output = f"{base}_part{part}{ext}"
    out_file = open(current_output, 'w', encoding='utf-8')
    current_chars = 0
    output_files = []

    def write_chunk(s: str):
        nonlocal out_file, current_chars, total_chars, part, current_output
        if current_chars + len(s) > MAX_CHARS:
            out_file.close()
            output_files.append(current_output)
            part += 1
            current_output = f"{base}_part{part}{ext}"
            out_file = open(current_output, 'w', encoding='utf-8')
            header = f"## Continuation Part {part}\n\n"
            out_file.write(header)
            current_chars = len(header)
            total_chars += len(header)
        out_file.write(s)
        current_chars += len(s)
        total_chars += len(s)

    if prelude_text:
        write_chunk(prelude_text + "\n\n")

    write_chunk("## File Structure (Matched Files)\n")
    write_chunk(generate_tree(found_files) + "\n\n")

    if PACKAGE_PATH and os.path.isfile(PACKAGE_PATH) and os.path.basename(PACKAGE_PATH) == "package.json":
        deps, dev_deps = parse_package_json(PACKAGE_PATH)
        if deps or dev_deps:
            write_chunk("## Package Dependencies\n")
            if deps:
                write_chunk("### Dependencies\n")
                for k, v in deps.items():
                    write_chunk(f"- {k}: {v}\n")
            if dev_deps:
                write_chunk("\n### DevDependencies\n")
                for k, v in dev_deps.items():
                    write_chunk(f"- {k}: {v}\n")
                write_chunk("\n")

    for fpath in found_files:
        if not os.path.isfile(fpath):
            continue
        try:
            with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
                content = f.read()
            write_chunk(f"## {os.path.relpath(fpath, SRC_DIR)}\n")
            write_chunk(content + "\n\n")
        except Exception as e:
            write_chunk(f"Error reading file {fpath}: {e}\n\n")

    out_file.close()
    output_files.append(current_output)
    if part == 1:
        os.rename(current_output, output_file)
        output_files = [output_file]
    return total_chars, output_files

# ================================
# Main
# ================================
if __name__ == '__main__':
    # Step 1: collect with existing INCLUDE/EXCLUDE constraints
    collected_files = collect_files(
        INCLUDE_FILES,
        INCLUDE_FOLDERS,
        ONLY_TARGETS_WITHIN_INCLUDED_FOLDERS
    )

    # Step 2: apply buzzword filter (ONLY include files that reference any buzzword)
    buzzwords = _normalize_buzzwords(BUZZWORD)
    found_files = filter_by_buzzwords(collected_files, buzzwords)

    # Prelude text
    prelude = f"""
* You are a tutor assisting a student with their Java programming assignment.
* You will never provide direct solutions or code snippets.
* Instead, guide the student through explanations, hints, and questions to help them understand the concepts.
* It is ok to provide working code examples, but only if the student is clearly stuck and requests them.
* The following files have been collected from the student's project directory.

##################################
## JAVA PROJECT
##################################

Found files:
{chr(10).join(f"- {os.path.relpath(p, SRC_DIR)}" for p in found_files) if found_files else "None"}
"""

    if found_files:
        # Count code lines before writing outputs (counts are based on raw files, not the output)
        total_raw_lines, total_non_empty_lines, per_file_counts = count_code_lines(found_files)

        total_chars, outs = append_files(found_files, prelude_text=prelude, output_file=OUTPUT_FILE)

        print(f"Contents of {len(found_files)} found files have been appended to {', '.join(outs)}.")
        print(f"Total characters included: {total_chars}")
        print("Found files:")
        for f in found_files:
            print(f"- {os.path.relpath(f, SRC_DIR)}")

        # New: code line metrics (raw code, not lines in the output file)
        print("\n==== Code Line Counts (Raw Source) ====")
        print(f"Total raw lines of code: {total_raw_lines}")
        print(f"Total non-empty lines of code: {total_non_empty_lines}")
    else:
        if buzzwords:
            print(f"No files containing buzzwords {buzzwords} matched the current INCLUDE/EXCLUDE constraints in {SRC_DIR}.")
        else:
            print(f"No matching files found in {SRC_DIR}")
