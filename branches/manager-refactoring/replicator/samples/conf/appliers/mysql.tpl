replicator.applier.dbms=com.continuent.tungsten.replicator.applier.MySQLDrizzleApplier
replicator.applier.dbms.host=${replicator.global.db.host}
replicator.applier.dbms.port=${replicator.global.db.port}
replicator.applier.dbms.user=${replicator.global.db.user}
replicator.applier.dbms.password=${replicator.global.db.password}
replicator.applier.dbms.ignoreSessionVars=autocommit
replicator.applier.dbms.getColumnMetadataFromDB=true
@{#(APPLIER.REPL_SVC_DATASOURCE_APPLIER_INIT_SCRIPT)}replicator.applier.dbms.initScript=@{APPLIER.REPL_SVC_DATASOURCE_APPLIER_INIT_SCRIPT}