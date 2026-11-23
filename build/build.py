#!/usr/bin/env python3
"""
Assemble the MySQL Galera LogicModule suite into importable LogicMonitor module JSON.

modules/*.json are compact definitions; scripts/*.groovy are the script bodies. Each body is
prepended with the closures it reaches from scripts/lib/galera_common.groovy, so every
embedded script is self-contained -- a Collector has no include mechanism.

Export shapes are copied from LogicMonitor's own exports, by way of the Proxmox VE v2 suite
(github.com/PhilTheITGuy-USA/Proxmox-LogicModule.v2), whose build established each of them in
a portal: datapoint defaults, statusDisplayNames, graphs, the Active Discovery block (with
autoDeleteInstances read inverted on import) and the plain PropertySource.

Outputs:
  dist/<Module>.json              import via My Module Toolbox > Add > Import from file
  dist/scripts/<Module>.*.groovy  the assembled scripts, for the compile job and the harness
  dist/dashboards/<Name>.json     import via Dashboards > Add > From File

Usage:
  python build/build.py            build everything
  python build/build.py --check    validate only, write nothing (exit 1 on problems)
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
MODULES = ROOT / "modules"
DASHBOARDS = ROOT / "dashboards"
DIST = ROOT / "dist"
PREAMBLE = SCRIPTS / "lib" / "galera_common.groovy"

SUITE_GROUP = "MySQL Galera"
CATEGORY = "MySQL_Galera"
DATASOURCE_APPLIES_TO = 'hasCategory("MySQL_Galera") && jdbc.mysql.user && jdbc.mysql.pass'

DATA_TYPE_DOUBLE = 7
DATAPOINT_DEFAULTS = {
    "type": "gauge",
    "useValue": "output",
    "interpretMethod": "namevalue",
    "dataType": DATA_TYPE_DOUBLE,
    "maxDigits": 4,
    "min": "",
    "max": "",
    "threshold": "",
    "triggerInterval": 0,
    "clearInterval": 0,
    "noData": "Do not trigger an alert",
}
DATAPOINT_KEYS = {"name", "description", "type", "min", "max", "threshold", "triggerInterval",
                  "alertBody", "conditional", "statusNames"}
METRIC_TYPES = {"gauge", "derive"}
CONDITIONAL_KEY = "conditional"
STATUS_NAMES_KEY = "statusNames"
STATUS_NAME_TOKEN = "##ALERTVALUEDISPLAYSTATUSNAME##"
WILDVALUE_PREFIX = "##WILDVALUE##."
RESERVED_DATAPOINT_NAMES = {"in"}
PROPERTYSOURCE_TYPE_ID = 5

GRAPH_COLORS = ("blue", "orange", "green", "red", "fuchsia", "black")
GRAPH_KEYS = {"title", "verticalLabel", "max", "scale1024", "lines"}
GRAPH_LINE_KEYS = {"dp", "legend", "color", "type"}
GRAPH_LINE_TYPES = ("line", "area", "stack")

MAX_FIELD_CHARS = 4096          # portal: "Field value cannot contain more than 4096 characters"
MAX_SCRIPT_CHARS = 49135        # portal import API, groovyScript field
DISCOVERY_INTERVALS = {"0m", "15m", "60m", "1440m"}

EMIT_RE = re.compile(r"""galeraEmit\(\s*[^,]+,\s*'([A-Za-z0-9_]+)'""")
TOP_LEVEL_START = re.compile(r"^(def |if |import |[A-Za-z_][A-Za-z0-9_]* = )")
CLOSURE_DEF = re.compile(r"^def ([A-Za-z_][A-Za-z0-9_]*) = \{")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def strip_comments(text: str) -> str:
    """Drop whole-line // comments and blank lines; the //!/lib-groovy/v4 header stays."""
    lines = text.split("\n")
    kept = [lines[0]]
    for line in lines[1:]:
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        kept.append(line)
    return "\n".join(kept)


def shake_preamble(preamble: str, users: str) -> str:
    """The preamble without the closures nothing in `users` reaches, directly or indirectly."""
    chunks: list[list[str]] = []
    for line in preamble.split("\n"):
        if not chunks or TOP_LEVEL_START.match(line):
            chunks.append([])
        chunks[-1].append(line)
    named = {}
    for i, chunk in enumerate(chunks):
        match = CLOSURE_DEF.match(chunk[0])
        if match:
            named[match.group(1)] = i
    keep = {i for i in range(len(chunks)) if i not in named.values()}
    frontier = users
    while True:
        reached = {i for name, i in named.items() if i not in keep and re.search(rf"\b{name}\b", frontier)}
        if not reached:
            break
        keep |= reached
        frontier = "\n".join("\n".join(chunks[i]) for i in reached)
    return "\n".join("\n".join(chunks[i]) for i in sorted(keep))


def assemble(body_name: str) -> str:
    body = read(SCRIPTS / body_name)
    return strip_comments(shake_preamble(read(PREAMBLE), body).rstrip() + "\n\n" + body.lstrip("\n")) + "\n"


def expand_datapoint(dp: dict) -> dict:
    out = dict(DATAPOINT_DEFAULTS)
    out.update(dp)
    out.pop(CONDITIONAL_KEY, None)
    names = out.pop(STATUS_NAMES_KEY, None)
    if names:
        out["statusDisplayNames"] = [
            {"statusDisplayName": label, "metricValue": value, "operator": "EQ"}
            for value, label in sorted(names.items(), key=lambda item: int(item[0]))
        ]
    # BatchScript output is <wildvalue>.<Datapoint>=<value>; the key must carry the instance.
    out["interpretExpr"] = WILDVALUE_PREFIX + out["name"]
    return out


def expand_graph(graph: dict, priority: int) -> dict:
    lines = graph["lines"]
    free = [c for c in GRAPH_COLORS if c not in {line.get("color") for line in lines}]
    colors = [line.get("color") or free.pop(0) for line in lines]
    out = {
        "name": graph["title"],
        "title": graph["title"],
        "verticalLabel": graph["verticalLabel"],
        "min": 0,
        "rigid": "max" in graph,
        "scale1024": graph.get("scale1024", False),
        "timeScale": "1day",
        "displayPriority": priority,
        "datapoints": [{"name": line["dp"], "datapointName": line["dp"], "consolidationFn": "average"}
                       for line in lines],
        "lines": [{"datapointName": line["dp"], "legend": line["legend"], "color": colors[i],
                   "type": line.get("type", "line"), "isVirtual": False}
                  for i, line in enumerate(lines)],
    }
    if "max" in graph:
        out["max"] = graph["max"]
    return out


def build_datasource(defn: dict) -> tuple[dict, dict[str, str]]:
    name = defn["name"]
    collect = assemble(defn["collectScript"])
    discovery = assemble(defn["discoveryScript"])
    module = {
        "name": name,
        "displayedAs": defn["displayedAs"],
        "description": defn["description"],
        "appliesTo": defn["appliesTo"],
        "technicalNotes": defn["technicalNotes"],
        "searchKeywords": defn["searchKeywords"],
        "group": defn["group"],
        "collectionMethod": "batchscript",
        "collectionInterval": defn["collectionInterval"],
        "collectionAttrs": {"type": "groovy", "content": collect},
        "multiInstance": True,
        "useWildValueAsUniqueIdentifier": True,
        "dataSourceType": 1,
        "type": 0,
        "datapoints": [expand_datapoint(dp) for dp in defn["datapoints"]],
        "graphs": [expand_graph(graph, i + 1) for i, graph in enumerate(defn["graphs"])],
        "activeDiscovery": {
            "enabled": True,
            "discoveryMethod": "ad_script",
            "params": {"type": "groovy", "content": discovery},
            "discoveryInterval": defn["discoveryInterval"],
            "deleteInactiveInstances": False,
            # Read inverted on import: true leaves "Automatically Delete Instances" off.
            "autoDeleteInstances": not defn["autoDelete"],
            "disableDiscoveredInstances": False,
            "groupMethod": "none",
            "filters": [],
        },
    }
    return module, {f"{name}.collect.groovy": collect, f"{name}.ad.groovy": discovery}


def build_propertysource(defn: dict) -> tuple[dict, dict[str, str]]:
    script = assemble(defn["collectScript"])
    module = {
        "name": defn["name"],
        "group": defn["group"],
        "description": defn["description"],
        "appliesTo": defn["appliesTo"],
        "technicalNotes": defn["technicalNotes"],
        "searchKeywords": defn["searchKeywords"],
        "script": {"type": "groovy", "content": script},
        "type": PROPERTYSOURCE_TYPE_ID,
    }
    return module, {f"{defn['name']}.groovy": script}


def check_definition(path: Path, defn: dict) -> list[str]:
    problems = [f"{path.name}: {key} is {len(value)} characters; the portal refuses more than {MAX_FIELD_CHARS}"
                for key, value in defn.items() if isinstance(value, str) and len(value) > MAX_FIELD_CHARS]
    kind = defn.get("moduleType", "datasource")
    common = ["name", "description", "appliesTo", "technicalNotes", "searchKeywords", "group", "collectScript"]
    required = common + (["displayedAs", "collectionInterval", "discoveryScript", "discoveryInterval",
                          "autoDelete", "datapoints", "graphs"] if kind == "datasource" else [])
    missing = [key for key in required if key not in defn]
    if missing:
        return problems + [f"{path.name}: missing {', '.join(missing)}"]
    if defn["group"] != SUITE_GROUP:
        problems.append(f"{path.name}: group must be {SUITE_GROUP!r}")
    if path.stem != defn["name"]:
        problems.append(f"{path.name}: file name must match the module name {defn['name']!r}")
    if kind == "propertysource":
        if not defn["name"].startswith("addCategory_"):
            problems.append(f"{path.name}: a PropertySource here is an addCategory_* module")
        return problems
    if kind != "datasource":
        return problems + [f"{path.name}: unknown moduleType {kind!r}"]
    if defn["appliesTo"] != DATASOURCE_APPLIES_TO:
        problems.append(f"{path.name}: appliesTo must be {DATASOURCE_APPLIES_TO!r}")
    if not defn["name"].startswith("MySQL_Galera_"):
        problems.append(f"{path.name}: DataSource names start MySQL_Galera_")
    if defn["discoveryInterval"] not in DISCOVERY_INTERVALS:
        problems.append(f"{path.name}: discoveryInterval must be one of {sorted(DISCOVERY_INTERVALS)}")
    if not isinstance(defn["autoDelete"], bool):
        problems.append(f"{path.name}: autoDelete must be true or false")
    for dp in defn["datapoints"]:
        where = f"{path.name}: datapoint {dp.get('name')!r}"
        for key in sorted(set(dp) - DATAPOINT_KEYS):
            problems.append(f"{where} has unknown key {key!r}")
        if not re.fullmatch(r"[A-Z][A-Za-z0-9]+", dp.get("name", "")):
            problems.append(f"{where}: names are PascalCase")
        if dp.get("name", "").lower() in RESERVED_DATAPOINT_NAMES:
            problems.append(f"{where} is a name LogicMonitor reserves")
        if dp.get("type", "gauge") not in METRIC_TYPES:
            problems.append(f"{where}: type must be one of {sorted(METRIC_TYPES)}")
        if dp.get("type") == "derive" and dp.get("min") != "0":
            problems.append(f"{where}: a derive needs min \"0\" so a counter reset is dropped")
        if not dp.get("description"):
            problems.append(f"{where} needs a description")
        if dp.get("threshold") and not dp.get("alertBody"):
            problems.append(f"{where} has a threshold but no alertBody")
        names = dp.get(STATUS_NAMES_KEY)
        if names is not None:
            if not (isinstance(names, dict) and names and all(k.isdigit() and v.strip() for k, v in names.items())):
                problems.append(f"{where}: statusNames maps \"<integer>\" to a name")
            elif dp.get("alertBody") and STATUS_NAME_TOKEN not in dp["alertBody"]:
                problems.append(f"{where}: an alertBody on a named status must use {STATUS_NAME_TOKEN}")
    return problems


def duplicates(items: list) -> list:
    """The items that occur more than once, sorted."""
    return sorted(item for item, n in Counter(items).items() if n > 1)


def check_datasource(defn: dict) -> list[str]:
    """Declared datapoints against what the collection script prints, and graph lines."""
    name = defn["name"]
    problems: list[str] = []
    # Comments stripped first, so a commented-out galeraEmit never counts as printed.
    emitted = EMIT_RE.findall(strip_comments(read(SCRIPTS / defn["collectScript"])))
    declared = [dp["name"] for dp in defn["datapoints"]]
    for dup in duplicates(emitted):
        problems.append(f"{name}: script prints {dup!r} more than once")
    for dup in duplicates(declared):
        problems.append(f"{name}: datapoint {dup!r} is declared twice")
    for missing in sorted(set(emitted) - set(declared)):
        problems.append(f"{name}: script prints {missing!r} but no datapoint declares it")
    for unfilled in sorted(set(declared) - set(emitted)):
        problems.append(f"{name}: datapoint {unfilled!r} is declared but never printed")
    plotted = set()
    titles = [graph.get("title") for graph in defn["graphs"]]
    for dup in duplicates(titles):
        problems.append(f"{name}: graph title {dup!r} is used twice")
    for graph in defn["graphs"]:
        title = graph.get("title")
        for key in sorted(set(graph) - GRAPH_KEYS):
            problems.append(f"{name}: graph {title!r} has unknown key {key!r}")
        colours = [line.get("color") for line in graph["lines"] if line.get("color")]
        for dup in duplicates(colours):
            problems.append(f"{name}: graph {title!r} uses {dup!r} twice")
        for line in graph["lines"]:
            for key in sorted(set(line) - GRAPH_LINE_KEYS):
                problems.append(f"{name}: graph {title!r} line has unknown key {key!r}")
            if line.get("dp") not in declared:
                problems.append(f"{name}: graph {title!r} plots undeclared {line.get('dp')!r}")
            if line.get("color", "blue") not in GRAPH_COLORS:
                problems.append(f"{name}: graph {title!r} colour {line.get('color')!r} is not one of {GRAPH_COLORS}")
            if line.get("type", "line") not in GRAPH_LINE_TYPES:
                problems.append(f"{name}: graph {title!r} line type {line.get('type')!r} is unknown")
            plotted.add(line.get("dp"))
    for unplotted in sorted(set(declared) - plotted):
        problems.append(f"{name}: datapoint {unplotted!r} is on no graph")
    return problems


def check_dashboard(path: Path, dashboard: dict, index: dict[str, set[str]]) -> list[str]:
    """Every widget's "<displayedAs> (<name>)" reference and datapoint must resolve."""
    problems: list[str] = []
    refs: list[tuple[str, str]] = []

    def walk(node):
        if isinstance(node, dict):
            module = node.get("dataSourceFullName")
            if isinstance(module, str):
                if isinstance(node.get("dataPointName"), str):
                    refs.append((module, node["dataPointName"]))
                for column in node.get("columns", []) or []:
                    if isinstance(column, dict) and "dataPointName" in column:
                        refs.append((module, column["dataPointName"]))
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(dashboard.get("widgets", []))
    for module, datapoint in refs:
        if module not in index:
            problems.append(f"{path.name}: widget references unknown module {module!r}")
        elif datapoint not in index[module]:
            problems.append(f"{path.name}: {module!r} has no datapoint {datapoint!r}")
    return problems


COUNTED_DOCS = ["README.md", "CLAUDE.md"]
NUMBER_WORDS = {w: n for n, w in enumerate("zero one two three four five six seven eight nine ten".split())}
_NUMBER = r"(\d+|" + "|".join(NUMBER_WORDS) + r")"
COUNT_PATTERNS = {
    "datasource": re.compile(rf"\b{_NUMBER}\s+DataSources?\b", re.IGNORECASE),
    "propertysource": re.compile(rf"\b{_NUMBER}\s+PropertySources?\b", re.IGNORECASE),
    "total": re.compile(rf"\b{_NUMBER}(?=\s+modules\b)", re.IGNORECASE),
}


def check_doc_counts(definitions: list[dict]) -> list[str]:
    """A number before "modules", "DataSources" or "PropertySources" in the docs must be true."""
    actual = {"datasource": 0, "propertysource": 0}
    for defn in definitions:
        actual[defn.get("moduleType", "datasource")] += 1
    actual["total"] = len(definitions)
    problems = []
    for name in COUNTED_DOCS:
        doc = ROOT / name
        if not doc.exists():
            continue
        text = read(doc)
        for kind, pattern in COUNT_PATTERNS.items():
            for match in pattern.finditer(text):
                token = match.group(1).lower()
                stated = int(token) if token.isdigit() else NUMBER_WORDS[token]
                if stated != actual[kind]:
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{name}:{line}: says {match.group(0)!r}, but modules/ has {actual[kind]}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate without writing")
    args = parser.parse_args()

    paths = sorted(MODULES.glob("*.json"))
    problems: list[str] = []
    built: list[tuple[dict, dict[str, str]]] = []
    definitions: list[dict] = []
    index: dict[str, set[str]] = {}

    for path in paths:
        defn = json.loads(read(path))
        definitions.append(defn)
        found = check_definition(path, defn)
        if found:
            problems.extend(found)
            continue
        if defn.get("moduleType") == "propertysource":
            module, scripts = build_propertysource(defn)
        else:
            module, scripts = build_datasource(defn)
            problems.extend(check_datasource(defn))
            index[f"{defn['displayedAs']} ({defn['name']})"] = {dp["name"] for dp in defn["datapoints"]}
        for filename, text in scripts.items():
            if len(text) > MAX_SCRIPT_CHARS:
                problems.append(f"{filename}: {len(text)} characters; the portal refuses more than {MAX_SCRIPT_CHARS}")
        built.append((module, scripts))

    problems.extend(check_doc_counts(definitions))

    dashboards = []
    for path in sorted(DASHBOARDS.glob("*.json")):
        dashboard = json.loads(read(path))
        problems.extend(check_dashboard(path, dashboard, index))
        dashboards.append((path, dashboard))

    if problems:
        print("Validation failed:\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    if args.check:
        print(f"OK: {len(built)} modules and {len(dashboards)} dashboards validated")
        return 0

    if DIST.exists():
        shutil.rmtree(DIST)
    (DIST / "scripts").mkdir(parents=True)
    for module, scripts in built:
        (DIST / f"{module['name']}.json").write_text(json.dumps(module, indent=2) + "\n", encoding="utf-8")
        for filename, text in scripts.items():
            (DIST / "scripts" / filename).write_text(text, encoding="utf-8", newline="\n")
        if "datapoints" in module:
            print(f"  {module['name']:28} batchscript  {len(module['datapoints']):2} datapoints")
        else:
            print(f"  {module['name']:28} propertysource")
    if dashboards:
        (DIST / "dashboards").mkdir()
        for path, dashboard in dashboards:
            shutil.copyfile(path, DIST / "dashboards" / path.name)
            print(f"  {path.stem:28} dashboard    {len(dashboard['widgets']):2} widgets")
    print(f"\nBuilt {len(built)} modules and {len(dashboards)} dashboards into dist/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
