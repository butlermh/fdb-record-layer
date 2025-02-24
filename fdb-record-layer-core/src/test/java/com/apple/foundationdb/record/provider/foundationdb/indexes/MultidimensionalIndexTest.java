/*
 * MultidimensionalIndexTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2023 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.provider.foundationdb.indexes;

import com.apple.foundationdb.async.RTree;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.IndexScanType;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorIterator;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.TestRecordsMultidimensionalProto;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexOptions;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.metadata.expressions.DimensionsKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoredRecord;
import com.apple.foundationdb.record.provider.foundationdb.IndexScanBounds;
import com.apple.foundationdb.record.provider.foundationdb.IndexScanParameters;
import com.apple.foundationdb.record.provider.foundationdb.MultidimensionalIndexScanBounds;
import com.apple.foundationdb.record.provider.foundationdb.query.FDBRecordStoreQueryTestBase;
import com.apple.foundationdb.record.query.RecordQuery;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.expressions.QueryComponent;
import com.apple.foundationdb.record.query.plan.AvailableFields;
import com.apple.foundationdb.record.query.plan.IndexKeyValueToPartialRecord;
import com.apple.foundationdb.record.query.plan.PlannableIndexTypes;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.TranslationMap;
import com.apple.foundationdb.record.query.plan.cascades.explain.Attribute;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.plans.QueryResult;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryCoveringIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryFetchFromPartialRecordPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryIndexPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.record.query.plan.plans.TranslateValueFunction;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.Tags;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.apple.foundationdb.async.RTree.Storage.BY_NODE;
import static com.apple.foundationdb.async.RTree.Storage.BY_SLOT;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concat;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concatenateFields;
import static com.apple.foundationdb.record.metadata.Key.Expressions.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for multidimensional type indexes.
 */
@Tag(Tags.RequiresFDB)
class MultidimensionalIndexTest extends FDBRecordStoreQueryTestBase {
    private static final Logger logger = LoggerFactory.getLogger(MultidimensionalIndexTest.class);

    private static final long epochMean = 1690360647L;  // 2023/07/26
    private static final long durationCutOff = 30L * 60L; // meetings are at least 30 minutes long
    private static final long expirationCutOff = 30L * 24L * 60L * 60L; // records expire in a month

    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");

    protected void openRecordStore(FDBRecordContext context) throws Exception {
        openRecordStore(context, NO_HOOK);
    }

    protected void openRecordStore(final FDBRecordContext context, final RecordMetaDataHook hook) throws Exception {
        RecordMetaDataBuilder metaDataBuilder = RecordMetaData.newBuilder().setRecords(TestRecordsMultidimensionalProto.getDescriptor());
        metaDataBuilder.getRecordType("MyMultidimensionalRecord").setPrimaryKey(concatenateFields("rec_domain", "rec_no"));
        metaDataBuilder.addIndex("MyMultidimensionalRecord",
                new Index("calendarNameEndEpochStartEpoch",
                        concat(field("calendar_name"), field("end_epoch"), field("start_epoch")),
                        IndexTypes.VALUE));

        hook.apply(metaDataBuilder);
        createOrOpenRecordStore(context, metaDataBuilder.getRecordMetaData());
    }

    @CanIgnoreReturnValue
    RecordMetaDataBuilder addCalendarNameStartEpochIndex(@Nonnull final RecordMetaDataBuilder metaDataBuilder) {
        metaDataBuilder.addIndex("MyMultidimensionalRecord",
                new Index("calendarNameStartEpoch",
                        concat(field("calendar_name"), field("start_epoch"), field("end_epoch")),
                        IndexTypes.VALUE));
        return metaDataBuilder;
    }

    @CanIgnoreReturnValue
    RecordMetaDataBuilder addMultidimensionalIndex(@Nonnull final RecordMetaDataBuilder metaDataBuilder,
                                                   @Nonnull final String storage,
                                                   final boolean storeHilbertValues) {
        metaDataBuilder.addIndex("MyMultidimensionalRecord",
                new Index("EventIntervals", DimensionsKeyExpression.of(field("calendar_name"),
                        concat(field("start_epoch"), field("end_epoch"))),
                        IndexTypes.MULTIDIMENSIONAL, ImmutableMap.of(IndexOptions.RTREE_STORAGE, storage,
                        IndexOptions.RTREE_STORE_HILBERT_VALUES, Boolean.toString(storeHilbertValues))));
        return metaDataBuilder;
    }

    public void loadRecords(@Nonnull final RecordMetaDataHook hook, final long seed, final List<String> calendarNames, final int numSamples) throws Exception {
        final Random random = new Random(seed);
        final long epochStandardDeviation = 3L * 24L * 60L * 60L;
        final long durationCutOff = 30L * 60L; // meetings are at least 30 minutes long
        final long durationStandardDeviation = 60L * 60L;
        final long expirationStandardDeviation = 24L * 60L * 60L;
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, hook);
            for (long recNo = 0; recNo < numSamples; recNo++) {
                final String calendarName = calendarNames.get(random.nextInt(calendarNames.size()));
                final long startEpoch = (long)(random.nextGaussian() * epochStandardDeviation) + epochMean;
                final long endEpoch = startEpoch + durationCutOff + (long)(Math.abs(random.nextGaussian()) * durationStandardDeviation);
                final long duration = endEpoch - startEpoch;
                Verify.verify(duration > 0L);
                final long expirationEpoch = endEpoch + expirationCutOff  + (long)(Math.abs(random.nextGaussian()) * expirationStandardDeviation);
                logRecord(calendarName, startEpoch, endEpoch, expirationEpoch);
                final TestRecordsMultidimensionalProto.MyMultidimensionalRecord record =
                        TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                                .setRecNo(recNo)
                                .setCalendarName(calendarName)
                                .setStartEpoch(startEpoch)
                                .setEndEpoch(endEpoch)
                                .setExpirationEpoch(expirationEpoch)
                                .build();
                recordStore.saveRecord(record);
            }
            commit(context);
        }
    }

    private static void logRecord(@Nonnull final String calendarName, final Long startEpoch, final Long endEpoch, final Long expirationEpoch) {
        if (logger.isTraceEnabled()) {
            final Long duration = (startEpoch == null || endEpoch  == null) ? null : (endEpoch - startEpoch);
            Verify.verify(duration == null || duration > 0L);

            final String startAsString = startEpoch == null ? "null" : timeFormat.format(new Date(startEpoch * 1000));
            final String endAsString = endEpoch == null ? "null" : timeFormat.format(new Date(endEpoch * 1000));
            final String durationAsString = duration == null ? "null" : (duration / 3600L + "h" +
                                                                         (duration % 3600L) / 60L + "m" + (duration % 3600L) % 60L + "s");
            logger.trace("calendarName: " + calendarName +
                         "; start: " + startAsString + "; end: " + endAsString +
                         "; duration: " + durationAsString +
                         "; startEpoch: " + (startEpoch == null ? "null" : startEpoch) +
                         "; endEpoch: " + (endEpoch == null ? "null" : endEpoch) +
                         "; expirationEpoch: " + (expirationEpoch == null ? "null" : expirationEpoch));
        }
    }

    public void loadRecordsWithNulls(@Nonnull final RecordMetaDataHook hook, final long seed,
                                     final List<String> calendarNames, final int numSamples) throws Exception {
        final Random random = new Random(seed);
        final long epochStandardDeviation = 3L * 24L * 60L * 60L;
        final long durationStandardDeviation = 60L * 60L;
        final long expirationStandardDeviation = 24L * 60L * 60L;
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, hook);
            for (long recNo = 0; recNo < numSamples; recNo++) {
                final String calendarName = calendarNames.get(random.nextInt(calendarNames.size()));
                final Long startEpoch = random.nextFloat() < 0.10f ? null : ((long)(random.nextGaussian() * epochStandardDeviation) + epochMean);
                final Long endEpoch =
                        random.nextFloat() < 0.10f ? null : ((startEpoch == null ? epochMean : startEpoch) + durationCutOff +
                        (long)(Math.abs(random.nextGaussian()) * durationStandardDeviation));
                final Long duration = (startEpoch == null || endEpoch  == null) ? null : (endEpoch - startEpoch);
                Verify.verify(duration == null || duration > 0L);
                final Long expirationEpoch =
                        random.nextFloat() < 0.10f
                        ? null
                        : (endEpoch == null ? epochMean : endEpoch) + expirationCutOff  +
                          (long)(Math.abs(random.nextGaussian()) * expirationStandardDeviation);
                logRecord(calendarName, startEpoch, endEpoch, expirationEpoch);
                final var recordBuilder =
                        TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                                .setRecNo(recNo)
                                .setCalendarName(calendarName);
                if (startEpoch != null) {
                    recordBuilder.setStartEpoch(startEpoch);
                }
                if (endEpoch != null) {
                    recordBuilder.setEndEpoch(endEpoch);
                }
                if (expirationEpoch != null) {
                    recordBuilder.setExpirationEpoch(expirationEpoch);
                }
                recordStore.saveRecord(recordBuilder.build());
            }
            commit(context);
        }
    }

    public void deleteRecords(@Nonnull final RecordMetaDataHook hook, final long seed, final int numRecords,
                              final int numDeletes) throws Exception {
        Preconditions.checkArgument(numDeletes <= numRecords);
        final Random random = new Random(seed);
        final List<Integer> recNos = IntStream.range(0, numRecords)
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(recNos, random);
        final List<Integer> recNosToBeDeleted = recNos.subList(0, numDeletes);
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, hook);
            for (final int recNo : recNosToBeDeleted) {
                recordStore.deleteRecord(Tuple.from(recNo));
            }
            commit(context);
        }
    }

    public void loadSpecificRecordsWithNullsAndMins(@Nonnull final RecordMetaDataHook hook) throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, hook);
            TestRecordsMultidimensionalProto.MyMultidimensionalRecord record =
                    TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                            .setRecNo(1L)
                            .setCalendarName("business")
                            .setStartEpoch(Long.MIN_VALUE)
                            .setEndEpoch(1L)
                            .setExpirationEpoch(2L)
                            .build();
            recordStore.saveRecord(record);
            record = TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                    .setRecNo(2L)
                    .setCalendarName("business")
                    .setStartEpoch(Long.MIN_VALUE)
                    .setEndEpoch(Long.MIN_VALUE)
                    .setExpirationEpoch(3L)
                    .build();
            recordStore.saveRecord(record);
            record = TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                    .setRecNo(3L)
                    .setCalendarName("business")
                    .setEndEpoch(1L)
                    .setExpirationEpoch(3L)
                    .build();
            recordStore.saveRecord(record);
            record = TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder()
                    .setRecNo(4L)
                    .setCalendarName("business")
                    .setExpirationEpoch(3L)
                    .build();
            recordStore.saveRecord(record);

            commit(context);
        }
    }

    @ParameterizedTest
    @MethodSource("argumentsForBasicReads")
    void basicRead(@Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndex = metaDataBuilder -> addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
        loadRecords(additionalIndex, 0, ImmutableList.of("business"), 500);
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, additionalIndex);
            FDBStoredRecord<Message> rec = recordStore.loadRecord(Tuple.from(null, 1L));
            assertNotNull(rec);
            TestRecordsMultidimensionalProto.MyMultidimensionalRecord.Builder recordBuilder =
                    TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder();
            recordBuilder.mergeFrom(rec.getRecord());
            assertEquals("business", recordBuilder.getCalendarName());
            commit(context);
        }
    }

    @ParameterizedTest
    @MethodSource("argumentsForBasicReads")
    void basicReadWithNulls(@Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndex = metaDataBuilder -> addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
        loadRecords(additionalIndex, 0, ImmutableList.of("business"), 500);
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, additionalIndex);
            FDBStoredRecord<Message> rec = recordStore.loadRecord(Tuple.from(null, 1L));
            assertNotNull(rec);
            TestRecordsMultidimensionalProto.MyMultidimensionalRecord.Builder recordBuilder =
                    TestRecordsMultidimensionalProto.MyMultidimensionalRecord.newBuilder();
            recordBuilder.mergeFrom(rec.getRecord());
            assertEquals("business", recordBuilder.getCalendarName());
            commit(context);
        }
    }

    static Stream<Arguments> argumentsForBasicReads() {
        return Stream.of(Arguments.of(BY_NODE.toString(), false),
                Arguments.of(BY_NODE.toString(), true),
                Arguments.of(BY_SLOT.toString(), false),
                Arguments.of(BY_SLOT.toString(), true));
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReads")
    void indexRead(final long seed, final int numRecords, @Nonnull final String storage,
                   final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecords(additionalIndexes, seed, ImmutableList.of("business"), numRecords);
        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 5L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                (Long)null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    /**
     * Arguments for index reads. Note that for each run we run the tests with a different seed. That is intentionally
     * done in a way that the seed itself is handed over to the test case which causes that seed to be reported.
     * If a testcase fails, the particular seed reported can be used here to recreate the exact conditions of the
     * failure.
     * @return a stream of arguments
     */
    static Stream<Arguments> argumentsForIndexReads() {
        final Random random = new Random(System.currentTimeMillis());
        return Stream.of(
                Arguments.of(random.nextLong(), 10, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 10, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 100, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 100, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 300, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 300, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 1000, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 1000, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 5000, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 5000, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 10, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 10, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 100, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 100, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 300, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 300, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 1000, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 1000, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 5000, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 5000, BY_NODE.toString(), true)
        );
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReads")
    void indexReadWithNulls(final long seed, final int numRecords, @Nonnull final String storage,
                            final boolean storeHilbertValues) throws Exception {
        RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecordsWithNulls(additionalIndexes, seed, ImmutableList.of("business"), numRecords);
        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 5L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                (Long)null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.or(
                                Query.field("start_epoch").isNull(),
                                Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive)),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @Test
    void indexReadWithNullsAndMins1() throws Exception {
        RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, BY_NODE.toString(), true);
                };
        loadSpecificRecordsWithNullsAndMins(additionalIndexes);
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                (Long)null, 0L,
                                0L, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.or(
                                Query.field("start_epoch").isNull(),
                                Query.field("start_epoch").lessThanOrEquals(0L)),
                        Query.field("end_epoch").greaterThanOrEquals(0L));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @Test
    void indexReadWithNullsAndMins2() throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, BY_NODE.toString(), true);
                };
        loadSpecificRecordsWithNullsAndMins(additionalIndexes);
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                Long.MIN_VALUE, 0L,
                                0L, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.field("start_epoch").lessThanOrEquals(0L),
                        Query.field("end_epoch").greaterThanOrEquals(0L));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReads")
    void indexReadIsNull(final long seed, final int numRecords, @Nonnull final String storage,
                         final boolean storeHilbertValues) throws Exception {
        RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecordsWithNulls(additionalIndexes, seed, ImmutableList.of("business"), numRecords);
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                (Long)null, Long.MIN_VALUE,
                                null, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.field("start_epoch").isNull());

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReadWithIn")
    void indexReadWithIn(final long seed, final int numRecords, final int numIns) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, BY_NODE.toString(), true);
                };

        loadRecords(additionalIndexes, seed, ImmutableList.of("business"), numRecords);

        final ImmutableList.Builder<MultidimensionalIndexScanBounds.SpatialPredicate> orTermsBuilder = ImmutableList.builder();
        final ImmutableList.Builder<Long> probesBuilder = ImmutableList.builder();
        final Random random = new Random(0);
        for (int i = 0; i < numIns; i++) {
            final long probe = (long)Math.abs(random.nextGaussian() * (3L * 60L * 60L)) + epochMean;
            probesBuilder.add(probe);
            final MultidimensionalIndexScanBounds.Hypercube hyperCube =
                            new MultidimensionalIndexScanBounds.Hypercube(
                                    ImmutableList.of(
                                            TupleRange.betweenInclusive(Tuple.from(probe), Tuple.from(probe)),
                                            TupleRange.betweenInclusive(null, null)));
            orTermsBuilder.add(hyperCube);
        }

        final MultidimensionalIndexScanBounds.Or orBounds =
                new MultidimensionalIndexScanBounds.Or(orTermsBuilder.build());

        final MultidimensionalIndexScanBounds.Hypercube greaterThanBounds =
                new MultidimensionalIndexScanBounds.Hypercube(
                        ImmutableList.of(
                                TupleRange.betweenInclusive(null, null),
                                TupleRange.betweenInclusive(Tuple.from(1690476099L), null)));

        final MultidimensionalIndexScanBounds.And andBounds =
                new MultidimensionalIndexScanBounds.And(ImmutableList.of(greaterThanBounds, orBounds));

        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new CompositeScanParameters(
                                new MultidimensionalIndexScanBounds(TupleRange.allOf(Tuple.from("business")), andBounds)),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.field("start_epoch").in(probesBuilder.build()),
                        Query.field("end_epoch").greaterThanOrEquals(1690476099L));

        planner.setConfiguration(planner.getConfiguration().asBuilder().setOptimizeForIndexFilters(true).build());

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    /**
     * Arguments for index reads using an IN clause. Note that for each run we run the tests with a different seed.
     * That is intentionally done in a way that the seed itself is handed over to the test case which causes that seed
     * to be reported. If a testcase fails, the particular seed reported can be used here to recreate the exact
     * conditions of the failure.
     * @return a stream of arguments
     */
    static Stream<Arguments> argumentsForIndexReadWithIn() {
        final Random random = new Random(System.currentTimeMillis());
        return Stream.of(
                Arguments.of(random.nextLong(), 5000, 100),
                Arguments.of(random.nextLong(), 5000, 1000),
                Arguments.of(random.nextLong(), 5000, 10000)
        );
    }


    @ParameterizedTest
    @MethodSource("argumentsForIndexReadsAfterDeletes")
    void indexReadsAfterDeletes(final long seed, final int numRecords, final int numDeletes,
                                @Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecordsWithNulls(additionalIndexes, seed, ImmutableList.of("business"), numRecords);
        deleteRecords(additionalIndexes, seed, numRecords, numDeletes);
        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 5L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business",
                                (Long)null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.or(Query.field("start_epoch").isNull(),
                                Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive)),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    static Stream<Arguments> argumentsForIndexReadsAfterDeletes() {
        final Random random = new Random(System.currentTimeMillis());
        return Stream.of(
                Arguments.of(random.nextLong(), 10, random.nextInt(10) + 1, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 10, random.nextInt(10) + 1, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 100, random.nextInt(100) + 1, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 100, random.nextInt(100) + 1, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 300, random.nextInt(300) + 1, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 300, random.nextInt(300) + 1, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 1000, random.nextInt(1000) + 1, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 1000, random.nextInt(1000) + 1, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 5000, random.nextInt(5000) + 1, RTree.Storage.BY_SLOT.toString(), false),
                Arguments.of(random.nextLong(), 5000, random.nextInt(5000) + 1, RTree.Storage.BY_SLOT.toString(), true),
                Arguments.of(random.nextLong(), 10, random.nextInt(10) + 1, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 10, random.nextInt(10) + 1, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 100, random.nextInt(100) + 1, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 100, random.nextInt(100) + 1, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 300, random.nextInt(300) + 1, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 300, random.nextInt(300) + 1, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 1000, random.nextInt(1000) + 1, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 1000, random.nextInt(1000) + 1, BY_NODE.toString(), true),
                Arguments.of(random.nextLong(), 5000, random.nextInt(5000) + 1, BY_NODE.toString(), false),
                Arguments.of(random.nextLong(), 5000, random.nextInt(5000) + 1, BY_NODE.toString(), true)
        );
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReads")
    void indexSkipScan(final long seed, final int numRecords, @Nonnull final String storage,
                       final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecords(additionalIndexes, seed, ImmutableList.of("business", "private"), numRecords);

        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 5L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business", "private",
                                null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.or(
                                Query.field("start_epoch").isNull(),
                                Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive)),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @SuppressWarnings("resource")
    @Test
    void continuation() throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, BY_NODE.toString(), true);
                };
        loadRecords(additionalIndexes, 0, ImmutableList.of("business", "private"), 500);

        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 10L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business", "private",
                                null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);

        final Set<Message> actualResults = Sets.newHashSet();
        byte[] continuation = null;
        do {
            try (FDBRecordContext context = openContext()) {
                openRecordStore(context, additionalIndexes);
                final RecordCursorIterator<QueryResult> recordCursorIterator =
                        indexPlan.executePlan(recordStore,
                                        EvaluationContext.empty(), continuation,
                                        ExecuteProperties.SERIAL_EXECUTE.setReturnedRowLimit(4))
                                .asIterator();
                int numRecordsInBatch = 0;
                while (recordCursorIterator.hasNext()) {
                    // make sure we are not adding duplicates
                    Assertions.assertTrue(actualResults.add(Objects.requireNonNull(recordCursorIterator.next()).getMessage()));
                    numRecordsInBatch ++;
                }
                continuation = recordCursorIterator.getContinuation();
                // Must be the returned row limit or smaller if this is the last batch.
                Assertions.assertTrue((continuation == null && numRecordsInBatch <= 4) || numRecordsInBatch == 4);
                commit(context);
            }
        } while (continuation != null);

        final QueryComponent filter =
                Query.and(
                        Query.or(
                                Query.field("start_epoch").isNull(),
                                Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive)),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive));

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndexes, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @ParameterizedTest
    @MethodSource("argumentsForBasicReads")
    void coveringIndexScanWithFetch(@Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    addCalendarNameStartEpochIndex(metaDataBuilder);
                    addMultidimensionalIndex(metaDataBuilder, storage, storeHilbertValues);
                };
        loadRecordsWithNulls(additionalIndexes, 0, ImmutableList.of("business", "private"), 500);

        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 10L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new HypercubeScanParameters("business", "private",
                                null, intervalEndInclusive,
                                intervalStartInclusive, null),
                        false);

        final RecordType myMultidimensionalRecord = recordStore.getRecordMetaData().getRecordType("MyMultidimensionalRecord");
        final Index index = recordStore.getRecordMetaData().getIndex("EventIntervals");
        final AvailableFields availableFields =
                AvailableFields.fromIndex(myMultidimensionalRecord,
                        index,
                        PlannableIndexTypes.DEFAULT,
                        concat(Key.Expressions.field("rec_domain"), Key.Expressions.field("rec_no")),
                        indexPlan);
        final IndexKeyValueToPartialRecord.Builder indexKeyToPartialRecord =
                Objects.requireNonNull(availableFields.buildIndexKeyValueToPartialRecord(myMultidimensionalRecord));
        final RecordQueryCoveringIndexPlan coveringIndexPlan =
                new RecordQueryCoveringIndexPlan(indexPlan,
                        "MyMultidimensionalRecord",
                        AvailableFields.NO_FIELDS,
                        indexKeyToPartialRecord.build());
        final Set<Message> actualResults = getResults(additionalIndexes, coveringIndexPlan);
        Assertions.assertEquals(57, actualResults.size());
        actualResults.forEach(record -> {
            final Descriptors.Descriptor descriptorForType = record.getDescriptorForType();
            final Descriptors.FieldDescriptor calendarNameField = descriptorForType.findFieldByName("calendar_name");
            Assertions.assertTrue(record.hasField(calendarNameField));
            Assertions.assertTrue(Sets.newHashSet("business", "private")
                    .contains(Objects.requireNonNull((String)record.getField(calendarNameField))));

            final Descriptors.FieldDescriptor startEpochField = descriptorForType.findFieldByName("start_epoch");
            final Descriptors.FieldDescriptor endEpochField = descriptorForType.findFieldByName("end_epoch");
            final Long startEpoch = record.hasField(startEpochField) ? (Long)record.getField(startEpochField) : null;
            final Long endEpoch = record.hasField(endEpochField) ? (Long)record.getField(endEpochField) : null;
            Assertions.assertTrue(startEpoch == null || startEpoch > 0);
            Assertions.assertTrue(endEpoch == null || endEpoch > 0);
            Assertions.assertTrue(startEpoch == null || endEpoch == null || startEpoch < endEpoch);

            final Descriptors.FieldDescriptor expirationEpochField = descriptorForType.findFieldByName("expiration_epoch");
            Assertions.assertFalse(record.hasField(expirationEpochField));
        });

        final var fetchPlan = new RecordQueryFetchFromPartialRecordPlan(coveringIndexPlan, TranslateValueFunction.UNABLE_TO_TRANSLATE,
                Type.any(), RecordQueryFetchFromPartialRecordPlan.FetchIndexRecords.PRIMARY_KEY);
        final Set<Message> actualResultsAfterFetch = getResults(additionalIndexes, fetchPlan);

        Assertions.assertEquals(57, actualResultsAfterFetch.size());
        actualResults.forEach(record -> {
            final Descriptors.Descriptor descriptorForType = record.getDescriptorForType();

            final Descriptors.FieldDescriptor endEpochField = descriptorForType.findFieldByName("end_epoch");
            final Long endEpoch = record.hasField(endEpochField) ? (Long)record.getField(endEpochField) : null;
            Assertions.assertTrue(endEpoch == null || endEpoch > 0);
            final Descriptors.FieldDescriptor expirationEpochField = descriptorForType.findFieldByName("expiration_epoch");
            final Long expirationEpoch = record.hasField(expirationEpochField) ? (Long)record.getField(expirationEpochField) : null;
            Assertions.assertTrue(expirationEpoch == null || expirationEpoch > 0);
            Assertions.assertTrue(endEpoch == null || expirationEpoch == null || endEpoch < expirationEpoch);
        });
    }

    @ParameterizedTest
    @MethodSource("argumentsForIndexReads")
    void indexScan3D(final long seed, final int numRecords, @Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndex = metaDataBuilder ->
                metaDataBuilder.addIndex("MyMultidimensionalRecord",
                        new Index("EventIntervals3D", DimensionsKeyExpression.of(field("calendar_name"),
                                concat(field("start_epoch"), field("end_epoch"), field("expiration_epoch"))),
                                IndexTypes.MULTIDIMENSIONAL, ImmutableMap.of(IndexOptions.RTREE_STORAGE, storage,
                                IndexOptions.RTREE_STORE_HILBERT_VALUES, Boolean.toString(storeHilbertValues))));

        loadRecordsWithNulls(additionalIndex, seed, ImmutableList.of("business"), numRecords);

        final long intervalStartInclusive = epochMean + 3600L;
        final long intervalEndInclusive = epochMean + 5L * 3600L;
        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals3D",
                        new HypercubeScanParameters("business",
                                Long.MIN_VALUE, intervalEndInclusive,
                                intervalStartInclusive, null,
                                epochMean + expirationCutOff, null),
                        false);
        final Set<Message> actualResults = getResults(additionalIndex, indexPlan);

        final QueryComponent filter =
                Query.and(
                        Query.field("calendar_name").equalsValue("business"),
                        Query.field("start_epoch").lessThanOrEquals(intervalEndInclusive),
                        Query.field("end_epoch").greaterThanOrEquals(intervalStartInclusive),
                        Query.field("expiration_epoch").greaterThanOrEquals(epochMean + expirationCutOff));

        planner.setConfiguration(planner.getConfiguration().asBuilder().setOptimizeForIndexFilters(true).build());

        final RecordQuery query = RecordQuery.newBuilder()
                .setRecordType("MyMultidimensionalRecord")
                .setFilter(filter)
                .build();
        final RecordQueryPlan plan = planner.plan(query);
        final Set<Message> expectedResults = getResults(additionalIndex, plan);
        Assertions.assertEquals(expectedResults, actualResults);
    }

    @Test
    void wrongDimensionTypes() {
        final RecordMetaDataHook additionalIndex = metaDataBuilder ->
                metaDataBuilder.addIndex("MyMultidimensionalRecord",
                        new Index("IndexWithWrongDimensions", DimensionsKeyExpression.of(field("calendar_name"),
                                concat(field("start_epoch"), field("calendar_name"), field("expiration_epoch"))),
                                IndexTypes.MULTIDIMENSIONAL, ImmutableMap.of(IndexOptions.RTREE_STORAGE, BY_NODE.toString(),
                                IndexOptions.RTREE_STORE_HILBERT_VALUES, "true")));

        Assertions.assertThrows(KeyExpression.InvalidExpressionException.class, () ->
                loadRecordsWithNulls(additionalIndex, 0, ImmutableList.of("business"), 10));
    }

    @ParameterizedTest
    @MethodSource("argumentsForBasicReads")
    void testDeleteWhere(@Nonnull final String storage, final boolean storeHilbertValues) throws Exception {
        final RecordMetaDataHook additionalIndexes =
                metaDataBuilder -> {
                    metaDataBuilder.addIndex("MyMultidimensionalRecord",
                            new Index("EventIntervals", DimensionsKeyExpression.of(
                                    concat(field("rec_domain"), field("calendar_name")),
                                    concat(field("start_epoch"), field("end_epoch"))),
                                    IndexTypes.MULTIDIMENSIONAL, ImmutableMap.of(IndexOptions.RTREE_STORAGE, storage,
                                    IndexOptions.RTREE_STORE_HILBERT_VALUES, Boolean.toString(storeHilbertValues))));
                    metaDataBuilder.removeIndex("MyMultidimensionalRecord$calendar_name");
                    metaDataBuilder.removeIndex("calendarNameEndEpochStartEpoch");
                };
        loadRecordsWithNulls(additionalIndexes, 0, ImmutableList.of("business", "private"), 500);

        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, additionalIndexes);

            recordStore.deleteRecordsWhere(Query.field("rec_domain").isNull());
            commit(context);
        }

        final var bounds = new MultidimensionalIndexScanBounds.Hypercube(ImmutableList.of(
                TupleRange.betweenInclusive(null, null),
                TupleRange.betweenInclusive(null, null)));

        final RecordQueryIndexPlan indexPlan =
                new RecordQueryIndexPlan("EventIntervals",
                        new CompositeScanParameters(
                                new MultidimensionalIndexScanBounds(TupleRange.allOf(Tuple.from(null, "business")), bounds)),
                        false);

        final Set<Message> actualResults = getResults(additionalIndexes, indexPlan);
        Assertions.assertTrue(actualResults.isEmpty());
    }

    @SuppressWarnings("resource")
    @Nonnull
    private Set<Message> getResults(final RecordMetaDataHook additionalIndexes, final RecordQueryPlan queryPlan) throws Exception {
        final Set<Message> actualResults;
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, additionalIndexes);
            final RecordCursor<QueryResult> recordCursor =
                    queryPlan.executePlan(recordStore, EvaluationContext.empty(), null, ExecuteProperties.SERIAL_EXECUTE);
            actualResults = recordCursor.asStream()
                    .map(queryResult -> Objects.requireNonNull(queryResult.getQueriedRecord()).getRecord())
                    .collect(Collectors.toSet());
            commit(context);
        }
        return actualResults;
    }

    @SuppressWarnings("CheckStyle")
    static class HypercubeScanParameters implements IndexScanParameters {
        @Nonnull
        private final String minCalendarName;
        @Nonnull
        private final String maxCalendarName;
        @Nonnull
        private final Long[] minsInclusive;
        @Nonnull
        private final Long[] maxsInclusive;

        public HypercubeScanParameters(@Nonnull final String calendarName,
                                       @Nonnull final Long... minMaxLimits) {
            this(calendarName, calendarName, minMaxLimits);
        }

        public HypercubeScanParameters(@Nonnull final String minCalendarName,
                                       @Nonnull final String maxCalendarName,
                                       @Nonnull final Long... minMaxLimits) {
            Preconditions.checkArgument(minMaxLimits.length % 2 == 0);
            this.minCalendarName = minCalendarName;
            this.maxCalendarName = maxCalendarName;
            this.minsInclusive = new Long[minMaxLimits.length / 2];
            this.maxsInclusive = new Long[minMaxLimits.length / 2];
            for (int i = 0; i < minMaxLimits.length; i += 2) {
                this.minsInclusive[i / 2] = minMaxLimits[i];
                this.maxsInclusive[i / 2] = minMaxLimits[i + 1];
            }
        }

        @Override
        public int planHash(@Nonnull final PlanHashKind hashKind) {
            return 11;
        }

        @Nonnull
        @Override
        public IndexScanType getScanType() {
            return IndexScanType.BY_VALUE;
        }

        @Nonnull
        @Override
        public IndexScanBounds bind(@Nonnull final FDBRecordStoreBase<?> store, @Nonnull final Index index, @Nonnull final EvaluationContext context) {
            final ImmutableList.Builder<TupleRange> tupleRangesBuilder = ImmutableList.builder();
            for (int i = 0; i < minsInclusive.length; i++) {
                final Long min = minsInclusive[i];
                final Long max = maxsInclusive[i];
                tupleRangesBuilder.add(TupleRange.betweenInclusive(min == null ? null : Tuple.from(min),
                        max == null ? null : Tuple.from(max)));
            }

            return new MultidimensionalIndexScanBounds(TupleRange.betweenInclusive(Tuple.from(minCalendarName), Tuple.from(maxCalendarName)),
                    new MultidimensionalIndexScanBounds.Hypercube(tupleRangesBuilder.build()));
        }

        @Override
        public boolean isUnique(@Nonnull final Index index) {
            return false;
        }

        @Nonnull
        @Override
        public String getScanDetails() {
            return "multidimensional";
        }

        @Override
        public void getPlannerGraphDetails(@Nonnull final ImmutableList.Builder<String> detailsBuilder, @Nonnull final ImmutableMap.Builder<String, Attribute> attributeMapBuilder) {
        }

        @Nonnull
        @Override
        public IndexScanParameters translateCorrelations(@Nonnull final TranslationMap translationMap) {
            throw new RecordCoreException("not supported");
        }

        @Nonnull
        @Override
        public Set<CorrelationIdentifier> getCorrelatedTo() {
            return ImmutableSet.of();
        }

        @Nonnull
        @Override
        public IndexScanParameters rebase(@Nonnull final AliasMap translationMap) {
            return this;
        }

        @Override
        public boolean semanticEquals(@Nullable final Object other, @Nonnull final AliasMap aliasMap) {
            return false;
        }

        @Override
        public int semanticHashCode() {
            return 0;
        }
    }

    @SuppressWarnings("CheckStyle")
    static class CompositeScanParameters implements IndexScanParameters {
        @Nonnull
        private final MultidimensionalIndexScanBounds scanBounds;

        public CompositeScanParameters(@Nonnull final MultidimensionalIndexScanBounds scanBounds) {
            this.scanBounds = scanBounds;
        }

        @Override
        public int planHash(@Nonnull final PlanHashKind hashKind) {
            return 13;
        }

        @Nonnull
        @Override
        public IndexScanType getScanType() {
            return IndexScanType.BY_VALUE;
        }

        @Nonnull
        @Override
        public IndexScanBounds bind(@Nonnull final FDBRecordStoreBase<?> store, @Nonnull final Index index, @Nonnull final EvaluationContext context) {
            return scanBounds;
        }

        @Override
        public boolean isUnique(@Nonnull final Index index) {
            return false;
        }

        @Nonnull
        @Override
        public String getScanDetails() {
            return "multidimensional";
        }

        @Override
        public void getPlannerGraphDetails(@Nonnull final ImmutableList.Builder<String> detailsBuilder, @Nonnull final ImmutableMap.Builder<String, Attribute> attributeMapBuilder) {
        }

        @Nonnull
        @Override
        public IndexScanParameters translateCorrelations(@Nonnull final TranslationMap translationMap) {
            throw new RecordCoreException("not supported");
        }

        @Nonnull
        @Override
        public Set<CorrelationIdentifier> getCorrelatedTo() {
            return ImmutableSet.of();
        }

        @Nonnull
        @Override
        public IndexScanParameters rebase(@Nonnull final AliasMap translationMap) {
            return this;
        }

        @Override
        public boolean semanticEquals(@Nullable final Object other, @Nonnull final AliasMap aliasMap) {
            return false;
        }

        @Override
        public int semanticHashCode() {
            return 0;
        }
    }
}
