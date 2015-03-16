package com.addthis.hydra.kafka.consumer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.addthis.basis.util.LessFiles;
import com.addthis.basis.util.Parameter;

import com.addthis.bark.ZkUtil;
import com.addthis.bundle.channel.DataChannelError;
import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.core.list.ListBundleFormat;
import com.addthis.codec.annotations.FieldConfig;
import com.addthis.hydra.data.util.DateUtil;
import com.addthis.hydra.store.db.DBKey;
import com.addthis.hydra.store.db.PageDB;
import com.addthis.hydra.task.run.TaskRunConfig;
import com.addthis.hydra.task.source.SimpleMark;
import com.addthis.hydra.task.source.TaskDataSource;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;

import org.apache.curator.framework.CuratorFramework;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.addthis.hydra.kafka.consumer.BundleWrapper.bundleQueueEndMarker;
import static com.addthis.hydra.kafka.consumer.MessageWrapper.messageQueueEndMarker;

public class KafkaSource extends TaskDataSource {

    private static final Logger log         = LoggerFactory.getLogger(KafkaSource.class);
    private static final int    pollRetries = Parameter.intValue("hydra.kafka.pollRetries", 180);

    @FieldConfig(codable = true, required = true)
    private String zookeeper;
    @FieldConfig(codable = true, required = true)
    private String topic;
    @FieldConfig(codable = true)
    private String inputBundleFormatType = KafkaByteDecoder.KafkaByteDecoderType.BUNDLE.toString();
    @FieldConfig(codable = true)
    private String startDate;
    @FieldConfig(codable = true)
    private String dateFormat = "YYMMdd";
    @FieldConfig(codable = true)
    private String markDir    = "marks";

    @FieldConfig(codable = true)
    private int fetchThreads  = Parameter.intValue("hydra.kafka.fetchThreads", 1);
    @FieldConfig(codable = true)
    private int decodeThreads = Parameter.intValue("hydra.kafka.decodeThreads", 1);
    @FieldConfig(codable = true)
    private int queueSize     = Parameter.intValue("hydra.kafka.queueSize", 10000);

    @FieldConfig
    private TaskRunConfig config;

    PageDB<SimpleMark> markDb;
    AtomicBoolean running;
    LinkedBlockingQueue<MessageWrapper> messageQueue;
    LinkedBlockingQueue<BundleWrapper> bundleQueue;
    CuratorFramework zkClient;
    final ConcurrentMap<String, Long> sourceOffsets = new ConcurrentHashMap<>();

    private ExecutorService fetchExecutor;
    private ExecutorService decodeExecutor;

    @Override
    public Bundle next() throws DataChannelError {
        if (!running.get()) {
            return null;
        }
        BundleWrapper bundle;
        try {
            bundle = bundleQueue.poll(pollRetries, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // reset interrupt status
            Thread.currentThread().interrupt();
            // fine to leave bundles on the queue
            return null;
        }
        if (bundle == null) {
            throw new DataChannelError(
                    "giving up on kafka source next() after waiting: " + pollRetries + " seconds");
        }
        if (bundle == bundleQueueEndMarker) {
            // add back end-marker in case someone continues calling peek/next on the source
            bundleQueue.add(bundleQueueEndMarker);
            return null;
        }
        sourceOffsets.put(bundle.sourceIdentifier, bundle.offset);
        return bundle.bundle;
    }

    @Override
    public Bundle peek() throws DataChannelError {
        BundleWrapper bundle = null;
        int retries = 0;
        while (bundle == null && retries < pollRetries) {
            bundle = bundleQueue.peek();
            // seemingly no better option than sleeping here - blocking queue doesn't have a
            // blocking peek, but
            // still want to maintain source.peek() == source.next() guarantees
            if (bundle == null) {
                Uninterruptibles.sleepUninterruptibly(1000, TimeUnit.MILLISECONDS);
            }
            retries++;
        }
        if (bundle == null) {
            throw new DataChannelError(
                    "giving up on kafka source peek() after retrying: " + retries);
        }
        if (bundle == bundleQueueEndMarker) {
            return null;
        }
        return bundle.bundle;
    }

    @Override
    public void close() {
        running.set(false);
        fetchExecutor.shutdown();
        decodeExecutor.shutdown();
        for (Map.Entry<String, Long> sourceOffset : sourceOffsets.entrySet()) {
            SimpleMark mark = new SimpleMark();
            mark.set(String.valueOf(sourceOffset.getValue()), sourceOffset.getValue());
            mark.setEnd(false);
            this.markDb.put(new DBKey(0, sourceOffset.getKey()), mark);
            log.info("updating mark db, source: {}, index: {}", sourceOffset.getKey(), sourceOffset.getValue());
        }
        this.markDb.close();
        this.zkClient.close();
    }

    @Override
    public void init() {
        try {
            this.messageQueue = new LinkedBlockingQueue<>(queueSize);
            this.bundleQueue = new LinkedBlockingQueue<>(queueSize);
            this.markDb = new PageDB<>(LessFiles.initDirectory(markDir), SimpleMark.class, 100, 100);
            // move to init method
            this.fetchExecutor = MoreExecutors.getExitingExecutorService(
                    new ThreadPoolExecutor(fetchThreads, fetchThreads,
                            0l, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<Runnable>(),
                            new ThreadFactoryBuilder().setNameFormat("source-kafka-fetch-%d").build())
            );
            this.decodeExecutor = MoreExecutors.getExitingExecutorService(
                    new ThreadPoolExecutor(decodeThreads, decodeThreads,
                            0l, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<Runnable>(),
                            new ThreadFactoryBuilder().setNameFormat("source-kafka-decode-%d").build())
            );
            this.running = new AtomicBoolean(true);
            final DateTime startTime = (startDate != null) ? DateUtil.getDateTime(dateFormat, startDate) : null;

            zkClient = ZkUtil.makeStandardClient(zookeeper, false);
            final Set<Integer> shards = new HashSet<>(Arrays.asList(config.calcShardList(countTotalPartitions(injectedBrokerList))));
            final CountDownLatch fetchLatch = new CountDownLatch(shards.size());
            // need to make due with legacy broker info format, since jobs have historic brokers serialized to spawn data store
            int partitionIndex = 0;
            List<FetchTask> sortedConsumers = new ArrayList<>();
            for (final InjectedBrokerInfo brokerInfo : injectedBrokerList) {
                for (int i = 0; i < brokerInfo.getNumberOfPartitions(); i++) {
                    if (shards.contains(partitionIndex)) {
                        FetchTask fetcher = new FetchTask(this, fetchLatch, zkClient, brokerInfo, i, startTime);
                        sortedConsumers.add(fetcher);
                    }
                    partitionIndex++;
                }
            }
            // sort consumer broker-partitions by partition to avoid multiple connections to same broker
            Collections.sort(sortedConsumers);
            for (FetchTask consumer : sortedConsumers) {
                fetchExecutor.execute(consumer);
            }
            fetchExecutor.submit(new MarkEndTask<>(fetchLatch, running, messageQueue, messageQueueEndMarker));

            final ListBundleFormat format = new ListBundleFormat();
            final CountDownLatch decodeLatch = new CountDownLatch(decodeThreads);
            Runnable decoder = new DecodeTask(decodeLatch, format, running, messageQueue, bundleQueue);
            for (int i = 0; i < decodeThreads; i++) {
                decodeExecutor.execute(decoder);
            }
            decodeExecutor.submit(new MarkEndTask<>(decodeLatch, running, bundleQueue, bundleQueueEndMarker));
        } catch (Exception ex) {
            log.error("Error initializing kafka source: ", ex);
            throw new RuntimeException(ex);
        }
    }

    private static int countTotalPartitions(InjectedBrokerInfo[] brokerInfos) {
        int total = 0;
        for (InjectedBrokerInfo info : brokerInfos) {
            total += info.getNumberOfPartitions();
        }
        return total;
    }

    // Put onto linked blocking queue, giving up (via exception) if running becomes false (interrupts are ignored in favor of the running flag).
    // Uses an exception rather than returning boolean since checking for return status was a huge mess (e.g. had to keep track of how far
    // your iterator got in the message set when retrying).
    static <E> void putWhileRunning(BlockingQueue<E> queue, E value, AtomicBoolean running) {
        boolean offered = false;
        while (!offered) {
            if (!running.get()) {
                throw BenignKafkaException.INSTANCE;
            }
            try {
                offered = queue.offer(value, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }
}