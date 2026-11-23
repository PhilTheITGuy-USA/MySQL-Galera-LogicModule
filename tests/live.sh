#!/bin/sh
# Live test against a real three-node MariaDB Galera cluster. Run from the repository root,
# after python build/build.py. Needs Docker; pulls mariadb:11.4, groovy:4-jdk17 and MySQL
# Connector/J (through Grape, from Maven Central).
#
#   main      every module on a healthy cluster, a write load, a desynced node
#   isolated  galera3 detached from the cluster network: it is Non-Primary, the others are two
#   rejoined  galera3 reattached: it rejoins and syncs
#
# The containers are removed at the end, pass or fail.
compose="docker compose -f tests/docker-compose.yml --profile live"
status=0

cleanup() { $compose down -v > /dev/null 2>&1; }
trap cleanup EXIT

# Fresh data directories every run: a bootstrap node reusing an old one is refused (correctly)
# by Galera as possibly not the most advanced.
cleanup
if ! $compose up -d --build --renew-anon-volumes --wait galera1 galera2 galera3 standalone; then
    $compose logs --tail 60 galera1 galera2 galera3
    exit 1
fi

$compose run --rm -e LIVE_PHASE=main live || status=1

galera3=$($compose ps -q galera3)
docker network disconnect galera-live-cluster "$galera3" || exit 1
$compose run --rm --no-deps -e LIVE_PHASE=isolated live || status=1

docker network connect --alias galera3 galera-live-cluster "$galera3" || exit 1
$compose run --rm --no-deps -e LIVE_PHASE=rejoined live || status=1

[ $status -eq 0 ] && echo "LIVE: ALL PHASES PASSED" || echo "LIVE: FAILURES ABOVE"
exit $status
