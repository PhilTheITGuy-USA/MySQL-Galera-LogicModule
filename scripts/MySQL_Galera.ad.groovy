// Active Discovery for both DataSources: one instance per port whose server is a Galera node.
// The wildvalue is the port, which is what identifies a server on a resource and does not
// move. Auto-delete is off in both modules, so a discovery that misses a node never retires it:
// a node that has left the cluster is what they exist to alert on.
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
