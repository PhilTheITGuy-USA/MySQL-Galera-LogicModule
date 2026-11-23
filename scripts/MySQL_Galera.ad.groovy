// Active Discovery for both DataSources: one instance per port whose server is a Galera node.
// The wildvalue is the port, which is what identifies a server on a resource and does not
// move. Auto-delete is off in both modules, so a discovery that misses a node never retires it:
// a node that has left the cluster is what they exist to alert on.
def lines = []
def result = galeraEachNode { port, read ->
    def cluster = read.vars['wsrep_cluster_name']?.toString()?.replace('#', '')?.trim()
    def node = read.vars['wsrep_node_name']?.toString()?.replace('#', '')?.trim()
    def name = 'Galera port ' + port + (cluster ? ' (' + cluster + ')' : '')
    def description = (node ? 'node ' + node + ', ' : '') + galeraFlavor(read.vars) + ' ' +
        (read.vars['version'] ?: '') + ', provider ' + (read.status['wsrep_provider_version'] ?: 'unknown')
    def props = galeraAdProps([
        'galera.port'            : port,
        'galera.cluster_name'    : cluster,
        'galera.node_name'       : node,
        'galera.provider_version': read.status['wsrep_provider_version'],
        'galera.server_flavor'   : galeraFlavor(read.vars),
    ])
    lines << "${port}##${name}##${description.replace('#', '').trim()}####${props}"
}
if (result.answered == 0) {
    System.err.println('No MySQL server answered on ' + galeraPorts().join(', ') + ': ' +
        result.failed.collect { k, v -> k + ' ' + v }.join('; '))
    return 2
}
result.failed.each { port, reason -> System.err.println('port ' + port + ': ' + reason) }
lines.each { println it }
return 0
