# MySQL Galera Cluster Monitoring for LogicMonitor: Build Guide

Version 1.0.1, 2026-11-23. Source and release: [github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule](https://github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule)

## Overview

Three LogicModules monitor a Galera cluster (MariaDB Galera Cluster, Percona XtraDB Cluster, Galera Cluster for MySQL) from each node's own `wsrep_%` status variables, over JDBC, with LogicMonitor's standard MySQL credentials. They watch what LogicMonitor's own MySQL modules do not: quorum, node state, cluster size, flow control, replication queues and write conflicts.

| Module | Type | Collects | Interval |
| --- | --- | --- | --- |
| `addCategory_MySQL_Galera` | PropertySource | Detects a Galera node; adds the category `MySQL_Galera` and `auto.galera.*` properties | PropertySource schedule |
| `MySQL_Galera_Node` | DataSource, BatchScript, multi-instance | Primary Component, node state, ready, connected, cluster size against the expected size | 1 minute |
| `MySQL_Galera_Replication` | DataSource, BatchScript, multi-instance | Flow control, receive and send queues, write-sets and bytes per second, certification failures | 1 minute |
| MySQL Galera Cluster | Dashboard | Node state table, alerts, flow control, queues, size, throughput, conflicts | n/a |

The suite applies itself. Setting `jdbc.mysql.user` and `jdbc.mysql.pass` on a node lets the PropertySource find Galera; both DataSources then apply on `hasCategory("MySQL_Galera")`. Each DataSource creates one instance per Galera port (normally just 3306) and opens one JDBC connection per port per poll.

**Two ways to deploy.** Import the ready-made module JSON from the [v1.0.1 release](https://github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule/releases/tag/v1.0.1), or build the modules by hand from this guide. Both produce the same modules; the scripts below are the ones in that release. Source, tests and design notes: [github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule](https://github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule).

**How this differs from the original design note** (one Script DataSource reading `wsrep_%`):

- Node state uses Galera's own numbering (1 Joining, 2 Donor/Desynced, 3 Joined, 4 Synced). The note's map put Donor at 3 and Joined at 2, so its thresholds made a Joined node critical.
- Cluster status distinguishes Primary (2), Non-Primary (1, quorum lost) and Disconnected (0).
- Missing values are not printed as 0. They read No Data, so an unknown never looks like a failure or a healthy reading.
- Flow control comes from the cumulative `wsrep_flow_control_paused_ns`. `wsrep_flow_control_paused` resets whenever any tool runs `FLUSH STATUS`.
- Cumulative counters (certification failures, aborts, write-sets, bytes) are rates per second, not gauges.
- Expected cluster size is a property (`galera.cluster.size`), not a hard-coded `< 3`.
- A PropertySource replaces the manual `mysql.galera = true` flag.
- The session sets `wsrep_sync_wait = 0` first, so a node that has lost quorum still answers instead of blocking.

## Prerequisites

**1. A monitoring account.** Create it on any one node; Galera replicates `CREATE USER` to the others. `USAGE` is the only privilege this suite needs (`SHOW GLOBAL STATUS` and `SHOW GLOBAL VARIABLES`).

```sql
CREATE USER 'logicmonitor'@'<collector address>' IDENTIFIED BY '<strong password>';
GRANT USAGE ON *.* TO 'logicmonitor'@'<collector address>';
```

If the same account also serves LogicMonitor's own MySQL modules, grant what they need too (for example `REPLICATION CLIENT`, or `SELECT` for table sizes), per [LogicMonitor's MySQL Monitoring page](https://www.logicmonitor.com/support/monitoring/applications-databases/mysql-monitoring).

**2. Resource properties**, set on each node or on the resource group that holds the cluster. The `jdbc.mysql.*` and `mysql.*` names are LogicMonitor's own MySQL properties, so a node already monitored by LogicMonitor's MySQL package has the credentials.

| Property | Required | Meaning |
| --- | --- | --- |
| `jdbc.mysql.user` | Yes | The monitoring account |
| `jdbc.mysql.pass` | Yes | Its password (masked by the portal) |
| `jdbc.mysql.port` | No | Extra ports, comma-separated (for example `3307,3308`). Port 3306 is always checked |
| `galera.cluster.size` | Recommended | How many nodes the cluster should have. Turns on ExpectedClusterSize and MissingNodes, which raises an error when a node is missing |
| `mysql.nossl` | No | `true` connects without TLS and allows public key retrieval |
| `mysql.url_driver_properties` | No | Extra JDBC URL options, for example `sslMode=REQUIRED` |

**3. Network.** The Collector must reach each node on its MySQL port. The Collector's bundled MySQL JDBC driver is used; nothing needs installing.

**4. LogicMonitor's MySQL Replica Status modules.** `Oracle_MySQL_ReplicaStatus` and the heartbeat lag modules watch MySQL's asynchronous replication, which Galera does not use. Unless a node is also an asynchronous replica of another server, exclude them from the cluster: add `&& !hasCategory("MySQL_Galera")` to their AppliesTo, and re-check it after LogicMonitor updates those modules. Keep LogicMonitor's other MySQL modules (connection status, InnoDB, databases) for general server health.

## Shared helper code

Every script in this suite starts with the same helper code, because a Collector has no way to include one script in another. **Each script field in the portal = this helper code, then the module's own script** from the sections below. Paste the helper code first, unchanged. (The release JSON already has it in place, with comments removed.)

What the helpers do:

| Helper | Purpose |
| --- | --- |
| `galeraProp` | Reads a resource property, trimmed; blank counts as unset |
| `galeraHasCredentials` | Both `jdbc.mysql.user` and `jdbc.mysql.pass` are set |
| `galeraPorts` | 3306, then each valid port in `jdbc.mysql.port`, once each |
| `galeraUrl` | Builds the JDBC URL with connect and socket timeouts, honouring `mysql.nossl` and `mysql.url_driver_properties` |
| `galeraDriver` | Loads the Collector's bundled driver: Connector/J 8+, the pre-8 class name, then MariaDB Connector/J. If none loads, the error names why the last one failed |
| `galeraFetch` | Checks credentials, connects, sets `wsrep_sync_wait = 0`, reads `wsrep%` status and six variables, closes. Throws on any failure |
| `galeraText` | A status or variable value as trimmed lower-case text |
| `galeraIsNode` | True only when `wsrep_on` is ON, a provider is loaded and a node state is reported |
| `galeraNum`, `galeraFlag` | A status value as a number, or ON/OFF as 1/0; anything else is null (never printed) |
| `galeraClusterStatus`, `galeraLocalState` | Map Galera's state words to the numbers in the datapoint tables |
| `galeraEmit` | Prints one BatchScript line, `<port>.<Datapoint>=<value>`; a null value prints nothing |
| `galeraClean` | Removes `#` and line breaks from text bound for discovery output or properties |
| `galeraEachNode` | Runs a block for every port that answers and is a Galera node; counts the rest |
| `galeraExit` | Ends a DataSource script: exit 2 with the reason when nothing usable answered, else exit 0 |

Why the driver is loaded directly: `groovy.sql.Sql` and `DriverManager` refuse a driver loaded by a different class loader than the script's, which is the case on a Collector. Calling the driver's own `connect()` avoids that check. The `galeraTestFetch` line near the top of `galeraFetch` is a test hook used by the repository's test harness; on a Collector that binding never exists, so it does nothing.

```groovy
//!/lib-groovy/v4
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

GALERA_CONNECT_TIMEOUT_MS = 10000
GALERA_SOCKET_TIMEOUT_MS = 20000
GALERA_QUERY_TIMEOUT_S = 15
GALERA_DEFAULT_PORT = '3306'
// Tried in order. Connector/J 8+ first, the pre-8 name, then MariaDB Connector/J.
GALERA_DRIVERS = ['com.mysql.cj.jdbc.Driver', 'com.mysql.jdbc.Driver', 'org.mariadb.jdbc.Driver']

// A resource property, trimmed; null when unset or blank.
def galeraProp = { String key ->
    def value = hostProps.get(key)
    (value == null || value.toString().trim().isEmpty()) ? null : value.toString().trim()
}

// LogicMonitor's MySQL credentials are both set (the password may legitimately be empty).
def galeraHasCredentials = { ->
    galeraProp('jdbc.mysql.user') != null && hostProps.get('jdbc.mysql.pass') != null
}

// The ports to probe: 3306 always, then each entry of jdbc.mysql.port, once each.
def galeraPorts = { ->
    def ports = [GALERA_DEFAULT_PORT]
    (galeraProp('jdbc.mysql.port') ?: '').split(',').each { raw ->
        def port = raw.trim()
        if (port ==~ /\d{1,5}/ && port.toInteger() in 1..65535 && !(port in ports)) { ports << port }
    }
    ports
}

// The JDBC URL for one port.
def galeraUrl = { String scheme, String port ->
    def host = galeraProp('system.hostname')
    if (host.contains(':') && !host.startsWith('[')) { host = '[' + host + ']' }
    def options = ["connectTimeout=${GALERA_CONNECT_TIMEOUT_MS}", "socketTimeout=${GALERA_SOCKET_TIMEOUT_MS}"]
    if ((galeraProp('mysql.nossl') ?: '').equalsIgnoreCase('true')) {
        options += ['useSSL=false', 'allowPublicKeyRetrieval=true']
    }
    def extra = galeraProp('mysql.url_driver_properties')
    if (extra) { options << extra.replaceFirst(/^[?&]+/, '') }
    "jdbc:${scheme}://${host}:${port}/?" + options.join('&')
}

// The first driver class the Collector can load, instantiated. Throws when there is none,
// naming why the last candidate failed, so the first Poll Now on a Collector says what to fix.
def galeraDriver = { ->
    def loaders = [Thread.currentThread().contextClassLoader, this.class.classLoader].findAll { it != null }
    Throwable last = null
    for (name in GALERA_DRIVERS) {
        for (loader in loaders) {
            try {
                return [name: name, driver: Class.forName(name, true, loader).getDeclaredConstructor().newInstance()]
            } catch (Throwable e) { last = e }
        }
    }
    throw new IllegalStateException('no MySQL JDBC driver on this Collector (tried ' + GALERA_DRIVERS.join(', ') +
        '; last error: ' + last + ')')
}

def galeraRows = { Statement statement, String sql ->
    def rows = [:]
    ResultSet rs = statement.executeQuery(sql)
    try {
        while (rs.next()) { rows[rs.getString(1).toLowerCase()] = rs.getString(2) }
    } finally { rs.close() }
    rows
}

// One read of a server: [status: wsrep_* global status, vars: the few variables used].
// Throws on any connection or query failure. wsrep_sync_wait = 0 keeps a node that has
// lost quorum answering instead of blocking (ERROR 1205).
// galeraTestFetch is the test harness's seam; on a Collector that binding never exists.
def galeraFetch = { String port ->
    if (!galeraHasCredentials()) { throw new IllegalStateException('jdbc.mysql.user and jdbc.mysql.pass must be set') }
    if (binding.hasVariable('galeraTestFetch')) { return binding.getVariable('galeraTestFetch').call(port) }
    def found = galeraDriver()
    def scheme = found.name.startsWith('org.mariadb') ? 'mariadb' : 'mysql'
    def props = new Properties()
    props.setProperty('user', galeraProp('jdbc.mysql.user'))
    props.setProperty('password', hostProps.get('jdbc.mysql.pass').toString())
    Connection conn = found.driver.connect(galeraUrl(scheme, port), props)
    if (conn == null) { throw new IllegalStateException(found.name + ' does not accept the URL for port ' + port) }
    try {
        Statement statement = conn.createStatement()
        try {
            statement.setQueryTimeout(GALERA_QUERY_TIMEOUT_S)
            try { statement.execute('SET SESSION wsrep_sync_wait = 0') } catch (Exception ignored) { }
            def status = galeraRows(statement, "SHOW GLOBAL STATUS LIKE 'wsrep%'")
            def vars = galeraRows(statement, "SHOW GLOBAL VARIABLES WHERE Variable_name IN " +
                "('wsrep_on', 'wsrep_provider', 'wsrep_cluster_name', 'wsrep_node_name', 'version', 'version_comment')")
            return [status: status, vars: vars]
        } finally { statement.close() }
    } finally { conn.close() }
}

// A status or variable value as trimmed lower-case text; '' when absent.
def galeraText = { Map values, String key -> (values[key] ?: '').toString().trim().toLowerCase() }

// A Galera node: wsrep enabled, a provider loaded, and the provider reporting its state.
def galeraIsNode = { Map read ->
    def vars = read?.vars ?: [:]
    galeraText(vars, 'wsrep_on') == 'on' && !(galeraText(vars, 'wsrep_provider') in ['', 'none']) &&
        (read?.status ?: [:]).containsKey('wsrep_local_state_comment')
}

// A status value as a number, or null when absent or not numeric.
def galeraNum = { Map status, String key ->
    def text = status[key]?.toString()?.trim()
    (text != null && text ==~ /-?\d+(\.\d+)?([eE][-+]?\d+)?/) ? new BigDecimal(text) : null
}

// ON / true / 1 -> 1, OFF / false / 0 -> 0, anything else -> null.
def galeraFlag = { Map status, String key ->
    [on: 1, 'true': 1, '1': 1, yes: 1, off: 0, 'false': 0, '0': 0, no: 0][galeraText(status, key)]
}

// wsrep_cluster_status: 2 Primary, 1 Non-Primary, 0 Disconnected; null for anything else.
def galeraClusterStatus = { Map status ->
    [primary: 2, 'non-primary': 1, disconnected: 0][galeraText(status, 'wsrep_cluster_status').replace('_', '-')]
}

// Galera's node-state numbering: 1 Joining, 2 Donor/Desynced, 3 Joined, 4 Synced;
// any other named state (Initialized, Error) is 0. Falls back to the number.
def galeraLocalState = { Map status ->
    def text = galeraText(status, 'wsrep_local_state_comment')
    if (text == 'synced') { return 4 }
    if (text == 'joined') { return 3 }
    if (text.startsWith('donor') || text.contains('desync')) { return 2 }
    if (text.startsWith('joining') || text.startsWith('waiting')) { return 1 }
    if (text) { return 0 }
    def number = galeraNum(status, 'wsrep_local_state')
    if (number == null) { return null }
    (number.intValue() in 1..4) ? number.intValue() : 0
}

// The server flavour: mariadb, percona or mysql.
def galeraFlavor = { Map vars ->
    def text = galeraText(vars, 'version_comment') + ' ' + galeraText(vars, 'version')
    text.contains('mariadb') ? 'mariadb' : (text.contains('percona') ? 'percona' : 'mysql')
}

// galera.cluster.size as a positive whole number, or null.
def galeraExpectedSize = { ->
    def raw = galeraProp('galera.cluster.size')
    (raw ==~ /\d+/ && raw.toInteger() > 0) ? raw.toInteger() : null
}

// One BatchScript line: <port>.<Datapoint>=<value>. A null value prints nothing (No Data).
def galeraEmit = { String wildvalue, String datapoint, value ->
    if (value == null) { return }
    def text = value instanceof BigDecimal ? value.stripTrailingZeros().toPlainString() : value.toString()
    println "${wildvalue}.${datapoint}=${text}"
}

// Text for a discovery field or property: '#' removed (it separates AD fields), trimmed.
def galeraClean = { value -> value?.toString()?.replace('#', '')?.replaceAll(/[\r\n]+/, ' ')?.trim() }

// Active Discovery property values are URL-encoded; a blank one is never printed.
def galeraAdProps = { Map props ->
    props.findAll { k, v -> v != null && !v.toString().trim().isEmpty() }
        .collect { k, v -> 'auto.' + k + '=' + URLEncoder.encode(v.toString().trim(), 'UTF-8') }.join('&')
}

// Run fn(port, read) over every Galera port.
// Returns [ports, galera: Galera nodes, answered: servers that answered, failed: [port: reason]].
def galeraEachNode = { Closure fn ->
    def result = [ports: galeraPorts(), galera: 0, answered: 0, failed: [:]]
    result.ports.each { port ->
        def read
        try {
            read = galeraFetch(port)
        } catch (Exception e) {
            result.failed[port] = (e.message ?: e.class.simpleName).toString()
            return
        }
        result.answered++
        if (galeraIsNode(read)) {
            result.galera++
            fn(port, read)
        }
    }
    result
}

// The end of a DataSource script: exit 2 with the reason on stderr when there was nothing to
// report (no server answered; for collection, no Galera node either), else note any failed
// ports on stderr and exit 0. Exiting 2 keeps existing instances and reads No Data, never zero.
def galeraExit = { Map result, boolean needGalera ->
    def failures = result.failed.collect { port, reason -> port + ' ' + reason }.join('; ')
    if (result.answered == 0) {
        System.err.println('No MySQL server answered on ' + result.ports.join(', ') + ': ' + failures)
        return 2
    }
    if (needGalera && result.galera == 0) {
        System.err.println('No Galera node answered on ' + result.ports.join(', '))
        return 2
    }
    if (failures) { System.err.println('Failed ports: ' + failures) }
    0
}
```

## Module 1: addCategory\_MySQL\_Galera (PropertySource)

This PropertySource marks a resource as a Galera node, which is what makes both DataSources apply. It is silent (no output, exit 0) on every server that is not a Galera node and on every error, so it can never put the category on the wrong server.

Create it under *Modules > My Module Toolbox > Add > PropertySource*:

| Field | Value |
| --- | --- |
| Name | `addCategory_MySQL_Galera` |
| Group | `MySQL Galera` |
| Description | Adds the MySQL\_Galera category to a MySQL, MariaDB or Percona XtraDB Cluster server that is a Galera cluster node. |
| Applies To | `jdbc.mysql.user && jdbc.mysql.pass` |
| Script type | Embedded Groovy |
| Script | Shared helper code, then the script below |

It prints `system.categories=MySQL_Galera` plus these properties, which the portal stores with the `auto.` prefix shown, read from the first Galera port:

| Property | From |
| --- | --- |
| `auto.galera.ports` | The Galera ports found, comma-separated |
| `auto.galera.cluster_name` | `wsrep_cluster_name` |
| `auto.galera.node_name` | `wsrep_node_name` |
| `auto.galera.provider_version` | `wsrep_provider_version` |
| `auto.galera.provider_vendor` | `wsrep_provider_vendor` |
| `auto.galera.server_flavor` | `mariadb`, `percona` or `mysql` |
| `auto.galera.server_version` | `version` |

A PropertySource can add a category but never remove one. If a server stops being a Galera node, remove `MySQL_Galera` from its `system.categories` by hand.

```groovy
// addCategory_MySQL_Galera -- paste after the shared helper code.
// Missing credentials make galeraFetch throw before any connection, so they end silently too.
try {
    def nodes = []
    galeraEachNode { port, read -> nodes << [port: port, read: read] }
    if (!nodes) { return 0 }
    def first = nodes[0].read
    def props = [
        'auto.galera.ports'           : nodes*.port.join(','),
        'auto.galera.cluster_name'    : first.vars['wsrep_cluster_name'],
        'auto.galera.node_name'       : first.vars['wsrep_node_name'],
        'auto.galera.provider_version': first.status['wsrep_provider_version'],
        'auto.galera.provider_vendor' : first.status['wsrep_provider_vendor'],
        'auto.galera.server_flavor'   : galeraFlavor(first.vars),
        'auto.galera.server_version'  : first.vars['version'],
    ]
    println 'system.categories=MySQL_Galera'
    props.each { key, value ->
        def text = galeraClean(value)
        if (text) { println "${key}=${text}" }
    }
    return 0
} catch (Throwable ignored) {
    return 0
}
```

Expected output on a Galera node (from the live test cluster):

```text
system.categories=MySQL_Galera
auto.galera.ports=3306
auto.galera.cluster_name=Labcluster
auto.galera.node_name=galera1
auto.galera.provider_version=26.4.27(rf032a460)
auto.galera.provider_vendor=Codership Oy <info@codership.com>
auto.galera.server_flavor=mariadb
auto.galera.server_version=11.4.13-MariaDB-ubu2404
```

## Module 2: MySQL\_Galera\_Node (DataSource)

This DataSource answers the first questions about a node: is it in the Primary Component, is it Synced, does it accept queries, and is the cluster the size it should be.

Create it under *Modules > My Module Toolbox > Add > DataSource*:

| Field | Value |
| --- | --- |
| Name | `MySQL_Galera_Node` |
| Displayed As | `MySQL Galera Node` |
| Group | `MySQL Galera` |
| Description | Galera cluster membership of this node: Primary Component, node state, whether it accepts queries, and cluster size against the expected size. |
| Applies To | `hasCategory("MySQL_Galera") && jdbc.mysql.user && jdbc.mysql.pass` |
| Collector | Script (BATCHSCRIPT), Embedded Groovy |
| Collect every | 1 minute |
| Multi-instance | Yes |
| Use Wildvalue as Unique Identifier | Yes (the wildvalue is the port) |
| Active Discovery | Script, Embedded Groovy; schedule 1 hour |
| Automatically Delete Instances | **Off**: a node that disappears is what this module alerts on |
| Instance grouping | None |

### Active Discovery script

Both DataSources use this same discovery. It creates one instance per port that holds a Galera node, named `Galera port 3306 (<cluster name>)`. If no server answers at all it exits 2 and prints nothing, which keeps existing instances.

```groovy
// Active Discovery (both DataSources) -- paste after the shared helper code.
def lines = []
def result = galeraEachNode { port, read ->
    def cluster = galeraClean(read.vars['wsrep_cluster_name'])
    def node = galeraClean(read.vars['wsrep_node_name'])
    def flavor = galeraFlavor(read.vars)
    def provider = galeraClean(read.status['wsrep_provider_version'])
    def name = 'Galera port ' + port + (cluster ? ' (' + cluster + ')' : '')
    def description = (node ? 'node ' + node + ', ' : '') + flavor + ' ' +
        (galeraClean(read.vars['version']) ?: '') + ', provider ' + (provider ?: 'unknown')
    def props = galeraAdProps([
        'galera.port'            : port,
        'galera.cluster_name'    : cluster,
        'galera.node_name'       : node,
        'galera.provider_version': provider,
        'galera.server_flavor'   : flavor,
    ])
    lines << "${port}##${name}##${description}####${props}"
}
// Discovery succeeds whenever any server answered, Galera or not: an empty answer from a
// standalone server is the truth, and instances are never auto-deleted anyway.
def exit = galeraExit(result, false)
if (exit == 0) { lines.each { println it } }
return exit
```

Expected output:

```text
3306##Galera port 3306 (Labcluster)##node galera1, mariadb 11.4.13-MariaDB-ubu2404, provider 26.4.27(rf032a460)####auto.galera.port=3306&auto.galera.cluster_name=Labcluster&auto.galera.node_name=galera1&auto.galera.provider_version=26.4.27%28rf032a460%29&auto.galera.server_flavor=mariadb
```

### Collection script

```groovy
// MySQL_Galera_Node collection -- paste after the shared helper code.
def expected = galeraExpectedSize()
def result = galeraEachNode { port, read ->
    def s = read.status
    galeraEmit(port, 'ClusterStatus', galeraClusterStatus(s))
    galeraEmit(port, 'LocalState', galeraLocalState(s))
    galeraEmit(port, 'Ready', galeraFlag(s, 'wsrep_ready'))
    galeraEmit(port, 'Connected', galeraFlag(s, 'wsrep_connected'))
    def size = galeraNum(s, 'wsrep_cluster_size')
    galeraEmit(port, 'ClusterSize', size)
    // Only with galera.cluster.size set: without it there is nothing to compare against.
    if (expected != null && size != null) {
        galeraEmit(port, 'ExpectedClusterSize', expected)
        galeraEmit(port, 'MissingNodes', Math.max(expected - size.intValue(), 0))
    }
    // Galera 4 (26.4+) and late Galera 3 only.
    galeraEmit(port, 'ClusterWeight', galeraNum(s, 'wsrep_cluster_weight'))
    galeraEmit(port, 'DesyncCount', galeraNum(s, 'wsrep_desync_count'))
    galeraEmit(port, 'OpenTransactions', galeraNum(s, 'wsrep_open_transactions'))
}
return galeraExit(result, true)
```

Expected output on a healthy node with `galera.cluster.size = 3`:

```text
3306.ClusterStatus=2
3306.LocalState=4
3306.Ready=1
3306.Connected=1
3306.ClusterSize=3
3306.ExpectedClusterSize=3
3306.MissingNodes=0
3306.ClusterWeight=3
3306.DesyncCount=0
3306.OpenTransactions=0
```

### Datapoints

Every datapoint is a Normal datapoint: source *Content the script writes to the standard output*, interpreted as *Multi-line key-value pairs*, with the key `##WILDVALUE##.<Name>` (for example `##WILDVALUE##.ClusterStatus`). Without the `##WILDVALUE##.` prefix a BatchScript datapoint never records data. All are gauges; *No Data* does not alert. Threshold syntax is LogicMonitor's `<operator> <warning> <error> <critical>`. "Polls" counts consecutive collections before alerting (Alert Trigger Interval = polls minus 1).

| Datapoint | Source | Values | Alert threshold | Polls |
| --- | --- | --- | --- | --- |
| ClusterStatus | `wsrep_cluster_status` | 2 Primary, 1 Non-Primary, 0 Disconnected | `< 2 2 2` (critical) | 2 |
| LocalState | `wsrep_local_state_comment` | 4 Synced, 3 Joined, 2 Donor/Desynced, 1 Joining, 0 Initialized or other | `< 4` (warning) | 5 |
| Ready | `wsrep_ready` | 1 Ready, 0 Not ready | `< 1 1 1` (critical) | 2 |
| Connected | `wsrep_connected` | 1 Connected, 0 Disconnected | `< 1 1 1` (critical) | 2 |
| ClusterSize | `wsrep_cluster_size` | nodes in this node's component | none |  |
| ExpectedClusterSize | property `galera.cluster.size` | nodes; only when the property is set | none |  |
| MissingNodes | expected minus actual, never below 0 | nodes; only when the property is set | `> 0 0` (error) | 2 |
| ClusterWeight | `wsrep_cluster_weight` | total pc.weight; Galera 4 only | none |  |
| DesyncCount | `wsrep_desync_count` | operations holding the node desynced; Galera 4 only | none |  |
| OpenTransactions | `wsrep_open_transactions` | Galera 4 only | none |  |

Give ClusterStatus, LocalState, Ready and Connected *Status Display Names* matching the Values column (operator EQ), so alerts read as words. Suggested alert messages:

- **ClusterStatus**: Galera node ##HOST## (##INSTANCE##) is ##ALERTVALUEDISPLAYSTATUSNAME## (##VALUE##), not part of the Primary Component. It refuses reads and writes until it rejoins a partition with quorum. If more than one node reports this, the cluster has partitioned or lost quorum: check the network between nodes and wsrep\_cluster\_size on each; a cluster with no Primary Component needs a manual bootstrap (pc.bootstrap=YES on the most advanced node).
- **LocalState**: Galera node ##HOST## (##INSTANCE##) has been ##ALERTVALUEDISPLAYSTATUSNAME## (##VALUE##) instead of Synced for five minutes. Joining and Joined mean it is catching up; Donor/Desynced means it is serving a state transfer or was desynced for a backup; Initialized means it is not in a cluster.
- **Ready**: Galera node ##HOST## (##INSTANCE##) is ##ALERTVALUEDISPLAYSTATUSNAME## (wsrep\_ready OFF): it rejects almost every query from applications.
- **Connected**: Galera node ##HOST## (##INSTANCE##) is ##ALERTVALUEDISPLAYSTATUSNAME## from the cluster (wsrep\_connected OFF). Check the network between the nodes (port 4567) and wsrep\_cluster\_address.
- **MissingNodes**: The Galera cluster seen from ##HOST## (##INSTANCE##) is ##VALUE## node(s) short of galera.cluster.size. A cluster that loses half its nodes or more loses quorum.

A node is Donor/Desynced on purpose during a state transfer or a backup run with `wsrep_desync`: put planned backups in SDT.

### Graphs

| Graph | Lines | Y axis |
| --- | --- | --- |
| Node State | LocalState, ClusterStatus | 0 to 4 |
| Availability | Ready, Connected | 0 to 1 |
| Cluster Size | ClusterSize, ExpectedClusterSize, MissingNodes, ClusterWeight | nodes |
| Desync and Transactions | DesyncCount, OpenTransactions | count |

## Module 3: MySQL\_Galera\_Replication (DataSource)

This DataSource shows how hard replication is working and whether this node is holding the cluster back: flow control, queues, throughput and write conflicts.

Create it exactly like Module 2, with these differences:

| Field | Value |
| --- | --- |
| Name | `MySQL_Galera_Replication` |
| Displayed As | `MySQL Galera Replication` |
| Description | Galera replication health of this node: flow control, receive and send queues, write-set throughput and certification conflicts. |
| Active Discovery script | The same script as Module 2 |
| Collection script | Below |

Everything else is the same: Applies To, BatchScript every minute, multi-instance, wildvalue as unique identifier, discovery every hour, automatic deletion off.

### Collection script

Cumulative counters are printed raw, and their datapoints are *derive* (LogicMonitor turns them into per-second rates). `wsrep_flow_control_paused_ns` is printed divided by 10^7, so its per-second rate is the percentage of time replication was paused during the poll.

```groovy
// MySQL_Galera_Replication collection -- paste after the shared helper code.
def result = galeraEachNode { port, read ->
    def s = read.status
    def pausedNs = galeraNum(s, 'wsrep_flow_control_paused_ns')
    galeraEmit(port, 'FlowControlPausedPercent', pausedNs == null ? null : pausedNs.divide(10000000G))
    def active = galeraFlag(s, 'wsrep_flow_control_active')
    galeraEmit(port, 'FlowControlActive', active != null ? active : galeraFlag(s, 'wsrep_flow_control_status'))
    galeraEmit(port, 'LocalRecvQueue', galeraNum(s, 'wsrep_local_recv_queue'))
    galeraEmit(port, 'LocalRecvQueueAvg', galeraNum(s, 'wsrep_local_recv_queue_avg'))
    galeraEmit(port, 'LocalSendQueue', galeraNum(s, 'wsrep_local_send_queue'))
    galeraEmit(port, 'LocalSendQueueAvg', galeraNum(s, 'wsrep_local_send_queue_avg'))
    galeraEmit(port, 'CertDepsDistance', galeraNum(s, 'wsrep_cert_deps_distance'))
    galeraEmit(port, 'TransactionsReplicated', galeraNum(s, 'wsrep_replicated'))
    galeraEmit(port, 'BytesReplicated', galeraNum(s, 'wsrep_replicated_bytes'))
    galeraEmit(port, 'TransactionsReceived', galeraNum(s, 'wsrep_received'))
    galeraEmit(port, 'BytesReceived', galeraNum(s, 'wsrep_received_bytes'))
    galeraEmit(port, 'LocalCommits', galeraNum(s, 'wsrep_local_commits'))
    galeraEmit(port, 'CertFailures', galeraNum(s, 'wsrep_local_cert_failures'))
    galeraEmit(port, 'BruteForceAborts', galeraNum(s, 'wsrep_local_bf_aborts'))
}
return galeraExit(result, true)
```

Expected output (from the live test cluster after a write load; counter values grow over time):

```text
3306.FlowControlPausedPercent=0
3306.FlowControlActive=0
3306.LocalRecvQueue=0
3306.LocalRecvQueueAvg=0.0909091
3306.LocalSendQueue=0
3306.LocalSendQueueAvg=0.00478469
3306.CertDepsDistance=51.7864
3306.TransactionsReplicated=206
3306.BytesReplicated=62264
3306.TransactionsReceived=11
3306.BytesReceived=876
3306.LocalCommits=200
3306.CertFailures=0
3306.BruteForceAborts=0
```

### Datapoints

Same datapoint settings as Module 2 (key `##WILDVALUE##.<Name>`, multi-line key-value pairs). Derive datapoints need **Min value 0**, so a counter reset after a server restart is dropped instead of graphed as a negative spike.

| Datapoint | Source | Type | Unit | Alert threshold | Polls |
| --- | --- | --- | --- | --- | --- |
| FlowControlPausedPercent | `wsrep_flow_control_paused_ns` / 10^7 | derive, min 0, max 100 | % of time paused | `> 10` (warning) | 5 |
| FlowControlActive | `wsrep_flow_control_active`, else `wsrep_flow_control_status` | gauge | 1 Paused, 0 Not paused | none |  |
| LocalRecvQueue | `wsrep_local_recv_queue` | gauge | write-sets now | none |  |
| LocalRecvQueueAvg | `wsrep_local_recv_queue_avg` | gauge | write-sets, average | `> 0.5` (warning) | 5 |
| LocalSendQueue | `wsrep_local_send_queue` | gauge | write-sets now | none |  |
| LocalSendQueueAvg | `wsrep_local_send_queue_avg` | gauge | write-sets, average | `> 1` (warning) | 5 |
| CertDepsDistance | `wsrep_cert_deps_distance` | gauge | write-sets | none |  |
| TransactionsReplicated | `wsrep_replicated` | derive, min 0 | per second | none |  |
| BytesReplicated | `wsrep_replicated_bytes` | derive, min 0 | bytes per second | none |  |
| TransactionsReceived | `wsrep_received` | derive, min 0 | per second | none |  |
| BytesReceived | `wsrep_received_bytes` | derive, min 0 | bytes per second | none |  |
| LocalCommits | `wsrep_local_commits` | derive, min 0 | per second | none |  |
| CertFailures | `wsrep_local_cert_failures` | derive, min 0 | per second | `> 5` (warning) | 5 |
| BruteForceAborts | `wsrep_local_bf_aborts` | derive, min 0 | per second | none |  |

The two queue averages are the server's own averages since the previous status query. On a node polled only by this module that is the one-minute poll; another tool querying the same server shortens the window. `wsrep_flow_control_sent` and `_recv` are deliberately left out, because Galera providers disagree on whether they reset at each status query.

Suggested alert messages:

- **FlowControlPausedPercent**: Replication on the Galera cluster was paused by flow control ##VALUE##% of the time, seen from ##HOST## (##INSTANCE##), for five minutes. While paused, no node commits writes. The node holding the cluster back has the largest receive queue: look there for a slow disk, CPU saturation, long transactions or too few applier threads.
- **LocalRecvQueueAvg**: Galera node ##HOST## (##INSTANCE##) has averaged ##VALUE## write-sets waiting to be applied for five minutes. It applies replicated writes more slowly than the cluster produces them and will soon throttle the cluster through flow control.
- **LocalSendQueueAvg**: Galera node ##HOST## (##INSTANCE##) has averaged ##VALUE## write-sets waiting to be sent for five minutes, which usually means network throughput or latency between the nodes.
- **CertFailures**: Galera node ##HOST## (##INSTANCE##) has had ##VALUE## transactions per second fail certification for five minutes: applications are writing the same rows on more than one node at once. Route conflicting writes to a single node.

### Graphs

| Graph | Lines | Y axis |
| --- | --- | --- |
| Flow Control | FlowControlPausedPercent | 0 to 100 % |
| Flow Control State | FlowControlActive | 0 to 1 |
| Queues | LocalRecvQueue, LocalRecvQueueAvg, LocalSendQueue, LocalSendQueueAvg | write-sets |
| Write-Sets | TransactionsReplicated, TransactionsReceived, LocalCommits | per second |
| Replication Throughput | BytesReplicated, BytesReceived | bytes per second (base 1024) |
| Conflicts | CertFailures, BruteForceAborts | per second |
| Parallelism | CertDepsDistance | write-sets |

## Dashboard, build order and validation

### Dashboard

Import `MySQL_Galera_Cluster.json` from the release through *Dashboards > Add > From File*, after the two DataSources exist (it refers to them by `MySQL Galera Node (MySQL_Galera_Node)` and `MySQL Galera Replication (MySQL_Galera_Replication)`, so their names must match exactly). Then set its `defaultResourceGroup` token to the group holding the cluster. It has 9 widgets; count them after import, because the portal drops a widget it cannot resolve without saying so.

| Widget | Shows |
| --- | --- |
| Galera Alerts by Node | Alert status per node |
| Node State | Table: ClusterStatus, LocalState, Ready, Connected, ClusterSize, MissingNodes per node |
| Flow Control Paused | FlowControlPausedPercent per node |
| Receive Queue (avg) | LocalRecvQueueAvg per node |
| Cluster Size | ClusterSize per node |
| Write-Sets Replicated | TransactionsReplicated per node |
| Certification Failures | CertFailures per node |
| Send Queue (avg) | LocalSendQueueAvg per node |
| Galera Alerts | Open alerts from both DataSources |

### Build and validate, in this order

These scripts were tested against a real MariaDB Galera cluster, but not yet on a LogicMonitor Collector. Steps 3 and 6 are the checks that prove them in your portal.

1. Create the monitoring account and set the resource properties (Prerequisites) on one node first.
2. Create `addCategory_MySQL_Galera`. Paste the shared helper code, then its script.
3. In the PropertySource's **Test Script**, pick that node. The output should match the expected output in Module 1. Empty output means the node was not recognised: see Troubleshooting.
4. Save, then refresh the node's properties. Confirm `MySQL_Galera` appears in `system.categories`.
5. Create `MySQL_Galera_Node`. Paste the helper code + discovery script into Active Discovery, and the helper code + collection script into Collector Attributes. Add the datapoints, Status Display Names and graphs.
6. **Test** both scripts against the node, then save. Open the instance and run **Poll Now**. Every datapoint should show a value, and *Raw Data* should start filling within two minutes.
7. Create `MySQL_Galera_Replication` the same way (same discovery script) and repeat step 6.
8. Import the dashboard and count its 9 widgets.
9. Roll out the properties to the remaining nodes or the cluster's resource group, and set `galera.cluster.size`.
10. Optional: exclude `Oracle_MySQL_ReplicaStatus` and the heartbeat modules from the cluster (Prerequisites, step 4).

**One check worth making early:** in step 6, if Poll Now reports `no MySQL JDBC driver on this Collector`, the Collector does not expose its bundled driver under any of the three class names the helper tries. Ask LogicMonitor support which JDBC driver class the Collector ships, and add it to `GALERA_DRIVERS` in the helper code.

## Troubleshooting and limitations

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| PropertySource Test Script prints nothing | Credentials missing or rejected; server unreachable; not a Galera node (`wsrep_on` OFF or `wsrep_provider` none) | Run the DataSource discovery Test Script on the same node: its error text names the cause |
| Discovery exits 2, `Access denied` | Wrong `jdbc.mysql.pass`, or the account's host part does not match the Collector's address | Fix the password or `CREATE USER ...@'<collector address>'` |
| Discovery exits 2, `Connection refused` or timeout | Firewall, wrong port, MySQL bound to localhost | Open the port from the Collector; set `jdbc.mysql.port` |
| `no MySQL JDBC driver on this Collector` | The Collector's driver class has a name the helper does not try; the message's last error says why the last candidate failed | Add the class name to `GALERA_DRIVERS` |
| SSL or `Public Key Retrieval is not allowed` error | Server without TLS, account using `caching_sha2_password` | Set `mysql.nossl = true`, or configure TLS and use `mysql.url_driver_properties` |
| Instances discovered, every datapoint No Data | Datapoint key missing `##WILDVALUE##.` | Set each key to `##WILDVALUE##.<Name>` |
| ExpectedClusterSize and MissingNodes No Data | `galera.cluster.size` not set, or not a positive whole number | Set it on the node or its group |
| ClusterWeight, DesyncCount, OpenTransactions or FlowControl datapoints No Data | Galera 3 provider, which lacks those variables | Expected; upgrade to Galera 4 for them |
| LocalState warning during backups | Backup tool desyncs the node (`wsrep_desync`) | Put planned backups in SDT |
| Rate datapoints spike or go blank after a restart | Server counters reset to 0 | Expected; min value 0 on derive datapoints drops the negative step |

**Limits of this design**

- Each node is judged on what it reports about itself. There is no cross-node check that all nodes share one `wsrep_cluster_state_uuid` and `wsrep_cluster_conf_id`; that would need a separate module.
- A node whose MySQL is down reads No Data in these modules. LogicMonitor's MySQL Connection Status module is what alerts on that.
- Removing `galera.cluster.size` while a MissingNodes alert is open leaves that alert open until the instance is deleted.
- The PropertySource cannot remove the `MySQL_Galera` category from a server that stops being a Galera node.

**What has been verified** (as of the date at the top)

| Layer | Result |
| --- | --- |
| Build and lint checks of the module definitions (lint: 0 errors, 0 warnings) | Pass |
| Groovy 4 / JDK 17 compile (the Collector's runtime) | Pass |
| Test harness, 58 checks over recorded MariaDB, Percona XtraDB Cluster, Galera 3, standalone MySQL and no-provider server shapes | Pass |
| Live: three-node MariaDB 11.4.13 / Galera 26.4.27 cluster through MySQL Connector/J 9.1.0: healthy, write load, node desynced, node partitioned off the network (Non-Primary; majority stayed Primary), node rejoined | Pass |
| Import into a LogicMonitor portal | Not yet done |
| Run on a LogicMonitor Collector with its bundled driver | Not yet done |
| Live Percona XtraDB Cluster, Galera 3, TLS-only accounts, several Galera ports on one host | Not yet done (recorded shapes only, or untested) |

**Sources**

- [LogicMonitor: MySQL Monitoring](https://www.logicmonitor.com/support/monitoring/applications-databases/mysql-monitoring)
- [LogicMonitor: JDBC data collection](https://www.logicmonitor.com/support/logicmodules/datasources/data-collection-methods/jdbc-data-collection)
- [MariaDB: Galera Cluster status variables](https://mariadb.com/docs/galera-cluster/reference/galera-cluster-status-variables)
- [MariaDB: Monitoring MariaDB Galera Cluster](https://mariadb.com/docs/galera-cluster/high-availability/monitoring-mariadb-galera-cluster)
- [MariaDB: Flow control in Galera Cluster](https://mariadb.com/docs/galera-cluster/galera-management/performance-tuning/flow-control-in-galera-cluster)
- [Galera Cluster: Using status variables](https://galeracluster.com/library/documentation/monitoring-cluster.html)
- [Source repository and release](https://github.com/PhilTheITGuy-USA/MySQL-Galera-LogicModule)
