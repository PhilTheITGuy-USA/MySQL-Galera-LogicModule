// Live test: the assembled scripts in dist/scripts/ against a real three-node MariaDB Galera
// cluster (galera1..3) and a standalone MariaDB server, over real JDBC with MySQL
// Connector/J -- the driver a Collector bundles. No test seam: galeraTestFetch is never set.
//
// Run: docker compose -f tests/docker-compose.yml --profile live run --rm live
// Then: docker compose -f tests/docker-compose.yml --profile live down -v

@GrabConfig(systemClassLoader = true)
@Grab('com.mysql:mysql-connector-j:9.1.0')
import java.sql.DriverManager

def root = new File(System.getenv('GALERA_MODULE_ROOT') ?: '.')
def dist = new File(root, 'dist')

class Props {
    private final Map values = [:]
    Props(Map initial) { values.putAll(initial) }
    def get(String key) { values[key] }
    void set(String key, value) { values[key] = value }
}

def failures = 0
def note = { String name, boolean ok, String detail = '' ->
    println((ok ? 'PASS    ' : 'FAILED  ') + name + (ok || !detail ? '' : '\n        ' + detail.replace('\n', '\n        ')))
    if (!ok) { failures++ }
}

def creds = ['jdbc.mysql.user': 'logicmonitor', 'jdbc.mysql.pass': 'monitorpw']
def run = { String script, String host, Map props = [:] ->
    def out = new StringWriter()
    def err = new ByteArrayOutputStream()
    def binding = new Binding()
    binding.setVariable('hostProps', new Props(['system.hostname': host] + creds + props))
    binding.setVariable('out', new PrintWriter(out, true))
    def savedErr = System.err
    System.setErr(new PrintStream(err, true))
    def exit
    try {
        exit = new GroovyShell(binding).evaluate(new File(dist, 'scripts/' + script))
    } finally {
        System.setErr(savedErr)
    }
    [exit: exit, out: out.toString().readLines().findAll { it.trim() }, err: err.toString()]
}
def values = { Map result ->
    result.out.findAll { it.startsWith('3306.') }
        .collectEntries { def kv = it.substring(5).split('=', 2); [(kv[0]): kv[1]] }
}
def node = { String host, Map props = [:] -> run('MySQL_Galera_Node.collect.groovy', host, props) }
def repl = { String host -> run('MySQL_Galera_Replication.collect.groovy', host) }

def admin = { String host, String sql ->
    def conn = DriverManager.getConnection("jdbc:mysql://${host}:3306/?useSSL=false&allowPublicKeyRetrieval=true", 'admin', 'adminpw')
    try { conn.createStatement().execute(sql) } finally { conn.close() }
}
def waitFor = { String what, int seconds, Closure<Boolean> ok ->
    def deadline = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < deadline) {
        if (ok()) { return true }
        sleep(1000)
    }
    println("        (gave up waiting for ${what} after ${seconds}s)")
    false
}

def phase = System.getenv('LIVE_PHASE') ?: 'main'
println("live phase: ${phase}")

// --- main: a healthy cluster ------------------------------------------------------------------
if (phase == 'main') {
// The accounts are created by galera1 just after it bootstraps; wait for them on every node.
['galera1', 'galera2', 'galera3'].each { host ->
    waitFor("the monitoring account on ${host}", 60) { node(host).exit == 0 }
}

// --- PropertySource ---------------------------------------------------------------------------
def r = run('addCategory_MySQL_Galera.groovy', 'galera1')
note('live ps: Galera node gets the category', r.exit == 0 && r.out.contains('system.categories=MySQL_Galera'), r.out.join('\n') + r.err)
note('live ps: cluster and node names', r.out.contains('auto.galera.cluster_name=Labcluster') && r.out.contains('auto.galera.node_name=galera1') &&
    r.out.contains('auto.galera.server_flavor=mariadb') && r.out.any { it.startsWith('auto.galera.provider_version=26.') }, r.out.join('\n'))
println('        ' + r.out.join('\n        '))
r = run('addCategory_MySQL_Galera.groovy', 'standalone')
note('live ps: standalone MariaDB is silent', r.exit == 0 && r.out.isEmpty(), r.out.join('\n'))
r = run('addCategory_MySQL_Galera.groovy', 'galera1', ['jdbc.mysql.pass': 'wrong'])
note('live ps: wrong password is silent', r.exit == 0 && r.out.isEmpty(), r.out.join('\n'))
r = run('addCategory_MySQL_Galera.groovy', 'galera1', ['mysql.nossl': 'true'])
note('live ps: mysql.nossl=true connects', r.out.contains('system.categories=MySQL_Galera'), r.out.join('\n'))

// --- Active Discovery -------------------------------------------------------------------------
['galera1', 'galera2', 'galera3'].each { host ->
    r = run('MySQL_Galera_Node.ad.groovy', host)
    note("live ad: ${host} is one instance on 3306", r.exit == 0 && r.out.size() == 1 && r.out[0].startsWith('3306##Galera port 3306 (Labcluster)##node ' + host),
        r.out.join('\n') + r.err)
}
println('        ' + run('MySQL_Galera_Node.ad.groovy', 'galera1').out[0])
r = run('MySQL_Galera_Node.ad.groovy', 'standalone')
note('live ad: standalone discovers nothing, exit 0', r.exit == 0 && r.out.isEmpty(), r.out.join('\n') + r.err)
r = run('MySQL_Galera_Node.ad.groovy', 'galera1', ['jdbc.mysql.port': '3307'])
note('live ad: an extra port with nothing on it is skipped', r.exit == 0 && r.out.size() == 1, r.err)

// --- Node, healthy ------------------------------------------------------------------------------
['galera1', 'galera2', 'galera3'].each { host ->
    def v = values(node(host, ['galera.cluster.size': '3']))
    note("live node: ${host} Primary, Synced, ready, 3 of 3", v.ClusterStatus == '2' && v.LocalState == '4' && v.Ready == '1' &&
        v.Connected == '1' && v.ClusterSize == '3' && v.MissingNodes == '0' && v.ExpectedClusterSize == '3', v.toString())
}
r = node('galera1')
println('        ' + r.out.join('\n        '))
def v1 = values(r)
note('live node: Galera 4 datapoints present on MariaDB 11.4', ['ClusterWeight', 'DesyncCount', 'OpenTransactions'].every { v1.containsKey(it) }, v1.toString())
r = node('galera1', ['jdbc.mysql.pass': 'wrong'])
note('live node: wrong password exits 2, prints nothing', r.exit == 2 && r.out.isEmpty() && r.err.contains('Access denied'), r.err)
r = node('standalone')
note('live node: standalone exits 2', r.exit == 2 && r.out.isEmpty(), r.err)

// --- Replication: a write load ------------------------------------------------------------------
def before1 = values(repl('galera1'))
def before2 = values(repl('galera2'))
note('live repl: every datapoint printed on MariaDB 11.4', before1.keySet().size() == 14, before1.keySet().toString())
admin('galera1', 'CREATE DATABASE IF NOT EXISTS lmtest')
admin('galera1', 'CREATE TABLE IF NOT EXISTS lmtest.t (id INT AUTO_INCREMENT PRIMARY KEY, v VARCHAR(64))')
(1..200).each { admin('galera1', "INSERT INTO lmtest.t (v) VALUES ('row ${it}')") }
def after1 = values(repl('galera1'))
def after2 = values(repl('galera2'))
println('        galera1 ' + after1)
note('live repl: galera1 sent the write-sets', after1.TransactionsReplicated.toBigDecimal() >= before1.TransactionsReplicated.toBigDecimal() + 200 &&
    after1.BytesReplicated.toBigDecimal() > before1.BytesReplicated.toBigDecimal() && after1.LocalCommits.toBigDecimal() >= before1.LocalCommits.toBigDecimal() + 200,
    "${before1} -> ${after1}")
note('live repl: galera2 received them', after2.TransactionsReceived.toBigDecimal() >= before2.TransactionsReceived.toBigDecimal() + 200 &&
    after2.BytesReceived.toBigDecimal() > before2.BytesReceived.toBigDecimal(), "${before2} -> ${after2}")
note('live repl: flow-control figures are numbers', after1.FlowControlPausedPercent ==~ /\d+(\.\d+)?/ && after1.FlowControlActive in ['0', '1'], after1.toString())

// --- Desync: Donor/Desynced -------------------------------------------------------------------
admin('galera2', 'SET GLOBAL wsrep_desync = ON')
def desynced = waitFor('galera2 desynced', 30) { values(node('galera2')).LocalState == '2' }
def vd = values(node('galera2'))
note('live node: wsrep_desync reads Donor/Desynced (2), DesyncCount 1', desynced && vd.DesyncCount == '1' && vd.ClusterStatus == '2', vd.toString())
admin('galera2', 'SET GLOBAL wsrep_desync = OFF')
note('live node: galera2 back to Synced', waitFor('galera2 synced', 60) { values(node('galera2')).LocalState == '4' })

}

// --- isolated: live.sh has detached galera3 from the cluster network ---------------------------
if (phase == 'isolated') {
    def isolated = waitFor('galera3 non-Primary', 90) { values(node('galera3')).ClusterStatus == '1' }
    def r = node('galera3', ['galera.cluster.size': '3'])
    def vi = values(r)
    println('        galera3 isolated ' + r.out.join(' '))
    note('live node: partitioned node answers and reads Non-Primary (1), not ready', isolated && r.exit == 0 && vi.Ready == '0' &&
        vi.ClusterSize == '1' && vi.MissingNodes == '2', vi.toString() + r.err)
    note('live node: partitioned node local state is not Synced', vi.LocalState != null && vi.LocalState != '4', vi.toString())
    def rr = values(repl('galera3'))
    note('live repl: partitioned node still reports replication figures', rr.size() >= 10, rr.toString())
    def shrunk = waitFor('galera1 sees two nodes', 60) { values(node('galera1')).ClusterSize == '2' }
    def vs = values(node('galera1', ['galera.cluster.size': '3']))
    println('        galera1 majority ' + vs)
    note('live node: the majority stays Primary with one node missing', shrunk && vs.ClusterStatus == '2' && vs.MissingNodes == '1' && vs.Ready == '1', vs.toString())
    r = run('MySQL_Galera_Node.ad.groovy', 'galera3')
    note('live ad: a partitioned node is still discovered', r.exit == 0 && r.out.size() == 1, r.out.join('\n') + r.err)
    r = run('addCategory_MySQL_Galera.groovy', 'galera3')
    note('live ps: a partitioned node keeps its category', r.out.contains('system.categories=MySQL_Galera'), r.out.join('\n'))
}

// --- rejoined: live.sh has reattached galera3 ---------------------------------------------------
if (phase == 'rejoined') {
    note('live node: galera3 rejoins, Primary and Synced', waitFor('galera3 rejoined', 180) {
        def v = values(node('galera3')); v.ClusterStatus == '2' && v.LocalState == '4' && v.ClusterSize == '3' })
    def vr = values(node('galera1', ['galera.cluster.size': '3']))
    note('live node: the cluster is whole again', vr.ClusterSize == '3' && vr.MissingNodes == '0', vr.toString())
}

println(failures ? "\n${failures} FAILED" : '\nALL PASSED')
System.exit(failures ? 1 : 0)
