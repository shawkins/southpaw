/*
 * Copyright 2018 Longtail Ad Solutions (DBA JW Player)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jwplayer.southpaw;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.time.StopWatch;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serde;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.jwplayer.southpaw.filter.BaseFilter;
import com.jwplayer.southpaw.index.BaseIndex;
import com.jwplayer.southpaw.index.MultiIndex;
import com.jwplayer.southpaw.index.Reversible;
import com.jwplayer.southpaw.json.ChildRecords;
import com.jwplayer.southpaw.json.DenormalizedRecord;
import com.jwplayer.southpaw.json.Record;
import com.jwplayer.southpaw.json.Relation;
import com.jwplayer.southpaw.metric.Metrics;
import com.jwplayer.southpaw.metric.StaticGauge;
import com.jwplayer.southpaw.record.BaseRecord;
import com.jwplayer.southpaw.serde.BaseSerde;
import com.jwplayer.southpaw.state.BaseState;
import com.jwplayer.southpaw.state.RocksDBState;
import com.jwplayer.southpaw.topic.BaseTopic;
import com.jwplayer.southpaw.topic.ConsumerRecordIterator;
import com.jwplayer.southpaw.topic.TopicConfig;
import com.jwplayer.southpaw.util.ByteArray;
import com.jwplayer.southpaw.util.ByteArraySet;
import com.jwplayer.southpaw.util.FileHelper;

import joptsimple.OptionParser;
import joptsimple.OptionSet;


/**
 * A class that creates denormalized records from input records based on hierarchical relationships. These
 * relationships are similar a LEFT OUTER JOIN defined by the following SQL statement:
 *     SELECT ...
 *     FROM table_a LEFT OUTER JOIN table_b on a_key = b_key
 * In this case 'table_b' is a child relationship of 'table_a.' 'a_key' is equivalent to the parent key and 'b_key'
 * is equivalent to the join key in a child relation. Ultimately, one 'table' is the root relation. The topic key
 * for each record in all input and denormalized records is treated as the primary key, which is used by the
 * various indices and within the denormalized records themselves.
 */
public class Southpaw {
    /**
     * Join key, the key in the child record used in joins (PaK == JK)
     */
    public static final String JK = "JK";
    /**
     * The name of the state keyspace for Southpaw's metadata
     */
    public static final String METADATA_KEYSPACE = "__southpaw.metadata";
    /**
     * Parent key, the key in the parent record used in joins (PaK == JK)
     */
    public static final String PaK = "PaK";
    /**
     * Primary key
     */
    public static final String PK = "PK";
    /**
     * Separator used by constructor keys and other things
     */
    public static final String SEP = "|";
    /**
     * Le Logger
     */
    private static final Logger logger =  LoggerFactory.getLogger(Southpaw.class);
    /**
     * Used for doing object <-> JSON mappings
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parsed Southpaw config
     */
    protected final Config config;
    /**
     * The PKs of the denormalized records yet to be created
     */
    protected Map<Relation, ByteArraySet> dePKsByType = new HashMap<>();
    /**
     * A map of foreign key indices needed by Southpaw. This includes parent indices (points at the root
     * records) and join indices (points at the child records). The key is the index name. Multiple offsets
     * can be stored per key.
     */
    protected final Map<String, BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>>> fkIndices = new HashMap<>();
    /**
     * A map of all input topics needed by Southpaw. The key is the short name of the topic.
     */
    protected Map<String, BaseTopic<BaseRecord, BaseRecord>> inputTopics;
    /**
     * Simple metrics class for Southpaw
     */
    protected final Metrics metrics = new Metrics();
    /**
     * A map of the output topics needed where the denormalized records are written. The key is the short name of
     * the topic.
     */
    protected Map<String, BaseTopic<byte[], DenormalizedRecord>> outputTopics;
    /**
     * Tells the run() method to process records. If this is set to false, it will stop.
     */
    protected boolean processRecords = true;
    /**
     * The configuration for Southpaw. Mostly Kafka and topic configuration. See
     * test/test-resources/config.sample.yaml for an example.
     */
    protected final Map<String, Object> rawConfig;
    /**
     * The top level relations that instruct Southpaw how to construct denormalized records. See
     * test-resources/relations.sample.json for an example.
     */
    protected final Relation[] relations;
    /**
     * State for Southpaw
     */
    protected BaseState state;

    protected boolean topicsPrefixed;
    private static final String TRANSACTIONS = "transactions";

    protected String currentTxn;

    /**
     * Base Southpaw config
     */
    protected static class Config {
        public static final String BACKUP_TIME_S_CONFIG = "backup.time.s";
        public static final int BACKUP_TIME_S_DEFAULT = 1800;
        public static final String COMMIT_TIME_S_CONFIG = "commit.time.s";
        public static final int COMMIT_TIME_S_DEFAULT = 0;
        public static final String CREATE_RECORDS_TRIGGER_CONFIG = "create.records.trigger";
        public static final int CREATE_RECORDS_TRIGGER_DEFAULT = 250000;
        public static final String TOTAL_LAG_TRIGGER_CONFIG = "total.lag.trigger";
        public static final int TOTAL_LAG_TRIGGER_DEFAULT = 2000;

        /**
         * Time interval (roughly) between backups
         */
        public int backupTimeS;

        /**
         * Time interval (roughly) between commits
         */
        public int commitTimeS;

        /**
         * Config for when to create denormalized records once the number of records to create has exceeded a certain amount
         */
        public int createRecordsTrigger;

        /**
         * Config for when to switch from one topic to the next (or to stop processing a topic entirely), when lag drops below this value
         */
        public int totalLagTrigger;

        public Config(Map<String, Object> rawConfig) throws ClassNotFoundException {
            this.backupTimeS = (int) rawConfig.getOrDefault(BACKUP_TIME_S_CONFIG, BACKUP_TIME_S_DEFAULT);
            this.commitTimeS = (int) rawConfig.getOrDefault(COMMIT_TIME_S_CONFIG, COMMIT_TIME_S_DEFAULT);
            this.createRecordsTrigger = (int) rawConfig.getOrDefault(CREATE_RECORDS_TRIGGER_CONFIG, CREATE_RECORDS_TRIGGER_DEFAULT);
            this.totalLagTrigger = (int) rawConfig.getOrDefault(TOTAL_LAG_TRIGGER_CONFIG, TOTAL_LAG_TRIGGER_DEFAULT);
        }
    }

    /**
     * Constructor
     * @param rawConfig - Southpaw configuration
     * @param relations - URIs to files containing the top level relations that define the denormalized
     *            objects to construct
     * @throws IOException -
     * @throws URISyntaxException -
     */
    public Southpaw(Map<String, Object> rawConfig, List<URI> relations)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, IOException, URISyntaxException, NoSuchMethodException, InvocationTargetException {
        this(rawConfig, loadRelations(Preconditions.checkNotNull(relations)));
    }

    /**
     * Constructor
     * @param rawConfig - Southpaw configuration
     * @param relations - The top level relations that define the denormalized objects to construct
     */
    public Southpaw(Map<String, Object> rawConfig, Relation[] relations)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        validateRootRelations(relations);

        this.rawConfig = Preconditions.checkNotNull(rawConfig);
        this.config = new Config(rawConfig);
        this.relations = Preconditions.checkNotNull(relations);
        this.state = new RocksDBState(rawConfig);
        this.state.open();
        this.state.createKeySpace(METADATA_KEYSPACE);
        this.inputTopics = new HashMap<>();
        this.outputTopics = new HashMap<>();
        for(Relation root: this.relations) {
            this.inputTopics.putAll(createInputTopics(root));
            this.outputTopics.put(root.getDenormalizedName(), createOutputTopic(root.getDenormalizedName()));
            this.metrics.registerOutputTopic(root.getDenormalizedName());
        }
        try {
            this.inputTopics.put(TRANSACTIONS, createTopic(TRANSACTIONS));
        } catch (NullPointerException e) {
            //transactions not defined
        }
        this.topicsPrefixed = (Boolean) rawConfig.getOrDefault("topics.prefixed", true);
        for(Map.Entry<String, BaseTopic<BaseRecord, BaseRecord>> entry: this.inputTopics.entrySet()) {
            this.metrics.registerInputTopic(entry.getKey());
        }
        createIndices();

        // Load any previous denormalized record PKs that have yet to be created
        for (Relation root : relations) {
            byte[] bytes = state.get(METADATA_KEYSPACE, createDePKEntryName(root).getBytes());
            dePKsByType.put(root, ByteArraySet.deserialize(bytes));
        }
    }

    class RecordHolder implements Comparable<RecordHolder> {
        String entity;
        ConsumerRecordIterator<BaseRecord, BaseRecord> records;

        long time;
        long order;
        String txn;

        public RecordHolder(String entity, ConsumerRecordIterator<BaseRecord, BaseRecord> records) {
            this.entity = entity;
            this.records = records;
        }

        boolean peek() {
            if (records.hasNext()) {
                ConsumerRecord<?, ?> record = records.peekRawConsumerRecord();
                BaseRecord baseRecord = records.peekValue();
                //take the time as the message, which should work whether txn metadata is available or not
                this.time = record.timestamp();
                if (entity.equals(TRANSACTIONS)) {
                    String status = (String)baseRecord.get("status");
                    if ("BEGIN".equals(status)) {
                        order = -1;
                    } else if ("END".equals(status)) {
                        order = Long.MAX_VALUE;
                    }
                    txn = (String)baseRecord.get("id");
                } else if (baseRecord != null) {
                    //since the full envelope is the metadata, we could use other notions of time
                    //ts_ms, source.ts_, or even something from the before/after
                    Map<String, ?> metadata = baseRecord.getMetadata();
                    if (metadata != null) {
                        Map<String, ?> transaction = (Map<String, ?>) metadata.get("transaction");
                        if (transaction != null) {
                            this.order = ((Number)transaction.get("total_order")).longValue();
                            txn = (String)transaction.get("id");
                        }
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public int compareTo(RecordHolder o) {
            int result = Long.compare(time, o.time);
            if (result != 0) {
                return result;
            }
            //events of equal time must be ordered by the
            //current txn if possible - this requires reordering
            //the queue once the current txn is set
            if (!Objects.equals(txn, o.txn) && currentTxn != null) {
                if (currentTxn.equals(txn)) {
                    return -1;
                } else if (currentTxn.equals(o.txn)) {
                    return 1;
                }
            }
            //after the txn, we can consider the order
            result = Long.compare(order, o.order);
            if (result != 0) {
                return result;
            }
            return 0;
        }
    }

    /**
     * Reads batches of new records from each of the input topics and creates the appropriate denormalized
     * records according to the top level relations. Performs a full commit and backup before returning.
     * @param runTimeS - Sets an amount of time in seconds for this method to run. The method will not run this amount
     *                of time exactly, but will stop after processing the latest batch of records. If set to 0,
     *                it will run until interrupted. Probably most useful for testing.
     */
    protected void build(int runTimeS) {
        logger.info("Building denormalized records");
        StopWatch backupWatch = new StopWatch();
        backupWatch.start();
        StopWatch runWatch = new StopWatch();
        runWatch.start();
        StopWatch commitWatch = new StopWatch();
        commitWatch.start();

        PriorityQueue<RecordHolder> topicsByTime = new PriorityQueue<>();
        Set<String> toProbe = new HashSet<>(inputTopics.keySet());
        Map<String, String> tablesToAlias = new HashMap<>();
        inputTopics.entrySet().stream().forEach((e) -> {
            String tableName = e.getValue().getTableName();
            if (tableName == null) {
                tableName = e.getValue().getTopicName();
                tableName = topicsPrefixed?tableName.substring(tableName.indexOf('.')+1):tableName;
            }
            tablesToAlias.put(tableName, e.getKey());
        });

        Map<String, Integer> transactionEvents = new HashMap<>();

        probe: while(processRecords) {
            //TODO: these metrics are computed too often
            calculateRecordsToCreate();
            calculateTotalLag();

            boolean foundEarlier = false;
            boolean foundAny = false;
            for (Iterator<String> iter = toProbe.iterator(); iter.hasNext();) {
                String entity = iter.next();
                BaseTopic<BaseRecord, BaseRecord> inputTopic = inputTopics.get(entity);

                ConsumerRecordIterator<BaseRecord, BaseRecord> records = (ConsumerRecordIterator<BaseRecord, BaseRecord>)inputTopic.readNext();

                if (records.getApproximateCount() > 0) {
                    RecordHolder holder = new RecordHolder(entity,  records);
                    foundAny = true;
                    if (holder.peek()) {
                        topicsByTime.add(holder);
                        if (!foundEarlier && topicsByTime.peek() == holder) {
                            foundEarlier = true;
                        }
                        iter.remove();
                    }
                }
            }
            if (!foundAny) {
                try {
                    //Thread.onSpinWait();
                    Thread.sleep(5); //prevent a busy wait and keep probing
                } catch (InterruptedException e1) {
                    Thread.interrupted();
                    throw new RuntimeException(e1);
                }
                //this could be a tighter loop here, but we need to account for runTimeS
            }

            boolean flush = (currentTxn == null);
            while (foundEarlier && !topicsByTime.isEmpty()) {
                RecordHolder holder = topicsByTime.peek();
                String entity = holder.entity;

                boolean txnEvent = false;

                if (entity.equals(TRANSACTIONS)) {
                    txnEvent = true;
                    BaseRecord peekValue = holder.records.peekValue();
                    Object status = peekValue.get("status");
                    if ("BEGIN".equals(status)) {
                        if(logger.isDebugEnabled()) {
                            logger.debug("starting transaction {}", holder.txn);
                        }
                        transactionEvents.clear();
                        if (currentTxn != null) {
                            throw new AssertionError("Unexpected begin of transaction");
                        }
                        currentTxn = holder.txn;
                        flush = false;
                        //reorder based upon the current txn
                        //this was the simplest approach with the existing code structure
                        //you could of course add more explicit buffering by txn
                        topicsByTime = new PriorityQueue<>(topicsByTime);
                    } else if ("END".equals(status)) {
                        if (!holder.txn.equals(currentTxn)) {
                            throw new AssertionError("Unexpected end of transaction");
                        }
                        List<Map<String, ?>> dataCollections = (List<Map<String, ?>>) peekValue.get("data_collections");
                        if (dataCollections != null) {
                            for (Map<String, ?> dataCollection : dataCollections) {
                                String topic = (String)dataCollection.get("data_collection");
                                String alias = tablesToAlias.get(topic);
                                if (alias == null) {
                                    continue; //not involved
                                }
                                Integer count = transactionEvents.get(alias);
                                if ((count == null || (((Number)dataCollection.get("event_count")).intValue()) > count)) {
                                    if (toProbe.contains(alias)) {
                                        logger.debug("waiting for {}", alias);
                                        continue probe; //TODO: probe only missing, and only do this a limited number of times
                                    }
                                    logger.warn("probes are up-to-date, but events do not line up");
                                }
                            }
                        }
                        currentTxn = null;
                        flush = true;  //TODO: could skip if there are pending txn messages
                        if(logger.isDebugEnabled()) {
                            logger.debug("ending transaction {}", holder.txn);
                        }
                    }
                } else if (holder.txn != null) {
                    if (!Objects.equals(currentTxn, holder.txn)) {
                        if (toProbe.contains(TRANSACTIONS)) {
                            //probe again to know what transaction to start
                            logger.info("waiting for transaction event");
                            continue probe;
                        }
                        throw new AssertionError("unexpected transaction " + holder.txn);
                    }
                    transactionEvents.compute(entity, (k, v)->v==null?1:v+1);
                }

                //advance the topic
                topicsByTime.poll();
                ConsumerRecord<BaseRecord, BaseRecord> newRecord = holder.records.next();
                if (holder.peek()) {
                    topicsByTime.add(holder);
                } else {
                    toProbe.add(entity);
                }

                if (txnEvent) {
                    if (flush && flushCommitBackup(runTimeS, backupWatch, runWatch, commitWatch, true)) {
                        return; //TODO: could skip flushing if there are pending txn events
                    }
                    continue; // don't enter the denormalized processing loop
                }

                long topicLag = inputTopics.get(entity).getLag();
                metrics.topicLagByTopic.get(entity).update(topicLag);

                ByteArray primaryKey = newRecord.key().toByteArray();
                for (Relation root : relations) {
                    Set<ByteArray> dePrimaryKeys = dePKsByType.get(root);
                    if (root.getEntity().equals(entity)) {
                        // The top level relation is the relation of the input record
                        dePrimaryKeys.add(primaryKey);
                    } else {
                        // Check the child relations instead
                        AbstractMap.SimpleEntry<Relation, Relation> child = getRelation(root, entity);
                        if (child != null && child.getValue() != null) {
                            BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> parentIndex =
                                    fkIndices.get(createParentIndexName(root, child.getKey(), child.getValue()));
                            ByteArray newParentKey = null;
                            Set<ByteArray> oldParentKeys;
                            if (newRecord.value() != null) {
                                newParentKey = ByteArray.toByteArray(newRecord.value().get(child.getValue().getJoinKey()));
                            }
                            BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> joinIndex =
                                    fkIndices.get(createJoinIndexName(child.getValue()));
                            oldParentKeys = ((Reversible) joinIndex).getForeignKeys(primaryKey);

                            // Create the denormalized records
                            if (oldParentKeys != null) {
                                for (ByteArray oldParentKey : oldParentKeys) {
                                    if (!ObjectUtils.equals(oldParentKey, newParentKey)) {
                                        Set<ByteArray> primaryKeys = parentIndex.getIndexEntry(oldParentKey);
                                        if (primaryKeys != null) {
                                            dePrimaryKeys.addAll(primaryKeys);
                                        }
                                    }
                                }
                            }
                            if (newParentKey != null) {
                                Set<ByteArray> primaryKeys = parentIndex.getIndexEntry(newParentKey);
                                if (primaryKeys != null) {
                                    dePrimaryKeys.addAll(primaryKeys);
                                }
                            }
                            // Update the join index
                            updateJoinIndex(child.getValue(), primaryKey, newRecord);
                        }
                    }
                    int size = dePrimaryKeys.size();
                    if(flush && size > config.createRecordsTrigger) {
                        createDenormalizedRecords(root, dePrimaryKeys);
                        dePrimaryKeys.clear();
                    }
                    metrics.denormalizedRecordsToCreateByTopic.get(root.getDenormalizedName()).update((long) size);
                }
                metrics.recordsConsumed.mark(1);
                metrics.recordsConsumedByTopic.get(entity).mark(1);

                reportRecordsToCreate();
                reportTotalLag();
            }

            //nothing left to read and we're in a flushable state
            if (flush) {
                Long totalLag = metrics.topicLag.getValue();
                if (flushCommitBackup(runTimeS, backupWatch, runWatch, commitWatch, totalLag == null || totalLag < config.totalLagTrigger)) {
                    return;
                }
            }
        }
        commit();
    }

    private boolean flushCommitBackup(int runTimeS, StopWatch backupWatch, StopWatch runWatch, StopWatch commitWatch, boolean createDenormalized) {
        //commitOrBackup at the end of the txn so that we don't need can
        //start fresh - has the loop exit like the old code
        metrics.timeSinceLastBackup.update(backupWatch.getTime());
        if(
                (config.backupTimeS > 0 && backupWatch.getTime() > config.backupTimeS * 1000)
                || (runWatch.getTime() > runTimeS * 1000 && runTimeS > 0)) {
            try(Timer.Context context = metrics.backupsCreated.time()) {
                logger.info("Performing a backup after a full commit");
                calculateRecordsToCreate();
                calculateTotalLag();
                commit();
                state.backup();
                backupWatch.reset();
                backupWatch.start();
                if (runWatch.getTime() > runTimeS * 1000 && runTimeS > 0) return true;
            }
        } else if(config.commitTimeS > 0 && commitWatch.getTime() > config.commitTimeS * 1000) {
            try(Timer.Context context = metrics.stateCommitted.time()) {
                logger.info("Performing a full commit");
                calculateRecordsToCreate();
                calculateTotalLag();
                commit();
                commitWatch.reset();
                commitWatch.start();
            }
        }

        if (createDenormalized) {
            // Create the denormalized records that have been queued up
            for(Map.Entry<Relation, ByteArraySet> entry: dePKsByType.entrySet()) {
                createDenormalizedRecords(entry.getKey(), entry.getValue());
                entry.getValue().clear();
            }
        }
        return false;
    }

    /**
     * Calculates and reports the total number of denormalized records to create
     */
    public void calculateRecordsToCreate() {
        long totalRecords = 0;
        for(Map.Entry<Relation, ByteArraySet> entry: dePKsByType.entrySet()) {
            long records = entry.getValue().size();
            totalRecords += records;
            metrics.denormalizedRecordsToCreateByTopic.get(entry.getKey().getDenormalizedName()).update(records);
        }
        metrics.denormalizedRecordsToCreate.update(totalRecords);
    }

    /**
     * Calculates and reports the total lag for all input topics
     */
    public void calculateTotalLag() {
        long topicLag;
        long totalLag = 0;
        for(Map.Entry<String, BaseTopic<BaseRecord, BaseRecord>> entry: inputTopics.entrySet()) {
            topicLag = entry.getValue().getLag();
            totalLag += topicLag;
            metrics.topicLagByTopic.get(entry.getKey()).update(topicLag);
        }
        metrics.topicLag.update(totalLag);
    }

    /**
     * Cleans up and closes anything used by Southpaw.
     */
    public void close() {
        metrics.close();
        state.close();
    }

    /**
     * Commit / flush offsets and data for the normalized topics and indices
     */
    public void commit() {
        // Commit / flush changes
        for(Map.Entry<String, BaseTopic<byte[], DenormalizedRecord>> topic: outputTopics.entrySet()) {
            topic.getValue().flush();
        }
        for(Map.Entry<String, BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>>> index: fkIndices.entrySet()) {
            index.getValue().flush();
        }
        for(Map.Entry<Relation, ByteArraySet> entry: dePKsByType.entrySet()) {
            state.put(METADATA_KEYSPACE, createDePKEntryName(entry.getKey()).getBytes(), entry.getValue().serialize());
            state.flush(METADATA_KEYSPACE);
        }
        for(Map.Entry<String, BaseTopic<BaseRecord, BaseRecord>> entry: inputTopics.entrySet()) {
            entry.getValue().commit();
        }
        state.flush();
    }

    /**
     * Create all indices for the given child relation and its children.
     * @param root - The root relation to create the indices for
     * @param parent - The parent relation to create the indices for
     * @param child - The child relation to create the indices for
     */
    protected void createChildIndices(
            Relation root,
            Relation parent,
            Relation child) {
        // Create this child's indices
        String joinIndexName = createJoinIndexName(child);
        fkIndices.put(joinIndexName, createFkIndex(joinIndexName, child.getEntity()));
        String parentIndexName = createParentIndexName(root, parent, child);
        fkIndices.put(parentIndexName, createFkIndex(parentIndexName, root.getEntity()));

        // Add its children's indices
        if(child.getChildren() != null) {
            for(Relation grandchild: child.getChildren()) {
                createChildIndices(root, child, grandchild);
            }
        }
    }

    /**
     * Recursively create a new denormalized record based on its relation definition, its parent's input record,
     * and its primary key.
     * @param root - The root relation of the denormalized record
     * @param relation - The current relation of the denormalized record to build
     * @param rootPrimaryKey - The PK of the root / denormalized record
     * @param relationPrimaryKey - The PK of the record
     * @return A fully created denormalized object
     */
    protected DenormalizedRecord createDenormalizedRecord(
            Relation root,
            Relation relation,
            ByteArray rootPrimaryKey,
            ByteArray relationPrimaryKey) {
        DenormalizedRecord denormalizedRecord = null;
        BaseTopic<BaseRecord, BaseRecord> relationTopic = inputTopics.get(relation.getEntity());
        BaseRecord relationRecord = relationTopic.readByPK(relationPrimaryKey);

        if(!(relationRecord == null || relationRecord.isEmpty())) {
            denormalizedRecord = new DenormalizedRecord();
            denormalizedRecord.setRecord(createInternalRecord(relationRecord));
            ChildRecords childRecords = new ChildRecords();
            denormalizedRecord.setChildren(childRecords);
            for (Relation child : relation.getChildren()) {
                ByteArray newParentKey = ByteArray.toByteArray(relationRecord.get(child.getParentKey()));
                updateParentIndex(root, relation, child, rootPrimaryKey, newParentKey);
                Map<ByteArray, DenormalizedRecord> records = new TreeMap<>();
                if (newParentKey != null) {
                    BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> joinIndex = fkIndices.get(createJoinIndexName(child));
                    Set<ByteArray> childPKs = joinIndex.getIndexEntry(newParentKey);
                    if (childPKs != null) {
                        for (ByteArray childPK : childPKs) {
                            DenormalizedRecord deChildRecord = createDenormalizedRecord(root, child, rootPrimaryKey, childPK);
                            if (deChildRecord != null) records.put(childPK, deChildRecord);
                        }
                    }
                    childRecords.setAdditionalProperty(child.getEntity(), new ArrayList<>(records.values()));
                }
            }
        }

        return denormalizedRecord;
    }

    /**
     * Creates a set of denormalized records and writes them to the appropriate output topic.
     * @param root - The top level relation defining the structure and relations of the denormalized records to create
     * @param rootRecordPKs - The primary keys of the root input records to create denormalized records for
     */
    protected void createDenormalizedRecords(
            Relation root,
            Set<ByteArray> rootRecordPKs) {
        if (rootRecordPKs.isEmpty()) {
            return;
        }
        logger.info("creating {} {}", rootRecordPKs.size(), root.getEntity());
        for(ByteArray dePrimaryKey: rootRecordPKs) {
            if(dePrimaryKey != null) {
                BaseTopic<byte[], DenormalizedRecord> outputTopic = outputTopics.get(root.getDenormalizedName());
                scrubParentIndices(root, root, dePrimaryKey);
                DenormalizedRecord newDeRecord = createDenormalizedRecord(root, root, dePrimaryKey, dePrimaryKey);
                if(logger.isDebugEnabled()) {
                    try {
                        logger.debug(
                                String.format(
                                        "Root Entity: %s / Primary Key: %s",
                                        root.getEntity(), dePrimaryKey.toString()
                                )
                        );
                        logger.debug(mapper.writeValueAsString(newDeRecord));
                    } catch (Exception ex) {
                        // noop
                    }
                }

                outputTopic.write(
                        dePrimaryKey.getBytes(),
                        newDeRecord
                );
            }
            metrics.denormalizedRecordsCreated.mark(1);
            metrics.denormalizedRecordsCreatedByTopic.get(root.getDenormalizedName()).mark(1);
            metrics.denormalizedRecordsToCreate.update(metrics.denormalizedRecordsToCreate.getValue() - 1);
            metrics.denormalizedRecordsToCreateByTopic.get(root.getDenormalizedName())
                    .update(metrics.denormalizedRecordsToCreateByTopic.get(root.getDenormalizedName()).getValue() - 1);
        }
    }

    /**
     * Create the entry name for the denormalized PKs yet to be created
     * @return - The entry name
     */
    protected String createDePKEntryName(Relation root) {
        return String.join(SEP, PK, root.getDenormalizedName());
    }

    /**
     * Simple class for creating a FK multi index
     * @param indexName - The name of the index to create
     * @param indexedTopicName - The name of the indexed topic
     * @return A brand new, shiny index
     */
    protected BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> createFkIndex(
            String indexName,
            String indexedTopicName) {
        MultiIndex<BaseRecord, BaseRecord> index = new MultiIndex<>();
        index.configure(indexName, rawConfig, state, inputTopics.get(indexedTopicName));
        return index;
    }

    /**
     * Creates all indices for all relations provided to Southpaw. Note: indices to the input records
     * can be shared between top level relations.
     */
    protected void createIndices() {
        for(Relation root: relations) {
            // Children - PK, parent key and join key indices for the input topics
            for(Relation child: root.getChildren()) {
                createChildIndices(root, root, child);
            }
        }
    }

    /**
     * Creates an internal record for a denormalized record based on the input record
     * @param inputRecord - The input record used to generate the internal record
     * @return The internal record of the denormalized record that contains the actual values for the input record
     */
    protected Record createInternalRecord(BaseRecord inputRecord) {
        Record internalRecord = new Record();

        for(Map.Entry<String, ?> entry: inputRecord.toMap().entrySet()) {
            internalRecord.setAdditionalProperty(entry.getKey(), entry.getValue());
        }

        return internalRecord;
    }

    /**
     * Creates the join index name from the child relation
     * @param child - The child relation to create the join index name for
     * @return The join index name
     */
    protected String createJoinIndexName(Relation child) {
        return String.join(SEP, JK, child.getEntity(), child.getJoinKey());
    }

    /**
     * Creates all input topics for this relation and its children.
     * @param relation - The relation to create topics for
     * @return A map of topics
     */
    protected Map<String, BaseTopic<BaseRecord, BaseRecord>> createInputTopics(Relation relation)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        Map<String, BaseTopic<BaseRecord, BaseRecord>> topics = new HashMap<>();

        topics.put(relation.getEntity(), createTopic(relation.getEntity()));

        if(relation.getChildren() != null) {
            for (Relation child : relation.getChildren()) {
                topics.putAll(createInputTopics(child));
            }
        }

        return topics;
    }

    /**
     * Creates an output topic for writing the created denormalized records to.
     * @param shortName - The short name of the topic to create
     * @return A shiny new topic
     * @throws ClassNotFoundException -
     * @throws IllegalAccessException -
     * @throws InstantiationException -
     */
    @SuppressWarnings("unchecked")
    protected BaseTopic<byte[], DenormalizedRecord> createOutputTopic(String shortName)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        Map<String, Object> topicConfig = createTopicConfig(shortName);
        Class keySerdeClass = Class.forName(Preconditions.checkNotNull(topicConfig.get(BaseTopic.KEY_SERDE_CLASS_CONFIG).toString()));
        Class valueSerdeClass = Class.forName(Preconditions.checkNotNull(topicConfig.get(BaseTopic.VALUE_SERDE_CLASS_CONFIG).toString()));
        Serde<byte[]> keySerde = (Serde<byte[]>) keySerdeClass.getDeclaredConstructor().newInstance();
        Serde<DenormalizedRecord> valueSerde = (Serde<DenormalizedRecord>) valueSerdeClass.getDeclaredConstructor().newInstance();
        return createTopic(
                shortName,
                topicConfig,
                keySerde,
                valueSerde,
                new BaseFilter(),
                metrics
        );
    }

    /**
     * Creates the parent index name from the parent and child relations
     * @param root - The root relation to create the join index name for
     * @param parent - The parent relation to create the join index name for
     * @param child - The child relation to create the join index name for
     * @return The join index name
     */
    protected String createParentIndexName(Relation root, Relation parent, Relation child) {
        return String.join(SEP, PaK, root.getEntity(), parent.getEntity(), child.getParentKey());
    }

    /**
     * Creates a new topic with the given short name. Pulls the key and value serde classes from the configuration,
     * which should be subclasses of BaseSerde.
     * @param shortName - The short name of the topic, used to construct it's configuration by combining the specific
     *                  configuration based on this short name and the default configuration.
     * @return A shiny, new topic
     */
    @SuppressWarnings("unchecked")
    protected <K extends BaseRecord, V extends BaseRecord> BaseTopic<K, V> createTopic(String shortName)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        Map<String, Object> topicConfig = createTopicConfig(shortName);
        Class keySerdeClass = Class.forName(Preconditions.checkNotNull(topicConfig.get(BaseTopic.KEY_SERDE_CLASS_CONFIG).toString()));
        Class valueSerdeClass = Class.forName(Preconditions.checkNotNull(topicConfig.get(BaseTopic.VALUE_SERDE_CLASS_CONFIG).toString()));
        Class filterClass = Class.forName(topicConfig.getOrDefault(BaseTopic.FILTER_CLASS_CONFIG, BaseTopic.FILTER_CLASS_DEFAULT).toString());
        BaseSerde<K> keySerde = (BaseSerde<K>) keySerdeClass.getDeclaredConstructor().newInstance();
        BaseSerde<V> valueSerde = (BaseSerde<V>) valueSerdeClass.getDeclaredConstructor().newInstance();
        BaseFilter filter = (BaseFilter) filterClass.getDeclaredConstructor().newInstance();
        return createTopic(
                shortName,
                topicConfig,
                keySerde,
                valueSerde,
                filter,
                metrics
        );
    }

    /**
     * Creates a new topic with the given parameters. Also useful for overriding for testing purposes.
     * @param shortName - The short name of the topic
     * @param southpawConfig - The topic configuration
     * @param keySerde - The serde used to (de)serialize the key bytes
     * @param valueSerde - The serde used to (de)serialize the value bytes
     * @param filter - The filter used to filter out consumed records, treating them like a tombstone
     * @param <K> - The key type. Usually a primitive type or a type deriving from BaseRecord
     * @param <V> - The value type. Usually a primitive type or a type deriving from BaseRecord
     * @return A shiny, new topic
     */
    @SuppressWarnings("unchecked")
    protected <K, V> BaseTopic<K, V> createTopic(
            String shortName,
            Map<String, Object> southpawConfig,
            Serde<K> keySerde,
            Serde<V> valueSerde,
            BaseFilter filter,
            Metrics metrics) throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        Class topicClass = Class.forName(Preconditions.checkNotNull(southpawConfig.get(BaseTopic.TOPIC_CLASS_CONFIG).toString()));
        BaseTopic<K, V> topic = (BaseTopic<K, V>) topicClass.getDeclaredConstructor().newInstance();
        keySerde.configure(southpawConfig, true);
        valueSerde.configure(southpawConfig, false);
        filter.configure(southpawConfig);

        topic.configure(new TopicConfig<K, V>()
            .setShortName(shortName)
            .setSouthpawConfig(southpawConfig)
            .setState(state)
            .setKeySerde(keySerde)
            .setValueSerde(valueSerde)
            .setFilter(filter)
            .setMetrics(metrics));

        return topic;
    }

    /**
     * Creates a new map containing the topic config for the given config name. This is a merging of the default config
     * and the specific config for the given config name, if it exists.
     * @param configName - The name of the specific config to use
     * @return A map of configuration settings for a topic
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> createTopicConfig(String configName) {
        Map<String, Object> topicsConfig = (Map<String, Object>) Preconditions.checkNotNull(rawConfig.get("topics"));
        Map<String, Object> defaultConfig = new HashMap<>(Preconditions.checkNotNull((Map<String, Object>) topicsConfig.get("default")));
        Map<String, Object> topicConfig = new HashMap<>(Preconditions.checkNotNull((Map<String, Object>) topicsConfig.get(configName)));
        defaultConfig.putAll(topicConfig);
        return defaultConfig;
    }

    /**
     * Deletes the backups for Southpaw state. Be very careful calling this! Unlike deleteState(), does not
     * require creating a new instance to continue processing.
     */
    public static void deleteBackups(Map<String, Object> config) {
        BaseState state =  new RocksDBState(config);
        state.deleteBackups();
    }

    /**
     * Resets Southpaw by deleting it's state. Denormalized records written to output topics are not deleted.
     * You must create a new Southpaw object to keep processing.
     */
    public static void deleteState(Map<String, Object> config) {
        BaseState state =  new RocksDBState(config);
        state.delete();
    }

    /**
     * Searches for the relation for the given child entity.
     * @param relation - The relation (and its children) to search
     * @param childEntity - The child entity to search for
     * @return The relation for the given child entity and it's parent, or null if it doesn't exist. Returned as a
     * Pair<Parent, Child> object. If the child entity found is the root entity, the Parent is null.
     */
    protected AbstractMap.SimpleEntry<Relation, Relation> getRelation(Relation relation, String childEntity) {
        Preconditions.checkNotNull(relation);
        if(relation.getEntity().equals(childEntity)) return new AbstractMap.SimpleEntry<>(null, relation);
        if(relation.getChildren() == null) return null;
        for(Relation child: relation.getChildren()) {
            if(child.getEntity().equals(childEntity)) return new AbstractMap.SimpleEntry<>(relation, child);
            AbstractMap.SimpleEntry<Relation, Relation> retVal = getRelation(child, childEntity);
            if(retVal != null) return retVal;
        }
        return null;
    }

    /**
     * Loads all top level relations from the given URIs. Needs to fit the relations JSON schema.
     * @param uris - The URIs to load
     * @return Top level relations from the given URIs
     * @throws IOException -
     * @throws URISyntaxException -
     */
    protected static Relation[] loadRelations(List<URI> uris) throws IOException, URISyntaxException {
        List<Relation> retVal = new ArrayList<>();
        for(URI uri: uris) {
            retVal.addAll(Arrays.asList(mapper.readValue(FileHelper.loadFileAsString(uri), Relation[].class)));
        }
        return retVal.toArray(new Relation[retVal.size()]);
    }

    public static void main(String args[]) throws Exception {
        String BUILD = "build";
        String CONFIG = "config";
        String DELETE_BACKUP = "delete-backup";
        String DELETE_STATE = "delete-state";
        String HELP = "help";
        String RELATIONS = "relations";
        String RESTORE = "restore";
        String VERIFY_STATE = "verify-state";

        OptionParser parser = new OptionParser() {
            {
                accepts(CONFIG, "Path to the Southpaw config file").withRequiredArg().required();
                accepts(RELATIONS, "Paths to one or more files containing input record relations").withRequiredArg().required();
                accepts(BUILD, "Builds denormalized records using an existing state.");
                accepts(DELETE_BACKUP, "Deletes existing backups specified in the config file. BE VERY CAREFUL WITH THIS!!!");
                accepts(DELETE_STATE, "Deletes the existing state specified in the config file. BE VERY CAREFUL WITH THIS!!!");
                accepts(RESTORE, "Restores the state from existing backups.");
                accepts(HELP, "Since you are seeing this, you probably know what this is for. :)").forHelp();
                accepts(VERIFY_STATE, "Verifies that the Southpaw state indices and reverse indices are in sync");
            }
        };
        OptionSet options = parser.parse(args);

        if (options.has(HELP)) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(FileHelper.getInputStream(new URI(options.valueOf(CONFIG).toString())));
        List<?> relations = options.valuesOf(RELATIONS);
        List<URI> relURIs = new ArrayList<>();
        for(Object relation: relations) relURIs.add(new URI(relation.toString()));

        if(options.has(VERIFY_STATE)) {
            Southpaw southpaw = new Southpaw(config, relURIs);
            try{
                southpaw.verifyState();
            } finally {
                southpaw.close();
            }
            System.exit(0);
        }

        if(options.has(DELETE_BACKUP)) {
            Southpaw.deleteBackups(config);
        }
        if(options.has(DELETE_STATE)) {
            Southpaw.deleteState(config);
        }
        if(options.has(RESTORE)) {
            Southpaw.restore(config);
        }

        if(options.has(BUILD)) {
            Southpaw southpaw = new Southpaw(config, relURIs);
            try{
                southpaw.run(0);
            } finally {
                southpaw.close();
            }
        }
    }

    /**
     * Reports the number of denormalized records that are queued to be created
     */
    protected void reportRecordsToCreate() {
        long totalRecords = 0;
        for(Map.Entry<String, StaticGauge<Long>> entry: metrics.denormalizedRecordsToCreateByTopic.entrySet()) {
            long records = entry.getValue().getValue();
            totalRecords += records;
            metrics.denormalizedRecordsToCreateByTopic.get(entry.getKey()).update(records);
        }
        metrics.denormalizedRecordsToCreate.update(totalRecords);
    }

    /**
     * Sums up and reports the total lag for all input topics
     */
    protected void reportTotalLag() {
        long topicLag;
        long totalLag = 0;
        for(Map.Entry<String, StaticGauge<Long>> entry: metrics.topicLagByTopic.entrySet()) {
            topicLag = entry.getValue().getValue();
            totalLag += topicLag;
            metrics.topicLagByTopic.get(entry.getKey()).update(topicLag);
        }
        metrics.topicLag.update(totalLag);
    }

    /**
     * Restores Southpaw state from the latest backup.
     */
    public static void restore(Map<String, Object> config){
        RocksDBState state = new RocksDBState(config);
        state.restore();
    }

    /**
     * Main method to call for reading input records and building denormalized records. Appropriately
     * switches between buildChildIndices and build to most efficiently build the records.
     * @param runTimeS - Sets an amount of time in seconds for this method to run. The method will not run this amount
     *                of time exactly, but will stop after processing the latest batch of records. If set to 0,
     *                it will run until interrupted. Probably most useful for testing.
     */
    public void run(int runTimeS) {
        build(runTimeS);
    }

    /**
     * Scrubs the parent indices of the given root primary key starting at the given relation. This is needed when a
     * tombstone record is seen for the root so that we remove all references to the now defunct root PK so we no
     * longer try to create (empty) records for it.
     * @param root - The root relation of the parent relation
     * @param parent  - The parent relation of the parent index to scrub
     * @param rootPrimaryKey - The primary key of the root record prior to the tombstone triggering this scrubbing
     */
    protected void scrubParentIndices(Relation root, Relation parent, ByteArray rootPrimaryKey) {
        Preconditions.checkNotNull(root);
        Preconditions.checkNotNull(parent);

        if(parent.getChildren() != null && rootPrimaryKey != null) {
            for(Relation child: parent.getChildren()) {
                BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> parentIndex =
                        fkIndices.get(createParentIndexName(root, parent, child));
                Set<ByteArray> oldForeignKeys = ((Reversible) parentIndex).getForeignKeys(rootPrimaryKey);
                if(oldForeignKeys != null) {
                    for(ByteArray oldForeignKey: ImmutableSet.copyOf(oldForeignKeys)) {
                        parentIndex.remove(oldForeignKey, rootPrimaryKey);
                    }
                }
                scrubParentIndices(root, child, rootPrimaryKey);
            }
        }
    }

    /**
     * Updates the join index for the given child relation using the new record and the old PK index entry.
     * @param relation - The child relation of the join index
     * @param primaryKey - The primary key of the child record.
     * @param newRecord - The new version of the child record. May technically not be the latest version of a
     *                  record, but that is ok, since the index will eventually be updated with the latest
     *                  record.
     */
    protected void updateJoinIndex(
            Relation relation,
            ByteArray primaryKey,
            ConsumerRecord<BaseRecord, BaseRecord> newRecord) {
        Preconditions.checkNotNull(relation.getJoinKey());
        Preconditions.checkNotNull(newRecord);
        BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> joinIndex = fkIndices.get(createJoinIndexName(relation));
        Set<ByteArray> oldJoinKeys = ((Reversible) joinIndex).getForeignKeys(primaryKey);
        ByteArray newJoinKey = null;
        if(newRecord.value() != null) {
            newJoinKey = ByteArray.toByteArray(newRecord.value().get(relation.getJoinKey()));
        }
        if (oldJoinKeys != null && oldJoinKeys.size() > 0) {
            for(ByteArray oldJoinKey: oldJoinKeys) {
                if(!oldJoinKey.equals(newJoinKey)) {
                    joinIndex.remove(oldJoinKey, primaryKey);
                }
            }
        }
        if (newJoinKey != null) {
            joinIndex.add(newJoinKey, primaryKey);
        }
    }

    /**
     * Updates the parent index of the given relations.
     * @param root - The root relation of the parent relation
     * @param parent - The parent relation of the parent index
     * @param child - The child relation of the parent index
     * @param rootPrimaryKey - The primary key of the new root record
     * @param newParentKey - The new parent key (may or may not differ from the old one)
     */
    protected void updateParentIndex(
            Relation root,
            Relation parent,
            Relation child,
            ByteArray rootPrimaryKey,
            ByteArray newParentKey
    ) {
        Preconditions.checkNotNull(root);
        Preconditions.checkNotNull(parent);
        Preconditions.checkNotNull(child);
        Preconditions.checkNotNull(rootPrimaryKey);

        BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>> parentIndex =
                fkIndices.get(createParentIndexName(root, parent, child));
        if (newParentKey != null) parentIndex.add(newParentKey, rootPrimaryKey);
    }

    /**
     * Validates the given child relation.
     * @param relation - The child relation to validate
     */
    protected static void validateChildRelation(Relation relation) {
        Preconditions.checkNotNull(
                relation.getEntity(),
                "A child relation must correspond to an input record"
        );
        Preconditions.checkNotNull(
                relation.getJoinKey(),
                String.format("Child relation '%s' must have a join key", relation.getEntity())
        );
        Preconditions.checkNotNull(
                relation.getParentKey(),
                String.format("Child relation '%s' must have a parent key", relation.getEntity())
        );
    }

    /**
     * Validates that the given root relations are properly constructed.
     * @param relations - The relations to validate
     */
    protected static void validateRootRelations(Relation[] relations) {
        for(Relation relation: relations) {
            Preconditions.checkNotNull(
                    relation.getDenormalizedName(),
                    "A root relation must have a denormalized object name"
            );
            Preconditions.checkNotNull(
                    relation.getEntity(),
                    String.format("Top level relation '%s' must correspond to an input record type", relation.getDenormalizedName())
            );
            Preconditions.checkNotNull(
                    relation.getChildren(),
                    String.format("Top level relation '%s' must have children", relation.getDenormalizedName())
            );

            for(Relation child: relation.getChildren()) {
                validateChildRelation(child);
            }
        }
    }

    /**
     * Utility command to verify all indices and reverse indices in the State are in sync with each other. Keys that
     * are not set properly in the index and reverse index are logged to error.
     * <b>Note: this requires a full scan of each index dataset. This could be an expensive operation on larger datasets</b>
     */
    protected void verifyState() {
        for(Map.Entry<String, BaseIndex<BaseRecord, BaseRecord, Set<ByteArray>>> index: fkIndices.entrySet()) {
            logger.info("Verifying index state integrity: " + index.getValue().getIndexedTopic().getShortName());
            Set<String> missingIndexKeys = ((MultiIndex)index.getValue()).verifyIndexState();
            if(missingIndexKeys.isEmpty()){
                logger.info("Index " + index.getValue().getIndexedTopic().getShortName() +  " integrity check complete");
            } else {
                logger.error("Index " + index.getValue().getIndexedTopic().getShortName() + " check failed for the following " + missingIndexKeys.size() + " keys: " + missingIndexKeys.toString());
            }

            logger.info("Verifying reverse index state integrity: " + index.getValue().getIndexedTopic().getShortName());
            Set<String> missingReverseIndexKeys = ((MultiIndex)index.getValue()).verifyReverseIndexState();
            if(missingReverseIndexKeys.isEmpty()){
                logger.info("Reverse index " + index.getValue().getIndexedTopic().getShortName() +  " integrity check complete");
            } else {
                logger.error("Reverse index " + index.getValue().getIndexedTopic().getShortName() + " check failed for the following " + missingReverseIndexKeys.size() + " keys: " + missingReverseIndexKeys.toString());
            }
        }
    }
}
