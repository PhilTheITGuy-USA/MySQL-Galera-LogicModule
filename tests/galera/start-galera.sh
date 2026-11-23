#!/bin/sh
# start-galera.sh <node name> [--wsrep-new-cluster]
#
# Starts one node of the live test cluster. The image's own entrypoint is bypassed: it starts
# a temporary server with the same arguments to initialise the data directory, and with wsrep
# on that server would try to join the cluster.
set -e
node="$1"; shift

if [ ! -d /var/lib/mysql/mysql ]; then
    mariadb-install-db --user=mysql --datadir=/var/lib/mysql --skip-test-db > /dev/null
fi

# The bootstrap node creates the test accounts before it accepts connections; the joiners
# get them in their state transfer.
init=""
[ "$1" = "--wsrep-new-cluster" ] && init="--init-file=/usr/local/share/galera-init.sql"

exec mariadbd --user=mysql --bind-address=0.0.0.0 \
    --binlog-format=ROW --default-storage-engine=InnoDB --innodb-autoinc-lock-mode=2 \
    --wsrep-on=ON --wsrep-provider=/usr/lib/galera/libgalera_smm.so \
    --wsrep-cluster-name=Labcluster --wsrep-node-name="$node" \
    --wsrep-cluster-address=gcomm://galera1,galera2,galera3 \
    --wsrep-sst-method=rsync $init "$@"
