/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.MemoryMetrics;
import org.apache.ignite.PersistenceMetrics;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.mem.DirectMemoryProvider;
import org.apache.ignite.internal.mem.file.MappedFileMemoryProvider;
import org.apache.ignite.internal.mem.unsafe.UnsafeMemoryProvider;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.pagemem.impl.PageMemoryNoStoreImpl;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.DynamicCacheDescriptor;
import org.apache.ignite.internal.processors.cache.GridCacheMapEntry;
import org.apache.ignite.internal.processors.cache.GridCacheSharedManagerAdapter;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionsExchangeFuture;
import org.apache.ignite.internal.processors.cache.persistence.evict.FairFifoPageEvictionTracker;
import org.apache.ignite.internal.processors.cache.persistence.evict.NoOpPageEvictionTracker;
import org.apache.ignite.internal.processors.cache.persistence.evict.PageEvictionTracker;
import org.apache.ignite.internal.processors.cache.persistence.evict.Random2LruPageEvictionTracker;
import org.apache.ignite.internal.processors.cache.persistence.evict.RandomLruPageEvictionTracker;
import org.apache.ignite.internal.processors.cache.persistence.filename.PdsFolderSettings;
import org.apache.ignite.internal.processors.cache.persistence.freelist.FreeList;
import org.apache.ignite.internal.processors.cache.persistence.freelist.FreeListImpl;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cluster.IgniteChangeGlobalStateSupport;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.LT;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteOutClosure;
import org.apache.ignite.mxbean.MemoryMetricsMXBean;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.configuration.MemoryConfiguration.DFLT_MEMORY_POLICY_INITIAL_SIZE;
import static org.apache.ignite.configuration.MemoryConfiguration.DFLT_MEM_PLC_DEFAULT_NAME;
import static org.apache.ignite.configuration.MemoryConfiguration.DFLT_PAGE_SIZE;

/**
 *
 */
public class IgniteCacheDatabaseSharedManager extends GridCacheSharedManagerAdapter
    implements IgniteChangeGlobalStateSupport, CheckpointLockStateChecker {
    /** MemoryPolicyConfiguration name reserved for internal caches. */
    static final String SYSTEM_MEMORY_POLICY_NAME = "sysMemPlc";

    /** Minimum size of memory chunk */
    private static final long MIN_PAGE_MEMORY_SIZE = 10 * 1024 * 1024;

    /** Maximum initial size on 32-bit JVM */
    private static final long MAX_PAGE_MEMORY_INIT_SIZE_32_BIT = 2L * 1024 * 1024 * 1024;

    /** */
    protected Map<String, MemoryPolicy> memPlcMap;

    /** */
    protected Map<String, MemoryMetrics> memMetricsMap;

    /** */
    protected MemoryPolicy dfltMemPlc;

    /** */
    private Map<String, FreeListImpl> freeListMap;

    /** */
    private FreeListImpl dfltFreeList;

    /** Page size from memory configuration, may be set only for fake(standalone) IgniteCacheDataBaseSharedManager */
    private int pageSize;

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        if (cctx.kernalContext().clientNode() && cctx.kernalContext().config().getMemoryConfiguration() == null)
            return;

        MemoryConfiguration memCfg = cctx.kernalContext().config().getMemoryConfiguration();

        assert memCfg != null;

        validateConfiguration(memCfg);

        pageSize = memCfg.getPageSize();
    }

    /**
     * Registers MBeans for all MemoryMetrics configured in this instance.
     */
    private void registerMetricsMBeans() {
        if(U.IGNITE_MBEANS_DISABLED)
            return;

        IgniteConfiguration cfg = cctx.gridConfig();

        for (MemoryMetrics memMetrics : memMetricsMap.values()) {
            MemoryPolicyConfiguration memPlcCfg = memPlcMap.get(memMetrics.getName()).config();

            registerMetricsMBean((MemoryMetricsImpl)memMetrics, memPlcCfg, cfg);
        }
    }

    /**
     * @param memMetrics Memory metrics.
     * @param memPlcCfg Memory policy configuration.
     * @param cfg Ignite configuration.
     */
    private void registerMetricsMBean(
        MemoryMetricsImpl memMetrics,
        MemoryPolicyConfiguration memPlcCfg,
        IgniteConfiguration cfg
    ) {
        assert !U.IGNITE_MBEANS_DISABLED;

        try {
            U.registerMBean(
                cfg.getMBeanServer(),
                cfg.getIgniteInstanceName(),
                "MemoryMetrics",
                memPlcCfg.getName(),
                new MemoryMetricsMXBeanImpl(memMetrics, memPlcCfg),
                MemoryMetricsMXBean.class);
        }
        catch (Throwable e) {
            U.error(log, "Failed to register MBean for MemoryMetrics with name: '" + memMetrics.getName() + "'", e);
        }
    }

    /**
     * @param dbCfg Database config.
     * @throws IgniteCheckedException If failed.
     */
    protected void initPageMemoryDataStructures(MemoryConfiguration dbCfg) throws IgniteCheckedException {
        freeListMap = U.newHashMap(memPlcMap.size());

        String dfltMemPlcName = dbCfg.getDefaultMemoryPolicyName();

        for (MemoryPolicy memPlc : memPlcMap.values()) {
            MemoryPolicyConfiguration memPlcCfg = memPlc.config();

            MemoryMetricsImpl memMetrics = (MemoryMetricsImpl) memMetricsMap.get(memPlcCfg.getName());

            FreeListImpl freeList = new FreeListImpl(0,
                    cctx.igniteInstanceName(),
                    memMetrics,
                    memPlc,
                    null,
                    cctx.wal(),
                    0L,
                    true);

            freeListMap.put(memPlcCfg.getName(), freeList);
        }

        dfltFreeList = freeListMap.get(dfltMemPlcName);
    }

    /**
     * @return Size of page used for PageMemory regions.
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     *
     */
    private void startMemoryPolicies() {
        for (MemoryPolicy memPlc : memPlcMap.values()) {
            memPlc.pageMemory().start();

            memPlc.evictionTracker().start();
        }
    }

    /**
     * @param memCfg Database config.
     * @throws IgniteCheckedException If failed to initialize swap path.
     */
    protected void initPageMemoryPolicies(MemoryConfiguration memCfg) throws IgniteCheckedException {
        MemoryPolicyConfiguration[] memPlcsCfgs = memCfg.getMemoryPolicies();

        if (memPlcsCfgs == null) {
            //reserve place for default and system memory policies
            memPlcMap = U.newHashMap(2);
            memMetricsMap = U.newHashMap(2);

            addMemoryPolicy(
                memCfg,
                memCfg.createDefaultPolicyConfig(),
                DFLT_MEM_PLC_DEFAULT_NAME
            );

            U.warn(log, "No user-defined default MemoryPolicy found; system default of 1GB size will be used.");
        }
        else {
            String dfltMemPlcName = memCfg.getDefaultMemoryPolicyName();

            if (DFLT_MEM_PLC_DEFAULT_NAME.equals(dfltMemPlcName) && !hasCustomDefaultMemoryPolicy(memPlcsCfgs)) {
                //reserve additional place for default and system memory policies
                memPlcMap = U.newHashMap(memPlcsCfgs.length + 2);
                memMetricsMap = U.newHashMap(memPlcsCfgs.length + 2);

                addMemoryPolicy(
                    memCfg,
                    memCfg.createDefaultPolicyConfig(),
                    DFLT_MEM_PLC_DEFAULT_NAME
                );

                U.warn(log, "No user-defined default MemoryPolicy found; system default of 1GB size will be used.");
            }
            else {
                //reserve additional space for system memory policy only
                memPlcMap = U.newHashMap(memPlcsCfgs.length + 1);
                memMetricsMap = U.newHashMap(memPlcsCfgs.length + 1);
            }

            for (MemoryPolicyConfiguration memPlcCfg : memPlcsCfgs)
                addMemoryPolicy(memCfg, memPlcCfg, memPlcCfg.getName());
        }

        addMemoryPolicy(
            memCfg,
            createSystemMemoryPolicy(
                memCfg.getSystemCacheInitialSize(),
                memCfg.getSystemCacheMaxSize()
            ),
            SYSTEM_MEMORY_POLICY_NAME
        );
    }

    /**
     * @param memCfg Database config.
     * @param memPlcCfg Memory policy config.
     * @param memPlcName Memory policy name.
     * @throws IgniteCheckedException If failed to initialize swap path.
     */
    private void addMemoryPolicy(
        MemoryConfiguration memCfg,
        MemoryPolicyConfiguration memPlcCfg,
        String memPlcName
    ) throws IgniteCheckedException {
        String dfltMemPlcName = memCfg.getDefaultMemoryPolicyName();

        if (dfltMemPlcName == null)
            dfltMemPlcName = DFLT_MEM_PLC_DEFAULT_NAME;

        MemoryMetricsImpl memMetrics = new MemoryMetricsImpl(memPlcCfg, fillFactorProvider(memPlcName));

        MemoryPolicy memPlc = initMemory(memCfg, memPlcCfg, memMetrics);

        memPlcMap.put(memPlcName, memPlc);

        memMetricsMap.put(memPlcName, memMetrics);

        if (memPlcName.equals(dfltMemPlcName))
            dfltMemPlc = memPlc;
        else if (memPlcName.equals(DFLT_MEM_PLC_DEFAULT_NAME))
            U.warn(log, "Memory Policy with name 'default' isn't used as a default. " +
                    "Please check Memory Policies configuration.");
    }

    /**
     * Closure that can be used to compute fill factor for provided memory policy.
     *
     * @param memPlcName Memory policy name.
     * @return Closure.
     */
    protected IgniteOutClosure<Float> fillFactorProvider(final String memPlcName) {
        return new IgniteOutClosure<Float>() {
            private FreeListImpl freeList;

            @Override public Float apply() {
                if (freeList == null) {
                    FreeListImpl freeList0 = freeListMap.get(memPlcName);

                    if (freeList0 == null)
                        return (float) 0;

                    freeList = freeList0;
                }

                T2<Long, Long> fillFactor = freeList.fillFactor();

                if (fillFactor.get2() == 0)
                    return (float) 0;

                return (float) fillFactor.get1() / fillFactor.get2();
            }
        };
    }

    /**
     * @param memPlcsCfgs User-defined memory policy configurations.
     */
    private boolean hasCustomDefaultMemoryPolicy(MemoryPolicyConfiguration[] memPlcsCfgs) {
        for (MemoryPolicyConfiguration memPlcsCfg : memPlcsCfgs) {
            if (DFLT_MEM_PLC_DEFAULT_NAME.equals(memPlcsCfg.getName()))
                return true;
        }

        return false;
    }

    /**
     * @param sysCacheInitSize Initial size of PageMemory to be created for system cache.
     * @param sysCacheMaxSize Maximum size of PageMemory to be created for system cache.
     *
     * @return {@link MemoryPolicyConfiguration configuration} of MemoryPolicy for system cache.
     */
    private MemoryPolicyConfiguration createSystemMemoryPolicy(long sysCacheInitSize, long sysCacheMaxSize) {
        MemoryPolicyConfiguration res = new MemoryPolicyConfiguration();

        res.setName(SYSTEM_MEMORY_POLICY_NAME);
        res.setInitialSize(sysCacheInitSize);
        res.setMaxSize(sysCacheMaxSize);

        return res;
    }

    /**
     * @param memCfg configuration to validate.
     */
    private void validateConfiguration(MemoryConfiguration memCfg) throws IgniteCheckedException {
        checkPageSize(memCfg);

        MemoryPolicyConfiguration[] plcCfgs = memCfg.getMemoryPolicies();

        Set<String> plcNames = (plcCfgs != null) ? U.<String>newHashSet(plcCfgs.length) : new HashSet<String>(0);

        checkSystemMemoryPolicySizeConfiguration(
            memCfg.getSystemCacheInitialSize(),
            memCfg.getSystemCacheMaxSize()
        );

        if (plcCfgs != null) {
            for (MemoryPolicyConfiguration plcCfg : plcCfgs) {
                assert plcCfg != null;

                checkPolicyName(plcCfg.getName(), plcNames);

                checkPolicySize(plcCfg);

                checkMetricsProperties(plcCfg);

                checkPolicyEvictionProperties(plcCfg, memCfg);
            }
        }

        checkDefaultPolicyConfiguration(
            memCfg.getDefaultMemoryPolicyName(),
            memCfg.getDefaultMemoryPolicySize(),
            plcNames
        );
    }

    /**
     * @param memCfg Memory config.
     */
    protected void checkPageSize(MemoryConfiguration memCfg) {
        if (memCfg.getPageSize() == 0)
            memCfg.setPageSize(DFLT_PAGE_SIZE);
    }

    /**
     * @param plcCfg Memory policy config.
     *
     * @throws IgniteCheckedException if validation of memory metrics properties fails.
     */
    private static void checkMetricsProperties(MemoryPolicyConfiguration plcCfg) throws IgniteCheckedException {
        if (plcCfg.getRateTimeInterval() <= 0)
            throw new IgniteCheckedException("Rate time interval must be greater than zero " +
                "(use MemoryPolicyConfiguration.rateTimeInterval property to adjust the interval) " +
                "[name=" + plcCfg.getName() +
                ", rateTimeInterval=" + plcCfg.getRateTimeInterval() + "]"
            );
        if (plcCfg.getSubIntervals() <= 0)
            throw new IgniteCheckedException("Sub intervals must be greater than zero " +
                "(use MemoryPolicyConfiguration.subIntervals property to adjust the sub intervals) " +
                "[name=" + plcCfg.getName() +
                ", subIntervals=" + plcCfg.getSubIntervals() + "]"
            );

        if (plcCfg.getRateTimeInterval() < 1_000)
            throw new IgniteCheckedException("Rate time interval must be longer that 1 second (1_000 milliseconds) " +
                "(use MemoryPolicyConfiguration.rateTimeInterval property to adjust the interval) " +
                "[name=" + plcCfg.getName() +
                ", rateTimeInterval=" + plcCfg.getRateTimeInterval() + "]");
    }

    /**
     * @param sysCacheInitSize System cache initial size.
     * @param sysCacheMaxSize System cache max size.
     *
     * @throws IgniteCheckedException In case of validation violation.
     */
    private static void checkSystemMemoryPolicySizeConfiguration(
        long sysCacheInitSize,
        long sysCacheMaxSize
    ) throws IgniteCheckedException {
        if (sysCacheInitSize < MIN_PAGE_MEMORY_SIZE)
            throw new IgniteCheckedException("Initial size for system cache must have size more than 10MB (use " +
                "MemoryConfiguration.systemCacheInitialSize property to set correct size in bytes) " +
                "[size=" + U.readableSize(sysCacheInitSize, true) + ']'
            );

        if (U.jvm32Bit() && sysCacheInitSize > MAX_PAGE_MEMORY_INIT_SIZE_32_BIT)
            throw new IgniteCheckedException("Initial size for system cache exceeds 2GB on 32-bit JVM (use " +
                "MemoryPolicyConfiguration.systemCacheInitialSize property to set correct size in bytes " +
                "or use 64-bit JVM) [size=" + U.readableSize(sysCacheInitSize, true) + ']'
            );

        if (sysCacheMaxSize < sysCacheInitSize)
            throw new IgniteCheckedException("MaxSize of system cache must not be smaller than " +
                "initialSize [initSize=" + U.readableSize(sysCacheInitSize, true) +
                ", maxSize=" + U.readableSize(sysCacheMaxSize, true) + "]. " +
                "Use MemoryConfiguration.systemCacheInitialSize/MemoryConfiguration.systemCacheMaxSize " +
                "properties to set correct sizes in bytes."
            );
    }

    /**
     * @param dfltPlcName Default MemoryPolicy name.
     * @param dfltPlcSize Default size of MemoryPolicy overridden by user (equals to -1 if wasn't specified by user).
     * @param plcNames All MemoryPolicy names.
     * @throws IgniteCheckedException In case of validation violation.
     */
    private static void checkDefaultPolicyConfiguration(
        String dfltPlcName,
        long dfltPlcSize,
        Collection<String> plcNames
    ) throws IgniteCheckedException {
        if (dfltPlcSize != MemoryConfiguration.DFLT_MEMORY_POLICY_MAX_SIZE) {
            if (!F.eq(dfltPlcName, MemoryConfiguration.DFLT_MEM_PLC_DEFAULT_NAME))
                throw new IgniteCheckedException("User-defined MemoryPolicy configuration " +
                    "and defaultMemoryPolicySize properties are set at the same time. " +
                    "Delete either MemoryConfiguration.defaultMemoryPolicySize property " +
                    "or user-defined default MemoryPolicy configuration");

            if (dfltPlcSize < MIN_PAGE_MEMORY_SIZE)
                throw new IgniteCheckedException("User-defined default MemoryPolicy size is less than 1MB. " +
                        "Use MemoryConfiguration.defaultMemoryPolicySize property to set correct size.");

            if (U.jvm32Bit() && dfltPlcSize > MAX_PAGE_MEMORY_INIT_SIZE_32_BIT)
                throw new IgniteCheckedException("User-defined default MemoryPolicy size exceeds 2GB on 32-bit JVM " +
                    "(use MemoryConfiguration.defaultMemoryPolicySize property to set correct size in bytes " +
                    "or use 64-bit JVM) [size=" + U.readableSize(dfltPlcSize, true) + ']'
                );
        }

        if (!DFLT_MEM_PLC_DEFAULT_NAME.equals(dfltPlcName)) {
            if (dfltPlcName.isEmpty())
                throw new IgniteCheckedException("User-defined default MemoryPolicy name must be non-empty");

            if (!plcNames.contains(dfltPlcName))
                throw new IgniteCheckedException("User-defined default MemoryPolicy name " +
                    "must be presented among configured MemoryPolices: " + dfltPlcName);
        }
    }

    /**
     * @param plcCfg MemoryPolicyConfiguration to validate.
     * @throws IgniteCheckedException If config is invalid.
     */
    private void checkPolicySize(MemoryPolicyConfiguration plcCfg) throws IgniteCheckedException {
        boolean dfltInitSize = false;

        if (plcCfg.getInitialSize() == 0) {
            plcCfg.setInitialSize(DFLT_MEMORY_POLICY_INITIAL_SIZE);

            dfltInitSize = true;
        }

        if (plcCfg.getInitialSize() < MIN_PAGE_MEMORY_SIZE)
            throw new IgniteCheckedException("MemoryPolicy must have size more than 10MB (use " +
                "MemoryPolicyConfiguration.initialSize property to set correct size in bytes) " +
                "[name=" + plcCfg.getName() + ", size=" + U.readableSize(plcCfg.getInitialSize(), true) + "]"
            );

        if (plcCfg.getMaxSize() < plcCfg.getInitialSize()) {
            // If initial size was not set, use the max size.
            if (dfltInitSize) {
                plcCfg.setInitialSize(plcCfg.getMaxSize());

                LT.warn(log, "MemoryPolicy maxSize=" + U.readableSize(plcCfg.getMaxSize(), true) +
                    " is smaller than defaultInitialSize=" +
                    U.readableSize(MemoryConfiguration.DFLT_MEMORY_POLICY_INITIAL_SIZE, true) +
                    ", setting initialSize to " + U.readableSize(plcCfg.getMaxSize(), true));
            }
            else {
                throw new IgniteCheckedException("MemoryPolicy maxSize must not be smaller than " +
                    "initialSize [name=" + plcCfg.getName() +
                    ", initSize=" + U.readableSize(plcCfg.getInitialSize(), true) +
                    ", maxSize=" + U.readableSize(plcCfg.getMaxSize(), true) + ']');
            }
        }

        if (U.jvm32Bit() && plcCfg.getInitialSize() > MAX_PAGE_MEMORY_INIT_SIZE_32_BIT)
            throw new IgniteCheckedException("MemoryPolicy initialSize exceeds 2GB on 32-bit JVM (use " +
                "MemoryPolicyConfiguration.initialSize property to set correct size in bytes or use 64-bit JVM) " +
                "[name=" + plcCfg.getName() +
                ", size=" + U.readableSize(plcCfg.getInitialSize(), true) + "]");
    }

    /**
     * @param plcCfg MemoryPolicyConfiguration to validate.
     * @param dbCfg Memory configuration.
     * @throws IgniteCheckedException If config is invalid.
     */
    protected void checkPolicyEvictionProperties(MemoryPolicyConfiguration plcCfg, MemoryConfiguration dbCfg)
        throws IgniteCheckedException {
        if (plcCfg.getPageEvictionMode() == DataPageEvictionMode.DISABLED)
            return;

        if (plcCfg.getEvictionThreshold() < 0.5 || plcCfg.getEvictionThreshold() > 0.999) {
            throw new IgniteCheckedException("Page eviction threshold must be between 0.5 and 0.999: " +
                plcCfg.getName());
        }

        if (plcCfg.getEmptyPagesPoolSize() <= 10)
            throw new IgniteCheckedException("Evicted pages pool size should be greater than 10: " + plcCfg.getName());

        long maxPoolSize = plcCfg.getMaxSize() / dbCfg.getPageSize() / 10;

        if (plcCfg.getEmptyPagesPoolSize() >= maxPoolSize) {
            throw new IgniteCheckedException("Evicted pages pool size should be lesser than " + maxPoolSize +
                ": " + plcCfg.getName());
        }
    }

    /**
     * @param plcName MemoryPolicy name to validate.
     * @param observedNames Names of MemoryPolicies observed before.
     * @throws IgniteCheckedException If config is invalid.
     */
    private static void checkPolicyName(String plcName, Collection<String> observedNames)
        throws IgniteCheckedException {
        if (plcName == null || plcName.isEmpty())
            throw new IgniteCheckedException("User-defined MemoryPolicyConfiguration must have non-null and " +
                "non-empty name.");

        if (observedNames.contains(plcName))
            throw new IgniteCheckedException("Two MemoryPolicies have the same name: " + plcName);

        if (SYSTEM_MEMORY_POLICY_NAME.equals(plcName))
            throw new IgniteCheckedException("'sysMemPlc' policy name is reserved for internal use.");

        observedNames.add(plcName);
    }

    /**
     * @param log Logger.
     */
    public void dumpStatistics(IgniteLogger log) {
        if (freeListMap != null) {
            for (FreeListImpl freeList : freeListMap.values())
                freeList.dumpStatistics(log);
        }
    }

    /**
     * @return collection of all configured {@link MemoryPolicy policies}.
     */
    public Collection<MemoryPolicy> memoryPolicies() {
        return memPlcMap != null ? memPlcMap.values() : null;
    }

    /**
     * @return MemoryMetrics for all MemoryPolicies configured in Ignite instance.
     */
    public Collection<MemoryMetrics> memoryMetrics() {
        if (!F.isEmpty(memMetricsMap)) {
            // Intentionally return a collection copy to make it explicitly serializable.
            Collection<MemoryMetrics> res = new ArrayList<>(memMetricsMap.size());

            for (MemoryMetrics metrics : memMetricsMap.values())
                res.add(new MemoryMetricsSnapshot(metrics));

            return res;
        }
        else
            return Collections.emptyList();
    }

    /**
     * @return PersistenceMetrics if persistence is enabled or {@code null} otherwise.
     */
    public PersistenceMetrics persistentStoreMetrics() {
        return null;
    }

    /**
     * @param cachesToStart Started caches.
     * @throws IgniteCheckedException If failed.
     */
    public void readCheckpointAndRestoreMemory(List<DynamicCacheDescriptor> cachesToStart) throws IgniteCheckedException {
        // No-op.
    }

    /**
     * @param memPlcName Name of {@link MemoryPolicy} to obtain {@link MemoryMetrics} for.
     * @return {@link MemoryMetrics} snapshot for specified {@link MemoryPolicy} or {@code null} if
     * no {@link MemoryPolicy} is configured for specified name.
     */
    @Nullable public MemoryMetrics memoryMetrics(String memPlcName) {
        if (!F.isEmpty(memMetricsMap)) {
            MemoryMetrics memMetrics = memMetricsMap.get(memPlcName);

            if (memMetrics == null)
                return null;
            else
                return new MemoryMetricsSnapshot(memMetrics);
        }
        else
            return null;
    }

    /**
     * @param memPlcName Memory policy name.
     * @return {@link MemoryPolicy} instance associated with a given {@link MemoryPolicyConfiguration}.
     * @throws IgniteCheckedException in case of request for unknown MemoryPolicy.
     */
    public MemoryPolicy memoryPolicy(String memPlcName) throws IgniteCheckedException {
        if (memPlcName == null)
            return dfltMemPlc;

        if (memPlcMap == null)
            return null;

        MemoryPolicy plc;

        if ((plc = memPlcMap.get(memPlcName)) == null)
            throw new IgniteCheckedException("Requested MemoryPolicy is not configured: " + memPlcName);

        return plc;
    }

    /**
     * @param memPlcName MemoryPolicyConfiguration name.
     * @return {@link FreeList} instance associated with a given {@link MemoryPolicyConfiguration}.
     */
    public FreeList freeList(String memPlcName) {
        if (memPlcName == null)
            return dfltFreeList;

        return freeListMap != null ? freeListMap.get(memPlcName) : null;
    }

    /**
     * @param memPlcName MemoryPolicyConfiguration name.
     * @return {@link ReuseList} instance associated with a given {@link MemoryPolicyConfiguration}.
     */
    public ReuseList reuseList(String memPlcName) {
        if (memPlcName == null)
            return dfltFreeList;

        return freeListMap != null ? freeListMap.get(memPlcName) : null;
    }

    /** {@inheritDoc} */
    @Override protected void stop0(boolean cancel) {
        if (memPlcMap != null) {
            for (MemoryPolicy memPlc : memPlcMap.values()) {
                memPlc.pageMemory().stop();

                memPlc.evictionTracker().stop();

                unregisterMBean(memPlc.memoryMetrics().getName());
            }

            memPlcMap.clear();

            memPlcMap = null;
        }
    }

    /**
     * Unregister MBean.
     * @param name Name of mbean.
     */
    private void unregisterMBean(String name) {
        if(U.IGNITE_MBEANS_DISABLED)
            return;

        IgniteConfiguration cfg = cctx.gridConfig();

        try {
            cfg.getMBeanServer().unregisterMBean(
                U.makeMBeanName(
                    cfg.getIgniteInstanceName(),
                    "MemoryMetrics", name
                    ));
        }
        catch (Throwable e) {
            U.error(log, "Failed to unregister MBean for memory metrics: " +
                name, e);
        }
    }

    /**
     *
     */
    public boolean persistenceEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean checkpointLockIsHeldByThread() {
        return true;
    }

    /**
     *
     */
    public void lock() throws IgniteCheckedException {

    }

    /**
     *
     */
    public void unLock() {

    }

    /**
     * No-op for non-persistent storage.
     */
    public void checkpointReadLock() {
        // No-op.
    }

    /**
     * No-op for non-persistent storage.
     */
    public void checkpointReadUnlock() {
        // No-op.
    }

    /**
     *
     */
    @Nullable public IgniteInternalFuture wakeupForCheckpoint(String reason) {
        return null;
    }

    /**
     * Waits until current state is checkpointed.
     *
     * @throws IgniteCheckedException If failed.
     */
    public void waitForCheckpoint(String reason) throws IgniteCheckedException {
        // No-op
    }

    /**
     * @param discoEvt Before exchange for the given discovery event.
     */
    public void beforeExchange(GridDhtPartitionsExchangeFuture discoEvt) throws IgniteCheckedException {
        // No-op.
    }

    /**
     * Needed action before any cache will stop
     */
    public void prepareCachesStop() {
        // No-op.
    }

    /**
     * @param stoppedGrps A collection of tuples (cache group, destroy flag).
     */
    public void onCacheGroupsStopped(Collection<IgniteBiTuple<CacheGroupContext, Boolean>> stoppedGrps) {
        // No-op.
    }

    /**
     * @return Future that will be completed when indexes for given cache are restored.
     */
    @Nullable public IgniteInternalFuture indexRebuildFuture(int cacheId) {
        return null;
    }

    /**
     * Reserve update history for exchange.
     *
     * @return Reserved update counters per cache and partition.
     */
    public Map<Integer, Map<Integer, Long>> reserveHistoryForExchange() {
        return Collections.emptyMap();
    }

    /**
     * Release reserved update history.
     */
    public void releaseHistoryForExchange() {
        // No-op
    }

    /**
     * Reserve update history for preloading.
     * @param grpId Cache group ID.
     * @param partId Partition Id.
     * @param cntr Update counter.
     * @return True if successfully reserved.
     */
    public boolean reserveHistoryForPreloading(int grpId, int partId, long cntr) {
        return false;
    }

    /**
     * Release reserved update history.
     */
    public void releaseHistoryForPreloading() {
        // No-op
    }

    /**
     * See {@link GridCacheMapEntry#ensureFreeSpace()}
     *
     * @param memPlc Memory policy.
     */
    public void ensureFreeSpace(MemoryPolicy memPlc) throws IgniteCheckedException {
        if (memPlc == null)
            return;

        MemoryPolicyConfiguration plcCfg = memPlc.config();

        if (plcCfg.getPageEvictionMode() == DataPageEvictionMode.DISABLED)
            return;

        long memorySize = plcCfg.getMaxSize();

        PageMemory pageMem = memPlc.pageMemory();

        int sysPageSize = pageMem.systemPageSize();

        FreeListImpl freeListImpl = freeListMap.get(plcCfg.getName());

        for (;;) {
            long allocatedPagesCnt = pageMem.loadedPages();

            int emptyDataPagesCnt = freeListImpl.emptyDataPages();

            boolean shouldEvict = allocatedPagesCnt > (memorySize / sysPageSize * plcCfg.getEvictionThreshold()) &&
                emptyDataPagesCnt < plcCfg.getEmptyPagesPoolSize();

            if (shouldEvict)
                memPlc.evictionTracker().evictDataPage();
            else
                break;
        }
    }

    /**
     * @param memCfg memory configuration with common parameters.
     * @param plcCfg memory policy with PageMemory specific parameters.
     * @param memMetrics {@link MemoryMetrics} object to collect memory usage metrics.
     * @return Memory policy instance.
     *
     * @throws IgniteCheckedException If failed to initialize swap path.
     */
    private MemoryPolicy initMemory(
        MemoryConfiguration memCfg,
        MemoryPolicyConfiguration plcCfg,
        MemoryMetricsImpl memMetrics
    ) throws IgniteCheckedException {
        File allocPath = buildAllocPath(plcCfg);

        DirectMemoryProvider memProvider = allocPath == null ?
            new UnsafeMemoryProvider(log) :
            new MappedFileMemoryProvider(
                log,
                allocPath);

        PageMemory pageMem = createPageMemory(memProvider, memCfg, plcCfg, memMetrics);

        return new MemoryPolicy(pageMem, plcCfg, memMetrics, createPageEvictionTracker(plcCfg, pageMem));
    }

    /**
     * @param plc Memory Policy Configuration.
     * @param pageMem Page memory.
     */
    private PageEvictionTracker createPageEvictionTracker(MemoryPolicyConfiguration plc, PageMemory pageMem) {
        if (plc.getPageEvictionMode() == DataPageEvictionMode.DISABLED || cctx.gridConfig().isPersistentStoreEnabled())
            return new NoOpPageEvictionTracker();

        assert pageMem instanceof PageMemoryNoStoreImpl : pageMem.getClass();

        PageMemoryNoStoreImpl pageMem0 = (PageMemoryNoStoreImpl)pageMem;

        if (Boolean.getBoolean("override.fair.fifo.page.eviction.tracker"))
            return new FairFifoPageEvictionTracker(pageMem0, plc, cctx);

        switch (plc.getPageEvictionMode()) {
            case RANDOM_LRU:
                return new RandomLruPageEvictionTracker(pageMem0, plc, cctx);
            case RANDOM_2_LRU:
                return new Random2LruPageEvictionTracker(pageMem0, plc, cctx);
            default:
                return new NoOpPageEvictionTracker();
        }
    }

    /**
     * Builds allocation path for memory mapped file to be used with PageMemory.
     *
     * @param plc MemoryPolicyConfiguration.
     *
     * @throws IgniteCheckedException If resolving swap directory fails.
     */
    @Nullable private File buildAllocPath(MemoryPolicyConfiguration plc) throws IgniteCheckedException {
        String path = plc.getSwapFilePath();

        if (path == null)
            return null;

        final PdsFolderSettings folderSettings = cctx.kernalContext().pdsFolderResolver().resolveFolders();
        final String folderName;

        if(folderSettings.isCompatible())
            folderName = String.valueOf(folderSettings.consistentId()).replaceAll("[:,\\.]", "_");
        else
            folderName = folderSettings.folderName();

        return buildPath(path, folderName);
    }

    /**
     * Creates PageMemory with given size and memory provider.
     *
     * @param memProvider Memory provider.
     * @param memCfg Memory configuartion.
     * @param memPlcCfg Memory policy configuration.
     * @param memMetrics MemoryMetrics to collect memory usage metrics.
     * @return PageMemory instance.
     */
    protected PageMemory createPageMemory(
        DirectMemoryProvider memProvider,
        MemoryConfiguration memCfg,
        MemoryPolicyConfiguration memPlcCfg,
        MemoryMetricsImpl memMetrics
    ) {
        memMetrics.persistenceEnabled(false);

        return new PageMemoryNoStoreImpl(
            log,
            memProvider,
            cctx,
            memCfg.getPageSize(),
            memPlcCfg,
            memMetrics,
            false
        );
    }

    /**
     * @param path Path to the working directory.
     * @param consId Consistent ID of the local node.
     * @return DB storage path.
     *
     * @throws IgniteCheckedException If resolving swap directory fails.
     */
    protected File buildPath(String path, String consId) throws IgniteCheckedException {
        String igniteHomeStr = U.getIgniteHome();

        File workDir = igniteHomeStr == null ? new File(path) : U.resolveWorkDirectory(igniteHomeStr, path, false);


        return new File(workDir, consId);
    }

    /** {@inheritDoc} */
    @Override public void onActivate(GridKernalContext kctx) throws IgniteCheckedException {
        if (cctx.kernalContext().clientNode() && cctx.kernalContext().config().getMemoryConfiguration() == null)
            return;

        MemoryConfiguration memCfg = cctx.kernalContext().config().getMemoryConfiguration();

        assert memCfg != null;

        initPageMemoryPolicies(memCfg);

        registerMetricsMBeans();

        startMemoryPolicies();

        initPageMemoryDataStructures(memCfg);
    }

    /** {@inheritDoc} */
    @Override public void onDeActivate(GridKernalContext kctx) {
        stop0(false);
    }

    /**
     * @return Name of MemoryPolicyConfiguration for internal caches.
     */
    public String systemMemoryPolicyName() {
        return SYSTEM_MEMORY_POLICY_NAME;
    }

    /**
     * Method for fake (standalone) context initialization. Not to be called in production code
     * @param pageSize configured page size
     */
    protected void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
