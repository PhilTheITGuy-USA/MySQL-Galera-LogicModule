-- Run by the bootstrap node through --init-file, before it accepts connections.
-- The joiners receive these accounts with the data directory in their state transfer.
CREATE USER IF NOT EXISTS 'logicmonitor'@'%' IDENTIFIED BY 'monitorpw';
GRANT USAGE ON *.* TO 'logicmonitor'@'%';
CREATE USER IF NOT EXISTS 'admin'@'%' IDENTIFIED BY 'adminpw';
GRANT ALL ON *.* TO 'admin'@'%' WITH GRANT OPTION;
