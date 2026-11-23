#!/bin/sh
# start-galera.sh <node name> [--wsrep-new-cluster]
#
# Starts one node of the live test cluster. The image's own entrypoint is bypassed: it starts
# a temporary server with the same arguments to initialise the data directory, and with wsrep
# on that server would try to join the cluster. The bootstrap node creates the accounts the
# test uses once it is up; CREATE USER replicates to the joiners.
set -e
node="$1"; shift

if [ ! -d /var/lib/mysql/mysql ]; then
    mariadb-install-db --user=mysql --datadir=/var/lib/mysql --skip-test-db > /dev/null
fi

if [ "$1" = "--wsrep-new-cluster" ]; then
    (
        until mariadb -uroot -e 'SELECT 1' > /dev/null 2>&1; do sleep 1; done
        mariadb -uroot -e "
            CREATE USER IF NOT EXISTS 'logicmonitor'@'%' IDENTIFIED BY 'monitorpw';
            GRANT USAGE ON *.* TO 'logicmonitor'@'%';
            CREATE USER IF NOT EXISTS 'admin'@'%' IDENTIFIED BY 'adminpw';
            GRANT ALL ON *.* TO 'admin'@'%' WITH GRANT OPTION;"
    ) &
fi

exec mariadbd --user=mysql --bind-address=0.0.0.0 \
    --binlog-format=ROW --default-storage-engine=InnoDB --innodb-autoinc-lock-mode=2 \
    --wsrep-on=ON --wsrep-provider=/usr/lib/galera/libgalera_smm.so \
    --wsrep-cluster-name=Labcluster --wsrep-node-name="$node" \
    --wsrep-cluster-address=gcomm://galera1,galera2,galera3 \
    --wsrep-sst-method=rsync "$@"
