#!/usr/bin/env python3
"""
Generate dashboards/MySQL_Galera_Cluster.json with the logicmonitor-modules skill's
lm_dashboards.Dashboard builder, which emits only key sets and values copied from
LogicMonitor's published dashboard exports.

A maintainer tool: it needs the skill (by default at ../logicmonitor-modules-skill/skill, or
LM_SKILL_DIR). The generated file is committed, and build/build.py checks every widget's module
and datapoint reference against modules/ on each build, so a release does not need the skill.
Once the dashboard has been imported and adjusted in a portal, replace the file with the
portal's export instead and stop regenerating it.

Usage: python build/make_dashboard.py
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILL = Path(os.environ.get("LM_SKILL_DIR", ROOT.parent / "logicmonitor-modules-skill" / "skill"))
sys.path.insert(0, str(SKILL / "scripts"))

from lm_dashboards import Dashboard, module_ref  # noqa: E402

NODE = module_ref("MySQL Galera Node", "MySQL_Galera_Node")
REPL = module_ref("MySQL Galera Replication", "MySQL_Galera_Replication")
# One resource per Galera node: legend by resource, not by instance (the instance is the port).
HOST = "##HOSTNAME##"


def main() -> int:
    d = Dashboard(
        "MySQL Galera Cluster",
        "Galera cluster health per node: quorum, node state, cluster size, flow control, "
        "queues and conflicts. Set the defaultResourceGroup token to the group holding the nodes.",
        "MySQL Galera",
    )
    d.alert_status(1, 1, 4, 4, "Galera Alerts by Node", "MySQL Galera*", group_by="device")
    d.table(1, 5, 8, 4, "Node State", NODE, [
        d.column("ClusterStatus", "Cluster (2 Primary)", display_type="raw", rounding=0),
        d.column("LocalState", "State (4 Synced)", display_type="raw", rounding=0),
        d.column("Ready", "Ready", display_type="raw", rounding=0),
        d.column("Connected", "Connected", display_type="raw", rounding=0),
        d.column("ClusterSize", "Cluster size", display_type="raw", rounding=0),
        d.column("MissingNodes", "Missing", display_type="raw", rounding=0),
    ], top_x=-1)
    d.graph(5, 1, 6, 4, "Flow Control Paused", [
        d.series(REPL, "FlowControlPausedPercent", legend=HOST)], vertical_label="% of time")
    d.graph(5, 7, 6, 4, "Receive Queue (avg)", [
        d.series(REPL, "LocalRecvQueueAvg", legend=HOST)], vertical_label="write-sets", maximum=None)
    d.graph(9, 1, 6, 4, "Cluster Size", [
        d.series(NODE, "ClusterSize", legend=HOST)], vertical_label="nodes", maximum=None)
    d.graph(9, 7, 6, 4, "Write-Sets Replicated", [
        d.series(REPL, "TransactionsReplicated", legend=HOST)], vertical_label="per second", maximum=None)
    d.graph(13, 1, 6, 4, "Certification Failures", [
        d.series(REPL, "CertFailures", legend=HOST)], vertical_label="per second", maximum=None)
    d.graph(13, 7, 6, 4, "Send Queue (avg)", [
        d.series(REPL, "LocalSendQueueAvg", legend=HOST)], vertical_label="write-sets", maximum=None)
    d.alert_table(17, 1, 12, 4, "Galera Alerts", "MySQL Galera*")

    out = ROOT / "dashboards" / "MySQL_Galera_Cluster.json"
    out.write_text(json.dumps(d.render(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)}: {len(d.widgets)} widgets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
