"""
Minimal JS sanity check for the dashboard's ES modules.

Why this exists: the dashboard has no build step, so nothing ever parses these files
before a browser does. A single syntax error in a SHARED module (glossary.js, ui.js,
format.js) makes every page render a blank white body with no server-side symptom at
all -- the HTML and every .js file still return 200, so curl/health checks all pass.
That happened on 2026-08-26 from one missing comma in a glossary entry, and it took the
whole dashboard down silently.

This is deliberately NOT a full parser. It checks the two things that actually broke,
and refuses to guess at anything it cannot check reliably:
  1. bracket balance, with strings / templates / comments / regex literals handled
  2. adjacent object-literal entries with no comma between them

Run: python scripts/check_js_syntax.py [dir]
Exit 1 on any finding.
"""
import re
import sys
import pathlib

BACKSLASH = chr(92)

# A '/' starts a regex (rather than division) only after one of these tokens.
REGEX_OK_AFTER = set("(,=:[!&|?{};~+-*%^") | {
    "return", "typeof", "case", "in", "of", "do", "else",
}

PAIRS = {")": "(", "]": "[", "}": "{"}


def scan_brackets(src, path, findings):
    """Walk the source once tracking lexical state, and check bracket balance."""
    stack = []
    i, n, line = 0, len(src), 1
    prev_sig = ""  # last significant token, for the regex-vs-divide decision

    while i < n:
        c = src[i]

        if c == "\n":
            line += 1
            i += 1
            continue

        # ---- comments
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            end = src.find("*/", i + 2)
            if end == -1:
                findings.append((path, line, "unterminated block comment"))
                return
            line += src.count("\n", i, end)
            i = end + 2
            continue

        # ---- strings and template literals
        if c in "\"'`":
            quote, start_line = c, line
            i += 1
            closed = False
            while i < n:
                ch = src[i]
                if ch == BACKSLASH:
                    i += 2
                    continue
                if ch == "\n":
                    line += 1
                    if quote != "`":
                        findings.append(
                            (path, start_line,
                             "unterminated " + quote + " string (newline inside)"))
                        return
                if ch == quote:
                    i += 1
                    closed = True
                    break
                i += 1
            if not closed:
                findings.append((path, start_line, "unterminated " + quote + " string"))
                return
            prev_sig = "str"
            continue

        # ---- regex literal
        if c == "/" and (prev_sig == "" or prev_sig in REGEX_OK_AFTER):
            i += 1
            in_class = False
            while i < n:
                ch = src[i]
                if ch == BACKSLASH:
                    i += 2
                    continue
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    i += 1
                    break
                elif ch == "\n":
                    findings.append((path, line, "unterminated regex literal"))
                    return
                i += 1
            prev_sig = "re"
            continue

        # ---- brackets
        if c in "([{":
            stack.append((c, line))
            prev_sig = c
            i += 1
            continue
        if c in ")]}":
            if not stack:
                findings.append((path, line, "stray closing '" + c + "'"))
                return
            open_c, open_line = stack.pop()
            if open_c != PAIRS[c]:
                findings.append(
                    (path, line,
                     "'" + c + "' closes '" + open_c + "' opened on line " + str(open_line)))
                return
            prev_sig = c
            i += 1
            continue

        # ---- identifiers / keywords
        if c.isalnum() or c == "_" or c == "$":
            j = i
            while j < n and (src[j].isalnum() or src[j] in "_$"):
                j += 1
            word = src[i:j]
            prev_sig = word if word in REGEX_OK_AFTER else "ident"
            i = j
            continue

        if not c.isspace():
            prev_sig = c
        i += 1

    if stack:
        open_c, open_line = stack[-1]
        findings.append((path, open_line, "'" + open_c + "' is never closed"))


ENTRY_START = re.compile(r"^\s*(?:'[^']*'|\"[^\"]*\"|[A-Za-z_$][\w$]*)\s*:")
VALUE_END = re.compile(r"""['")\]\d]\s*$""")
CONTINUES = (",", "{", "[", "(", "+", "&&", "||", "?", ":", "=>", ";")


def check_missing_commas(text, path, findings):
    """
    Catches the exact 2026-08-26 bug: an object entry whose value ends without a comma,
    followed by another `key:` entry. Line-based and intentionally conservative -- it
    fires only when the next meaningful line clearly starts a new entry.
    """
    lines = text.split("\n")

    for idx, raw in enumerate(lines[:-1]):
        stripped = raw.strip()
        if not stripped or stripped.startswith("//") or stripped.startswith("*"):
            continue
        if not ENTRY_START.match(raw):
            continue
        if stripped.endswith(CONTINUES):
            continue
        if not VALUE_END.search(stripped):
            continue
        for nxt in lines[idx + 1:]:
            ns = nxt.strip()
            if not ns or ns.startswith("//") or ns.startswith("*") or ns.startswith("/*"):
                continue
            if ENTRY_START.match(nxt):
                findings.append(
                    (path, idx + 1,
                     "object entry has no trailing comma before the next entry"))
            break


def check_import_placement(text, path, findings):
    """An `import` that is not at statement position - most often inside another import block.

    ES modules only allow `import` at the top level, so this is a hard SyntaxError and the module
    never loads: the page renders blank while the server happily returns 200 for the HTML and for
    every .js file. Brace counting cannot see it, because

        import {
        import { a } from './a.js';
          b, c,
        } from './b.js';

    is perfectly balanced. This is how the Discovery page went blank on 2026-08-28, from an edit
    script that appended after "the last import line" and landed inside one.
    """
    depth = 0
    in_block_comment = False
    for i, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()

        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
                line = line.split("*/", 1)[1].strip()
            else:
                continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block_comment = True
            continue
        if line.startswith("//") or not line:
            continue

        if line.startswith("import ") and depth > 0:
            findings.append((path, i,
                             "`import` at brace depth " + str(depth)
                             + " - ES modules allow import only at the top level, so this file "
                               "fails to load and its page renders blank"))

        # Count after the check so an import opening its own block is not flagged.
        code = line.split("//", 1)[0]
        for ch in code:
            if ch in "{([":
                depth += 1
            elif ch in "})]":
                depth -= 1
        if depth < 0:
            depth = 0


def main():
    root = pathlib.Path(
        sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/static/js")
    files = sorted(root.glob("*.js"))
    if not files:
        print("no .js files under " + str(root))
        return 1

    findings = []
    for f in files:
        text = f.read_text(encoding="utf-8")
        scan_brackets(text, f.name, findings)
        check_missing_commas(text, f.name, findings)
        check_import_placement(text, f.name, findings)

    if findings:
        print("FAIL -- " + str(len(findings)) + " problem(s):")
        print("")
        for path, line, msg in findings:
            print("  " + path + ":" + str(line) + "  " + msg)
        return 1

    print("OK -- " + str(len(files)) + " module(s) checked, no syntax problems found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
