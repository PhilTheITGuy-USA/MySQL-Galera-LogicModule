// MySQL_Galera_Replication: how hard replication is working and whether this node is holding
// the cluster back. One connection per Galera port per poll.
//
// Cumulative counters are printed raw and declared derive, so LogicMonitor turns them into
// per-second rates. wsrep_flow_control_paused_ns is printed divided by 10^7: its per-second
// rate is then the percentage of wall time replication was paused, over the poll, measured by
// the server rather than averaged since whoever last ran SHOW STATUS or FLUSH STATUS, which is
// what wsrep_flow_control_paused gives.
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
if (result.galera == 0) {
    System.err.println(result.answered == 0
        ? 'No MySQL server answered: ' + result.failed.collect { k, v -> k + ' ' + v }.join('; ')
        : 'No Galera node answered on ' + galeraPorts().join(', '))
    return 2
}
result.failed.each { port, reason -> System.err.println('port ' + port + ': ' + reason) }
return 0
