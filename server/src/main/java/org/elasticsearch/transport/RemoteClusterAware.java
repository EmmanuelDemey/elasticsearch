/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport;

import org.elasticsearch.cluster.metadata.ClusterNameExpressionResolver;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base class for all services and components that need up-to-date information about the registered remote clusters
 */
public abstract class RemoteClusterAware {

    public static final char REMOTE_CLUSTER_INDEX_SEPARATOR = ':';
    public static final String LOCAL_CLUSTER_GROUP_KEY = "";

    protected final Settings settings;
    private final ClusterNameExpressionResolver clusterNameResolver;

    /**
     * Creates a new {@link RemoteClusterAware} instance
     * @param settings the nodes level settings
     */
    protected RemoteClusterAware(Settings settings) {
        this.settings = settings;
        this.clusterNameResolver = new ClusterNameExpressionResolver();
    }

    /**
     * Returns remote clusters that are enabled in these settings
     */
    protected static Set<String> getEnabledRemoteClusters(final Settings settings) {
        return RemoteConnectionStrategy.getRemoteClusters(settings);
    }

    /**
     * Groups indices per cluster by splitting remote cluster-alias, index-name pairs on {@link #REMOTE_CLUSTER_INDEX_SEPARATOR}. All
     * indices per cluster are collected as a list in the returned map keyed by the cluster alias. Local indices are grouped under
     * {@link #LOCAL_CLUSTER_GROUP_KEY}. The returned map is mutable.
     *
     * @param remoteClusterNames the remote cluster names
     * @param requestIndices the indices in the search request to filter
     *
     * @return a map of grouped remote and local indices
     */
    protected Map<String, List<String>> groupClusterIndices(Set<String> remoteClusterNames, String[] requestIndices) {
        Map<String, List<String>> perClusterIndices = new HashMap<>();
        for (String index : requestIndices) {
            int i = index.indexOf(RemoteClusterService.REMOTE_CLUSTER_INDEX_SEPARATOR);
            if (i >= 0) {
                String remoteClusterName = index.substring(0, i);
                List<String> clusters = clusterNameResolver.resolveClusterNames(remoteClusterNames, remoteClusterName);
                String indexName = index.substring(i + 1);
                for (String clusterName : clusters) {
                    perClusterIndices.computeIfAbsent(clusterName, k -> new ArrayList<>()).add(indexName);
                }
            } else {
                perClusterIndices.computeIfAbsent(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY, k -> new ArrayList<>()).add(index);
            }
        }
        return perClusterIndices;
    }

    void validateAndUpdateRemoteCluster(String clusterAlias, Settings settings) {
        if (RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY.equals(clusterAlias)) {
            throw new IllegalArgumentException("remote clusters must not have the empty string as its key");
        }
        updateRemoteCluster(clusterAlias, settings);
    }

    /**
     * Subclasses must implement this to receive information about updated cluster aliases.
     */
    protected abstract void updateRemoteCluster(String clusterAlias, Settings settings);

    /**
     * Registers this instance to listen to updates on the cluster settings.
     */
    public void listenForUpdates(ClusterSettings clusterSettings) {
        List<Setting.AffixSetting<?>> remoteClusterSettings = Arrays.asList(
            RemoteClusterService.REMOTE_CLUSTER_COMPRESS,
            RemoteClusterService.REMOTE_CLUSTER_PING_SCHEDULE,
            RemoteConnectionStrategy.REMOTE_CONNECTION_MODE,
            SniffConnectionStrategy.REMOTE_CLUSTERS_PROXY,
            SniffConnectionStrategy.REMOTE_CLUSTER_SEEDS,
            SniffConnectionStrategy.REMOTE_CLUSTER_SEEDS_OLD,
            SniffConnectionStrategy.REMOTE_NODE_CONNECTIONS,
            SimpleConnectionStrategy.REMOTE_CLUSTER_ADDRESSES,
            SimpleConnectionStrategy.REMOTE_SOCKET_CONNECTIONS);
        clusterSettings.addAffixGroupUpdateConsumer(remoteClusterSettings, this::validateAndUpdateRemoteCluster);
    }

    public static String buildRemoteIndexName(String clusterAlias, String indexName) {
        return clusterAlias == null || LOCAL_CLUSTER_GROUP_KEY.equals(clusterAlias)
            ? indexName : clusterAlias + REMOTE_CLUSTER_INDEX_SEPARATOR + indexName;
    }
}
