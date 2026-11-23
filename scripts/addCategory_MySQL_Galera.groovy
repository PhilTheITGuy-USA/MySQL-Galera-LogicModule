// addCategory_MySQL_Galera: marks a resource whose MySQL / MariaDB server is a Galera node.
//
// Silent on everything that is not a Galera node, and on every failure: exit 0, no output.
// Missing credentials make galeraFetch throw before any connection, so they land here too.
// A PropertySource can add a category but never remove one, so a wrong category here would
// apply the suite to a server that is not part of a cluster, permanently.
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
