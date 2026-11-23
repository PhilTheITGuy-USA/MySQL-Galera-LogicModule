// Runs the assembled scripts in dist/scripts/ against recorded server reads (tests/fixtures/)
// and asserts the output is what LogicMonitor parses. The scripts' one seam is the
// galeraTestFetch binding variable: when set, galeraFetch calls it instead of opening a JDBC
// connection. Everything else -- port list, detection, mapping, output -- is the shipped code.
//
// Run: docker compose -f tests/docker-compose.yml run --rm tests
// Each check prints PASS or FAILED with its name; the exit status is 1 on any failure.

import groovy.json.JsonSlurper

def root = new File(System.getenv('GALERA_MODULE_ROOT') ?: '.')
def dist = new File(root, 'dist')
def fixtures = new File(root, 'tests/fixtures')
def slurper = new JsonSlurper()

def t = new GaleraTestSupport(root)
def note = t.&note
def values = GaleraTestSupport.&values

def fixture = { String name -> slurper.parse(new File(fixtures, name + '.json')) }

// Run one assembled script. reads: port -> fixture map, or an Exception to throw for that port;
// a port with no entry refuses the connection.
def run = { String script, Map props, Map reads ->
    t.run(script, ['system.hostname': 'node-a.example.com', 'jdbc.mysql.user': 'logicmonitor', 'jdbc.mysql.pass': 'x'] + props) { String port ->
        def read = reads[port]
        if (read == null) { throw new java.net.ConnectException('Connection refused') }
        if (read instanceof Exception) { throw read }
        [status: read.status, vars: read.vars]
    }
}

def module = { String name -> slurper.parse(new File(dist, name + '.json')) }.memoize()
def declared = { String name -> module(name).datapoints*.name as Set }
// The definitions' "conditional" datapoints may legitimately not print; every other one must.
def required = { String name ->
    slurper.parse(new File(root, 'modules/' + name + '.json')).datapoints.findAll { !it.conditional }*.name as Set
}

// --- output shape: every BatchScript line is <port>.<Datapoint>=<number> and declared --------
def batchShape = { String label, String moduleName, Map result ->
    def bad = result.out.findAll { !(it ==~ /\d{1,5}\.[A-Z][A-Za-z0-9]*=-?\d+(\.\d+)?/) }
    note(label + ': every line is <port>.<Datapoint>=<number>', bad.isEmpty(), bad.join('\n'))
    def names = result.out.collect { it.replaceFirst(/^\d+\./, '').replaceFirst(/=.*/, '') } as Set
    def undeclared = names - declared(moduleName)
    note(label + ': every printed datapoint is declared', undeclared.isEmpty(), undeclared.join(', '))
}

def synced = fixture('mariadb_synced')
def donor = fixture('pxc_donor')
def nonprimary = fixture('nonprimary')
def joining = fixture('galera3_joining')
def plain = fixture('not_galera')
def noProvider = fixture('provider_none')

// --- addCategory_MySQL_Galera ----------------------------------------------------------------
def ps = 'addCategory_MySQL_Galera.groovy'
def r = run(ps, [:], ['3306': synced])
note('ps: Galera node gets the category', r.exit == 0 && r.out.contains('system.categories=MySQL_Galera'), r.out.join('\n'))
note('ps: properties carry the auto. prefix and values', r.out.contains('auto.galera.cluster_name=Labcluster') &&
    r.out.contains('auto.galera.node_name=Node-A') && r.out.contains('auto.galera.ports=3306') &&
    r.out.contains('auto.galera.server_flavor=mariadb') && r.out.contains('auto.galera.provider_version=26.4.20(r7b5a1b9)'), r.out.join('\n'))
note('ps: only system.categories and auto.* keys', r.out.every { it.startsWith('system.categories=') || it.startsWith('auto.') }, r.out.join('\n'))
r = run(ps, [:], ['3306': donor])
note('ps: Percona flavour', r.out.contains('auto.galera.server_flavor=percona'), r.out.join('\n'))
[['standalone MySQL', ['3306': plain]], ['wsrep without a provider', ['3306': noProvider]],
 ['nothing listening', [:]], ['a query error', ['3306': new java.sql.SQLException('Access denied')]]].each { label, reads ->
    def silent = run(ps, [:], reads)
    note('ps: silent on ' + label, silent.exit == 0 && silent.out.isEmpty() && silent.err.isEmpty(), silent.out.join('\n') + silent.err)
}
r = run(ps, ['jdbc.mysql.pass': null], ['3306': synced])
note('ps: silent without credentials', r.exit == 0 && r.out.isEmpty(), r.out.join('\n'))
r = run(ps, ['jdbc.mysql.port': '3307, 3308'], ['3306': plain, '3308': synced])
note('ps: finds Galera on a listed port only', r.out.contains('auto.galera.ports=3308'), r.out.join('\n'))

// --- Active Discovery --------------------------------------------------------------------------
def ad = 'MySQL_Galera_Node.ad.groovy'
note('ad: both DataSources ship the same discovery', new File(dist, 'scripts/MySQL_Galera_Node.ad.groovy').text ==
    new File(dist, 'scripts/MySQL_Galera_Replication.ad.groovy').text)
r = run(ad, [:], ['3306': synced])
note('ad: one instance per Galera port', r.exit == 0 && r.out.size() == 1 && r.out[0].startsWith('3306##Galera port 3306 (Labcluster)##'), r.out.join('\n'))
note('ad: line shape wildvalue##name##description####props', r.out[0] ==~ /3306##[^#]+##[^#]+####(auto\.[a-z_.]+=[^&=\s]+&?)+/, r.out[0])
note('ad: properties are URL-encoded', r.out[0].contains('auto.galera.provider_version=26.4.20%28r7b5a1b9%29'), r.out[0])
r = run(ad, ['jdbc.mysql.port': '3307,3306,abc,70000'], ['3307': synced, '3306': plain])
note('ad: listed ports probed, bad entries ignored', r.exit == 0 && r.out.size() == 1 && r.out[0].startsWith('3307##'), r.out.join('\n') + r.err)
r = run(ad, ['jdbc.mysql.port': '3307'], ['3307': synced])
note('ad: 3306 refusing is not a failure when another port answers', r.exit == 0 && r.out.size() == 1, r.err)
r = run(ad, [:], [:])
note('ad: nothing answering exits 2 and prints nothing', r.exit == 2 && r.out.isEmpty() && r.err.contains('Connection refused'), r.err)
r = run(ad, [:], ['3306': plain])
note('ad: a standalone server discovers nothing, exit 0', r.exit == 0 && r.out.isEmpty())

// --- MySQL_Galera_Node --------------------------------------------------------------------------
def node = 'MySQL_Galera_Node.collect.groovy'
r = run(node, [:], ['3306': synced])
batchShape('node synced', 'MySQL_Galera_Node', r)
def v = values(r, '3306')
note('node: healthy values', r.exit == 0 && v.ClusterStatus == '2' && v.LocalState == '4' && v.Ready == '1' &&
    v.Connected == '1' && v.ClusterSize == '3' && v.ClusterWeight == '3' && v.DesyncCount == '0' && v.OpenTransactions == '0', v.toString())
note('node: no expected size without galera.cluster.size', !v.containsKey('MissingNodes') && !v.containsKey('ExpectedClusterSize'), v.toString())
r = run(node, ['galera.cluster.size': '3'], ['3306': synced])
v = values(r, '3306')
note('node: expected size, nothing missing', v.ExpectedClusterSize == '3' && v.MissingNodes == '0', v.toString())
r = run(node, ['galera.cluster.size': '3'], ['3306': nonprimary])
batchShape('node non-primary', 'MySQL_Galera_Node', r)
v = values(r, '3306')
note('node: isolated node', v.ClusterStatus == '1' && v.LocalState == '0' && v.Ready == '0' && v.ClusterSize == '1' && v.MissingNodes == '2', v.toString())
r = run(node, ['galera.cluster.size': '2'], ['3306': donor])
v = values(r, '3306')
note('node: donor, more nodes than expected is not negative', v.LocalState == '2' && v.DesyncCount == '1' && v.MissingNodes == '0', v.toString())
r = run(node, ['galera.cluster.size': 'three'], ['3306': synced])
note('node: a non-numeric galera.cluster.size is ignored', !values(r, '3306').containsKey('MissingNodes'))
r = run(node, [:], ['3306': joining])
batchShape('node galera 3', 'MySQL_Galera_Node', r)
v = values(r, '3306')
note('node: Galera 3 joining, Galera 4 datapoints withheld', v.LocalState == '1' && v.Ready == '0' &&
    !v.containsKey('ClusterWeight') && !v.containsKey('DesyncCount') && !v.containsKey('OpenTransactions'), v.toString())
def odd = [status: synced.status + ['wsrep_cluster_status': 'Something-New', 'wsrep_ready': 'MAYBE'], vars: synced.vars]
v = values(run(node, [:], ['3306': odd]), '3306')
note('node: unknown words are withheld, never guessed', !v.containsKey('ClusterStatus') && !v.containsKey('Ready') && v.LocalState == '4', v.toString())
def numericOnly = synced.status + ['wsrep_local_state_comment': '', 'wsrep_local_state': '3']
note('node: state number used when the comment is blank', values(run(node, [:], ['3306': [status: numericOnly, vars: synced.vars]]), '3306').LocalState == '3')
def outOfRange = synced.status + ['wsrep_local_state_comment': '', 'wsrep_local_state': '5']
note('node: a state number outside 1-4 reads 0', values(run(node, [:], ['3306': [status: outOfRange, vars: synced.vars]]), '3306').LocalState == '0')
r = run(node, ['jdbc.mysql.port': '3307'], ['3306': synced, '3307': nonprimary])
note('node: two Galera ports, each under its own wildvalue', values(r, '3306').LocalState == '4' && values(r, '3307').LocalState == '0', r.out.join('\n'))
r = run(node, [:], [:])
note('node: nothing answering exits 2, prints nothing', r.exit == 2 && r.out.isEmpty())
r = run(node, [:], ['3306': plain])
note('node: no Galera node answering exits 2', r.exit == 2 && r.out.isEmpty() && r.err.contains('No Galera node'), r.err)
r = run(node, ['jdbc.mysql.port': '3307'], ['3306': synced, '3307': new java.sql.SQLException('Lost connection')])
note('node: one port failing does not blank the other', r.exit == 0 && values(r, '3306').ClusterStatus == '2' && r.err.contains('3307'), r.err)

// --- MySQL_Galera_Replication --------------------------------------------------------------------
def repl = 'MySQL_Galera_Replication.collect.groovy'
r = run(repl, [:], ['3306': synced])
batchShape('repl synced', 'MySQL_Galera_Replication', r)
v = values(r, '3306')
note('repl: paused_ns / 10^7', v.FlowControlPausedPercent == '123.456789', v.FlowControlPausedPercent)
note('repl: counters printed raw for derive', v.TransactionsReplicated == '9120' && v.BytesReplicated == '4882112' &&
    v.TransactionsReceived == '9131' && v.BytesReceived == '4885004' && v.LocalCommits == '9118' &&
    v.CertFailures == '2' && v.BruteForceAborts == '1', v.toString())
note('repl: queues and flags', v.LocalRecvQueue == '0' && v.LocalRecvQueueAvg == '0.0125' && v.LocalSendQueueAvg == '0.000731' &&
    v.CertDepsDistance == '14.5' && v.FlowControlActive == '0', v.toString())
note('repl: every declared datapoint printed on Galera 4', declared('MySQL_Galera_Replication') == (v.keySet() as Set),
    (declared('MySQL_Galera_Replication') - v.keySet()).toString())
['MySQL_Galera_Node', 'MySQL_Galera_Replication'].each { name ->
    def printed = values(run(name + '.collect.groovy', [:], ['3306': joining]), '3306').keySet()
    note(name + ': every datapoint not marked conditional prints, even on Galera 3', printed.containsAll(required(name)),
        (required(name) - printed).toString())
}
r = run(repl, [:], ['3306': donor])
v = values(r, '3306')
note('repl: Percona flow-control flag', v.FlowControlActive == '1' && v.FlowControlPausedPercent == '9800' && v.LocalRecvQueueAvg == '12.25', v.toString())
r = run(repl, [:], ['3306': joining])
batchShape('repl galera 3', 'MySQL_Galera_Replication', r)
v = values(r, '3306')
note('repl: Galera 3 withholds flow control, keeps the rest', !v.containsKey('FlowControlPausedPercent') && !v.containsKey('FlowControlActive') &&
    v.LocalRecvQueueAvg == '0' && v.TransactionsReceived == '3', v.toString())
r = run(repl, [:], [:])
note('repl: nothing answering exits 2', r.exit == 2 && r.out.isEmpty())

// --- the module JSON the scripts are shipped in -------------------------------------------------
['MySQL_Galera_Node', 'MySQL_Galera_Replication'].each { name ->
    def m = module(name)
    note(name + ': batchscript keys carry ##WILDVALUE##.', m.datapoints.every { it.interpretExpr == '##WILDVALUE##.' + it.name })
    note(name + ': instances are kept (autoDeleteInstances true on import)', m.activeDiscovery.autoDeleteInstances == true &&
        m.activeDiscovery.deleteInactiveInstances == false)
    note(name + ': embedded scripts are the tested ones', m.collectionAttrs.content == new File(dist, 'scripts/' + name + '.collect.groovy').text &&
        m.activeDiscovery.params.content == new File(dist, 'scripts/' + name + '.ad.groovy').text)
}
note('ps: embedded script is the tested one', module('addCategory_MySQL_Galera').script.content == new File(dist, 'scripts/' + ps).text)

System.exit(t.finish())
