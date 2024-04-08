// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveMetaStoreTable;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.Version;
import com.starrocks.connector.ConnectorTableId;
import com.starrocks.connector.MetastoreType;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.hive.events.MetastoreNotificationFetchException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.common.RefreshMode;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.DoubleColumnStatsData;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Sets.difference;
import static com.starrocks.connector.PartitionUtil.toHivePartitionName;
import static com.starrocks.connector.PartitionUtil.toPartitionValues;
import static com.starrocks.connector.hive.HiveMetadata.STARROCKS_QUERY_ID;
import static com.starrocks.connector.hive.HiveMetastoreApiConverter.toHiveCommonStats;
import static com.starrocks.connector.hive.HiveMetastoreApiConverter.toMetastoreApiTable;
import static com.starrocks.connector.hive.HiveMetastoreApiConverter.updateStatisticsParameters;
import static com.starrocks.connector.hive.HiveMetastoreApiConverter.validateHiveTableType;
import static com.starrocks.connector.hive.HiveMetastoreOperations.LOCATION_PROPERTY;
import static com.starrocks.connector.hive.Partition.TRANSIENT_LAST_DDL_TIME;
import static java.lang.String.join;

public class HiveMetastore implements IHiveMetastore {

    private static final Logger LOG = LogManager.getLogger(CachingHiveMetastore.class);
    private final HiveMetaClient client;
    private final HiveConf conf;
    private final String catalogName;
    private final MetastoreType metastoreType;

    public HiveMetastore(HiveMetaClient client, String catalogName, MetastoreType metastoreType) {
        this.client = client;
        this.conf = client.getConf();
        this.catalogName = catalogName;
        this.metastoreType = metastoreType;
    }

    @Override
    public List<String> getAllDatabaseNames() {
        return client.getAllDatabaseNames();
    }

    @Override
    public void createDb(String dbName, Map<String, String> properties) {
        String location = properties.getOrDefault(LOCATION_PROPERTY, "");
        long dbId = ConnectorTableId.CONNECTOR_ID_GENERATOR.getNextId().asInt();
        Database database = new Database(dbId, dbName, location);
        client.createDatabase(HiveMetastoreApiConverter.toMetastoreApiDatabase(database));
    }

    @Override
    public void dropDb(String dbName, boolean deleteData) {
        client.dropDatabase(dbName, deleteData);
    }

    @Override
    public List<String> getAllTableNames(String dbName) {
        return client.getAllTableNames(dbName);
    }

    @Override
    public Database getDb(String dbName) {
        org.apache.hadoop.hive.metastore.api.Database db = client.getDb(dbName);
        return HiveMetastoreApiConverter.toDatabase(db);
    }

    @Override
    public void createTable(String dbName, Table table) {
        org.apache.hadoop.hive.metastore.api.Table hiveTable = toMetastoreApiTable((HiveTable) table);
        client.createTable(hiveTable);
    }

    @Override
    public void dropTable(String dbName, String tableName) {
        client.dropTable(dbName, tableName);
    }

    public Table getTable(String dbName, String tableName) {
        org.apache.hadoop.hive.metastore.api.Table table = client.getTable(dbName, tableName);
        StorageDescriptor sd = table.getSd();
        if (sd == null) {
            throw new StarRocksConnectorException("Table is missing storage descriptor");
        }

        if (!HiveMetastoreApiConverter.isHudiTable(table.getSd().getInputFormat())) {
            validateHiveTableType(table.getTableType());
            if (AcidUtils.isFullAcidTable(table)) {
                throw new StarRocksConnectorException(
                        String.format("%s.%s is a hive transactional table(full acid), sr didn't support it yet", dbName,
                                tableName));
            }
            if (table.getTableType().equalsIgnoreCase("VIRTUAL_VIEW")) {
                return HiveMetastoreApiConverter.toHiveView(table, catalogName);
            } else {
                return HiveMetastoreApiConverter.toHiveTable(table, catalogName);
            }
        } else {
            return HiveMetastoreApiConverter.toHudiTable(table, catalogName);
        }
    }

    @Override
    public boolean tableExists(String dbName, String tableName) {
        return client.tableExists(dbName, tableName);
    }

    @Override
    public List<String> getPartitionKeysByValue(String dbName, String tableName, List<Optional<String>> partitionValues) {
        if (partitionValues.isEmpty()) {
            return client.getPartitionKeys(dbName, tableName);
        } else {
            List<String> partitionValuesStr = partitionValues.stream()
                    .map(v -> v.orElse("")).collect(Collectors.toList());
            return client.getPartitionKeysByValue(dbName, tableName, partitionValuesStr);
        }
    }

    @Override
    public boolean partitionExists(Table table, List<String> partitionValues) {
        HiveTable hiveTable = (HiveTable) table;
        String dbName = hiveTable.getDbName();
        String tableName = hiveTable.getTableName();
        if (metastoreType == MetastoreType.GLUE && hiveTable.hasBooleanTypePartitionColumn()) {
            List<String> allPartitionNames = client.getPartitionKeys(dbName, tableName);
            String hivePartitionName = toHivePartitionName(hiveTable.getPartitionColumnNames(), partitionValues);
            return allPartitionNames.contains(hivePartitionName);
        } else {
            return !client.getPartitionKeysByValue(dbName, tableName, partitionValues).isEmpty();
        }
    }

    @Override
    public Partition getPartition(String dbName, String tblName, List<String> partitionValues) {
        StorageDescriptor sd;
        Map<String, String> params;
        if (partitionValues.size() > 0) {
            org.apache.hadoop.hive.metastore.api.Partition partition =
                    client.getPartition(dbName, tblName, partitionValues);
            sd = partition.getSd();
            params = partition.getParameters();
        } else {
            org.apache.hadoop.hive.metastore.api.Table table = client.getTable(dbName, tblName);
            sd = table.getSd();
            params = table.getParameters();
        }

        return HiveMetastoreApiConverter.toPartition(sd, params);
    }

    public Map<String, Partition> getPartitionsByNames(String dbName, String tblName, List<String> partitionNames) {
        List<org.apache.hadoop.hive.metastore.api.Partition> partitions = new ArrayList<>();
        // fetch partitions by batch per RPC
        for (int start = 0; start < partitionNames.size(); start += Config.max_hive_partitions_per_rpc) {
            int end = Math.min(start + Config.max_hive_partitions_per_rpc, partitionNames.size());
            List<String> namesPerRPC = partitionNames.subList(start, end);
            List<org.apache.hadoop.hive.metastore.api.Partition> partsPerRPC =
                    client.getPartitionsByNames(dbName, tblName, namesPerRPC);
            partitions.addAll(partsPerRPC);
        }

        Map<String, List<String>> partitionNameToPartitionValues = partitionNames.stream()
                .collect(Collectors.toMap(Function.identity(), PartitionUtil::toPartitionValues));

        Map<List<String>, Partition> partitionValuesToPartition = partitions.stream()
                .collect(Collectors.toMap(
                        org.apache.hadoop.hive.metastore.api.Partition::getValues,
                        partition -> HiveMetastoreApiConverter.toPartition(partition.getSd(), partition.getParameters())));

        ImmutableMap.Builder<String, Partition> resultBuilder = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> entry : partitionNameToPartitionValues.entrySet()) {
            Partition partition = partitionValuesToPartition.get(entry.getValue());
            resultBuilder.put(entry.getKey(), partition);
        }
        return resultBuilder.build();
    }

    @Override
    public void addPartitions(String dbName, String tableName, List<HivePartitionWithStats> partitions) {
        List<org.apache.hadoop.hive.metastore.api.Partition> hivePartitions = partitions.stream()
                .map(HiveMetastoreApiConverter::toMetastoreApiPartition)
                .collect(Collectors.toList());
        client.addPartitions(dbName, tableName, hivePartitions);
        // TODO(stephen): add partition column statistics
    }

    @Override
    public void dropPartition(String dbName, String tableName, List<String> partValues, boolean deleteData) {
        client.dropPartition(dbName, tableName, partValues, deleteData);
    }

    public HivePartitionStats getTableStatistics(String dbName, String tblName) {
        org.apache.hadoop.hive.metastore.api.Table table = client.getTable(dbName, tblName);
        HiveCommonStats commonStats = toHiveCommonStats(table.getParameters());
        long totalRowNums = commonStats.getRowNums();
        if (totalRowNums == -1) {
            return HivePartitionStats.empty();
        }

        List<String> dataColumns = table.getSd().getCols().stream()
                .map(FieldSchema::getName)
                .collect(toImmutableList());
        List<ColumnStatisticsObj> statisticsObjs = client.getTableColumnStats(dbName, tblName, dataColumns);
        if (statisticsObjs.isEmpty() && Config.enable_reuse_spark_column_statistics) {
            // Try to use spark unpartitioned table column stats
            try {
                if (table.getParameters().keySet().stream().anyMatch(k -> k.startsWith("spark.sql.statistics.colStats."))) {
                    statisticsObjs = HiveMetastoreApiConverter.getColStatsFromSparkParams(table);
                }
            } catch (Exception e) {
                LOG.warn("Failed to get column stats from table [{}.{}]", dbName, tblName);
            }
        }
        Map<String, HiveColumnStats> columnStatistics =
                HiveMetastoreApiConverter.toSinglePartitionColumnStats(statisticsObjs, totalRowNums);
        return new HivePartitionStats(commonStats, columnStatistics);
    }

    public void updateTableStatistics(String dbName, String tableName, Function<HivePartitionStats, HivePartitionStats> update) {
        org.apache.hadoop.hive.metastore.api.Table originTable = client.getTable(dbName, tableName);
        if (originTable == null) {
            throw new StarRocksConnectorException("Table '%s.%s' not found", dbName, tableName);
        }

        org.apache.hadoop.hive.metastore.api.Table newTable = originTable.deepCopy();
        HiveCommonStats curCommonStats = toHiveCommonStats(originTable.getParameters());
        HivePartitionStats curPartitionStats = new HivePartitionStats(curCommonStats, new HashMap<>());
        HivePartitionStats updatedStats = update.apply(curPartitionStats);

        HiveCommonStats commonStats = updatedStats.getCommonStats();
        Map<String, String> originParams = newTable.getParameters();
        originParams.put(TRANSIENT_LAST_DDL_TIME, String.valueOf(System.currentTimeMillis() / 1000));
        newTable.setParameters(updateStatisticsParameters(originParams, commonStats));
        client.alterTable(dbName, tableName, newTable);

        //TODO(stephen): update table column statistics
    }

    public void updatePartitionStatistics(String dbName, String tableName, String partitionName,
                                          Function<HivePartitionStats, HivePartitionStats> update) {
        List<org.apache.hadoop.hive.metastore.api.Partition> partitions = client.getPartitionsByNames(
                dbName, tableName, ImmutableList.of(partitionName));
        if (partitions.size() != 1) {
            throw new StarRocksConnectorException("Metastore returned multiple partitions for name: " + partitionName);
        }

        org.apache.hadoop.hive.metastore.api.Partition originPartition = getOnlyElement(partitions);
        HiveCommonStats curCommonStats = toHiveCommonStats(originPartition.getParameters());
        HivePartitionStats curPartitionStats = new HivePartitionStats(curCommonStats, new HashMap<>());
        HivePartitionStats updatedStats = update.apply(curPartitionStats);

        org.apache.hadoop.hive.metastore.api.Partition modifiedPartition = originPartition.deepCopy();
        HiveCommonStats commonStats = updatedStats.getCommonStats();
        Map<String, String> originParams = modifiedPartition.getParameters();
        originParams.put(TRANSIENT_LAST_DDL_TIME, String.valueOf(System.currentTimeMillis() / 1000));
        modifiedPartition.setParameters(updateStatisticsParameters(modifiedPartition.getParameters(), commonStats));
        client.alterPartition(dbName, tableName, modifiedPartition);

        //TODO(stephen): update partition column statistics
    }

    public Map<String, HivePartitionStats> getPartitionStatistics(Table table, List<String> partitionNames) {
        HiveMetaStoreTable hmsTbl = (HiveMetaStoreTable) table;
        String dbName = hmsTbl.getDbName();
        String tblName = hmsTbl.getTableName();
        List<String> dataColumns = hmsTbl.getDataColumnNames();
        Map<String, Partition> partitions = getPartitionsByNames(hmsTbl.getDbName(), hmsTbl.getTableName(), partitionNames);

        Map<String, HiveCommonStats> partitionCommonStats = partitions.entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> toHiveCommonStats(entry.getValue().getParameters())));

        Map<String, Long> partitionRowNums = partitionCommonStats.entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().getRowNums()));

        ImmutableMap.Builder<String, HivePartitionStats> resultBuilder = ImmutableMap.builder();
        Map<String, List<ColumnStatisticsObj>> partitionNameToColumnStatsObj =
                client.getPartitionColumnStats(dbName, tblName, partitionNames, dataColumns);

        Map<String, Map<String, HiveColumnStats>> partitionColumnStats = HiveMetastoreApiConverter
                .toPartitionColumnStatistics(partitionNameToColumnStatsObj, partitionRowNums);

        for (String partitionName : partitionCommonStats.keySet()) {
            HiveCommonStats commonStats = partitionCommonStats.get(partitionName);
            Map<String, HiveColumnStats> columnStatistics = partitionColumnStats
                    .getOrDefault(partitionName, ImmutableMap.of());
            resultBuilder.put(partitionName, new HivePartitionStats(commonStats, columnStatistics));
        }

        return resultBuilder.build();
    }

    public long getCurrentEventId() {
        return client.getCurrentNotificationEventId().getEventId();
    }

    public NotificationEventResponse getNextEventResponse(long lastSyncedEventId, String catalogName,
                                                          final boolean getAllEvents)
            throws MetastoreNotificationFetchException {
        try {
            int batchSize = getAllEvents ? -1 : Config.hms_events_batch_size_per_rpc;
            NotificationEventResponse response = client.getNextNotification(lastSyncedEventId, batchSize, null);
            if (response.getEvents().size() == 0) {
                LOG.info("Event size is 0 when pulling events on catalog [{}]", catalogName);
                return null;
            }
            LOG.info(String.format("Received %d events. Start event id : %d. Last synced id : %d on catalog : %s",
                    response.getEvents().size(), response.getEvents().get(0).getEventId(),
                    lastSyncedEventId, catalogName));

            return response;
        } catch (MetastoreNotificationFetchException e) {
            LOG.error("Unable to fetch notifications from metastore. Last synced event id is {}", lastSyncedEventId, e);
            throw new MetastoreNotificationFetchException("Unable to fetch notifications from metastore. " +
                    "Last synced event id is " + lastSyncedEventId, e);
        }
    }

    @Override
    public void syncPartitions(String db, String tableName, List<String> partitionNames, RefreshMode mode) {
        Table table = getTable(db, tableName);
        String tableLocation = table.getTableLocation();
        List<String> partitionCols = table.getPartitionColumnNames();
        Set<String> partitionsInStorage;

        if (partitionNames != null && !partitionNames.isEmpty()) {
            partitionsInStorage = client.getPartitionsByNames(db, tableName, partitionNames).stream()
                    .map(p ->{
                        StringBuilder partName = new StringBuilder();
                        Iterator<String> valIter = p.getValuesIterator();
                        for(Iterator<String> colIter = partitionCols.iterator(); colIter.hasNext() && valIter.hasNext();) {
                            String col = colIter.next();
                            String val = valIter.next();
                            partName.append(col).append("=").append(val);
                            partName.append("/");
                        }
                        partName.delete(partName.length() - 1, partName.length());
                        return partName.toString();
                    }).collect(Collectors.toSet());
        } else {
            partitionsInStorage = new HashSet<>(client.getPartitionKeys(db, tableName));
        }

        Set<String> partitionsInFs = listPartitionNames(tableLocation, partitionCols, partitionNames);

        // partitions in file system but not in metastore
        Set<String> partitionsToAdd = difference(partitionsInFs, partitionsInStorage);

        // partitions in metastore but not in file system
        Set<String> partitionsToDrop = difference(partitionsInStorage, partitionsInFs);

        HiveTable hiveTable = (HiveTable)table;
        if (mode == RefreshMode.ADD || mode == RefreshMode.SYNC) {
            List<HivePartitionWithStats> partitionWithStats = partitionsToAdd.stream().map(p -> {
                HivePartition hivePartition = HivePartition.builder()
                        .setDatabaseName(hiveTable.getDbName())
                        .setTableName(hiveTable.getTableName())
                        .setColumns(hiveTable.getDataColumnNames().stream()
                                .map(table::getColumn)
                                .collect(Collectors.toList()))
                        .setValues(toPartitionValues(p))
                        .setParameters(ImmutableMap.<String, String>builder()
                                .put("starrocks_version", Version.STARROCKS_VERSION + "-" + Version.STARROCKS_COMMIT_HASH)
                                .put(STARROCKS_QUERY_ID, ConnectContext.get().getQueryId().toString())
                                .buildOrThrow())
                        .setStorageFormat(hiveTable.getStorageFormat())
                        .setLocation(hiveTable.getTableLocation() + "/" + p)
                        .build();
                return new HivePartitionWithStats(p, hivePartition, HivePartitionStats.empty());
            }).collect(Collectors.toList());
            addPartitions(db, tableName, partitionWithStats);
        }

        if (mode == RefreshMode.DROP || mode == RefreshMode.SYNC) {
            partitionsToDrop.forEach(
                    p -> dropPartition(db, tableName, PartitionUtil.toPartitionValues(p), false)
            );
        }
    }

    private Set<String> listPartitionNames(String tableLocation, List<String> partitionCols, List<String> partitionNames) {
            try {
                FileSystem fs = FileSystem.get(new Path(tableLocation).toUri(), conf);

                if (partitionNames != null && !partitionNames.isEmpty()) {
                    Set<String> names = new HashSet<>();
                    for (String name : partitionNames) {
                        if (fs.exists(new Path(tableLocation, name))) {
                            names.add(name);
                        }
                    }
                    return names;
                }else {
                    return listParts(fs, new Path(tableLocation), partitionCols, 0, ImmutableList.of());
                }
            } catch (IOException e) {
                throw new StarRocksConnectorException("error listing partition names", e);
            }
    }

    private static Set<String> listParts(FileSystem fs, Path location, List<String> partitionCols, int depth, List<String> partitions) throws IOException {
        if (depth == partitionCols.size()) {
            return ImmutableSet.of(join("/", partitions));
        }
        PathFilter filter = path -> path.getName().startsWith(partitionCols.get(depth) + "=");

        ImmutableSet.Builder<String> result = ImmutableSet.builder();
        for (FileStatus file : fs.listStatus(location, filter)) {
            if (!file.isDirectory()) continue;
            Path path = file.getPath();
            List<String> current = ImmutableList.<String>builder().addAll(partitions).add(path.getName()).build();
            result.addAll(listParts(fs, path, partitionCols, depth + 1, current));
        }
        return result.build();
    }
}