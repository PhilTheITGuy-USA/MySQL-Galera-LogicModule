// MySQL_Galera_Node: is this node in the Primary Component, synced, and accepting queries,
// and is the cluster the size it should be. One connection per Galera port per poll.
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
