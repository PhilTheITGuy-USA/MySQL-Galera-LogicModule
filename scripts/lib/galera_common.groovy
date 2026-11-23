//!/lib-groovy/v4
// Shared preamble, prepended by build/build.py to every script in this suite. The Collector
// has no include mechanism, so each embedded script carries the closures it reaches.
//
// Connection: the Collector's bundled MySQL JDBC driver, instantiated directly and asked for
// a connection with Driver.connect(). DriverManager is avoided on purpose: it refuses a driver
// loaded by a class loader other than the caller's, which is the script's own on a Collector.

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

// The ports to probe: 3306 always, as LogicMonitor's MySQL modules do, then each entry of
// jdbc.mysql.port (a comma-separated list), in order, once each.
def galeraPorts = { ->
    def ports = [GALERA_DEFAULT_PORT]
    (galeraProp('jdbc.mysql.port') ?: '').split(',').each { raw ->
        def port = raw.trim()
        if (port ==~ /\d{1,5}/ && port.toInteger() in 1..65535 && !(port in ports)) { ports << port }
    }
    ports
}

// The JDBC URL for one port. mysql.nossl and mysql.url_driver_properties are LogicMonitor's
// own MySQL properties, honoured with the meaning LM documents for them.
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
// Throws on any connection or query failure; the caller decides what that means.
//
// wsrep_sync_wait is set to 0 for this session first. With causality checks on, a read on a
// node that has lost the Primary Component blocks and then fails (ERROR 1205), which is the
// state monitoring most needs to see. On a server without wsrep the SET fails, and is ignored.
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
// wsrep_on alone is not enough: a server built with wsrep but running with
// wsrep_provider=none reports wsrep_on and no cluster.
def galeraIsNode = { Map read ->
    def vars = read?.vars ?: [:]
    galeraText(vars, 'wsrep_on') == 'on' && !(galeraText(vars, 'wsrep_provider') in ['', 'none']) &&
        (read?.status ?: [:]).containsKey('wsrep_local_state_comment')
}

// A status value as a number, or null when absent or not numeric (rule: never print a string).
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

// Galera's own node-state numbering (wsrep_local_state): 1 Joining, 2 Donor/Desynced,
// 3 Joined, 4 Synced. Read from the comment, which every provider version spells the same way;
// anything else named (Initialized, Error) is 0. Falls back to the number when no comment.
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

// The server flavour, from version_comment / version: mariadb, percona or mysql.
def galeraFlavor = { Map vars ->
    def text = galeraText(vars, 'version_comment') + ' ' + galeraText(vars, 'version')
    text.contains('mariadb') ? 'mariadb' : (text.contains('percona') ? 'percona' : 'mysql')
}

// A positive whole number from a resource property, or null.
def galeraExpectedSize = { ->
    def raw = galeraProp('galera.cluster.size')
    (raw ==~ /\d+/ && raw.toInteger() > 0) ? raw.toInteger() : null
}

// One BatchScript line: <port>.<Datapoint>=<value>. A null value prints nothing (No Data).
// Values are BigDecimal (printed without exponent or trailing zeros) or whole numbers.
def galeraEmit = { String wildvalue, String datapoint, value ->
    if (value == null) { return }
    def text = value instanceof BigDecimal ? value.stripTrailingZeros().toPlainString() : value.toString()
    println "${wildvalue}.${datapoint}=${text}"
}

// Text for a discovery field or property: '#' removed (it separates AD fields), trimmed.
def galeraClean = { value -> value?.toString()?.replace('#', '')?.replaceAll(/[\r\n]+/, ' ')?.trim() }

// Active Discovery property values are URL-encoded, and a blank one is never printed.
def galeraAdProps = { Map props ->
    props.findAll { k, v -> v != null && !v.toString().trim().isEmpty() }
        .collect { k, v -> 'auto.' + k + '=' + URLEncoder.encode(v.toString().trim(), 'UTF-8') }.join('&')
}

// Run fn(port, read) over every Galera port. Returns [ports, galera: n Galera nodes,
// answered: n servers that answered, failed: [port: reason]]. A port that refuses the
// connection is a failure only when no port answered at all: 3306 is probed whether or not
// anything listens there.
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
