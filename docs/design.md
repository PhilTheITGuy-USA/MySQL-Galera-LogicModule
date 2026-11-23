# MySQL Galera LogicModule suite: design

This suite started from a short design note, kept as [`original-design.rtf`](original-design.rtf):
one Groovy Script DataSource that reads `SHOW GLOBAL STATUS LIKE 'wsrep_%'` with LogicMonitor's
`jdbc.mysql.user` / `jdbc.mysql.pass`, maps the string states to numbers, and applies on
`hasCategory("MySQL") && mysql.galera == "true"`. That note was checked against the Galera, MariaDB
and LogicMonitor documentation (sources at the end) and against a real three-node MariaDB Galera
cluster. Its approach holds. What changed, and why, is below.

## 1. What the original design got right

- **Status variables over JDBC are the right source.** Every `wsrep_*` figure a Galera node knows
  about itself is in `SHOW GLOBAL STATUS`. One query per poll, and it needs only the `USAGE`
  privilege.
- **LogicMonitor's own credential properties.** `jdbc.mysql.user` / `jdbc.mysql.pass` (and
  `jdbc.mysql.port`) are what LogicMonitor's MySQL package reads, so a resource already
  monitored by that package needs nothing new.
- **The headline checks:** `wsrep_cluster_status` (Primary), `wsrep_ready`, `wsrep_connected`,
  `wsrep_local_state_comment`, `wsrep_cluster_size`, flow control, receive queue, certification
  failures. These are the checks Galera's and MariaDB's monitoring guides name.
- **Keep LogicMonitor's MySQL modules for general health** (connections, InnoDB, sizes).

## 2. What was changed

| # | Original | Problem | Now |
|---|---|---|---|
| 1 | Node state map 4 Synced, 3 Donor/Desynced, 2 Joined, 1 Joining | Not Galera's numbering: `wsrep_local_state` is 1 Joining, 2 Donor/Desynced, 3 Joined, 4 Synced. The `Warn < 4 / Critical < 3` thresholds then made a *Joined* node critical and a donor a warning. | Galera's own numbering, 0 for Initialized or any other state. One warning level below Synced for five polls; a node out of the cluster is already critical through ClusterStatus and Ready. |
| 2 | `cluster_status` Primary = 1, anything else 0 | Non-Primary (quorum lost, a partition) and Disconnected are different failures. | 2 Primary, 1 Non-Primary, 0 Disconnected, named in alerts. Critical below Primary. |
| 3 | `?: 0` on every metric; `catch` prints and returns 1 | A missing value printed as 0 reads as a healthy (or failing) measurement. | Absent or non-numeric values are withheld (No Data). An unknown state word is withheld, never guessed. Failure exits 2 with the reason on stderr. |
| 4 | `flow_control_paused` gauge, thresholds 0.1 / 0.3 | `wsrep_flow_control_paused` is a fraction *since the last FLUSH STATUS*: any other tool resets it, and between resets it averages over an unknown window. | `wsrep_flow_control_paused_ns` (cumulative) as a derive, printed / 10^7 so the rate is the percent of time paused over the poll. Warn above 10% for five polls. |
| 5 | `cert_failures` "Counter", `bf_aborts` gauge | Both are cumulative totals since server start. | Derive (per second), with the other cumulative counters (write-sets and bytes sent and received, local commits). |
| 6 | `cluster_size < 3` hard-coded | The right size is each cluster's own. | `galera.cluster.size` on the resource or group: ExpectedClusterSize and MissingNodes (error when > 0). Without the property, no size alert. |
| 7 | Manual `mysql.galera = true` on each node, AppliesTo also needing `hasCategory("MySQL")` | A per-resource flag gets forgotten; the category comes from a module some portals do not run. | `addCategory_MySQL_Galera` detects a Galera node (wsrep on, a provider loaded, a node state reported) and adds category `MySQL_Galera`. Setting the MySQL credentials is the on-switch. |
| 8 | One port, `useSSL=false&allowPublicKeyRetrieval=true` always | Turns TLS off for everyone; ignores `jdbc.mysql.port`. | One instance per Galera port (3306 and `jdbc.mysql.port`, as LogicMonitor's modules do). TLS left to the driver's default; LogicMonitor's own `mysql.nossl` and `mysql.url_driver_properties` honoured. Connect and socket timeouts set. |
| 9 | `Sql.withInstance(url, user, pass, driver)` | Goes through `DriverManager`, which refuses a driver loaded by another class loader; the class name was a manual edit for old drivers. | The driver is instantiated and asked directly (`Driver.connect`), trying Connector/J 8+, the pre-8 name and MariaDB Connector/J in turn. |
| 10 | Plain session | With causality checks on (`wsrep_sync_wait`), reads on a node that lost the Primary Component block and fail with ERROR 1205: the node most in need of watching goes dark. | `SET SESSION wsrep_sync_wait = 0` first (MariaDB's monitoring guidance). Confirmed live: a partitioned node still answers. |
| 11 | Script collection, one module | | BatchScript, split in two by subject: **Node** (membership and state) and **Replication** (flow control, queues, throughput, conflicts), each with graphs, plus a dashboard. |

Not taken from the original: `wsrep_flow_control_sent` / `_recv`, whose reset behaviour the
providers document differently; `wsrep_cluster_state_uuid` / `wsrep_cluster_conf_id`, which only
mean something compared across nodes (a cross-node consistency module is possible later).

### LogicMonitor's MySQL Replica Status module

The original note says to unassign `Oracle_MySQL_ReplicaStatus` (and the heartbeat lag modules)
from Galera nodes unless a node is also an asynchronous replica. That stands: Galera replication
is not MySQL replication, and those modules report nothing useful there. Once this suite is
installed, the clean way is an AppliesTo exclusion, `&& !hasCategory("MySQL_Galera")`, on those
modules, re-applied after LogicMonitor updates them, or disabling them on the group holding the
cluster.

## 3. Modules

| Module | Type | What |
|---|---|---|
| `addCategory_MySQL_Galera` | PropertySource | Category `MySQL_Galera`; `auto.galera.*` cluster name, node name, provider version and vendor, server flavour and version, ports |
| `MySQL_Galera_Node` | DataSource, BatchScript, 1 min | ClusterStatus, LocalState, Ready, Connected, ClusterSize, ExpectedClusterSize, MissingNodes, ClusterWeight, DesyncCount, OpenTransactions |
| `MySQL_Galera_Replication` | DataSource, BatchScript, 1 min | FlowControlPausedPercent, FlowControlActive, receive / send queues (now and average), CertDepsDistance, write-sets and bytes sent / received, LocalCommits, CertFailures, BruteForceAborts |

Both DataSources share one discovery: one instance per Galera port, wildvalue = the port.
Instances are never auto-deleted: a node that has gone is what they alert on. Each poll is one
JDBC connection per Galera port.

## 4. Verification

The one record of what has been proved, and by which layer.

| Layer | What | Result |
|---|---|---|
| Local: build | `python build/build.py`: definitions against scripts (every printed datapoint declared and every declared one printed, graphs, status names, field and script length limits), dashboard references | Pass, 2026-11-23 |
| Local: lint | `lm_lint_module.py dist`, `lm_dashboards.py check` | 0 errors; 2 warnings judged (the wildvalue is the port number, validated as digits) |
| Local: compile | `groovyc` in `groovy:4-jdk17` on every assembled script | Pass, 2026-11-23 |
| Local: harness | `tests/harness.groovy`: 56 checks over six recorded server shapes (MariaDB Galera 4, Percona XtraDB Cluster donor, a partitioned node, Galera 3 joining, standalone MySQL, wsrep without a provider) | All pass, 2026-11-23 |
| Live target | `tests/live.sh`: a real three-node MariaDB 11.4.13 / Galera 26.4.27 cluster and a standalone MariaDB, through MySQL Connector/J 9.1.0, no test seam. Healthy cluster; a 200-row write load; a node desynced (`wsrep_desync`); a node partitioned off the network (Non-Primary, LocalState 0, Ready 0, size 1, MissingNodes 2; the majority Primary with size 2, MissingNodes 1); the node healed and rejoined | All pass, 2026-11-23 |
| Portal import | Import of the three modules and the dashboard | **Not yet done** |
| Collector | The scripts on a real Collector, with its bundled JDBC driver | **Not yet done.** The driver class names and direct `Driver.connect` are inferred from LogicMonitor's documentation that the Collector bundles JDBC drivers; first thing to check in a portal is a Poll Now on `MySQL_Galera_Node` |

Not tested anywhere yet: Percona XtraDB Cluster and Galera 3 against a live server (fixtures
only), TLS-required accounts, multiple Galera ports on one live host.

## Sources

- Galera / MariaDB: [Galera Cluster status variables](https://mariadb.com/docs/galera-cluster/reference/galera-cluster-status-variables),
  [Monitoring MariaDB Galera Cluster](https://mariadb.com/docs/galera-cluster/high-availability/monitoring-mariadb-galera-cluster),
  [Flow control in Galera Cluster](https://mariadb.com/docs/galera-cluster/galera-management/performance-tuning/flow-control-in-galera-cluster),
  [Galera: using status variables](https://galeracluster.com/library/documentation/monitoring-cluster.html),
  [Codership list: wsrep_local_state 2 = Donor/Desynced](https://groups.google.com/g/codership-team/c/RgEIvvKqKRk/m/01D_3UV0L6wJ),
  [Percona: realtime stats to watch in PXC and Galera](https://www.percona.com/blog/2012/11/26/realtime-stats-to-pay-attention-to-in-percona-xtradb-cluster-and-galera/)
- LogicMonitor: [MySQL Monitoring](https://www.logicmonitor.com/support/monitoring/applications-databases/mysql-monitoring)
  (package modules, `addCategory_OracleMySQL` and category `MySQL`, `jdbc.mysql.*`, `mysql.nossl`,
  `mysql.url_driver_properties`, privileges, tested versions),
  [JDBC data collection](https://www.logicmonitor.com/support/logicmodules/datasources/data-collection-methods/jdbc-data-collection)
  (bundled JDBC drivers)
- Export shapes and Collector contract: the `logicmonitor-modules` skill, distilled from the
  portal-verified Proxmox VE suite.
