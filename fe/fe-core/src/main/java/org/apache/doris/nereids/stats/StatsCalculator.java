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

package org.apache.doris.nereids.stats;

import org.apache.doris.analysis.IntLiteral;
import org.apache.doris.analysis.LiteralExpr;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.DatabaseIf;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.ListPartitionItem;
import org.apache.doris.catalog.MTMV;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.PartitionItem;
import org.apache.doris.catalog.PartitionKey;
import org.apache.doris.catalog.PartitionType;
import org.apache.doris.catalog.RangePartitionItem;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.Pair;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.memo.Group;
import org.apache.doris.nereids.memo.GroupExpression;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.AssertNumRowsElement;
import org.apache.doris.nereids.trees.expressions.CTEId;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.WindowExpression;
import org.apache.doris.nereids.trees.expressions.functions.agg.AggregateFunction;
import org.apache.doris.nereids.trees.expressions.functions.agg.Count;
import org.apache.doris.nereids.trees.expressions.functions.agg.Max;
import org.apache.doris.nereids.trees.expressions.functions.agg.Min;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.Aggregate;
import org.apache.doris.nereids.trees.plans.algebra.CatalogRelation;
import org.apache.doris.nereids.trees.plans.algebra.EmptyRelation;
import org.apache.doris.nereids.trees.plans.algebra.Filter;
import org.apache.doris.nereids.trees.plans.algebra.Generate;
import org.apache.doris.nereids.trees.plans.algebra.Join;
import org.apache.doris.nereids.trees.plans.algebra.Limit;
import org.apache.doris.nereids.trees.plans.algebra.OlapScan;
import org.apache.doris.nereids.trees.plans.algebra.PartitionTopN;
import org.apache.doris.nereids.trees.plans.algebra.Project;
import org.apache.doris.nereids.trees.plans.algebra.Relation;
import org.apache.doris.nereids.trees.plans.algebra.Repeat;
import org.apache.doris.nereids.trees.plans.algebra.SetOperation;
import org.apache.doris.nereids.trees.plans.algebra.TopN;
import org.apache.doris.nereids.trees.plans.algebra.Union;
import org.apache.doris.nereids.trees.plans.algebra.Window;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEAnchor;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEConsumer;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEProducer;
import org.apache.doris.nereids.trees.plans.logical.LogicalCatalogRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalDeferMaterializeOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalDeferMaterializeTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalEsScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalExcept;
import org.apache.doris.nereids.trees.plans.logical.LogicalFileScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalGenerate;
import org.apache.doris.nereids.trees.plans.logical.LogicalHudiScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalIntersect;
import org.apache.doris.nereids.trees.plans.logical.LogicalJdbcScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalLimit;
import org.apache.doris.nereids.trees.plans.logical.LogicalOdbcScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalPartitionTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalRepeat;
import org.apache.doris.nereids.trees.plans.logical.LogicalSchemaScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalSink;
import org.apache.doris.nereids.trees.plans.logical.LogicalSort;
import org.apache.doris.nereids.trees.plans.logical.LogicalTVFRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalUnion;
import org.apache.doris.nereids.trees.plans.logical.LogicalWindow;
import org.apache.doris.nereids.trees.plans.physical.PhysicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.physical.PhysicalCTEAnchor;
import org.apache.doris.nereids.trees.plans.physical.PhysicalCTEConsumer;
import org.apache.doris.nereids.trees.plans.physical.PhysicalCTEProducer;
import org.apache.doris.nereids.trees.plans.physical.PhysicalDeferMaterializeOlapScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalDeferMaterializeTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalDistribute;
import org.apache.doris.nereids.trees.plans.physical.PhysicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalEsScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalExcept;
import org.apache.doris.nereids.trees.plans.physical.PhysicalFileScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalFilter;
import org.apache.doris.nereids.trees.plans.physical.PhysicalGenerate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalHashAggregate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalHashJoin;
import org.apache.doris.nereids.trees.plans.physical.PhysicalIntersect;
import org.apache.doris.nereids.trees.plans.physical.PhysicalJdbcScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalLimit;
import org.apache.doris.nereids.trees.plans.physical.PhysicalNestedLoopJoin;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOdbcScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOlapScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalPartitionTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalProject;
import org.apache.doris.nereids.trees.plans.physical.PhysicalQuickSort;
import org.apache.doris.nereids.trees.plans.physical.PhysicalRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalRepeat;
import org.apache.doris.nereids.trees.plans.physical.PhysicalSchemaScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalSink;
import org.apache.doris.nereids.trees.plans.physical.PhysicalStorageLayerAggregate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalTVFRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalUnion;
import org.apache.doris.nereids.trees.plans.physical.PhysicalWindow;
import org.apache.doris.nereids.trees.plans.visitor.DefaultPlanVisitor;
import org.apache.doris.nereids.types.DataType;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.SessionVariable;
import org.apache.doris.statistics.AnalysisManager;
import org.apache.doris.statistics.ColumnStatistic;
import org.apache.doris.statistics.ColumnStatisticBuilder;
import org.apache.doris.statistics.Histogram;
import org.apache.doris.statistics.PartitionColumnStatistic;
import org.apache.doris.statistics.PartitionColumnStatisticBuilder;
import org.apache.doris.statistics.StatisticConstants;
import org.apache.doris.statistics.StatisticRange;
import org.apache.doris.statistics.Statistics;
import org.apache.doris.statistics.StatisticsBuilder;
import org.apache.doris.statistics.StatisticsCache.OlapTableStatistics;
import org.apache.doris.statistics.TableStatsMeta;
import org.apache.doris.statistics.util.StatisticsUtil;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Used to calculate the stats for each plan
 */
public class StatsCalculator extends DefaultPlanVisitor<Statistics, Void> {
    public static double DEFAULT_AGGREGATE_RATIO = 1 / 3.0;
    public static double AGGREGATE_COLUMN_CORRELATION_COEFFICIENT = 0.75;
    public static double DEFAULT_COLUMN_NDV_RATIO = 0.5;

    public static StatsCalculator INSTANCE = new StatsCalculator(null);
    protected static final Logger LOG = LogManager.getLogger(StatsCalculator.class);
    protected final GroupExpression groupExpression;

    protected boolean forbidUnknownColStats = false;

    protected Map<String, ColumnStatistic> totalColumnStatisticMap = new HashMap<>();

    protected boolean isPlayNereidsDump = false;

    protected Map<String, Histogram> totalHistogramMap = new HashMap<>();

    protected Map<CTEId, Statistics> cteIdToStats;

    protected CascadesContext cascadesContext;
    protected ConnectContext connectContext = ConnectContext.get();

    public StatsCalculator(CascadesContext context) {
        this.groupExpression = null;
        this.cascadesContext = context;
    }

    /**
     * StatsCalculator
     * @param groupExpression group Expression
     * @param forbidUnknownColStats forbid UnknownColStats
     * @param columnStatisticMap columnStatisticMap
     * @param isPlayNereidsDump isPlayNereidsDump
     * @param cteIdToStats cteIdToStats
     * @param context CascadesContext
     */
    public StatsCalculator(GroupExpression groupExpression, boolean forbidUnknownColStats,
            Map<String, ColumnStatistic> columnStatisticMap, boolean isPlayNereidsDump,
            Map<CTEId, Statistics> cteIdToStats, CascadesContext context) {
        this.groupExpression = groupExpression;
        this.forbidUnknownColStats = forbidUnknownColStats;
        this.totalColumnStatisticMap = columnStatisticMap;
        this.isPlayNereidsDump = isPlayNereidsDump;
        this.cteIdToStats = Objects.requireNonNull(cteIdToStats, "CTEIdToStats can't be null");
        this.cascadesContext = context;
    }

    public Map<String, Histogram> getTotalHistogramMap() {
        return totalHistogramMap;
    }

    public void setTotalHistogramMap(Map<String, Histogram> totalHistogramMap) {
        this.totalHistogramMap = totalHistogramMap;
    }

    public Map<String, ColumnStatistic> getTotalColumnStatisticMap() {
        return totalColumnStatisticMap;
    }

    /**
     *
     * get the max row count of tables used in a query
     */
    public static double getMaxTableRowCount(List<LogicalCatalogRelation> scans, CascadesContext context) {
        StatsCalculator calculator = new StatsCalculator(context);
        double max = -1;
        for (LogicalCatalogRelation scan : scans) {
            double row;
            if (scan instanceof LogicalOlapScan) {
                row = calculator.getOlapTableRowCount((LogicalOlapScan) scan);
            } else {
                row = scan.getTable().getRowCount();
            }
            max = Math.max(row, max);
        }
        return max;
    }

    /**
     * disable join reorder if
     * 1. any table rowCount is not available, or
     * 2. col stats ndv=0 but minExpr or maxExpr is not null
     * 3. ndv > 10 * rowCount
     */
    public static Optional<String> disableJoinReorderIfStatsInvalid(List<CatalogRelation> scans,
            CascadesContext context) {
        StatsCalculator calculator = new StatsCalculator(context);
        if (ConnectContext.get() == null) {
            // ut case
            return Optional.empty();
        }
        boolean enableDebugLog = LOG.isDebugEnabled();
        for (CatalogRelation scan : scans) {
            double rowCount = calculator.getTableRowCount(scan);
            // row count not available
            if (rowCount == -1) {
                if (enableDebugLog) {
                    LOG.debug("disable join reorder since row count not available: "
                            + scan.getTable().getNameWithFullQualifiers());
                }
                return Optional.of("table[" + scan.getTable().getName() + "] row count is invalid");
            }
            if (scan instanceof OlapScan) {
                // ndv abnormal
                Optional<String> reason = calculator.checkNdvValidation((OlapScan) scan, rowCount);
                if (reason.isPresent()) {
                    try {
                        context.getConnectContext().getSessionVariable()
                                .setVarOnce(SessionVariable.DISABLE_JOIN_REORDER, "true");
                        if (enableDebugLog) {
                            LOG.debug("disable join reorder since col stats invalid: " + reason.get());
                        }
                    } catch (Exception e) {
                        LOG.error("disable NereidsJoinReorderOnce failed");
                    }
                    return reason;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * estimate
     */
    public void estimate() {
        Plan plan = groupExpression.getPlan();
        Statistics newStats;
        try {
            newStats = plan.accept(this, null);
        } catch (Exception e) {
            // throw exception in debug mode
            if (ConnectContext.get() != null && ConnectContext.get().getSessionVariable().feDebug) {
                throw e;
            }
            LOG.warn("stats calculation failed, plan " + plan.toString(), e);
            // use unknown stats or the first child's stats
            if (plan.children().isEmpty() || !(plan.child(0) instanceof GroupPlan)) {
                Map<Expression, ColumnStatistic> columnStatisticMap = new HashMap<>();
                for (Slot slot : plan.getOutput()) {
                    columnStatisticMap.put(slot, ColumnStatistic.createUnknownByDataType(slot.getDataType()));
                }
                newStats = new Statistics(1, 1, columnStatisticMap);
            } else {
                newStats = ((GroupPlan) plan.child(0)).getStats();
            }
        }
        newStats.normalizeColumnStatistics();

        // We ensure that the rowCount remains unchanged in order to make the cost of each plan comparable.
        if (groupExpression.getOwnerGroup().getStatistics() == null) {
            boolean isReliable = true;
            for (Expression expression : groupExpression.getPlan().getExpressions()) {
                if (newStats.isInputSlotsUnknown(expression.getInputSlots())) {
                    isReliable = false;
                    break;
                }
            }
            groupExpression.getOwnerGroup().setStatsReliable(isReliable);
            groupExpression.getOwnerGroup().setStatistics(newStats);
        } else {
            // the reason why we update col stats here.
            // consider join between 3 tables: A/B/C with join condition: A.id=B.id=C.id and a filter: C.id=1
            // in the final join result, the ndv of A.id/B.id/C.id should be 1
            // suppose we have 2 candidate plans
            // plan1: (A join B on A.id=B.id) join C on B.id=C.id
            // plan2:(B join C)join A
            // suppose plan1 is estimated before plan2
            //
            // after estimate the outer join of plan1 (join C), we update B.id.ndv=1, but A.id.ndv is not updated
            // then we estimate plan2. the stats of plan2 is denoted by stats2. obviously, stats2.A.id.ndv is 1
            // now we update OwnerGroup().getStatistics().A.id.ndv to 1
            groupExpression.getOwnerGroup().getStatistics().updateNdv(newStats);
        }
        groupExpression.setEstOutputRowCount(newStats.getRowCount());
        groupExpression.setStatDerived(true);
    }

    // For unit test only
    public static void estimate(GroupExpression groupExpression, CascadesContext context) {
        StatsCalculator statsCalculator = new StatsCalculator(groupExpression, false,
                new HashMap<>(), false, Collections.emptyMap(), context);
        statsCalculator.estimate();
    }

    @Override
    public Statistics visitLogicalSink(LogicalSink<? extends Plan> logicalSink, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitLogicalEmptyRelation(LogicalEmptyRelation emptyRelation, Void context) {
        return computeEmptyRelation(emptyRelation);
    }

    @Override
    public Statistics visitLogicalLimit(LogicalLimit<? extends Plan> limit, Void context) {
        return computeLimit(limit, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalLimit(PhysicalLimit<? extends Plan> limit, Void context) {
        return computeLimit(limit, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalOneRowRelation(LogicalOneRowRelation oneRowRelation, Void context) {
        return computeOneRowRelation(oneRowRelation.getProjects());
    }

    @Override
    public Statistics visitLogicalAggregate(LogicalAggregate<? extends Plan> aggregate, Void context) {
        return computeAggregate(aggregate, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalRepeat(LogicalRepeat<? extends Plan> repeat, Void context) {
        return computeRepeat(repeat, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalFilter(LogicalFilter<? extends Plan> filter, Void context) {
        return computeFilter(filter, groupExpression.childStatistics(0));
    }

    /**
     * returns the sum of deltaRowCount for all selected partitions or for the table.
     */
    private long computeDeltaRowCount(OlapScan olapScan) {
        AnalysisManager analysisManager = Env.getCurrentEnv().getAnalysisManager();
        TableStatsMeta tableMeta = analysisManager.findTableStatsStatus(olapScan.getTable().getId());
        long deltaRowCount = 0;
        if (tableMeta != null) {
            deltaRowCount = tableMeta.getBaseIndexDeltaRowCount(olapScan.getTable());
        }
        return deltaRowCount;
    }

    private ColumnStatistic getColumnStatsFromTableCache(CatalogRelation catalogRelation, SlotReference slot) {
        long idxId = -1;
        if (catalogRelation instanceof OlapScan) {
            idxId = ((OlapScan) catalogRelation).getSelectedIndexId();
        }
        return getColumnStatistic(catalogRelation.getTable(), slot.getName(), idxId);
    }

    /**
     * if get partition col stats failed, then return table level col stats
     */
    private ColumnStatistic getColumnStatsFromPartitionCacheOrTableCache(
            OlapTableStatistics olapTableStats, SlotReference slot, List<String> partitionNames) {
        return getColumnStatistic(olapTableStats, slot.getName(), partitionNames);
    }

    private double getSelectedPartitionRowCount(OlapScan olapScan, double tableRowCount) {
        // the number of partitions whose row count is not available
        double unknownPartitionCount = 0;
        double partRowCountSum = 0;
        for (long id : olapScan.getSelectedPartitionIds()) {
            long partRowCount = olapScan.getTable()
                    .getRowCountForPartitionIndex(id, olapScan.getSelectedIndexId(), true);
            if (partRowCount == -1) {
                unknownPartitionCount++;
            } else {
                partRowCountSum += partRowCount;
            }
        }
        // estimate row count for unknownPartitionCount
        if (unknownPartitionCount > 0) {
            // each selected partition has at least one row
            partRowCountSum += Math.max(unknownPartitionCount,
                    tableRowCount * unknownPartitionCount / olapScan.getTable().getPartitionNum());
        }
        return partRowCountSum;
    }

    private void setHasUnknownColStatsInStatementContext() {
        if (ConnectContext.get() != null && ConnectContext.get().getStatementContext() != null) {
            ConnectContext.get().getStatementContext().setHasUnknownColStats(true);
        }
    }

    private void checkIfUnknownStatsUsedAsKey(StatisticsBuilder builder) {
        if (ConnectContext.get() != null && ConnectContext.get().getStatementContext() != null) {
            for (Map.Entry<Expression, ColumnStatistic> entry : builder.getExpressionColumnStatsEntries()) {
                if (entry.getKey() instanceof SlotReference
                        && ConnectContext.get().getStatementContext().isKeySlot((SlotReference) entry.getKey())) {
                    if (entry.getValue().isUnKnown) {
                        ConnectContext.get().getStatementContext().setHasUnknownColStats(true);
                        break;
                    }
                }
            }
        }
    }

    private double getTableRowCount(CatalogRelation scan) {
        if (scan instanceof OlapScan) {
            return getOlapTableRowCount((OlapScan) scan);
        } else {
            return scan.getTable().getRowCount();
        }
    }

    private boolean isRegisteredRowCount(OlapScan olapScan) {
        AnalysisManager analysisManager = Env.getCurrentEnv().getAnalysisManager();
        TableStatsMeta tableMeta = analysisManager.findTableStatsStatus(olapScan.getTable().getId());
        return tableMeta != null && tableMeta.userInjected;
    }

    /**
     * if the table is not analyzed and BE does not report row count, return -1
     */
    private double getOlapTableRowCount(OlapScan olapScan) {
        OlapTable olapTable = olapScan.getTable();
        AnalysisManager analysisManager = Env.getCurrentEnv().getAnalysisManager();
        TableStatsMeta tableMeta = analysisManager.findTableStatsStatus(olapScan.getTable().getId());
        double rowCount = -1;
        if (tableMeta != null && tableMeta.userInjected) {
            rowCount = tableMeta.getRowCount(olapScan.getSelectedIndexId());
        } else {
            rowCount = olapTable.getRowCountForIndex(olapScan.getSelectedIndexId(), true);
            if (rowCount == -1) {
                if (tableMeta != null) {
                    rowCount = tableMeta.getRowCount(olapScan.getSelectedIndexId()) + computeDeltaRowCount(olapScan);
                }
            }
        }
        return rowCount;
    }

    // check validation of ndv.
    private Optional<String> checkNdvValidation(OlapScan olapScan, double rowCount) {
        OlapTableStatistics olapTableStats = Env.getCurrentEnv().getStatisticsCache().getOlapTableStats(olapScan);
        for (Slot slot : ((Plan) olapScan).getOutput()) {
            if (isVisibleSlotReference(slot)) {
                ColumnStatistic cache = olapTableStats.getColumnStatistics(slot.getName(), connectContext);
                if (!cache.isUnKnown) {
                    if ((cache.ndv == 0 && (cache.minExpr != null || cache.maxExpr != null))
                            || cache.ndv > rowCount * 10) {
                        return Optional.of("slot " + slot.getName() + " has invalid column stats: " + cache);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * computeOlapScan
     */
    public Statistics computeOlapScan(OlapScan olapScan) {
        OlapTable olapTable = olapScan.getTable();
        double tableRowCount = getOlapTableRowCount(olapScan);
        tableRowCount = Math.max(1, tableRowCount);

        OlapTableStatistics olapTableStats = Env.getCurrentEnv().getStatisticsCache().getOlapTableStats(olapScan);

        if (olapScan.getSelectedIndexId() != olapScan.getTable().getBaseIndexId() || olapTable instanceof MTMV) {
            // mv is selected, return its estimated stats
            Optional<Statistics> optStats = ConnectContext.get().getStatementContext()
                    .getStatistics(((Relation) olapScan).getRelationId());
            LOG.info("computeOlapScan optStats isPresent {}, tableRowCount is {}, table name is {}",
                    optStats.isPresent(), tableRowCount, olapTable.getQualifiedName());
            if (optStats.isPresent()) {
                double selectedPartitionsRowCount = getSelectedPartitionRowCount(olapScan, tableRowCount);
                LOG.info("computeOlapScan optStats is {}, selectedPartitionsRowCount is {}", optStats.get(),
                        selectedPartitionsRowCount);
                // if estimated mv rowCount is more than actual row count, fall back to base table stats
                if (selectedPartitionsRowCount >= optStats.get().getRowCount()) {
                    Statistics derivedStats = optStats.get();
                    double derivedRowCount = derivedStats.getRowCount();
                    for (Slot slot : ((Relation) olapScan).getOutput()) {
                        if (derivedStats.findColumnStatistics(slot) == null) {
                            derivedStats.addColumnStats(slot,
                                    new ColumnStatisticBuilder(ColumnStatistic.UNKNOWN, derivedRowCount).build());
                        }
                    }
                    return derivedStats;
                }
            }
        }

        StatisticsBuilder builder = new StatisticsBuilder();

        // for system table or FeUt, use ColumnStatistic.UNKNOWN
        if (StatisticConstants.isSystemTable(olapTable) || !FeConstants.enableInternalSchemaDb
                || ConnectContext.get() == null
                || ConnectContext.get().getState().isInternal()) {
            for (Slot slot : ((Plan) olapScan).getOutput()) {
                builder.putColumnStatistics(slot, ColumnStatistic.UNKNOWN);
            }
            setHasUnknownColStatsInStatementContext();
            builder.setRowCount(tableRowCount);
            return builder.build();
        }

        // for regression shape test
        if (ConnectContext.get() == null || !ConnectContext.get().getSessionVariable().enableStats) {
            // get row count from any visible slotReference's colStats
            for (Slot slot : ((Plan) olapScan).getOutput()) {
                builder.putColumnStatistics(slot,
                        new ColumnStatisticBuilder(ColumnStatistic.UNKNOWN, tableRowCount).build());
            }
            setHasUnknownColStatsInStatementContext();
            return builder.setRowCount(tableRowCount).build();
        }

        // build Stats for olapScan
        double deltaRowCount = computeDeltaRowCount(olapScan);
        builder.setDeltaRowCount(deltaRowCount);
        // if slot is invisible, use UNKNOWN
        List<SlotReference> visibleOutputSlots = new ArrayList<>();
        for (Slot slot : ((Plan) olapScan).getOutput()) {
            if (isVisibleSlotReference(slot)) {
                visibleOutputSlots.add((SlotReference) slot);
            } else {
                builder.putColumnStatistics(slot, ColumnStatistic.UNKNOWN);
            }
        }

        if (!isRegisteredRowCount(olapScan)
                && olapScan.getSelectedPartitionIds().size() < olapScan.getTable().getPartitionNum()) {
            // partition pruned
            // try to use selected partition stats, if failed, fall back to table stats
            double selectedPartitionsRowCount = getSelectedPartitionRowCount(olapScan, tableRowCount);
            List<String> selectedPartitionNames = new ArrayList<>(olapScan.getSelectedPartitionIds().size());
            olapScan.getSelectedPartitionIds().forEach(id -> {
                selectedPartitionNames.add(olapScan.getTable().getPartition(id).getName());
            });
            boolean enablePartitionStatics = connectContext != null
                    && connectContext.getSessionVariable().enablePartitionAnalyze;
            for (SlotReference slot : visibleOutputSlots) {
                ColumnStatistic cache;
                if (enablePartitionStatics) {
                    cache = getColumnStatsFromPartitionCacheOrTableCache(
                            olapTableStats, slot, selectedPartitionNames);
                } else {
                    cache = olapTableStats.getColumnStatistics(slot.getName(), connectContext);
                }
                if (slot.getOriginalColumn().isPresent()) {
                    cache = updateMinMaxForPartitionKey(olapTable, selectedPartitionNames, slot, cache);
                }
                ColumnStatisticBuilder colStatsBuilder = new ColumnStatisticBuilder(cache,
                        selectedPartitionsRowCount);
                colStatsBuilder.normalizeAvgSizeByte(slot);
                builder.putColumnStatistics(slot, colStatsBuilder.build());
            }
            checkIfUnknownStatsUsedAsKey(builder);
            builder.setRowCount(selectedPartitionsRowCount);
        } else {
            // get table level stats
            for (SlotReference slot : visibleOutputSlots) {
                ColumnStatistic cache = olapTableStats.getColumnStatistics(slot.getName(), connectContext);
                ColumnStatisticBuilder colStatsBuilder = new ColumnStatisticBuilder(cache, tableRowCount);
                colStatsBuilder.normalizeAvgSizeByte(slot);
                builder.putColumnStatistics(slot, colStatsBuilder.build());
            }
            checkIfUnknownStatsUsedAsKey(builder);
            builder.setRowCount(tableRowCount);
        }
        return builder.build();
    }

    /**
     * Determine whether it is a partition key inside the function.
     */
    private ColumnStatistic updateMinMaxForPartitionKey(OlapTable olapTable,
            List<String> selectedPartitionNames,
            SlotReference slot, ColumnStatistic cache) {
        if (olapTable.getPartitionType() == PartitionType.LIST) {
            cache = updateMinMaxForListPartitionKey(olapTable, selectedPartitionNames, slot, cache);
        } else if (olapTable.getPartitionType() == PartitionType.RANGE) {
            cache = updateMinMaxForTheFirstRangePartitionKey(olapTable, selectedPartitionNames, slot, cache);
        }
        return cache;
    }

    private double convertLegacyLiteralToDouble(LiteralExpr literal) throws org.apache.doris.common.AnalysisException {
        return StatisticsUtil.convertToDouble(literal.getType(), literal.getStringValue());
    }

    private ColumnStatistic updateMinMaxForListPartitionKey(OlapTable olapTable,
            List<String> selectedPartitionNames,
            SlotReference slot, ColumnStatistic cache) {
        int partitionColumnIdx = olapTable.getPartitionColumns().indexOf(slot.getOriginalColumn().get());
        if (partitionColumnIdx != -1) {
            try {
                LiteralExpr minExpr = null;
                LiteralExpr maxExpr = null;
                double minValue = 0;
                double maxValue = 0;
                for (String selectedPartitionName : selectedPartitionNames) {
                    PartitionItem item = olapTable.getPartitionItemOrAnalysisException(
                            selectedPartitionName);
                    if (item instanceof ListPartitionItem) {
                        ListPartitionItem lp = (ListPartitionItem) item;
                        for (PartitionKey key : lp.getItems()) {
                            if (minExpr == null) {
                                minExpr = key.getKeys().get(partitionColumnIdx);
                                minValue = convertLegacyLiteralToDouble(minExpr);
                                maxExpr = key.getKeys().get(partitionColumnIdx);
                                maxValue = convertLegacyLiteralToDouble(maxExpr);
                            } else {
                                double current = convertLegacyLiteralToDouble(key.getKeys().get(partitionColumnIdx));
                                if (current > maxValue) {
                                    maxValue = current;
                                    maxExpr = key.getKeys().get(partitionColumnIdx);
                                } else if (current < minValue) {
                                    minValue = current;
                                    minExpr = key.getKeys().get(partitionColumnIdx);
                                }
                            }
                        }
                    }
                }
                if (minExpr != null) {
                    cache = updateMinMax(cache, minValue, minExpr, maxValue, maxExpr);
                }
            } catch (org.apache.doris.common.AnalysisException e) {
                LOG.debug(e.getMessage());
            }
        }
        return cache;
    }

    private ColumnStatistic updateMinMaxForTheFirstRangePartitionKey(OlapTable olapTable,
            List<String> selectedPartitionNames,
            SlotReference slot, ColumnStatistic cache) {
        int partitionColumnIdx = olapTable.getPartitionColumns().indexOf(slot.getOriginalColumn().get());
        // for multi partition keys, only the first partition key need to adjust min/max
        if (partitionColumnIdx == 0) {
            // update partition column min/max by partition info
            try {
                LiteralExpr minExpr = null;
                LiteralExpr maxExpr = null;
                double minValue = 0;
                double maxValue = 0;
                for (String selectedPartitionName : selectedPartitionNames) {
                    PartitionItem item = olapTable.getPartitionItemOrAnalysisException(
                            selectedPartitionName);
                    if (item instanceof RangePartitionItem) {
                        RangePartitionItem ri = (RangePartitionItem) item;
                        Range<PartitionKey> range = ri.getItems();
                        PartitionKey upper = range.upperEndpoint();
                        PartitionKey lower = range.lowerEndpoint();
                        if (maxExpr == null) {
                            maxExpr = upper.getKeys().get(partitionColumnIdx);
                            maxValue = convertLegacyLiteralToDouble(maxExpr);
                            minExpr = lower.getKeys().get(partitionColumnIdx);
                            minValue = convertLegacyLiteralToDouble(minExpr);
                        } else {
                            double currentValue = convertLegacyLiteralToDouble(upper.getKeys()
                                    .get(partitionColumnIdx));
                            if (currentValue > maxValue) {
                                maxValue = currentValue;
                                maxExpr = upper.getKeys().get(partitionColumnIdx);
                            }
                            currentValue = convertLegacyLiteralToDouble(lower.getKeys().get(partitionColumnIdx));
                            if (currentValue < minValue) {
                                minValue = currentValue;
                                minExpr = lower.getKeys().get(partitionColumnIdx);
                            }
                        }
                    }
                }
                if (minExpr != null) {
                    cache = updateMinMax(cache, minValue, minExpr, maxValue, maxExpr);
                }
            } catch (org.apache.doris.common.AnalysisException e) {
                LOG.debug(e.getMessage());
            }
        }
        return cache;
    }

    private ColumnStatistic updateMinMax(ColumnStatistic cache, double minValue, LiteralExpr minExpr,
            double maxValue, LiteralExpr maxExpr) {
        boolean shouldUpdateCache = false;
        if (!cache.isUnKnown) {
            // merge the min/max with cache.
            // example: min/max range in cache is [10-20]
            // range from partition def is [15-30]
            // the final range is [15-20]
            if (cache.minValue > minValue) {
                minValue = cache.minValue;
                minExpr = cache.minExpr;
            } else {
                shouldUpdateCache = true;
            }
            if (cache.maxValue < maxValue) {
                maxValue = cache.maxValue;
                maxExpr = cache.maxExpr;
            } else {
                shouldUpdateCache = true;
            }
            // if min/max is invalid, do not update cache
            if (minValue > maxValue) {
                shouldUpdateCache = false;
            }
        }

        if (shouldUpdateCache) {
            cache = new ColumnStatisticBuilder(cache)
                    .setMinExpr(minExpr)
                    .setMinValue(minValue)
                    .setMaxExpr(maxExpr)
                    .setMaxValue(maxValue)
                    .build();
        }
        return cache;
    }

    @Override
    public Statistics visitLogicalOlapScan(LogicalOlapScan olapScan, Void context) {
        return computeOlapScan(olapScan);
    }

    private boolean isVisibleSlotReference(Slot slot) {
        if (slot instanceof SlotReference) {
            Optional<Column> colOpt = ((SlotReference) slot).getOriginalColumn();
            if (colOpt.isPresent()) {
                return colOpt.get().isVisible();
            }
        }
        return false;
    }

    @Override
    public Statistics visitLogicalDeferMaterializeOlapScan(LogicalDeferMaterializeOlapScan deferMaterializeOlapScan,
            Void context) {
        return computeOlapScan(deferMaterializeOlapScan.getLogicalOlapScan());
    }

    @Override
    public Statistics visitLogicalSchemaScan(LogicalSchemaScan schemaScan, Void context) {
        return computeCatalogRelation(schemaScan);
    }

    @Override
    public Statistics visitLogicalFileScan(LogicalFileScan fileScan, Void context) {
        return computeCatalogRelation(fileScan);
    }

    @Override
    public Statistics visitLogicalHudiScan(LogicalHudiScan fileScan, Void context) {
        return computeCatalogRelation(fileScan);
    }

    @Override
    public Statistics visitLogicalTVFRelation(LogicalTVFRelation tvfRelation, Void context) {
        return tvfRelation.getFunction().computeStats(tvfRelation.getOutput());
    }

    @Override
    public Statistics visitLogicalJdbcScan(LogicalJdbcScan jdbcScan, Void context) {
        jdbcScan.getExpressions();
        return computeCatalogRelation(jdbcScan);
    }

    @Override
    public Statistics visitLogicalOdbcScan(LogicalOdbcScan odbcScan, Void context) {
        odbcScan.getExpressions();
        return computeCatalogRelation(odbcScan);
    }

    @Override
    public Statistics visitLogicalEsScan(LogicalEsScan esScan, Void context) {
        esScan.getExpressions();
        return computeCatalogRelation(esScan);
    }

    @Override
    public Statistics visitLogicalProject(LogicalProject<? extends Plan> project, Void context) {
        return computeProject(project, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalSort(LogicalSort<? extends Plan> sort, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitLogicalTopN(LogicalTopN<? extends Plan> topN, Void context) {
        return computeTopN(topN, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalDeferMaterializeTopN(LogicalDeferMaterializeTopN<? extends Plan> topN, Void context) {
        return computeTopN(topN.getLogicalTopN(), groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalPartitionTopN(LogicalPartitionTopN<? extends Plan> partitionTopN, Void context) {
        return computePartitionTopN(partitionTopN, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalJoin(LogicalJoin<? extends Plan, ? extends Plan> join, Void context) {
        Statistics joinStats = computeJoin(join, groupExpression.childStatistics(0),
                groupExpression.childStatistics(1));
        // NOTE: physical operator visiting doesn't need the following
        // logic which will ONLY be used in no-stats estimation.
        joinStats = new StatisticsBuilder(joinStats).setWidthInJoinCluster(
                groupExpression.childStatistics(0).getWidthInJoinCluster()
                        + groupExpression.childStatistics(1).getWidthInJoinCluster()).build();
        return joinStats;
    }

    @Override
    public Statistics visitLogicalAssertNumRows(
            LogicalAssertNumRows<? extends Plan> assertNumRows, Void context) {
        return computeAssertNumRows(assertNumRows.getAssertNumRowsElement(), groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalUnion(
            LogicalUnion union, Void context) {
        return computeUnion(union,
                groupExpression.children()
                .stream().map(Group::getStatistics).collect(Collectors.toList()));
    }

    @Override
    public Statistics visitLogicalExcept(
            LogicalExcept except, Void context) {
        return computeExcept(except, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalIntersect(
            LogicalIntersect intersect, Void context) {
        List<Statistics> childrenStats = new ArrayList<>();
        for (int i = 0; i < intersect.arity(); i++) {
            childrenStats.add(groupExpression.childStatistics(i));
        }
        return computeIntersect(intersect, childrenStats);
    }

    @Override
    public Statistics visitLogicalGenerate(LogicalGenerate<? extends Plan> generate, Void context) {
        return computeGenerate(generate, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitLogicalWindow(LogicalWindow<? extends Plan> window, Void context) {
        return computeWindow(window, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalSink(PhysicalSink<? extends Plan> physicalSink, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitPhysicalWindow(PhysicalWindow window, Void context) {
        return computeWindow(window, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalPartitionTopN(PhysicalPartitionTopN partitionTopN, Void context) {
        return computePartitionTopN(partitionTopN, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalEmptyRelation(PhysicalEmptyRelation emptyRelation, Void context) {
        return computeEmptyRelation(emptyRelation);
    }

    @Override
    public Statistics visitPhysicalHashAggregate(PhysicalHashAggregate<? extends Plan> agg, Void context) {
        return computeAggregate(agg, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalRepeat(PhysicalRepeat<? extends Plan> repeat, Void context) {
        return computeRepeat(repeat, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalOneRowRelation(PhysicalOneRowRelation oneRowRelation, Void context) {
        return computeOneRowRelation(oneRowRelation.getProjects());
    }

    @Override
    public Statistics visitPhysicalOlapScan(PhysicalOlapScan olapScan, Void context) {
        return computeOlapScan(olapScan);
    }

    @Override
    public Statistics visitPhysicalDeferMaterializeOlapScan(PhysicalDeferMaterializeOlapScan deferMaterializeOlapScan,
            Void context) {
        return computeCatalogRelation(deferMaterializeOlapScan.getPhysicalOlapScan());
    }

    @Override
    public Statistics visitPhysicalSchemaScan(PhysicalSchemaScan schemaScan, Void context) {
        return computeCatalogRelation(schemaScan);
    }

    @Override
    public Statistics visitPhysicalFileScan(PhysicalFileScan fileScan, Void context) {
        return computeCatalogRelation(fileScan);
    }

    @Override
    public Statistics visitPhysicalStorageLayerAggregate(
            PhysicalStorageLayerAggregate storageLayerAggregate, Void context) {
        PhysicalRelation relation = storageLayerAggregate.getRelation();
        return relation.accept(this, context);

    }

    @Override
    public Statistics visitPhysicalTVFRelation(PhysicalTVFRelation tvfRelation, Void context) {
        return tvfRelation.getFunction().computeStats(tvfRelation.getOutput());
    }

    @Override
    public Statistics visitPhysicalJdbcScan(PhysicalJdbcScan jdbcScan, Void context) {
        return computeCatalogRelation(jdbcScan);
    }

    @Override
    public Statistics visitPhysicalOdbcScan(PhysicalOdbcScan odbcScan, Void context) {
        return computeCatalogRelation(odbcScan);
    }

    @Override
    public Statistics visitPhysicalEsScan(PhysicalEsScan esScan, Void context) {
        return computeCatalogRelation(esScan);
    }

    @Override
    public Statistics visitPhysicalQuickSort(PhysicalQuickSort<? extends Plan> sort, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitPhysicalTopN(PhysicalTopN<? extends Plan> topN, Void context) {
        return computeTopN(topN, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalDeferMaterializeTopN(PhysicalDeferMaterializeTopN<? extends Plan> topN,
            Void context) {
        return computeTopN(topN.getPhysicalTopN(), groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalHashJoin(
            PhysicalHashJoin<? extends Plan, ? extends Plan> hashJoin, Void context) {
        return computeJoin(hashJoin, groupExpression.childStatistics(0), groupExpression.childStatistics(1));
    }

    @Override
    public Statistics visitPhysicalNestedLoopJoin(
            PhysicalNestedLoopJoin<? extends Plan, ? extends Plan> nestedLoopJoin,
            Void context) {
        return computeJoin(nestedLoopJoin, groupExpression.childStatistics(0),
                groupExpression.childStatistics(1));
    }

    // TODO: We should subtract those pruned column, and consider the expression transformations in the node.
    @Override
    public Statistics visitPhysicalProject(PhysicalProject<? extends Plan> project, Void context) {
        return computeProject(project, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalFilter(PhysicalFilter<? extends Plan> filter, Void context) {
        return computeFilter(filter, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalDistribute(PhysicalDistribute<? extends Plan> distribute,
            Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitPhysicalAssertNumRows(PhysicalAssertNumRows<? extends Plan> assertNumRows,
            Void context) {
        return computeAssertNumRows(assertNumRows.getAssertNumRowsElement(), groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalUnion(PhysicalUnion union, Void context) {
        return computeUnion(union, groupExpression.children()
                .stream().map(Group::getStatistics).collect(Collectors.toList()));
    }

    @Override
    public Statistics visitPhysicalExcept(PhysicalExcept except, Void context) {
        return computeExcept(except, groupExpression.childStatistics(0));
    }

    @Override
    public Statistics visitPhysicalIntersect(PhysicalIntersect intersect, Void context) {
        List<Statistics> childrenStats = new ArrayList<>();
        for (int i = 0; i < intersect.arity(); i++) {
            childrenStats.add(groupExpression.childStatistics(i));
        }
        return computeIntersect(intersect, childrenStats);
    }

    @Override
    public Statistics visitPhysicalGenerate(PhysicalGenerate<? extends Plan> generate, Void context) {
        return computeGenerate(generate, groupExpression.childStatistics(0));
    }

    /**
     * computeAssertNumRows
     */
    public Statistics computeAssertNumRows(AssertNumRowsElement assertNumRowsElement, Statistics inputStats) {
        long newRowCount;
        long rowCount = (long) inputStats.getRowCount();
        long desiredNumOfRows = assertNumRowsElement.getDesiredNumOfRows();
        switch (assertNumRowsElement.getAssertion()) {
            case EQ:
                newRowCount = desiredNumOfRows;
                break;
            case GE:
                newRowCount = inputStats.getRowCount() >= desiredNumOfRows ? rowCount : desiredNumOfRows;
                break;
            case GT:
                newRowCount = inputStats.getRowCount() > desiredNumOfRows ? rowCount : desiredNumOfRows;
                break;
            case LE:
                newRowCount = inputStats.getRowCount() <= desiredNumOfRows ? rowCount : desiredNumOfRows;
                break;
            case LT:
                newRowCount = inputStats.getRowCount() < desiredNumOfRows ? rowCount : desiredNumOfRows;
                break;
            case NE:
                return inputStats;
            default:
                throw new IllegalArgumentException("Unknown assertion: " + assertNumRowsElement.getAssertion());
        }
        Statistics newStatistics = inputStats.withRowCountAndEnforceValid(newRowCount);
        return new StatisticsBuilder(newStatistics).setWidthInJoinCluster(1).build();
    }

    /**
     * computeFilter
     */
    public Statistics computeFilter(Filter filter, Statistics inputStats) {
        return new FilterEstimation().estimate(filter.getPredicate(), inputStats);
    }

    private ColumnStatistic getColumnStatistic(TableIf table, String colName, long idxId) {
        if (connectContext != null && connectContext.getState().isInternal()) {
            return ColumnStatistic.UNKNOWN;
        }
        long catalogId;
        long dbId;
        try {
            DatabaseIf database = table.getDatabase();
            catalogId = database.getCatalog().getId();
            dbId = database.getId();
        } catch (Exception e) {
            // Use -1 for catalog id and db id when failed to get them from metadata.
            // This is OK because catalog id and db id is not in the hashcode function of ColumnStatistics cache
            // and the table id is globally unique.
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Fail to get catalog id and db id for table %s", table.getName()));
            }
            catalogId = -1;
            dbId = -1;
        }
        ColumnStatistic columnStatistics = Env.getCurrentEnv().getStatisticsCache().getColumnStatistics(
                catalogId, dbId, table.getId(), idxId, colName, connectContext);
        if (!columnStatistics.isUnKnown
                && columnStatistics.ndv == 0
                && (columnStatistics.minExpr != null || columnStatistics.maxExpr != null)
                && columnStatistics.numNulls == columnStatistics.count) {
            return ColumnStatistic.UNKNOWN;
        }
        return columnStatistics;
    }

    private ColumnStatistic getColumnStatistic(
            OlapTableStatistics olapTableStatistics, String colName, List<String> partitionNames) {
        if (connectContext != null && connectContext.getState().isInternal()) {
            return ColumnStatistic.UNKNOWN;
        }
        OlapTable table = olapTableStatistics.olapTable;
        if (isPlayNereidsDump) {
            if (totalColumnStatisticMap.get(table.getName() + colName) != null) {
                return totalColumnStatisticMap.get(table.getName() + colName);
            } else {
                return ColumnStatistic.UNKNOWN;
            }
        } else {
            if (!partitionNames.isEmpty()) {
                PartitionColumnStatisticBuilder builder = new PartitionColumnStatisticBuilder();
                boolean hasUnknown = false;
                // check if there is any unknown stats to avoid unnecessary partition column stats merge.
                List<PartitionColumnStatistic> pColStatsLists = new ArrayList<>(partitionNames.size());
                for (String partitionName : partitionNames) {
                    PartitionColumnStatistic pcolStats
                            = olapTableStatistics.getPartitionColumnStatistics(partitionName, colName, connectContext);
                    if (pcolStats.isUnKnown) {
                        hasUnknown = true;
                        break;
                    } else {
                        pColStatsLists.add(pcolStats);
                    }
                }
                if (!hasUnknown) {
                    boolean isFirst = true;
                    // try to merge partition column stats
                    for (PartitionColumnStatistic pcolStats : pColStatsLists) {
                        if (isFirst) {
                            builder = new PartitionColumnStatisticBuilder(pcolStats);
                            isFirst = false;
                        } else {
                            builder.merge(pcolStats);
                        }
                    }
                    return builder.toColumnStatistics();
                }
            }

            // if any partition-col-stats is unknown, fall back to table level col stats
            return olapTableStatistics.getColumnStatistics(colName, connectContext);
        }
    }

    /**
     * compute stats for catalogRelations except OlapScan
     */
    public Statistics computeCatalogRelation(CatalogRelation catalogRelation) {
        StatisticsBuilder builder = new StatisticsBuilder();
        double tableRowCount = catalogRelation.getTable().getRowCount();
        // for FeUt, use ColumnStatistic.UNKNOWN
        if (!FeConstants.enableInternalSchemaDb
                || ConnectContext.get() == null
                || ConnectContext.get().getState().isInternal()) {
            builder.setRowCount(Math.max(1, tableRowCount));
            for (Slot slot : catalogRelation.getOutput()) {
                builder.putColumnStatistics(slot, ColumnStatistic.UNKNOWN);
            }
            setHasUnknownColStatsInStatementContext();
            return builder.build();
        }

        List<Slot> output = catalogRelation.getOutput();
        ImmutableSet.Builder<SlotReference> slotSetBuilder = ImmutableSet.builderWithExpectedSize(output.size());
        for (Slot slot : output) {
            if (slot instanceof SlotReference) {
                slotSetBuilder.add((SlotReference) slot);
            }
        }
        Set<SlotReference> slotSet = slotSetBuilder.build();
        if (tableRowCount <= 0) {
            tableRowCount = 1;
            // try to get row count from col stats
            for (SlotReference slot : slotSet) {
                ColumnStatistic cache = getColumnStatsFromTableCache(catalogRelation, slot);
                tableRowCount = Math.max(cache.count, tableRowCount);
            }
        }
        for (SlotReference slot : slotSet) {
            ColumnStatistic cache;
            if (ConnectContext.get() != null && ! ConnectContext.get().getSessionVariable().enableStats) {
                cache = ColumnStatistic.UNKNOWN;
            } else {
                cache = getColumnStatsFromTableCache(catalogRelation, slot);
            }
            ColumnStatisticBuilder colStatsBuilder = new ColumnStatisticBuilder(cache, tableRowCount);
            builder.putColumnStatistics(slot, colStatsBuilder.build());
        }
        checkIfUnknownStatsUsedAsKey(builder);
        return builder.setRowCount(tableRowCount).build();
    }

    /**
     * computeJoin
     */
    public Statistics computeJoin(Join join, Statistics leftStats, Statistics rightStats) {
        return JoinEstimation.estimate(leftStats, rightStats, join);
    }

    /**
     * computeTopN
     */
    public Statistics computeTopN(TopN topN, Statistics inputStats) {
        return inputStats.withRowCountAndEnforceValid(Math.min(inputStats.getRowCount(), topN.getLimit()));
    }

    /**
     * computePartitionTopN
     */
    public Statistics computePartitionTopN(PartitionTopN partitionTopN, Statistics inputStats) {
        double rowCount = inputStats.getRowCount();
        List<Expression> partitionKeys = partitionTopN.getPartitionKeys();
        if (!partitionTopN.hasGlobalLimit() && !partitionKeys.isEmpty()) {
            // If there is no global limit. So result for the cardinality estimation is:
            // NDV(partition key) * partitionLimit
            List<ColumnStatistic> partitionByKeyStats = partitionKeys.stream()
                    .map(partitionKey -> {
                        ColumnStatistic partitionKeyStats = inputStats.findColumnStatistics(partitionKey);
                        if (partitionKeyStats == null) {
                            partitionKeyStats = new ExpressionEstimation().visit(partitionKey, inputStats);
                        }
                        return partitionKeyStats;
                    })
                    .filter(s -> !s.isUnKnown)
                    .collect(Collectors.toList());
            if (partitionByKeyStats.isEmpty()) {
                // all column stats are unknown, use default ratio
                rowCount = rowCount * DEFAULT_COLUMN_NDV_RATIO;
            } else {
                rowCount = Math.min(rowCount, partitionByKeyStats.stream().map(s -> s.ndv)
                        .max(Double::compare).get() * partitionTopN.getPartitionLimit());
            }
        } else {
            rowCount = Math.min(rowCount, partitionTopN.getPartitionLimit());
        }
        // TODO: for the filter push down window situation, we will prune the row count twice
        //  because we keep the pushed down filter. And it will be calculated twice, one of them in 'PartitionTopN'
        //  and the other is in 'Filter'. It's hard to dismiss.
        return inputStats.withRowCountAndEnforceValid(rowCount);
    }

    /**
     * computeLimit
     */
    public Statistics computeLimit(Limit limit, Statistics inputStats) {
        return inputStats.withRowCountAndEnforceValid(Math.min(inputStats.getRowCount(), limit.getLimit()));
    }

    /**
     * computeAggregate
     */
    private double estimateGroupByRowCount(List<Expression> groupByExpressions, Statistics childStats) {
        double rowCount = 1;
        // if there is group-bys, output row count is childStats.getRowCount() * DEFAULT_AGGREGATE_RATIO,
        // o.w. output row count is 1
        // example: select sum(A) from T;
        if (groupByExpressions.isEmpty()) {
            return 1;
        }
        List<Double> groupByNdvs = new ArrayList<>();
        for (Expression groupByExpr : groupByExpressions) {
            ColumnStatistic colStats = childStats.findColumnStatistics(groupByExpr);
            if (colStats == null) {
                colStats = ExpressionEstimation.estimate(groupByExpr, childStats);
            }
            if (colStats.isUnKnown()) {
                rowCount = childStats.getRowCount() * DEFAULT_AGGREGATE_RATIO;
                rowCount = Math.max(1, rowCount);
                rowCount = Math.min(rowCount, childStats.getRowCount());
                return rowCount;
            }
            double ndv = colStats.ndv;
            groupByNdvs.add(ndv);
        }
        groupByNdvs.sort(Collections.reverseOrder());

        rowCount = groupByNdvs.get(0);
        for (int groupByIndex = 1; groupByIndex < groupByExpressions.size(); ++groupByIndex) {
            rowCount *= Math.max(1, groupByNdvs.get(groupByIndex) * Math.pow(
                    AGGREGATE_COLUMN_CORRELATION_COEFFICIENT, groupByIndex + 1D));
            if (rowCount > childStats.getRowCount()) {
                rowCount = childStats.getRowCount();
                break;
            }
        }
        rowCount = Math.max(1, rowCount);
        rowCount = Math.min(rowCount, childStats.getRowCount());
        return rowCount;
    }

    /**
     * computeAggregate
     */
    public Statistics computeAggregate(Aggregate<? extends Plan> aggregate, Statistics childStats) {
        List<Expression> groupByExpressions = aggregate.getGroupByExpressions();
        double rowCount = estimateGroupByRowCount(groupByExpressions, childStats);
        Map<Expression, ColumnStatistic> slotToColumnStats = Maps.newHashMap();
        List<NamedExpression> outputExpressions = aggregate.getOutputExpressions();
        // TODO: 1. Estimate the output unit size by the type of corresponding AggregateFunction
        //       2. Handle alias, literal in the output expression list
        double factor = childStats.getRowCount() / rowCount;
        for (NamedExpression outputExpression : outputExpressions) {
            ColumnStatistic columnStat = ExpressionEstimation.estimate(outputExpression, childStats);
            ColumnStatisticBuilder builder = new ColumnStatisticBuilder(columnStat);
            builder.setMinValue(columnStat.minValue / factor);
            builder.setMaxValue(columnStat.maxValue / factor);
            if (columnStat.ndv > rowCount) {
                builder.setNdv(rowCount);
            }
            builder.setDataSize(rowCount * outputExpression.getDataType().width());
            slotToColumnStats.put(outputExpression.toSlot(), columnStat);
        }
        Statistics aggOutputStats = new Statistics(rowCount, 1, slotToColumnStats);
        aggOutputStats.normalizeColumnStatistics();
        return aggOutputStats;
    }

    /**
     * computeRepeat
     */
    public Statistics computeRepeat(Repeat<? extends Plan> repeat, Statistics childStats) {
        Map<Expression, ColumnStatistic> slotIdToColumnStats = childStats.columnStatistics();
        int groupingSetNum = repeat.getGroupingSets().size();
        double rowCount = childStats.getRowCount();
        Map<Expression, ColumnStatistic> columnStatisticMap = slotIdToColumnStats.entrySet()
                .stream().map(kv -> {
                    ColumnStatistic stats = kv.getValue();
                    ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder(stats);
                    columnStatisticBuilder
                            .setNumNulls(stats.numNulls < 0 ? stats.numNulls : stats.numNulls * groupingSetNum)
                            .setDataSize(stats.dataSize < 0 ? stats.dataSize : stats.dataSize * groupingSetNum);
                    return Pair.of(kv.getKey(), columnStatisticBuilder.build());
                }).collect(Collectors.toMap(Pair::key, Pair::value, (item1, item2) -> item1));
        return new Statistics(rowCount < 0 ? rowCount : rowCount * groupingSetNum, 1, columnStatisticMap);
    }

    /**
     * computeProject
     */
    public Statistics computeProject(Project project, Statistics childStats) {
        List<NamedExpression> projections = project.getProjects();
        Map<Expression, ColumnStatistic> projectionStats = new LinkedHashMap<>(projections.size());
        for (NamedExpression projection : projections) {
            ColumnStatistic columnStatistic = ExpressionEstimation.estimate(projection, childStats);
            projectionStats.putIfAbsent(projection.toSlot(), columnStatistic);
        }
        return new Statistics(childStats.getRowCount(), childStats.getWidthInJoinCluster(), projectionStats);
    }

    /**
     * computeOneRowRelation
     */
    public Statistics computeOneRowRelation(List<NamedExpression> projects) {
        Map<Expression, ColumnStatistic> columnStatsMap = projects.stream()
                .map(project -> {
                    ColumnStatistic statistic = new ColumnStatisticBuilder().setNdv(1).build();
                    // TODO: compute the literal size
                    return Pair.of(project.toSlot(), statistic);
                })
                .collect(Collectors.toMap(Pair::key, Pair::value, (item1, item2) -> item1));
        int rowCount = 1;
        return new Statistics(rowCount, 1, columnStatsMap);
    }

    /**
     * computeEmptyRelation
     */
    public Statistics computeEmptyRelation(EmptyRelation emptyRelation) {
        Map<Expression, ColumnStatistic> columnStatsMap = emptyRelation.getProjects()
                .stream()
                .map(project -> {
                    ColumnStatisticBuilder columnStat = new ColumnStatisticBuilder()
                            .setNdv(0)
                            .setNumNulls(0)
                            .setAvgSizeByte(0);
                    return Pair.of(project.toSlot(), columnStat.build());
                })
                .collect(Collectors.toMap(Pair::key, Pair::value, (item1, item2) -> item1));
        int rowCount = 0;
        return new Statistics(rowCount, 1, columnStatsMap);
    }

    /**
     * computeUnion
     */
    public Statistics computeUnion(Union union, List<Statistics> childStats) {
        // TODO: refactor this for one row relation
        List<SlotReference> head;
        Statistics headStats;
        List<List<SlotReference>> childOutputs = Lists.newArrayList(union.getRegularChildrenOutputs());

        if (!union.getConstantExprsList().isEmpty()) {
            childOutputs.addAll(union.getConstantExprsList().stream()
                    .map(l -> l.stream().map(NamedExpression::toSlot)
                            .map(SlotReference.class::cast)
                            .collect(Collectors.toList()))
                    .collect(Collectors.toList()));
            childStats.addAll(union.getConstantExprsList().stream()
                    .map(this::computeOneRowRelation)
                    .collect(Collectors.toList()));
        }

        head = childOutputs.get(0);
        headStats = childStats.get(0);

        StatisticsBuilder statisticsBuilder = new StatisticsBuilder();
        List<NamedExpression> unionOutput = union.getOutputs();
        for (int i = 0; i < head.size(); i++) {
            double leftRowCount = headStats.getRowCount();
            Slot headSlot = head.get(i);
            for (int j = 1; j < childOutputs.size(); j++) {
                Slot slot = childOutputs.get(j).get(i);
                ColumnStatistic rightStatistic = childStats.get(j).findColumnStatistics(slot);
                double rightRowCount = childStats.get(j).getRowCount();
                ColumnStatistic estimatedColumnStatistics
                        = unionColumn(headStats.findColumnStatistics(headSlot),
                        headStats.getRowCount(), rightStatistic, rightRowCount, headSlot.getDataType());
                headStats.addColumnStats(headSlot, estimatedColumnStatistics);
                leftRowCount += childStats.get(j).getRowCount();
            }
            statisticsBuilder.setRowCount(leftRowCount);
            statisticsBuilder.putColumnStatistics(unionOutput.get(i), headStats.findColumnStatistics(headSlot));
        }
        return statisticsBuilder.setWidthInJoinCluster(1).build();
    }

    /**
     * computeExcept
     */
    public Statistics computeExcept(SetOperation setOperation, Statistics leftStats) {
        List<NamedExpression> operatorOutput = setOperation.getOutputs();
        List<SlotReference> childSlots = setOperation.getRegularChildOutput(0);
        StatisticsBuilder statisticsBuilder = new StatisticsBuilder();
        for (int i = 0; i < operatorOutput.size(); i++) {
            ColumnStatistic columnStatistic = leftStats.findColumnStatistics(childSlots.get(i));
            statisticsBuilder.putColumnStatistics(operatorOutput.get(i), columnStatistic);
        }
        statisticsBuilder.setRowCount(leftStats.getRowCount());
        return statisticsBuilder.setWidthInJoinCluster(1).build();
    }

    /**
     * computeIntersect
     */
    public Statistics computeIntersect(SetOperation setOperation, List<Statistics> childrenStats) {
        Statistics leftChildStats = childrenStats.get(0);
        Preconditions.checkArgument(leftChildStats != null, "Intersect: " + setOperation
                + " child stats is null");
        double rowCount = leftChildStats.getRowCount();
        for (int i = 1; i < setOperation.getArity(); ++i) {
            rowCount = Math.min(rowCount, childrenStats.get(i).getRowCount());
        }
        double minProd = Double.POSITIVE_INFINITY;
        for (Statistics childStats : childrenStats) {
            double prod = 1.0;
            for (ColumnStatistic columnStatistic : childStats.columnStatistics().values()) {
                prod *= columnStatistic.ndv;
            }
            if (minProd < prod) {
                minProd = prod;
            }
        }
        rowCount = Math.min(rowCount, minProd);
        List<NamedExpression> outputs = setOperation.getOutputs();
        List<SlotReference> leftChildOutputs = setOperation.getRegularChildOutput(0);
        for (int i = 0; i < outputs.size(); i++) {
            leftChildStats.addColumnStats(outputs.get(i),
                    leftChildStats.findColumnStatistics(leftChildOutputs.get(i)));
        }
        return new StatisticsBuilder(leftChildStats.withRowCountAndEnforceValid(rowCount))
                .setWidthInJoinCluster(1).build();
    }

    /**
     * computeGenerate
     */
    public Statistics computeGenerate(Generate generate, Statistics inputStats) {
        int statsFactor = ConnectContext.get().getSessionVariable().generateStatsFactor;
        double count = inputStats.getRowCount() * generate.getGeneratorOutput().size() * statsFactor;
        Map<Expression, ColumnStatistic> columnStatsMap = Maps.newHashMap();
        for (Map.Entry<Expression, ColumnStatistic> entry : inputStats.columnStatistics().entrySet()) {
            ColumnStatistic columnStatistic = new ColumnStatisticBuilder(entry.getValue()).build();
            columnStatsMap.put(entry.getKey(), columnStatistic);
        }
        for (Slot output : generate.getGeneratorOutput()) {
            ColumnStatistic columnStatistic = new ColumnStatisticBuilder()
                    .setMinValue(Double.NEGATIVE_INFINITY)
                    .setMaxValue(Double.POSITIVE_INFINITY)
                    .setNdv(count)
                    .setNumNulls(0)
                    .setAvgSizeByte(output.getDataType().width())
                    .build();
            columnStatsMap.put(output, columnStatistic);
        }
        return new Statistics(count, 1, columnStatsMap);
    }

    /**
     * computeWindow
     */
    public Statistics computeWindow(Window windowOperator, Statistics childStats) {
        Map<Expression, ColumnStatistic> childColumnStats = childStats.columnStatistics();
        Map<Expression, ColumnStatistic> columnStatisticMap = windowOperator.getWindowExpressions().stream()
                .map(expr -> {
                    Preconditions.checkArgument(expr instanceof Alias
                                    && expr.child(0) instanceof WindowExpression,
                            "need WindowExpression, but we meet " + expr);
                    WindowExpression windExpr = (WindowExpression) expr.child(0);
                    ColumnStatisticBuilder colStatsBuilder = new ColumnStatisticBuilder();
                    colStatsBuilder.setOriginal(null);

                    Double partitionCount = windExpr.getPartitionKeys().stream().map(key -> {
                        ColumnStatistic keyStats = childStats.findColumnStatistics(key);
                        if (keyStats == null) {
                            keyStats = new ExpressionEstimation().visit(key, childStats);
                        }
                        return keyStats;
                    })
                            .filter(columnStatistic -> !columnStatistic.isUnKnown)
                            .map(colStats -> colStats.ndv).max(Double::compare)
                            .orElseGet(() -> -1.0);

                    if (partitionCount == -1.0) {
                        // partition key stats are all unknown
                        colStatsBuilder.setNdv(1)
                                .setMinValue(Double.NEGATIVE_INFINITY)
                                .setMaxValue(Double.POSITIVE_INFINITY);
                    } else {
                        partitionCount = Math.max(1, partitionCount);
                        if (windExpr.getFunction() instanceof AggregateFunction) {
                            if (windExpr.getFunction() instanceof Count) {
                                colStatsBuilder.setNdv(1)
                                        .setMinValue(0)
                                        .setMinExpr(new IntLiteral(0))
                                        .setMaxValue(childStats.getRowCount())
                                        .setMaxExpr(new IntLiteral((long) childStats.getRowCount()));
                            } else if (windExpr.getFunction() instanceof Min
                                    || windExpr.getFunction() instanceof Max) {
                                Expression minmaxChild = windExpr.getFunction().child(0);
                                ColumnStatistic minChildStats = new ExpressionEstimation()
                                        .visit(minmaxChild, childStats);
                                colStatsBuilder.setNdv(1)
                                        .setMinValue(minChildStats.minValue)
                                        .setMinExpr(minChildStats.minExpr)
                                        .setMaxValue(minChildStats.maxValue)
                                        .setMaxExpr(minChildStats.maxExpr);
                            } else {
                                // sum/avg
                                colStatsBuilder.setNdv(1).setMinValue(Double.NEGATIVE_INFINITY)
                                        .setMaxValue(Double.POSITIVE_INFINITY);
                            }
                        } else {
                            // rank/dense_rank/row_num ...
                            colStatsBuilder.setNdv(childStats.getRowCount() / partitionCount)
                                    .setMinValue(0)
                                    .setMinExpr(new IntLiteral(0))
                                    .setMaxValue(childStats.getRowCount())
                                    .setMaxExpr(new IntLiteral((long) childStats.getRowCount()));
                        }
                    }
                    return Pair.of(expr.toSlot(), colStatsBuilder.build());
                }).collect(Collectors.toMap(Pair::key, Pair::value, (item1, item2) -> item1));
        columnStatisticMap.putAll(childColumnStats);
        return new Statistics(childStats.getRowCount(), 1, columnStatisticMap);
    }

    private ColumnStatistic unionColumn(ColumnStatistic leftStats, double leftRowCount, ColumnStatistic rightStats,
            double rightRowCount, DataType dataType) {
        if (leftStats.isUnKnown() || rightStats.isUnKnown()) {
            return new ColumnStatisticBuilder(leftStats).build();
        }
        ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder();
        columnStatisticBuilder.setMaxValue(Math.max(leftStats.maxValue, rightStats.maxValue));
        columnStatisticBuilder.setMinValue(Math.min(leftStats.minValue, rightStats.minValue));
        StatisticRange leftRange = StatisticRange.from(leftStats, dataType);
        StatisticRange rightRange = StatisticRange.from(rightStats, dataType);
        StatisticRange newRange = leftRange.union(rightRange);
        double newRowCount = leftRowCount + rightRowCount;
        double leftSize = (leftRowCount - leftStats.numNulls) * leftStats.avgSizeByte;
        double rightSize = (rightRowCount - rightStats.numNulls) * rightStats.avgSizeByte;
        double newNullFraction = (leftStats.numNulls + rightStats.numNulls) / StatsMathUtil.maxNonNaN(1, newRowCount);
        double newNonNullRowCount = newRowCount * (1 - newNullFraction);

        double newAverageRowSize = newNonNullRowCount == 0 ? 0 : (leftSize + rightSize) / newNonNullRowCount;
        columnStatisticBuilder.setMinValue(newRange.getLow())
                .setMaxValue(newRange.getHigh())
                .setNdv(newRange.getDistinctValues())
                .setNumNulls(leftStats.numNulls + rightStats.numNulls)
                .setAvgSizeByte(newAverageRowSize);
        return columnStatisticBuilder.build();
    }

    @Override
    public Statistics visitLogicalCTEProducer(LogicalCTEProducer<? extends Plan> cteProducer, Void context) {
        StatisticsBuilder builder = new StatisticsBuilder(groupExpression.childStatistics(0));
        Statistics statistics = builder.setWidthInJoinCluster(1).build();
        cteIdToStats.put(cteProducer.getCteId(), statistics);
        return statistics;
    }

    @Override
    public Statistics visitLogicalCTEConsumer(LogicalCTEConsumer cteConsumer, Void context) {
        CTEId cteId = cteConsumer.getCteId();
        cascadesContext.addCTEConsumerGroup(cteConsumer.getCteId(), groupExpression.getOwnerGroup(),
                cteConsumer.getProducerToConsumerOutputMap());
        Statistics prodStats = cteIdToStats.get(cteId);
        Preconditions.checkArgument(prodStats != null, String.format("Stats for CTE: %s not found", cteId));
        Statistics consumerStats = new Statistics(prodStats.getRowCount(), 1, new HashMap<>());
        for (Slot slot : cteConsumer.getOutput()) {
            Slot prodSlot = cteConsumer.getProducerSlot(slot);
            ColumnStatistic colStats = prodStats.columnStatistics().get(prodSlot);
            if (colStats == null) {
                continue;
            }
            consumerStats.addColumnStats(slot, colStats);
        }
        return consumerStats;
    }

    @Override
    public Statistics visitLogicalCTEAnchor(LogicalCTEAnchor<? extends Plan, ? extends Plan> cteAnchor, Void context) {
        return groupExpression.childStatistics(1);
    }

    @Override
    public Statistics visitPhysicalCTEProducer(PhysicalCTEProducer<? extends Plan> cteProducer,
            Void context) {
        Statistics statistics = new StatisticsBuilder(groupExpression.childStatistics(0))
                .setWidthInJoinCluster(1).build();
        cteIdToStats.put(cteProducer.getCteId(), statistics);
        cascadesContext.updateConsumerStats(cteProducer.getCteId(), statistics);
        return statistics;
    }

    @Override
    public Statistics visitPhysicalCTEConsumer(PhysicalCTEConsumer cteConsumer, Void context) {
        cascadesContext.addCTEConsumerGroup(cteConsumer.getCteId(), groupExpression.getOwnerGroup(),
                cteConsumer.getProducerToConsumerSlotMap());
        CTEId cteId = cteConsumer.getCteId();
        Statistics prodStats = cteIdToStats.get(cteId);
        if (prodStats == null) {
            prodStats = groupExpression.getOwnerGroup().getStatistics();
        }
        Preconditions.checkArgument(prodStats != null, String.format("Stats for CTE: %s not found", cteId));
        Statistics consumerStats = new Statistics(prodStats.getRowCount(), 1, new HashMap<>());
        for (Slot slot : cteConsumer.getOutput()) {
            Slot prodSlot = cteConsumer.getProducerSlot(slot);
            ColumnStatistic colStats = prodStats.columnStatistics().get(prodSlot);
            if (colStats == null) {
                continue;
            }
            consumerStats.addColumnStats(slot, colStats);
        }
        return consumerStats;
    }

    @Override
    public Statistics visitPhysicalCTEAnchor(
            PhysicalCTEAnchor<? extends Plan, ? extends Plan> cteAnchor, Void context) {
        return groupExpression.childStatistics(1);
    }
}
