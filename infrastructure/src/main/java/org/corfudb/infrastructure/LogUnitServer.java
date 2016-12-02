package org.corfudb.infrastructure;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.channel.ChannelHandlerContext;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.corfudb.infrastructure.log.InMemoryStreamLog;
import org.corfudb.infrastructure.log.LogAddress;
import org.corfudb.infrastructure.log.StreamLog;
import org.corfudb.infrastructure.log.StreamLogFiles;
import org.corfudb.protocols.wireprotocol.CommitRequest;
import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.IMetadata;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.protocols.wireprotocol.ReadRequest;
import org.corfudb.protocols.wireprotocol.ReadResponse;
import org.corfudb.protocols.wireprotocol.TrimRequest;
import org.corfudb.protocols.wireprotocol.WriteMode;
import org.corfudb.protocols.wireprotocol.WriteRequest;
import org.corfudb.router.*;
import org.corfudb.runtime.exceptions.DataCorruptionException;
import org.corfudb.runtime.exceptions.OverwriteException;
import org.corfudb.util.Utils;
import org.corfudb.util.retry.IRetry;
import org.corfudb.util.retry.IntervalAndSentinelRetry;

/**
 * Created by mwei on 12/10/15.
 * <p>
 * A Log Unit Server, which is responsible for providing the persistent storage for the Corfu Distributed Shared Log.
 * <p>
 * All reads and writes go through a cache. For persistence, every 10,000 log entries are written to individual
 * files (logs), which are represented as FileHandles. Each FileHandle contains a pointer to the tail of the file, a
 * memory-mapped file channel, and a set of addresses known to be in the file. To write an entry, the pointer to the
 * tail is first extended to the length of the entry, and the entry is added to the set of known addresses. A header
 * is written, which consists of the ASCII characters LE, followed by a set of flags, the log unit address, the size
 * of the entry, then the metadata size, metadata and finally the entry itself. When the entry is complete, a written
 * flag is set in the flags field.
 */
@Slf4j
public class LogUnitServer extends AbstractEpochedServer {

    private ServerContext serverContext;

    /**
     * A scheduler, which is used to schedule periodic tasks like garbage collection.
     */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(
                    1,
                    new ThreadFactoryBuilder()
                            .setDaemon(true)
                            .setNameFormat("LogUnit-Maintenance-%d")
                            .build());
    /**
     * The options map.
     */
    Map<String, Object> opts;

    /** Handler for the base server */
    @Getter
    private final PreconditionServerMsgHandler<CorfuMsg, CorfuMsgType> preconditionMsgHandler =
            new PreconditionServerMsgHandler<CorfuMsg, CorfuMsgType>(this)
                    .generateHandlers(MethodHandles.lookup(), this, ServerHandler.class, ServerHandler::type);


    /**
     * Service an incoming write request.
     */
    @ServerHandler(type=CorfuMsgType.WRITE)
    public CorfuMsg write(CorfuPayloadMsg<WriteRequest> msg, IChannel<CorfuMsg> channel) {
        log.debug("log write: global: {}, streams: {}, backpointers: {}", msg.getPayload().getGlobalAddress(),
                msg.getPayload().getStreamAddresses(), msg.getPayload().getData().getBackpointerMap());
        // clear any commit record (or set initially to false).
        msg.getPayload().clearCommit();
        try {
            if (msg.getPayload().getWriteMode() != WriteMode.REPLEX_STREAM) {
                dataCache.put(new LogAddress(msg.getPayload().getGlobalAddress(), null), msg.getPayload().getData());
                return CorfuMsgType.WRITE_OK_RESPONSE.msg();
            } else {
                for (UUID streamID : msg.getPayload().getStreamAddresses().keySet()) {
                    dataCache.put(new LogAddress(msg.getPayload().getStreamAddresses().get(streamID), streamID),
                            msg.getPayload().getData());
                }
                return CorfuMsgType.WRITE_OK_RESPONSE.msg();
            }
        } catch (OverwriteException ex) {
            if (msg.getPayload().getWriteMode() != WriteMode.REPLEX_STREAM)
                return CorfuMsgType.OVERWRITE_ERROR.msg();
            else
                return CorfuMsgType.REPLEX_OVERWRITE_ERROR.msg();
        }
    }

    /**
     * Service an incoming commit request.
     */
    @ServerHandler(type=CorfuMsgType.COMMIT)
    public CorfuMsg commit(CorfuPayloadMsg<CommitRequest> msg, IChannel<CorfuMsg> channel) {
        Map<UUID, Long> streamAddresses = msg.getPayload().getStreams();
        if (streamAddresses == null) {
            // Then this is a commit bit for the global log.
            LogData entry = dataCache.get(new LogAddress(msg.getPayload().getAddress(), null));
            if (entry == null) {
                return CorfuMsgType.NOENTRY_ERROR.msg();
            }
            else {
                entry.getMetadataMap().put(IMetadata.LogUnitMetadataType.COMMIT, msg.getPayload().getCommit());
            }
        } else {
            for (UUID streamID : msg.getPayload().getStreams().keySet()) {
                LogData entry = dataCache.get(new LogAddress(streamAddresses.get(streamID), streamID));
                if (entry == null) {
                    return CorfuMsgType.NOENTRY_ERROR.msg();
                    // TODO: Crap, we have to go back and undo all the commit bits??
                }
                else {
                    entry.getMetadataMap().put(IMetadata.LogUnitMetadataType.COMMIT, msg.getPayload().getCommit());
                }
            }
        }
        return CorfuMsgType.ACK_RESPONSE.msg();
    }

    @ServerHandler(type=CorfuMsgType.READ_REQUEST)
    private CorfuMsg read(CorfuPayloadMsg<ReadRequest> msg, IChannel<CorfuMsg> channel) {
        log.debug("log read: {} {}", msg.getPayload().getStreamID(), msg.getPayload().getRange());
        ReadResponse rr = new ReadResponse();
        try {
            for (Long l = msg.getPayload().getRange().lowerEndpoint();
                 l < msg.getPayload().getRange().upperEndpoint()+1L; l++) {
                LogData e = dataCache.get(new LogAddress(l, msg.getPayload().getStreamID()));
                if (e == null) {
                    rr.put(l, LogData.EMPTY);
                } else if (e.getType() == DataType.HOLE) {
                    rr.put(l, LogData.HOLE);
                } else {
                    rr.put(l, e);
                }
            }
            return CorfuMsgType.READ_RESPONSE.payloadMsg(rr);
        } catch (DataCorruptionException e) {
           return CorfuMsgType.DATA_CORRUPTION_ERROR.msg();
        }
    }

    @ServerHandler(type=CorfuMsgType.GC_INTERVAL)
    private CorfuMsg setGcInterval(CorfuPayloadMsg<Long> msg, IChannel<CorfuMsg> channel) {
        gcRetry.setRetryInterval(msg.getPayload());
        return CorfuMsgType.ACK_RESPONSE.msg();
    }

    @ServerHandler(type=CorfuMsgType.FORCE_GC)
    private CorfuMsg forceGc(CorfuMsg msg, IChannel<CorfuMsg> channel) {
        gcThread.interrupt();
        return CorfuMsgType.ACK_RESPONSE.msg();
    }

    @ServerHandler(type=CorfuMsgType.FILL_HOLE)
    private CorfuMsg fillHole(CorfuPayloadMsg<TrimRequest> msg, IChannel<CorfuMsg> channel) {
        try {
            dataCache.put(new LogAddress(msg.getPayload().getPrefix(), msg.getPayload().getStream()), LogData.HOLE);
            return CorfuMsgType.WRITE_OK_RESPONSE.msg();

        } catch (OverwriteException e) {
            return CorfuMsgType.OVERWRITE_ERROR.msg();
        }
    }

    @ServerHandler(type=CorfuMsgType.TRIM)
    private CorfuMsg trim(CorfuPayloadMsg<TrimRequest> msg, IChannel<CorfuMsg> channel) {
        trimMap.compute(msg.getPayload().getStream(), (key, prev) ->
                prev == null ? msg.getPayload().getPrefix() : Math.max(prev, msg.getPayload().getPrefix()));
        return CorfuMsgType.ACK_RESPONSE.msg();
    }


    /**
     * The garbage collection thread.
     */
    Thread gcThread;

    ConcurrentHashMap<UUID, Long> trimMap;
    IntervalAndSentinelRetry gcRetry;
    AtomicBoolean running = new AtomicBoolean(true);
    /**
     * This cache services requests for data at various addresses. In a memory implementation,
     * it is not backed by anything, but in a disk implementation it is backed by persistent storage.
     */
    LoadingCache<LogAddress, LogData> dataCache;
    long maxCacheSize;

    private StreamLog localLog;

    // This shouldn't be a max. This should be the size of the mapping window.
    public static long maxLogFileSize = Integer.MAX_VALUE >> 4;  // 512MB by default

    private final ConcurrentHashMap<UUID, StreamLog> streamLogs = new ConcurrentHashMap<>();

    private StreamLog getLog(UUID stream) {
        if (stream == null) return localLog;
        else {
            return streamLogs.computeIfAbsent(stream, x-> {
                if ((Boolean) opts.get("--memory")) {
                    return new InMemoryStreamLog();
                }
                else {
                    String logdir = opts.get("--log-path") + File.separator + "log" + File.separator + stream;
                    return new StreamLogFiles(logdir, (Boolean) opts.get("--no-verify"));
                }
            });
        }
    }

    public LogUnitServer(IServerRouter<CorfuMsg,CorfuMsgType> router,
                         ServerContext serverContext) {
        super(router, serverContext);
        this.opts = serverContext.getServerConfig();
        this.serverContext = serverContext;

        maxCacheSize = Utils.parseLong(opts.get("--max-cache"));
        if (opts.get("--quickcheck-test-mode") != null &&
            (Boolean) opts.get("--quickcheck-test-mode")) {
            // It's really annoying when using OS X + HFS+ that HFS+ does not
            // support sparse files.  If we use the default 2GB file size, then
            // every time that a sparse file is closed, the OS will always
            // write 2GB of data to disk.  {sadpanda}  Use this static class
            // var to signal to StreamLogFiles to use a smaller file size.
            maxLogFileSize = 4_000_000;
        }

        reboot();

/*       compactTail seems to be broken, disabling it for now
         scheduler.scheduleAtFixedRate(this::compactTail,
                Utils.getOption(opts, "--compact", Long.class, 60L),
                Utils.getOption(opts, "--compact", Long.class, 60L),
                TimeUnit.SECONDS);*/

        gcThread = new Thread(this::runGC);
        gcThread.start();
    }


    public void reset() {
        String d = serverContext.getDataStore().getLogDir();
        localLog.close();
        if (d != null) {
            Path dir = FileSystems.getDefault().getPath(d);
            String prefixes[] = new String[]{"log"};

            for (String pfx : prefixes) {
                try (DirectoryStream<Path> stream =
                             Files.newDirectoryStream(dir, pfx + "*")) {
                    for (Path entry : stream) {
                        Files.delete(entry);
                    }
                } catch (IOException e) {
                    log.error("reset: error deleting prefix " + pfx + ": " + e.toString());
                }
            }
        }
        reboot();
    }

    public void reboot() {
        if ((Boolean) opts.get("--memory")) {
            log.warn("Log unit opened in-memory mode (Maximum size={}). " +
                    "This should be run for testing purposes only. " +
                    "If you exceed the maximum size of the unit, old entries will be AUTOMATICALLY trimmed. " +
                    "The unit WILL LOSE ALL DATA if it exits.", Utils.convertToByteStringRepresentation(maxCacheSize));
            localLog = new InMemoryStreamLog();
        } else {
            String logdir = opts.get("--log-path") + File.separator + "log";
            localLog = new StreamLogFiles(logdir, (Boolean) opts.get("--no-verify"));
        }

        if (dataCache != null) {
            /** Free all references */
            dataCache.asMap().values().parallelStream()
                    .map(m -> m.getData().release());
        }

        dataCache = Caffeine.<LogAddress,LogData>newBuilder()
                .<LogAddress,LogData>weigher((k, v) -> v.getData() == null ? 1 : v.getData().readableBytes())
                .maximumWeight(maxCacheSize)
                .removalListener(this::handleEviction)
                .writer(new CacheWriter<LogAddress, LogData>() {
                    @Override
                    public void write(@Nonnull LogAddress address, @Nonnull LogData entry) {
                        if (address.getStream() != null) {
                            getLog(address.getStream()).append(address.getAddress(), entry);
                        } else {
                            localLog.append(address.getAddress(), entry);
                        }
                    }

                    @Override
                    public void delete(LogAddress aLong, LogData logUnitEntry, RemovalCause removalCause) {
                        // never need to delete
                    }
                }).<LogAddress,LogData>build(this::handleRetrieval);

        // Trim map is set to empty on start
        // TODO: persist trim map - this is optional since trim is just a hint.
        trimMap = new ConcurrentHashMap<>();
    }

    /**
     * Retrieve the LogUnitEntry from disk, given an address.
     *
     * @param address The address to retrieve the entry from.
     * @return The log unit entry to retrieve into the cache.
     * This function should not care about trimmed addresses, as that is handled in
     * the read() and write(). Any address that cannot be retrieved should be returned as
     * unwritten (null).
     */
    public synchronized LogData handleRetrieval(LogAddress address) {
        LogData entry;
        if (address.getStream() != null) {
            entry = getLog(address.getStream()).read(address.getAddress());
        }
        else {
            entry = localLog.read(address.getAddress());
        }
        log.trace("Retrieved[{} : {}]", address, entry);
        return entry;
    }

    public synchronized void handleEviction(LogAddress address, LogData entry, RemovalCause cause) {
        log.trace("Eviction[{}]: {}", address, cause);
        if (entry.getData() != null) {
            // Free the internal buffer once the data has been evicted (in the case the server is not sync).
            entry.getData().release();
        }
    }


    public void runGC() {
        Thread.currentThread().setName("LogUnit-GC");
        val retry = IRetry.build(IntervalAndSentinelRetry.class, this::handleGC)
                .setOptions(x -> x.setSentinelReference(running))
                .setOptions(x -> x.setRetryInterval(60_000));

        gcRetry = (IntervalAndSentinelRetry) retry;

        retry.runForever();
    }

    @SuppressWarnings("unchecked")
    public boolean handleGC() {
        log.info("Garbage collector starting...");
        long freedEntries = 0;

        /* Pick a non-compacted region or just scan the cache */
        Map<LogAddress, LogData> map = dataCache.asMap();
        SortedSet<LogAddress> addresses = new TreeSet<>(map.keySet());
        for (LogAddress address : addresses) {
            LogData buffer = dataCache.getIfPresent(address);
            if (buffer != null) {
                Set<UUID> streams = buffer.getStreams();
                // this is a normal entry
                if (streams.size() > 0) {
                    boolean trimmable = true;
                    for (java.util.UUID stream : streams) {
                        Long trimMark = trimMap.getOrDefault(stream, null);
                        // if the stream has not been trimmed, or has not been trimmed to this point
                        if (trimMark == null || address.getAddress() > trimMark) {
                            trimmable = false;
                            break;
                        }
                        // it is not trimmable.
                    }
                    if (trimmable) {
                        log.trace("Trimming entry at {}", address);
                        trimEntry(address.getAddress(), streams, buffer);
                        freedEntries++;
                    }
                } else {
                    //this is an entry which belongs in all streams
                }
            }
        }

        log.info("Garbage collection pass complete. Freed {} entries", freedEntries);
        return true;
    }

    public void trimEntry(long address, Set<java.util.UUID> streams, LogData entry) {
        // Add this entry to the trimmed range map.
        //trimRange.add(Range.closed(address, address));
        // Invalidate this entry from the cache. This will cause the CacheLoader to free the entry from the disk
        // assuming the entry is back by disk
        dataCache.invalidate(address);
        //and free any references the buffer might have
        if (entry.getData() != null) {
            entry.getData().release();
        }
    }

    /**
     * Shutdown the server.
     */
    public void shutdown() {
        scheduler.shutdownNow();
        dataCache.invalidateAll(); //should evict all entries
    }

    @VisibleForTesting
    LoadingCache<LogAddress, LogData> getDataCache() {
        return dataCache;
    }
}
