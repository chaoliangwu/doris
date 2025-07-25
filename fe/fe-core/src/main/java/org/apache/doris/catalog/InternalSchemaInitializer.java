// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog;

import org.apache.doris.analysis.AddColumnClause;
import org.apache.doris.analysis.AlterClause;
import org.apache.doris.analysis.AlterTableStmt;
import org.apache.doris.analysis.ColumnDef;
import org.apache.doris.analysis.ColumnNullableType;
import org.apache.doris.analysis.CreateTableStmt;
import org.apache.doris.analysis.DbName;
import org.apache.doris.analysis.DistributionDesc;
import org.apache.doris.analysis.HashDistributionDesc;
import org.apache.doris.analysis.KeysDesc;
import org.apache.doris.analysis.ModifyColumnClause;
import org.apache.doris.analysis.ModifyPartitionClause;
import org.apache.doris.analysis.PartitionDesc;
import org.apache.doris.analysis.RangePartitionDesc;
import org.apache.doris.analysis.TableName;
import org.apache.doris.analysis.TypeDef;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.PropertyAnalyzer;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.ha.FrontendNodeType;
import org.apache.doris.nereids.trees.plans.commands.AlterTableCommand;
import org.apache.doris.nereids.trees.plans.commands.CreateDatabaseCommand;
import org.apache.doris.nereids.trees.plans.commands.info.AddColumnsOp;
import org.apache.doris.nereids.trees.plans.commands.info.AlterTableOp;
import org.apache.doris.nereids.trees.plans.commands.info.ReorderColumnsOp;
import org.apache.doris.nereids.trees.plans.commands.info.TableNameInfo;
import org.apache.doris.plugin.audit.AuditLoader;
import org.apache.doris.statistics.StatisticConstants;
import org.apache.doris.statistics.util.StatisticsUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public class InternalSchemaInitializer extends Thread {

    private static final Logger LOG = LogManager.getLogger(InternalSchemaInitializer.class);
    private static boolean StatsTableSchemaValid = false;

    public InternalSchemaInitializer() {
        super("InternalSchemaInitializer");
    }

    public void run() {
        if (!FeConstants.enableInternalSchemaDb) {
            return;
        }
        modifyColumnStatsTblSchema();
        while (!created()) {
            try {
                FrontendNodeType feType = Env.getCurrentEnv().getFeType();
                if (feType.equals(FrontendNodeType.INIT) || feType.equals(FrontendNodeType.UNKNOWN)) {
                    LOG.warn("FE is not ready");
                    Thread.sleep(Config.resource_not_ready_sleep_seconds * 1000);
                    continue;
                }
                Thread.currentThread()
                        .join(Config.resource_not_ready_sleep_seconds * 1000L);
                createDb();
                createTbl();
            } catch (Throwable e) {
                LOG.warn("Statistics storage initiated failed, will try again later", e);
                try {
                    Thread.sleep(Config.resource_not_ready_sleep_seconds * 1000);
                } catch (InterruptedException ex) {
                    LOG.info("Sleep interrupted. {}", ex.getMessage());
                }
            }
        }
        LOG.info("Internal schema is initialized");
        Optional<Database> op
                = Env.getCurrentEnv().getInternalCatalog().getDb(StatisticConstants.DB_NAME);
        if (!op.isPresent()) {
            LOG.warn("Internal DB got deleted!");
            return;
        }
        Database database = op.get();
        modifyTblReplicaCount(database, StatisticConstants.TABLE_STATISTIC_TBL_NAME);
        modifyTblReplicaCount(database, StatisticConstants.PARTITION_STATISTIC_TBL_NAME);
        modifyTblReplicaCount(database, AuditLoader.AUDIT_LOG_TABLE);
    }

    public void modifyColumnStatsTblSchema() {
        while (true) {
            try {
                Table table = findStatsTable();
                if (table == null) {
                    break;
                }
                table.writeLock();
                try {
                    doSchemaChange(table);
                    break;
                } finally {
                    table.writeUnlock();
                }
            } catch (Throwable t) {
                LOG.warn("Failed to do schema change for stats table. Try again later.", t);
            }
            try {
                Thread.sleep(Config.resource_not_ready_sleep_seconds *  1000);
            } catch (InterruptedException t) {
                // IGNORE
            }
        }
        StatsTableSchemaValid = true;
    }

    public Table findStatsTable() {
        // 1. check database exist
        Optional<Database> dbOpt = Env.getCurrentEnv().getInternalCatalog().getDb(FeConstants.INTERNAL_DB_NAME);
        if (!dbOpt.isPresent()) {
            return null;
        }

        // 2. check table exist
        Database db = dbOpt.get();
        Optional<Table> tableOp = db.getTable(StatisticConstants.TABLE_STATISTIC_TBL_NAME);
        return tableOp.orElse(null);
    }

    public void doSchemaChange(Table table) throws UserException {
        List<AlterClause> clauses = getModifyColumnClauses(table);
        if (!clauses.isEmpty()) {
            TableName tableName = new TableName(InternalCatalog.INTERNAL_CATALOG_NAME,
                    StatisticConstants.DB_NAME, table.getName());
            AlterTableStmt alter = new AlterTableStmt(tableName, clauses);
            Env.getCurrentEnv().alterTable(alter);
        }
    }

    public List<AlterClause> getModifyColumnClauses(Table table) throws AnalysisException {
        List<AlterClause> clauses = Lists.newArrayList();
        Set<String> currentColumnNames = table.getBaseSchema().stream()
                .map(Column::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!currentColumnNames.containsAll(InternalSchema.TABLE_STATS_SCHEMA.stream()
                .map(ColumnDef::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toList()))) {
            for (ColumnDef expected : InternalSchema.TABLE_STATS_SCHEMA) {
                if (!currentColumnNames.contains(expected.getName().toLowerCase())) {
                    AddColumnClause addColumnClause = new AddColumnClause(expected, null, null, null);
                    addColumnClause.setColumn(expected.toColumn());
                    clauses.add(addColumnClause);
                }
            }
        }
        for (Column col : table.getFullSchema()) {
            if (col.isKey() && col.getType().isVarchar()
                    && col.getType().getLength() < StatisticConstants.MAX_NAME_LEN) {
                TypeDef typeDef = new TypeDef(
                        ScalarType.createVarchar(StatisticConstants.MAX_NAME_LEN), col.isAllowNull());
                ColumnNullableType nullableType =
                        col.isAllowNull() ? ColumnNullableType.NULLABLE : ColumnNullableType.NOT_NULLABLE;
                ColumnDef columnDef = new ColumnDef(col.getName(), typeDef, true, null,
                        nullableType, -1, new ColumnDef.DefaultValue(false, null), "");
                try {
                    columnDef.analyze(true);
                } catch (AnalysisException e) {
                    LOG.warn("Failed to analyze column {}", col.getName());
                    continue;
                }
                ModifyColumnClause clause = new ModifyColumnClause(columnDef, null, null, Maps.newHashMap());
                clause.setColumn(columnDef.toColumn());
                clauses.add(clause);
            }
        }
        return clauses;
    }

    @VisibleForTesting
    public static void modifyTblReplicaCount(Database database, String tblName) {
        if (Config.isCloudMode()
                || Config.min_replication_num_per_tablet >= StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM
                || Config.max_replication_num_per_tablet < StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM) {
            return;
        }
        while (true) {
            int backendNum = Env.getCurrentSystemInfo().getStorageBackendNumFromDiffHosts(true);
            if (FeConstants.runningUnitTest) {
                backendNum = Env.getCurrentSystemInfo().getAllBackendIds().size();
            }
            if (backendNum >= StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM) {
                try {
                    OlapTable tbl = (OlapTable) StatisticsUtil.findTable(InternalCatalog.INTERNAL_CATALOG_NAME,
                            StatisticConstants.DB_NAME, tblName);
                    tbl.writeLock();
                    try {
                        if (tbl.getTableProperty().getReplicaAllocation().getTotalReplicaNum()
                                >= StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM) {
                            return;
                        }
                        if (!tbl.isPartitionedTable()) {
                            Map<String, String> props = new HashMap<>();
                            props.put(PropertyAnalyzer.PROPERTIES_REPLICATION_ALLOCATION, "tag.location.default: "
                                    + StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM);
                            Env.getCurrentEnv().modifyTableReplicaAllocation(database, tbl, props);
                        } else {
                            TableName tableName = new TableName(InternalCatalog.INTERNAL_CATALOG_NAME,
                                    StatisticConstants.DB_NAME, tbl.getName());
                            // 1. modify table's default replica num
                            Map<String, String> props = new HashMap<>();
                            props.put("default." + PropertyAnalyzer.PROPERTIES_REPLICATION_NUM,
                                    "" + StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM);
                            Env.getCurrentEnv().modifyTableDefaultReplicaAllocation(database, tbl, props);
                            // 2. modify each partition's replica num
                            List<AlterClause> clauses = Lists.newArrayList();
                            props.clear();
                            props.put(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM,
                                    "" + StatisticConstants.STATISTIC_INTERNAL_TABLE_REPLICA_NUM);
                            clauses.add(ModifyPartitionClause.createStarClause(props, false));
                            AlterTableStmt alter = new AlterTableStmt(tableName, clauses);
                            Env.getCurrentEnv().alterTable(alter);
                        }
                    } finally {
                        tbl.writeUnlock();
                    }
                    break;
                } catch (Throwable t) {
                    LOG.warn("Failed to scale replica of stats tbl:{} to 3", tblName, t);
                }
            }
            try {
                Thread.sleep(Config.resource_not_ready_sleep_seconds *  1000);
            } catch (InterruptedException t) {
                // IGNORE
            }
        }
    }

    @VisibleForTesting
    public static void createTbl() throws UserException {
        // statistics
        Env.getCurrentEnv().getInternalCatalog().createTable(
                buildStatisticsTblStmt(StatisticConstants.TABLE_STATISTIC_TBL_NAME,
                        Lists.newArrayList("id", "catalog_id", "db_id", "tbl_id", "idx_id", "col_id", "part_id")));
        Env.getCurrentEnv().getInternalCatalog().createTable(
                buildStatisticsTblStmt(StatisticConstants.PARTITION_STATISTIC_TBL_NAME,
                        Lists.newArrayList("catalog_id", "db_id", "tbl_id", "idx_id", "part_name", "part_id",
                                "col_id")));
        // audit table
        Env.getCurrentEnv().getInternalCatalog().createTable(buildAuditTblStmt());
    }

    @VisibleForTesting
    public static void createDb() {
        CreateDatabaseCommand command = new CreateDatabaseCommand(true,
                new DbName("internal", FeConstants.INTERNAL_DB_NAME), null);
        try {
            Env.getCurrentEnv().createDb(command);
        } catch (DdlException e) {
            LOG.warn("Failed to create database: {}, will try again later",
                    FeConstants.INTERNAL_DB_NAME, e);
        }
    }

    private static CreateTableStmt buildStatisticsTblStmt(String statsTableName, List<String> uniqueKeys)
            throws UserException {
        TableName tableName = new TableName("", FeConstants.INTERNAL_DB_NAME, statsTableName);
        String engineName = "olap";
        KeysDesc keysDesc = new KeysDesc(KeysType.UNIQUE_KEYS, uniqueKeys);
        DistributionDesc distributionDesc = new HashDistributionDesc(
                StatisticConstants.STATISTIC_TABLE_BUCKET_COUNT, uniqueKeys);
        Map<String, String> properties = new HashMap<String, String>() {
            {
                put(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM, String.valueOf(
                        Math.max(1, Config.min_replication_num_per_tablet)));
            }
        };

        PropertyAnalyzer.getInstance().rewriteForceProperties(properties);
        CreateTableStmt createTableStmt = new CreateTableStmt(true, false,
                tableName, InternalSchema.getCopiedSchema(statsTableName),
                engineName, keysDesc, null, distributionDesc,
                properties, null, "Doris internal statistics table, DO NOT MODIFY IT", null);
        StatisticsUtil.analyze(createTableStmt);
        return createTableStmt;
    }

    private static CreateTableStmt buildAuditTblStmt() throws UserException {
        TableName tableName = new TableName("",
                FeConstants.INTERNAL_DB_NAME, AuditLoader.AUDIT_LOG_TABLE);

        String engineName = "olap";
        ArrayList<String> dupKeys = Lists.newArrayList("query_id", "time", "client_ip");
        KeysDesc keysDesc = new KeysDesc(KeysType.DUP_KEYS, dupKeys);
        // partition
        PartitionDesc partitionDesc = new RangePartitionDesc(Lists.newArrayList("time"), Lists.newArrayList());
        // distribution
        int bucketNum = 2;
        DistributionDesc distributionDesc = new HashDistributionDesc(bucketNum, Lists.newArrayList("query_id"));
        Map<String, String> properties = new HashMap<String, String>() {
            {
                put("dynamic_partition.time_unit", "DAY");
                put("dynamic_partition.start", "-30");
                put("dynamic_partition.end", "3");
                put("dynamic_partition.prefix", "p");
                put("dynamic_partition.buckets", String.valueOf(bucketNum));
                put("dynamic_partition.enable", "true");
                put("replication_num", String.valueOf(Math.max(1,
                        Config.min_replication_num_per_tablet)));
            }
        };

        PropertyAnalyzer.getInstance().rewriteForceProperties(properties);
        CreateTableStmt createTableStmt = new CreateTableStmt(true, false,
                tableName, InternalSchema.getCopiedSchema(AuditLoader.AUDIT_LOG_TABLE),
                engineName, keysDesc, partitionDesc, distributionDesc,
                properties, null, "Doris internal audit table, DO NOT MODIFY IT", null);
        StatisticsUtil.analyze(createTableStmt);
        return createTableStmt;
    }


    private boolean created() {
        // 1. check database exist
        Optional<Database> optionalDatabase =
                Env.getCurrentEnv().getInternalCatalog()
                        .getDb(FeConstants.INTERNAL_DB_NAME);
        if (!optionalDatabase.isPresent()) {
            return false;
        }
        Database db = optionalDatabase.get();
        Optional<Table> optionalTable = db.getTable(StatisticConstants.TABLE_STATISTIC_TBL_NAME);
        if (!optionalTable.isPresent()) {
            return false;
        }

        // 2. check statistic tables
        Table statsTbl = optionalTable.get();
        Optional<Column> optionalColumn =
                statsTbl.fullSchema.stream().filter(c -> c.getName().equals("count")).findFirst();
        if (!optionalColumn.isPresent() || !optionalColumn.get().isAllowNull()) {
            try {
                Env.getCurrentEnv().getInternalCatalog()
                        .dropTable(StatisticConstants.DB_NAME, StatisticConstants.TABLE_STATISTIC_TBL_NAME,
                                false, false, true, true);
            } catch (Exception e) {
                LOG.warn("Failed to drop outdated table", e);
            }
            return false;
        }
        optionalTable = db.getTable(StatisticConstants.PARTITION_STATISTIC_TBL_NAME);
        if (!optionalTable.isPresent()) {
            return false;
        }

        // 3. check audit table
        optionalTable = db.getTable(AuditLoader.AUDIT_LOG_TABLE);
        if (!optionalTable.isPresent()) {
            return false;
        }

        // 4. check and update audit table schema
        OlapTable auditTable = (OlapTable) optionalTable.get();

        // 5. check if we need to add new columns
        return alterAuditSchemaIfNeeded(auditTable);
    }

    private boolean alterAuditSchemaIfNeeded(OlapTable auditTable) {
        List<ColumnDef> expectedSchema = InternalSchema.AUDIT_SCHEMA;
        List<String> expectedColumnNames = expectedSchema.stream()
                .map(ColumnDef::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        List<Column> currentColumns = auditTable.getBaseSchema();
        List<String> currentColumnNames = currentColumns.stream()
                .map(Column::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        // check if all expected columns are exists and in the right order
        if (currentColumnNames.size() >= expectedColumnNames.size()
                && expectedColumnNames.equals(currentColumnNames.subList(0, expectedColumnNames.size()))) {
            return true;
        }

        List<AlterTableOp> alterClauses = Lists.newArrayList();
        // add new columns
        List<Column> addColumns = Lists.newArrayList();
        for (ColumnDef expected : expectedSchema) {
            if (!currentColumnNames.contains(expected.getName().toLowerCase())) {
                addColumns.add(new Column(expected.getName(), expected.getType(), expected.isAllowNull()));
            }
        }
        if (!addColumns.isEmpty()) {
            AddColumnsOp addColumnsOp = new AddColumnsOp(null, Maps.newHashMap(), addColumns);
            alterClauses.add(addColumnsOp);
        }
        // reorder columns
        List<String> removedColumnNames = Lists.newArrayList(currentColumnNames);
        removedColumnNames.removeAll(expectedColumnNames);
        List<String> newColumnOrders = Lists.newArrayList(expectedColumnNames);
        newColumnOrders.addAll(removedColumnNames);
        ReorderColumnsOp reorderColumnsOp = new ReorderColumnsOp(newColumnOrders, null, Maps.newHashMap());
        alterClauses.add(reorderColumnsOp);
        TableNameInfo auditTableName = new TableNameInfo(InternalCatalog.INTERNAL_CATALOG_NAME,
                FeConstants.INTERNAL_DB_NAME, AuditLoader.AUDIT_LOG_TABLE);
        AlterTableCommand alterTableCommand = new AlterTableCommand(auditTableName, alterClauses);
        try {
            Env.getCurrentEnv().alterTable(alterTableCommand);
        } catch (Exception e) {
            LOG.warn("Failed to alter audit table schema", e);
            return false;
        }
        return true;
    }

    public static boolean isStatsTableSchemaValid() {
        return StatsTableSchemaValid;
    }
}
