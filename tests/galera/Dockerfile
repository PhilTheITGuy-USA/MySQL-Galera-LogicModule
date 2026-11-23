# One node of the live test cluster: MariaDB 11.4 with its bundled Galera 4 provider, and
# rsync for state transfers (the official image ships without it).
FROM mariadb:11.4
RUN apt-get update \
 && apt-get install -y --no-install-recommends rsync lsof iproute2 \
 && rm -rf /var/lib/apt/lists/*
COPY start-galera.sh /usr/local/bin/start-galera.sh
RUN chmod +x /usr/local/bin/start-galera.sh
ENTRYPOINT ["/usr/local/bin/start-galera.sh"]
