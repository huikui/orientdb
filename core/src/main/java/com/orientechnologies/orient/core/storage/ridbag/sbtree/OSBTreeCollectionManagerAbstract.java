/*
  *
  *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
  *  *
  *  *  Licensed under the Apache License, Version 2.0 (the "License");
  *  *  you may not use this file except in compliance with the License.
  *  *  You may obtain a copy of the License at
  *  *
  *  *       http://www.apache.org/licenses/LICENSE-2.0
  *  *
  *  *  Unless required by applicable law or agreed to in writing, software
  *  *  distributed under the License is distributed on an "AS IS" BASIS,
  *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *  *  See the License for the specific language governing permissions and
  *  *  limitations under the License.
  *  *
  *  * For more information: http://orientdb.com
  *
  */

package com.orientechnologies.orient.core.storage.ridbag.sbtree;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.orientechnologies.common.concur.resource.OCloseable;
import com.orientechnologies.orient.core.OOrientShutdownListener;
import com.orientechnologies.orient.core.OOrientStartupListener;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.index.sbtreebonsai.local.OSBTreeBonsai;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Artem Orobets (enisher-at-gmail.com)
 */
public abstract class OSBTreeCollectionManagerAbstract
    implements OCloseable, OSBTreeCollectionManager, OOrientStartupListener, OOrientShutdownListener {
  public static final String FILE_NAME_PREFIX  = "collections_";
  public static final String DEFAULT_EXTENSION = ".sbc";

  /**
   * Generates a lock name for the given cluster ID.
   *
   * @param clusterId the cluster ID to generate the lock name for.
   *
   * @return the generated lock name.
   */
  public static String generateLockName(int clusterId) {
    return FILE_NAME_PREFIX + clusterId + DEFAULT_EXTENSION;
  }

  private static final ConcurrentLinkedHashMap<CacheKey, SBTreeBonsaiContainer> GLOBAL_TREE_CACHE = new ConcurrentLinkedHashMap.Builder<CacheKey, SBTreeBonsaiContainer>()
      .maximumWeightedCapacity(Long.MAX_VALUE).build();

  private static final int GLOBAL_EVICTION_THRESHOLD = OGlobalConfiguration.SBTREEBONSAI_LINKBAG_CACHE_EVICTION_SIZE
      .getValueAsInteger();
  private static final int GLOBAL_CACHE_MAX_SIZE     = OGlobalConfiguration.SBTREEBONSAI_LINKBAG_CACHE_SIZE.getValueAsInteger();

  private static final Object[] GLOBAL_LOCKS;
  private static final int      GLOBAL_SHIFT;
  private static final int      GLOBAL_MASK;

  static {
    final int concurrencyLevel = Runtime.getRuntime().availableProcessors() * 8;
    int size = 1;

    int shifted = 0;
    while (size < concurrencyLevel) {
      size <<= 1;
      shifted++;
    }

    GLOBAL_SHIFT = 32 - shifted;
    GLOBAL_MASK = size - 1;

    final Object[] locks = new Object[size];
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new Object();
    }

    GLOBAL_LOCKS = locks;
  }

  private final int                                                      evictionThreshold;
  private final int                                                      cacheMaxSize;
  private final int                                                      shift;
  private final int                                                      mask;
  private final Object[]                                                 locks;
  private final ConcurrentLinkedHashMap<CacheKey, SBTreeBonsaiContainer> treeCache;
  private final OStorage                                                 storage;
  private final ConcurrentHashMap<CacheKey, List<OBonsaiCollectionPointer>> markedForDeletion = new ConcurrentHashMap<>();

  public OSBTreeCollectionManagerAbstract(OStorage storage) {
    this(GLOBAL_TREE_CACHE, storage, GLOBAL_EVICTION_THRESHOLD, GLOBAL_CACHE_MAX_SIZE, GLOBAL_LOCKS);
  }

  // for testing purposes
  /* internal */ OSBTreeCollectionManagerAbstract(OStorage storage, int evictionThreshold, int cacheMaxSize) {
    this(new ConcurrentLinkedHashMap.Builder<CacheKey, SBTreeBonsaiContainer>().maximumWeightedCapacity(Long.MAX_VALUE).build(),
        storage, evictionThreshold, cacheMaxSize, null);
  }

  private OSBTreeCollectionManagerAbstract(ConcurrentLinkedHashMap<CacheKey, SBTreeBonsaiContainer> treeCache, OStorage storage,
      int evictionThreshold, int cacheMaxSize, Object[] locks) {
    this.treeCache = treeCache;
    this.storage = storage;

    this.evictionThreshold = evictionThreshold;
    this.cacheMaxSize = cacheMaxSize;

    if (locks == null) {
      final int concurrencyLevel = Runtime.getRuntime().availableProcessors() * 8;
      int size = 1;

      int shifted = 0;
      while (size < concurrencyLevel) {
        size <<= 1;
        shifted++;
      }

      shift = 32 - shifted;
      mask = size - 1;

      locks = new Object[size];
      for (int i = 0; i < locks.length; i++) {
        locks[i] = new Object();
      }
    } else {
      shift = GLOBAL_SHIFT;
      mask = GLOBAL_MASK;
    }

    this.locks = locks;

    Orient.instance().registerWeakOrientStartupListener(this);
    Orient.instance().registerWeakOrientShutdownListener(this);
  }

  @Override
  public void onStartup() {
    // do nothing
  }

  @Override
  public void onShutdown() {
    treeCache.clear();
  }

  @Override
  public OSBTreeBonsai<OIdentifiable, Integer> createAndLoadTree(int clusterId) throws IOException {
    return loadSBTree(createSBTree(clusterId, null));
  }

  @Override
  public OBonsaiCollectionPointer createSBTree(int clusterId, UUID ownerUUID) throws IOException {
    OSBTreeBonsai<OIdentifiable, Integer> tree = createTree(clusterId);
    return tree.getCollectionPointer();
  }

  @Override
  public OSBTreeBonsai<OIdentifiable, Integer> loadSBTree(OBonsaiCollectionPointer collectionPointer) {
    final CacheKey cacheKey = new CacheKey(storage, collectionPointer);
    final Object lock = treesSubsetLock(cacheKey);

    final OSBTreeBonsai<OIdentifiable, Integer> tree;

    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (lock) {
      SBTreeBonsaiContainer container = treeCache.get(cacheKey);            
       
      if (container != null && container.usagesCounter <= 1){
        List<OBonsaiCollectionPointer> collectionToDelete = markedForDeletion.get(cacheKey);
        if (collectionToDelete != null){
          synchronized(collectionToDelete){
            for (OBonsaiCollectionPointer pointer : collectionToDelete){
              final CacheKey ck = new CacheKey(storage, pointer);
              treeCache.remove(ck);
            }
            collectionToDelete.clear();            
          }
          //should be safe logical removal because add in is synchronized on same object
          markedForDeletion.remove(cacheKey);
        }
      }
      
      if (container != null) {
        container.usagesCounter++;
        tree = container.tree;
      } else {
        tree = loadTree(collectionPointer);
        if (tree != null) {
          assert tree.getRootBucketPointer().equals(collectionPointer.getRootPointer());

          container = new SBTreeBonsaiContainer(tree);
          container.usagesCounter++;

          treeCache.put(cacheKey, container);
        }
      }
            
    }

    evict();        

    return tree;
  }

  @Override
  public void releaseSBTree(OBonsaiCollectionPointer collectionPointer) {
    final CacheKey cacheKey = new CacheKey(storage, collectionPointer);
    final Object lock = treesSubsetLock(cacheKey);
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (lock) {
      SBTreeBonsaiContainer container = treeCache.getQuietly(cacheKey);
      assert container != null;
      container.usagesCounter--;
      assert container.usagesCounter >= 0;
    }

    evict();
  }

  @Override
  public void delete(OBonsaiCollectionPointer collectionPointer) {
    final CacheKey cacheKey = new CacheKey(storage, collectionPointer);
    final Object lock = treesSubsetLock(cacheKey);
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (lock) {
      SBTreeBonsaiContainer container = treeCache.getQuietly(cacheKey);
      assert container != null;

      if (container.usagesCounter != 0) {
        List<OBonsaiCollectionPointer> pointersCollection = markedForDeletion.get(cacheKey);
        //lets say that I know what I am doing
        if (pointersCollection == null){
          pointersCollection = new LinkedList<>();
          markedForDeletion.put(cacheKey, pointersCollection);
        }
        synchronized(pointersCollection){
          pointersCollection.add(collectionPointer);
        }
      }
      else{
        treeCache.remove(cacheKey);
      }
    }
  }

  private void evict() {
    if (treeCache.size() <= cacheMaxSize) {
      return;
    }

    for (CacheKey cacheKey : treeCache.ascendingKeySetWithLimit(evictionThreshold)) {
      final Object treeLock = treesSubsetLock(cacheKey);
      //noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (treeLock) {
        SBTreeBonsaiContainer container = treeCache.getQuietly(cacheKey);
        if (container != null && container.usagesCounter == 0) {
          treeCache.remove(cacheKey);
        }
      }
    }
  }

  @Override
  public void close() {
    clear();
  }

  public void clear() {
    treeCache.keySet().removeIf(cacheKey -> cacheKey.storage == storage);
  }

  protected abstract OSBTreeBonsai<OIdentifiable, Integer> createTree(int clusterId) throws IOException;

  protected abstract OSBTreeBonsai<OIdentifiable, Integer> loadTree(OBonsaiCollectionPointer collectionPointer);

  int size() {
    return treeCache.size();
  }

  private Object treesSubsetLock(CacheKey cacheKey) {
    final int hashCode = cacheKey.hashCode();
    final int index = (hashCode >>> shift) & mask;

    return locks[index];
  }

  private static final class SBTreeBonsaiContainer {
    private final OSBTreeBonsai<OIdentifiable, Integer> tree;
    private int usagesCounter = 0;

    private SBTreeBonsaiContainer(OSBTreeBonsai<OIdentifiable, Integer> tree) {
      this.tree = tree;
    }
  }

  private static final class CacheKey {
    private final OStorage                 storage;
    private final OBonsaiCollectionPointer pointer;

    CacheKey(OStorage storage, OBonsaiCollectionPointer pointer) {
      this.storage = storage;
      this.pointer = pointer;
    }

    @Override
    public int hashCode() {
      return storage.hashCode() ^ pointer.hashCode();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass") // it's a private class used in a private context
    @Override
    public boolean equals(Object obj) {
      final CacheKey other = (CacheKey) obj;
      return this.storage == other.storage && this.pointer.equals(other.pointer);
    }
  }
}
