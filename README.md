# MySQL Galera LogicModule suite

LogicMonitor monitoring for **Galera clusters**: MariaDB Galera Cluster, Percona XtraDB Cluster
and Galera Cluster for MySQL. It watches what LogicMonitor's own MySQL modules do not: quorum,
node state, cluster size, flow control, replication queues and write conflicts.

3 modules and a dashboard:

| Module | Type | Watches |
|---|---|---|
| `addCategory_MySQL_Galera` | PropertySource | Finds Galera nodes and adds the `MySQL_Galera` category, with cluster name, node name and provider version |
| `MySQL_Galera_Node` | DataSource | Primary Component, node state (Synced, Donor/Desynced, Joining, Joined), ready, connected, cluster size against the expected size |
| `MySQL_Galera_Replication` | DataSource | Flow control (% of time paused), receive and send queues, write-sets and bytes per second, certification failures, brute-force aborts |
| `MySQL Galera Cluster` | Dashboard | Node state table, alerts, flow control, queues, cluster size, throughput, conflicts |

That is 2 DataSources and 1 PropertySource. Both DataSources poll every minute, with one JDBC
connection per Galera port, and create one instance per port.

## Install

1. **A monitoring account.** On any one node (it replicates to the others):

   ```sql
   CREATE USER 'logicmonitor'@'<collector address>' IDENTIFIED BY '<password>';
   GRANT USAGE ON *.* TO 'logicmonitor'@'<collector address>';
   ```

   `USAGE` is all this suite needs. LogicMonitor's own MySQL modules may need more (see their
   documentation).

2. **Resource properties**, on each node or on the group holding them. These are LogicMonitor's
   own MySQL properties, so a node already monitored by LogicMonitor's MySQL package has them:

   | Property | |
   |---|---|
   | `jdbc.mysql.user` | the account above |
   | `jdbc.mysql.pass` | its password |
   | `jdbc.mysql.port` | optional: other ports, comma-separated; 3306 is always checked |
   | `galera.cluster.size` | optional but recommended: how many nodes the cluster should have. Turns on ExpectedClusterSize and MissingNodes, which raises an error when a node is missing |
   | `mysql.nossl` | optional: `true` connects without TLS (LogicMonitor's own property) |
   | `mysql.url_driver_properties` | optional: extra JDBC URL options, e.g. `sslMode=REQUIRED` |

3. **Import** `dist/*.json` (after `python build/build.py`) through *Modules > My Module Toolbox >
   Add > Import from file*, then `dist/dashboards/MySQL_Galera_Cluster.json` through *Dashboards >
   Add > From File*, and set its `defaultResourceGroup` token to the group holding the nodes.
   Release zips carry the built files.

The PropertySource runs on its own schedule; to apply the suite at once, run it from the resource
(*Resource > Properties > Refresh*). Nothing applies to a server that is not a Galera node.

**LogicMonitor's MySQL Replica Status module** (`Oracle_MySQL_ReplicaStatus`) and its heartbeat
modules watch MySQL's asynchronous replication, which a Galera node does not use. Unless a node
is also an asynchronous replica of another server, exclude them from the cluster, for example by
adding `&& !hasCategory("MySQL_Galera")` to their AppliesTo. Keep LogicMonitor's other MySQL
modules (connection status, InnoDB, databases) for general server health.

## Alerts

| Datapoint | Alerts when | Level |
|---|---|---|
| ClusterStatus | not Primary (Non-Primary: quorum lost; Disconnected) for 2 polls | critical |
| Ready | `wsrep_ready` OFF for 2 polls | critical |
| Connected | `wsrep_connected` OFF for 2 polls | critical |
| MissingNodes | fewer nodes than `galera.cluster.size`, for 2 polls | error |
| LocalState | not Synced for 5 polls (Joining, Joined, Donor/Desynced, Initialized) | warning |
| FlowControlPausedPercent | replication paused more than 10% of the time, 5 polls | warning |
| LocalRecvQueueAvg | average receive queue above 0.5, 5 polls | warning |
| LocalSendQueueAvg | average send queue above 1, 5 polls | warning |
| CertFailures | more than 5 certification failures per second, 5 polls | warning |

A node is Donor/Desynced on purpose during a state transfer or a backup run with `wsrep_desync`:
put planned backups in SDT. Each module's *Technical Notes* say what every datapoint means.

## Build and test

```sh
python build/build.py                                        # build dist/ and check it
docker compose -f tests/docker-compose.yml run --rm compile  # groovyc, Groovy 4 / JDK 17
docker compose -f tests/docker-compose.yml run --rm tests    # fixture harness
sh tests/live.sh                                             # a real 3-node MariaDB Galera cluster
```

`tests/live.sh` builds a three-node MariaDB 11.4 Galera cluster and a standalone server in
Docker, runs the built scripts against them over MySQL Connector/J, then partitions one node off
the network and heals it again.

What has been verified, and how: [docs/design.md](docs/design.md#4-verification). The design,
and how it refines the [original note](docs/original-design.rtf), is in the same file. In short:
build, lint, compile, harness and a live cluster pass; **a portal import and a run on a real
Collector have not been done yet.**

## License

MIT
