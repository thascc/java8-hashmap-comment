package java.util;

import sun.misc.SharedSecrets;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class HashMap<K, V> extends AbstractMap<K, V>
    implements Map<K, V>, Cloneable, Serializable {

    /**
     * 默认数组大小 16 , (必须为2的倍数)
     */
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // aka 16
    /**
     * 最大数组大小 2 ^ 31 (必须为2的倍数)
     */
    static final int MAXIMUM_CAPACITY = 1 << 30;
    /**
     * 默认负载因子
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    /**
     * 树化阈值 , 碰撞元素数量 >8 时转为红黑树
     */
    static final int TREEIFY_THRESHOLD = 8;
    /**
     * 反树化阈值 , 碰撞元素数量 <6 时转为链表 只有在resize过程split树才会用到, 并不是remove时使用
     */
    static final int UNTREEIFY_THRESHOLD = 6;
    /**
     * 最小可树化的数组大小 , 只有数组大小 >=64 时才会启用树化
     */
    static final int MIN_TREEIFY_CAPACITY = 64;
    private static final long serialVersionUID = 362498820763181265L;
    /**
     * 负载因子 默认0.75
     */
    final float loadFactor;

    /* ---------------- 静态公共方法 -------------- */
    /**
     * 实际用来保存哈希表元素的数组, 实例化时不初始化, 在第一次被使用(必要)时初始化 , 数组容量永远是2的幂 (在某些操作中，我们还允许长度为零，以允许使用当前不需要的引导机制)
     */
    transient Node<K, V>[] table;
    /**
     * 哈希表中所有元素的集合 {@link #entrySet()}
     */
    transient Set<Entry<K, V>> entrySet;
    /**
     * 哈希表中实际存储的元素数量
     */
    transient int size;
    /**
     * 用于支持fail-fast机制的计数器 每次修改时都会使modCount+1
     */
    transient int modCount;

    /* ---------------- 内部字段 -------------- */
    /**
     * 阈值 也就是哈希实际可以容纳的元素数量 区别于{@link #table}的大小 threshold = {@link #table}.length * {@link #loadFactor}
     */
    int threshold;

    /**
     * @param initialCapacity 初始数组容量 不是threshold
     * @param loadFactor      负载因子
     * @throws IllegalArgumentException 参数不合法异常
     */
    public HashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal initial capacity: " +
                initialCapacity);
        }
        if (initialCapacity > MAXIMUM_CAPACITY) { initialCapacity = MAXIMUM_CAPACITY; }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal load factor: " +
                loadFactor);
        }
        this.loadFactor = loadFactor;
        // 数组未初始化时 使用threshold暂时来存储数组的大小 这里非常容易被误解
        this.threshold = tableSizeFor(initialCapacity);
    }

    /**
     * @param initialCapacity 初始数组容量 不是threshold
     * @throws IllegalArgumentException 参数不合法异常
     */
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public HashMap() {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
    }

    /**
     * 复制一个Map
     *
     * @param sourceMap 原来的Map
     * @throws NullPointerException 空指针异常
     */
    public HashMap(Map<? extends K, ? extends V> sourceMap) {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
        putMapEntries(sourceMap, false);
    }

    /**
     * hash值扰动函数 高16位不变 低16位与高16位异或 降低低16位碰撞的概率
     *
     * @param javaHashCode {@link Object#hashCode()} java方法得到的hash值
     * @return 哈希表的hash值 逻辑上的hash索引
     */
    static int hash(Object javaHashCode) {
        int h;
        return (javaHashCode == null) ? 0 : (h = javaHashCode.hashCode()) ^ (h >>> 16);
    }

    /* ---------------- 公开的方法 -------------- */

    /**
     * 如果对象{@param keyObj}(Key)继承了Comparable<对象类型>(该对象是可比较大小的) 则返回该对象的类型 ; 否则返回null
     *
     * @param keyObj 待检查的对象 通常是Key
     * @return 该对象的类型
     */
    static Class<?> comparableClassFor(Object keyObj) {
        if (keyObj instanceof Comparable) {
            // 对象类型
            Class<?> clazz;
            // 该对象所有的接口类型
            Type[] allInterfaceTypes;
            // 遍历过程当前接口类型
            Type currentType;
            // 当前接口类型的泛型参数类型
            ParameterizedType parameterizedType;
            // 当前接口类型的泛型参数具体类型
            Type[] actualTypes;

            if ((clazz = keyObj.getClass()) == String.class) {
                // String类型不用检查
                return clazz;
            }
            if ((allInterfaceTypes = clazz.getGenericInterfaces()) != null) {
                for (int i = 0; i < allInterfaceTypes.length; ++i) {
                    if (((currentType = allInterfaceTypes[i]) instanceof ParameterizedType) &&
                        ((parameterizedType = (ParameterizedType)currentType).getRawType() ==
                            Comparable.class) &&
                        (actualTypes = parameterizedType.getActualTypeArguments()) != null &&
                        actualTypes.length == 1 && actualTypes[0] == clazz) {
                        // 判断Comparable<T>的泛型参数T是不是本对象的类型 否则就算实现了Comparable接口也是不可比较的
                        return clazz;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 比较{@param key}(当前Key)和比较对象{@param anotherObj}的大小
     *
     * @param clazz
     * @param key        当前Key
     * @param anotherObj 比较对象 比如已在Hash表中的某个元素的Key
     * @return {@param key} - {@param anotherObj}
     */
    @SuppressWarnings({"rawtypes", "unchecked"}) // for cast to Comparable
    static int compareComparables(Class<?> clazz, Object key, Object anotherObj) {
        return (anotherObj == null || anotherObj.getClass() != clazz ? 0 :
            ((Comparable)key).compareTo(anotherObj));
    }

    /**
     * 将数组容量调整为2的幂 比如 1010, 先减一得1001, 后面全部变为一得1111, 再加一得10000
     *
     * @param currentCapacity 当前容量
     * @return 调整后的容量
     */
    static final int tableSizeFor(int currentCapacity) {
        int n = currentCapacity - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /**
     * 导入一个Map的内容
     *
     * @param sourceMap 待导入的Map
     * @param evict     启用逐出模式 当初始化时应当将它设置为false
     */
    final void putMapEntries(Map<? extends K, ? extends V> sourceMap, boolean evict) {
        // 原先Map的元素数量 可以当做本Map的阈值(期望存放元素数量)
        int sourceMapSize = sourceMap.size();
        if (sourceMapSize > 0) {
            if (table == null) {
                // 尚未初始化table 可以根据原Map的元素数量计算应当分配的数组大小
                float tmpExpectCapacity = ((float)sourceMapSize / loadFactor) + 1.0F;
                int capacity = ((tmpExpectCapacity < (float)MAXIMUM_CAPACITY) ? (int)tmpExpectCapacity
                    : MAXIMUM_CAPACITY);
                if (capacity > threshold) {
                    // 与实例化时相同 使用threshold临时存放capacity 等到put元素时 会触发resize进行纠正
                    threshold = tableSizeFor(capacity);
                }
            } else if (sourceMapSize > threshold) {
                // 数组已初始化 阈值不够则进行扩容
                resize();
            }
            for (Entry<? extends K, ? extends V> e : sourceMap.entrySet()) {
                K key = e.getKey();
                V value = e.getValue();
                putVal(hash(key), key, value, false, evict);
            }
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public V get(Object key) {
        Node<K, V> e;
        return (e = getNode(hash(key), key)) == null ? null : e.value;
    }

    /**
     * {@link #get(Object)}的具体实现
     *
     * @param hash 扰动计算后的hash值
     * @param key  待查找的key
     * @return {@param key}所关联的完整Node Key或Value是否为null 不影响这里的结果
     */
    final Node<K, V> getNode(int hash, Object key) {
        Node<K, V>[] tab;
        int tableLength;
        K currentKey;
        if ((tab = table) != null && (tableLength = tab.length) > 0) {
            // 计算hash值在数组中的索引
            int index = (tableLength - 1) & hash;
            Node<K, V> targetNode = tab[index];
            if (targetNode != null) {
                if (targetNode.hash == hash &&
                    ((currentKey = targetNode.key) == key || (key != null && key.equals(currentKey)))) {
                    // 如果该索引位置的节点的Key与待查找的key相等 则直接返回该元素
                    return targetNode;
                }
                Node<K, V> current;
                if ((current = targetNode.next) != null) {
                    // 如果该索引处不止一个节点
                    if (targetNode instanceof TreeNode) {
                        // 如果该处是红黑树结构
                        return ((TreeNode<K, V>)targetNode).getTreeNode(hash, key);
                    }
                    do {
                        // 如果该处只是链表 则遍历至找到相等的key或最后一个结点
                        if (current.hash == hash &&
                            ((currentKey = current.key) == key || (key != null && key.equals(currentKey)))) {
                            return current;
                        }
                    } while ((current = current.next) != null);
                }
            }
        }
        // 如果该索引处没有任何任何元素
        return null;
    }

    /**
     * 判断是否存在某个Key 需要注意 {@link #get(Object)}返回null不能说明不存在某个元素, 可能Node存在, 但Node的Value为null 而 {@link #getNode(int,
     * Object)} )}返回null则真正说明该{@param key}不存在
     *
     * @param key 待查找的key
     * @return 存在返回true
     */
    @Override
    public boolean containsKey(Object key) {
        return getNode(hash(key), key) != null;
    }

    @Override
    public V put(K key, V value) {
        return putVal(hash(key), key, value, false, true);
    }

    /**
     * {@link #put(Object, Object)}的具体实现
     *
     * @param hash         扰动后的Hash值
     * @param key          key
     * @param value        value
     * @param onlyIfAbsent 如果已存在key则不操作
     * @param evict        (容量满时)是否逐出首元素, 做缓存时使用 (初始化时应当置为false)
     * @return 如果先前已存在该Key 则返回原来的value, 否则返回null
     */
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
                   boolean evict) {
        Node<K, V>[] tab;
        int tableLength;
        if ((tab = table) == null || (tableLength = tab.length) == 0) {
            // 当数组尚未初始化时, 使用resize方法初始化
            tableLength = (tab = resize()).length;
        }
        int index = (tableLength - 1) & hash;
        Node<K, V> currentNode = tab[index];
        if (currentNode == null) {
            // 如果该位置没有Key(没有出现Hash碰撞) 构建Node直接放上去
            tab[index] = newNode(hash, key, value, null);
        } else {
            Node<K, V> oldNode;
            K k;
            if (currentNode.hash == hash &&
                ((k = currentNode.key) == key || (key != null && key.equals(k)))) {
                // 如果找到了相同的Key的元素 统一在最后设置Value即可(不用新构建Node)
                oldNode = currentNode;
            } else if (currentNode instanceof TreeNode) {
                // 如果该节点是红黑树节点(根节点), 类似getNode, 直接调用红黑树putTreeVal进行操作
                // 同样的 如果红黑树上已经存在了该Key, 则应当返回该Node, 统一设置新Value; 如果没有该元素, 应当返回null
                oldNode = ((TreeNode<K, V>)currentNode).putTreeVal(this, tab, hash, key, value);
            } else {
                // 否则说明该节点是个普通的链表节点 向后遍历该链表
                for (int binCount = 0; ; ++binCount) {
                    if ((oldNode = currentNode.next) == null) {
                        // 如果遍历至最后都没有找到相同Key的Node 则新构建Node追加到链表最后
                        currentNode.next = newNode(hash, key, value, null);
                        if (binCount >= TREEIFY_THRESHOLD - 1) {
                            // 如果链表节点超过8个 则树化
                            treeifyBin(tab, hash);
                        }
                        break;
                    }
                    if (oldNode.hash == hash &&
                        ((k = oldNode.key) == key || (key != null && key.equals(k)))) {
                        break;
                    }
                    // 如果在遍历过程找到了该Key 记录下它 在下面统一设置新值
                    currentNode = oldNode;
                }
            }
            if (oldNode != null) {
                V oldValue = oldNode.value;
                if (!onlyIfAbsent || oldValue == null) {
                    // 为已存在的Node设置新的Value
                    oldNode.value = value;
                }
                // 在Node被访问后做一些事情 (做缓存时有用 , 访问后代表该数据是热点数据)
                afterNodeAccess(oldNode);
                return oldValue;
            }
        }
        ++modCount;
        if (++size > threshold) {
            // 容量超出了阈值(期望的容量) 扩容
            resize();
        }
        afterNodeInsertion(evict);
        return null;
    }

    /**
     * 原话翻译: 初始化或翻倍表大小, 如果为空，则根据字段阈值中保存的初始容量目标进行分配。 否则，因为我们使用的是2的幂，所以每个bin中的元素必须保持相同的索引，或者在新表中以2的幂偏移。
     * <p>
     * 当数组还没初始化时, 使用了threshold来临时存储表的容量. 当数组扩容时, 扩容是2倍, 那么数组容量依然是2的幂 新的索引等于 currentNode.hash & (newCapacity - 1)
     *
     * @return 扩容后的数组
     */
    final Node<K, V>[] resize() {
        Node<K, V>[] oldTable = table;
        int oldCapacity = (oldTable == null) ? 0 : oldTable.length;
        int oldThreshold = threshold;
        int newCapacity, newThreshold = 0;
        if (oldCapacity > 0) {
            // 如果数组已经初始化过了
            if (oldCapacity >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return oldTable;
            } else if ((newCapacity = oldCapacity << 1) < MAXIMUM_CAPACITY &&
                oldCapacity >= DEFAULT_INITIAL_CAPACITY) {
                // 数组容量范围 阈值翻倍
                newThreshold = oldThreshold << 1;
            }
        } else if (oldThreshold > 0) {
            // 如果数组尚未初始化 且(初始化)设置了threshold(initialCapacity)
            newCapacity = oldThreshold;
            // 在下面newThreshold == 0中恢复threshold的真正作用
        } else {
            // 没有设置initialCapacity
            newCapacity = DEFAULT_INITIAL_CAPACITY;
            newThreshold = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
        }
        if (newThreshold == 0) {
            // 初始化时 恢复threshold的真正作用
            float ft = (float)newCapacity * loadFactor;
            newThreshold = (newCapacity < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                (int)ft : Integer.MAX_VALUE);
        }
        threshold = newThreshold;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Node<K, V>[] newTable = (Node<K, V>[])new Node[newCapacity];
        // 替换原数组
        table = newTable;
        // 下面开始迁移
        if (oldTable != null) {
            for (int j = 0; j < oldCapacity; ++j) {
                Node<K, V> currentNode;
                if ((currentNode = oldTable[j]) != null) {
                    oldTable[j] = null;
                    if (currentNode.next == null) {
                        int newIndex = currentNode.hash & (newCapacity - 1);
                        newTable[newIndex] = currentNode;
                    } else if (currentNode instanceof TreeNode) {
                        // 红黑树的节点可能不再在新数组的bin上碰撞 它们可能索引不变 也可能是偏移原数组容量个位置 (扩容的那一侧)
                        // 所以红黑树这里用了一个split函数 拆分树
                        ((TreeNode<K, V>)currentNode).split(this, newTable, j, oldCapacity);
                    } else {
                        // 这里特别备注了 保留原顺序
                        // 虽然链表会拆分 一部分留在扩容前那一侧 一部分会偏移到扩容的那一侧 但是顺序不变
                        Node<K, V> lowHead = null, lowTail = null;
                        Node<K, V> highHead = null, highTail = null;
                        Node<K, V> next;
                        do {
                            next = currentNode.next;
                            if ((currentNode.hash & oldCapacity) == 0) {
                                // 1101101 & 100
                                // hash值 & 原数组容量等于0 说明这个hash值在原数组容量最高位的那个位置是0
                                // 扩容前这个位置 & 0 = 0 扩容后 这个位置将 & 1 = 0
                                // 所以本if块的节点不需要偏移到扩容的那一侧 称为low侧
                                if (lowTail == null) {
                                    // 记录当前节点 为头结点 也就是说放在数组bin中的那个节点是链表头结点
                                    lowHead = currentNode;
                                } else {
                                    // 按遍历顺序(原链表的顺序)往后追加
                                    lowTail.next = currentNode;
                                }
                                // 将最后一个节点标记为尾结点 尾插法
                                lowTail = currentNode;
                            } else {
                                // 拆分到扩容后的那一侧的节点也是相同的操作
                                // 头结点highHead 尾结点highTail
                                if (highTail == null) {
                                    highHead = currentNode;
                                } else {
                                    highTail.next = currentNode;
                                }
                                highTail = currentNode;
                            }
                        } while ((currentNode = next) != null);
                        // 下面就更清晰了 验证上面的 (currentNode.hash & oldCapacity) == 0 的用处
                        if (lowTail != null) {
                            lowTail.next = null;
                            // 扩容前的那一侧
                            newTable[j] = lowHead;
                        }
                        if (highTail != null) {
                            highTail.next = null;
                            // 扩容后(偏移)的那一侧
                            newTable[j + oldCapacity] = highHead;
                        }
                    }
                }
            }
        }
        return newTable;
    }

    /**
     * 树化 数组的每个位置都成为bin(箱子)
     */
    final void treeifyBin(Node<K, V>[] tab, int hash) {
        int tableLength, index;
        Node<K, V> oldNode;
        if (tab == null || (tableLength = tab.length) < MIN_TREEIFY_CAPACITY) {
            // 当数组容量 < 64时, 只扩容 不树化 (以此来降低碰撞概率)
            resize();
        } else if (
            (oldNode = tab[index = (tableLength - 1) & hash]) != null) {
            TreeNode<K, V> headNode = null, lastNode = null;
            do {
                // 将链表中的每个链表节点替换为树节点
                TreeNode<K, V> currentNode = replacementTreeNode(oldNode, null);
                if (lastNode == null) {
                    headNode = currentNode;
                } else {
                    currentNode.prev = lastNode;
                    lastNode.next = currentNode;
                }
                lastNode = currentNode;
            } while ((oldNode = oldNode.next) != null);
            if ((tab[index] = headNode) != null) {
                // 从根节点开始树化
                headNode.treeify(tab);
            }
        }
    }

    /**
     * 导入一个Map
     *
     * @param sourceMap 待导入的Map
     * @throws NullPointerException 空指针异常
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> sourceMap) {
        putMapEntries(sourceMap, true);
    }

    @Override
    public V remove(Object key) {
        Node<K, V> e;
        return (e = removeNode(hash(key), key, null, false, true)) == null ?
            null : e.value;
    }

    /**
     * {@link #remove(Object)}的具体实现
     *
     * @param hash       扰动后的Hash值
     * @param key        key
     * @param value      当{@param value} = true 时 要求value一致才能删除
     * @param matchValue matchValue
     * @param movable    设置为true 保证红黑树的根节点始终在数组bin上 (红黑树自平衡时 根节点可能会变化)
     * @return 被删除的node ; 如果不存在该node则返回null
     */
    final Node<K, V> removeNode(int hash, Object key, Object value,
                                boolean matchValue, boolean movable) {
        Node<K, V>[] tab;
        Node<K, V> currentNode;
        int n, index;
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (currentNode = tab[index = (n - 1) & hash]) != null) {
            // 索引处存在节点
            Node<K, V> targetNode = null, nextNode;
            K k;
            V v;
            if (currentNode.hash == hash &&
                ((k = currentNode.key) == key || (key != null && key.equals(k)))) {
                // key相等 说明是期望删除的Node
                targetNode = currentNode;
            } else if ((nextNode = currentNode.next) != null) {
                if (currentNode instanceof TreeNode) {
                    // 委托给红黑树去查找节点
                    targetNode = ((TreeNode<K, V>)currentNode).getTreeNode(hash, key);
                } else {
                    // 如果是普通链表节点 则遍历查找
                    do {
                        if (nextNode.hash == hash &&
                            ((k = nextNode.key) == key ||
                                (key != null && key.equals(k)))) {
                            targetNode = nextNode;
                            break;
                        }
                        currentNode = nextNode;
                    } while ((nextNode = nextNode.next) != null);
                }
            }
            if (targetNode != null && (!matchValue || (v = targetNode.value) == value ||
                (value != null && value.equals(v)))) {
                // 找到了目标节点
                if (targetNode instanceof TreeNode) {
                    // 红黑树节点委托给红黑树去删除
                    ((TreeNode<K, V>)targetNode).removeTreeNode(this, tab, movable);
                } else if (targetNode == currentNode) {
                    // 如果targetNode == currentNode 说明是链表第一个节点; 否则currentNode应当是targetNode的下一节点
                    tab[index] = targetNode.next;
                } else {
                    currentNode.next = targetNode.next;
                }
                ++modCount;
                --size;
                afterNodeRemoval(targetNode);
                return targetNode;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        Node<K, V>[] tab;
        modCount++;
        if ((tab = table) != null && size > 0) {
            size = 0;
            for (int i = 0; i < tab.length; ++i) { tab[i] = null; }
        }
    }

    @Override
    public boolean containsValue(Object value) {
        Node<K, V>[] tab;
        V v;
        if ((tab = table) != null && size > 0) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K, V> e = tab[i]; e != null; e = e.next) {
                    if ((v = e.value) == value ||
                        (value != null && value.equals(v))) { return true; }
                }
            }
        }
        return false;
    }

    /**
     * {@link KeySet}与{@link EntrySet}类似 不存放元素 而是构造了一个迭代器直接操作本类中的元素
     */
    @Override
    public Set<K> keySet() {
        Set<K> ks = keySet;
        if (ks == null) {
            ks = new KeySet();
            keySet = ks;
        }
        return ks;
    }

    /**
     * {@link Values}与{@link EntrySet}类似 不存放元素 而是构造了一个迭代器直接操作本类中的元素
     */
    @Override
    public Collection<V> values() {
        Collection<V> vs = values;
        if (vs == null) {
            vs = new Values();
            values = vs;
        }
        return vs;
    }

    /**
     * 类型{@link EntrySet} 不同于普通Set, 这个Set并不保存任何元素的副本, 而是构造了一个迭代器{@link EntryIterator}直接操作本类中的元素
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> es;
        return (es = entrySet) == null ? (entrySet = new EntrySet()) : es;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> e;
        return (e = getNode(hash(key), key)) == null ? defaultValue : e.value;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return putVal(hash(key), key, value, true, true);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return removeNode(hash(key), key, value, true, true) != null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K, V> e;
        V v;
        if ((e = getNode(hash(key), key)) != null &&
            ((v = e.value) == oldValue || (v != null && v.equals(oldValue)))) {
            e.value = newValue;
            afterNodeAccess(e);
            return true;
        }
        return false;
    }

    // Overrides of JDK8 Map extension methods

    @Override
    public V replace(K key, V value) {
        Node<K, V> e;
        if ((e = getNode(hash(key), key)) != null) {
            V oldValue = e.value;
            e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
        return null;
    }

    @Override
    public V computeIfAbsent(K key,
                             Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null) { throw new NullPointerException(); }
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0) { n = (tab = resize()).length; }
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode) { old = (t = (TreeNode<K, V>)first).getTreeNode(hash, key); } else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
            V oldValue;
            if (old != null && (oldValue = old.value) != null) {
                afterNodeAccess(old);
                return oldValue;
            }
        }
        V v = mappingFunction.apply(key);
        if (v == null) {
            return null;
        } else if (old != null) {
            old.value = v;
            afterNodeAccess(old);
            return v;
        } else if (t != null) { t.putTreeVal(this, tab, hash, key, v); } else {
            tab[i] = newNode(hash, key, v, first);
            if (binCount >= TREEIFY_THRESHOLD - 1) { treeifyBin(tab, hash); }
        }
        ++modCount;
        ++size;
        afterNodeInsertion(true);
        return v;
    }

    @Override
    public V computeIfPresent(K key,
                              BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null) { throw new NullPointerException(); }
        Node<K, V> e;
        V oldValue;
        int hash = hash(key);
        if ((e = getNode(hash, key)) != null &&
            (oldValue = e.value) != null) {
            V v = remappingFunction.apply(key, oldValue);
            if (v != null) {
                e.value = v;
                afterNodeAccess(e);
                return v;
            } else { removeNode(hash, key, null, false, true); }
        }
        return null;
    }

    @Override
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null) { throw new NullPointerException(); }
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0) { n = (tab = resize()).length; }
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode) { old = (t = (TreeNode<K, V>)first).getTreeNode(hash, key); } else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        V oldValue = (old == null) ? null : old.value;
        V v = remappingFunction.apply(key, oldValue);
        if (old != null) {
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            } else { removeNode(hash, key, null, false, true); }
        } else if (v != null) {
            if (t != null) { t.putTreeVal(this, tab, hash, key, v); } else {
                tab[i] = newNode(hash, key, v, first);
                if (binCount >= TREEIFY_THRESHOLD - 1) { treeifyBin(tab, hash); }
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        return v;
    }

    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null) { throw new NullPointerException(); }
        if (remappingFunction == null) { throw new NullPointerException(); }
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0) { n = (tab = resize()).length; }
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode) { old = (t = (TreeNode<K, V>)first).getTreeNode(hash, key); } else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        if (old != null) {
            V v;
            if (old.value != null) { v = remappingFunction.apply(old.value, value); } else { v = value; }
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            } else { removeNode(hash, key, null, false, true); }
            return v;
        }
        if (value != null) {
            if (t != null) { t.putTreeVal(this, tab, hash, key, value); } else {
                tab[i] = newNode(hash, key, value, first);
                if (binCount >= TREEIFY_THRESHOLD - 1) { treeifyBin(tab, hash); }
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        return value;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Node<K, V>[] tab;
        if (action == null) { throw new NullPointerException(); }
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K, V> e = tab[i]; e != null; e = e.next) { action.accept(e.key, e.value); }
            }
            if (modCount != mc) { throw new ConcurrentModificationException(); }
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Node<K, V>[] tab;
        if (function == null) { throw new NullPointerException(); }
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K, V> e = tab[i]; e != null; e = e.next) {
                    e.value = function.apply(e.key, e.value);
                }
            }
            if (modCount != mc) { throw new ConcurrentModificationException(); }
        }
    }

    /**
     * Returns a shallow copy of this <tt>HashMap</tt> instance: the keys and values themselves are not cloned.
     *
     * @return a shallow copy of this map
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        HashMap<K, V> result;
        try {
            result = (HashMap<K, V>)super.clone();
        } catch (CloneNotSupportedException e) {
            // this shouldn't happen, since we are Cloneable
            throw new InternalError(e);
        }
        result.reinitialize();
        result.putMapEntries(this, false);
        return result;
    }

    // These methods are also used when serializing HashSets
    final float loadFactor() { return loadFactor; }

    final int capacity() {
        return (table != null) ? table.length :
            (threshold > 0) ? threshold :
                DEFAULT_INITIAL_CAPACITY;
    }

    /**
     * Save the state of the <tt>HashMap</tt> instance to a stream (i.e., serialize it).
     *
     * @serialData The <i>capacity</i> of the HashMap (the length of the bucket array) is emitted (int), followed by
     * the
     * <i>size</i> (an int, the number of key-value
     * mappings), followed by the key (Object) and value (Object) for each key-value mapping.  The key-value mappings
     * are emitted in no particular order.
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws IOException {
        int buckets = capacity();
        // Write out the threshold, loadfactor, and any hidden stuff
        s.defaultWriteObject();
        s.writeInt(buckets);
        s.writeInt(size);
        internalWriteEntries(s);
    }

    /* ------------------------------------------------------------ */
    // Cloning and serialization

    /**
     * Reconstitutes this map from a stream (that is, deserializes it).
     *
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object could not be found
     * @throws IOException            if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws IOException, ClassNotFoundException {
        // Read in the threshold (ignored), loadfactor, and any hidden stuff
        s.defaultReadObject();
        reinitialize();
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new InvalidObjectException("Illegal load factor: " +
                loadFactor);
        }
        s.readInt();                // Read and ignore number of buckets
        int mappings = s.readInt(); // Read number of mappings (size)
        if (mappings < 0) {
            throw new InvalidObjectException("Illegal mappings count: " +
                mappings);
        } else if (mappings > 0) { // (if zero, use defaults)
            // Size the table using given load factor only if within
            // range of 0.25...4.0
            float lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);
            float fc = (float)mappings / lf + 1.0f;
            int cap = ((fc < DEFAULT_INITIAL_CAPACITY) ?
                DEFAULT_INITIAL_CAPACITY :
                (fc >= MAXIMUM_CAPACITY) ?
                    MAXIMUM_CAPACITY :
                    tableSizeFor((int)fc));
            float ft = (float)cap * lf;
            threshold = ((cap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ?
                (int)ft : Integer.MAX_VALUE);

            // Check Map.Entry[].class since it's the nearest public type to
            // what we're actually creating.
            SharedSecrets.getJavaOISAccess().checkArray(s, Entry[].class, cap);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Node<K, V>[] tab = (Node<K, V>[])new Node[cap];
            table = tab;

            // Read the keys and values, and put the mappings in the HashMap
            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                K key = (K)s.readObject();
                @SuppressWarnings("unchecked")
                V value = (V)s.readObject();
                putVal(hash(key), key, value, false, false);
            }
        }
    }

    // Create a regular (non-tree) node
    Node<K, V> newNode(int hash, K key, V value, Node<K, V> next) {
        return new Node<>(hash, key, value, next);
    }

    // For conversion from TreeNodes to plain nodes
    Node<K, V> replacementNode(Node<K, V> p, Node<K, V> next) {
        return new Node<>(p.hash, p.key, p.value, next);
    }

    // Create a tree bin node
    TreeNode<K, V> newTreeNode(int hash, K key, V value, Node<K, V> next) {
        return new TreeNode<>(hash, key, value, next);
    }

    // For treeifyBin
    TreeNode<K, V> replacementTreeNode(Node<K, V> p, Node<K, V> next) {
        return new TreeNode<>(p.hash, p.key, p.value, next);
    }

    /* ------------------------------------------------------------ */
    // iterators

    /**
     * Reset to initial default state.  Called by clone and readObject.
     */
    void reinitialize() {
        table = null;
        entrySet = null;
        keySet = null;
        values = null;
        modCount = 0;
        threshold = 0;
        size = 0;
    }

    // Callbacks to allow LinkedHashMap post-actions
    void afterNodeAccess(Node<K, V> p) { }

    void afterNodeInsertion(boolean evict) { }

    void afterNodeRemoval(Node<K, V> p) { }

    /* ------------------------------------------------------------ */
    // spliterators

    // Called only from writeObject, to ensure compatible ordering.
    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        Node<K, V>[] tab;
        if (size > 0 && (tab = table) != null) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K, V> e = tab[i]; e != null; e = e.next) {
                    s.writeObject(e.key);
                    s.writeObject(e.value);
                }
            }
        }
    }

    /**
     * 基本的元素节点
     *
     * @param <K> key类型
     * @param <V> value类型
     */
    static class Node<K, V> implements Entry<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        @Override
        public final K getKey() { return key; }

        @Override
        public final V getValue() { return value; }

        @Override
        public final String toString() { return key + "=" + value; }

        @Override
        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        @Override
        public final V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }

        @Override
        public final boolean equals(Object o) {
            if (o == this) { return true; }
            if (o instanceof Map.Entry) {
                Entry<?, ?> e = (Entry<?, ?>)o;
                if (Objects.equals(key, e.getKey()) &&
                    Objects.equals(value, e.getValue())) { return true; }
            }
            return false;
        }
    }

    static class HashMapSpliterator<K, V> {
        final HashMap<K, V> map;
        Node<K, V> current;          // current node
        int index;                  // current index, modified on advance/split
        int fence;                  // one past last index
        int est;                    // size estimate
        int expectedModCount;       // for comodification checks

        HashMapSpliterator(HashMap<K, V> m, int origin,
                           int fence, int est,
                           int expectedModCount) {
            this.map = m;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        final int getFence() { // initialize fence and size on first use
            int hi;
            if ((hi = fence) < 0) {
                HashMap<K, V> m = map;
                est = m.size;
                expectedModCount = m.modCount;
                Node<K, V>[] tab = m.table;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            return hi;
        }

        public final long estimateSize() {
            getFence(); // force init
            return (long)est;
        }
    }

    static final class KeySpliterator<K, V>
        extends HashMapSpliterator<K, V>
        implements Spliterator<K> {
        KeySpliterator(HashMap<K, V> m, int origin, int fence, int est,
                       int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        @Override
        public KeySpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new KeySpliterator<>(map, lo, index = mid, est >>>= 1,
                    expectedModCount);
        }

        @Override
        public void forEachRemaining(Consumer<? super K> action) {
            int i, hi, mc;
            if (action == null) { throw new NullPointerException(); }
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else { mc = expectedModCount; }
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null) { p = tab[i++]; } else {
                        action.accept(p.key);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }

        @Override
        public boolean tryAdvance(Consumer<? super K> action) {
            int hi;
            if (action == null) { throw new NullPointerException(); }
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null) { current = tab[index++]; } else {
                        K k = current.key;
                        current = current.next;
                        action.accept(k);
                        if (map.modCount != expectedModCount) { throw new ConcurrentModificationException(); }
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    /* ------------------------------------------------------------ */
    // LinkedHashMap support


    /*
     * The following package-protected methods are designed to be
     * overridden by LinkedHashMap, but not by any other subclass.
     * Nearly all other internal methods are also package-protected
     * but are declared final, so can be used by LinkedHashMap, view
     * classes, and HashSet.
     */

    static final class ValueSpliterator<K, V>
        extends HashMapSpliterator<K, V>
        implements Spliterator<V> {
        ValueSpliterator(HashMap<K, V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        @Override
        public ValueSpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new ValueSpliterator<>(map, lo, index = mid, est >>>= 1,
                    expectedModCount);
        }

        @Override
        public void forEachRemaining(Consumer<? super V> action) {
            int i, hi, mc;
            if (action == null) { throw new NullPointerException(); }
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else { mc = expectedModCount; }
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null) { p = tab[i++]; } else {
                        action.accept(p.value);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }

        @Override
        public boolean tryAdvance(Consumer<? super V> action) {
            int hi;
            if (action == null) { throw new NullPointerException(); }
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null) { current = tab[index++]; } else {
                        V v = current.value;
                        current = current.next;
                        action.accept(v);
                        if (map.modCount != expectedModCount) { throw new ConcurrentModificationException(); }
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0);
        }
    }

    static final class EntrySpliterator<K, V>
        extends HashMapSpliterator<K, V>
        implements Spliterator<Entry<K, V>> {
        EntrySpliterator(HashMap<K, V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        @Override
        public EntrySpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new EntrySpliterator<>(map, lo, index = mid, est >>>= 1,
                    expectedModCount);
        }

        @Override
        public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
            int i, hi, mc;
            if (action == null) { throw new NullPointerException(); }
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else { mc = expectedModCount; }
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null) { p = tab[i++]; } else {
                        action.accept(p);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }

        @Override
        public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
            int hi;
            if (action == null) { throw new NullPointerException(); }
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null) { current = tab[index++]; } else {
                        Node<K, V> e = current;
                        current = current.next;
                        action.accept(e);
                        if (map.modCount != expectedModCount) { throw new ConcurrentModificationException(); }
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    /**
     * 红黑树节点
     *
     * @param <K> key类型
     * @param <V> value类型
     */
    static final class TreeNode<K, V> extends HashMapAddition.Entry<K, V> {
        TreeNode<K, V> parent;  // red-black tree links
        TreeNode<K, V> left;
        TreeNode<K, V> right;
        TreeNode<K, V> prev;    // needed to unlink next upon deletion
        boolean red;

        TreeNode(int hash, K key, V val, Node<K, V> next) {
            super(hash, key, val, next);
        }

        /**
         * Ensures that the given root is the first node of its bin.
         */
        static <K, V> void moveRootToFront(Node<K, V>[] tab, TreeNode<K, V> root) {
            int n;
            if (root != null && tab != null && (n = tab.length) > 0) {
                int index = (n - 1) & root.hash;
                TreeNode<K, V> first = (TreeNode<K, V>)tab[index];
                if (root != first) {
                    Node<K, V> rn;
                    tab[index] = root;
                    TreeNode<K, V> rp = root.prev;
                    if ((rn = root.next) != null) { ((TreeNode<K, V>)rn).prev = rp; }
                    if (rp != null) { rp.next = rn; }
                    if (first != null) { first.prev = root; }
                    root.next = first;
                    root.prev = null;
                }
                assert checkInvariants(root);
            }
        }

        /**
         * Tie-breaking utility for ordering insertions when equal hashCodes and non-comparable. We don't require a
         * total order, just a consistent insertion rule to maintain equivalence across rebalancings. Tie-breaking
         * further than necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                (d = a.getClass().getName().
                    compareTo(b.getClass().getName())) == 0) {
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                    -1 : 1);
            }
            return d;
        }

        static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> root,
                                                TreeNode<K, V> p) {
            TreeNode<K, V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null) { rl.parent = p; }
                if ((pp = r.parent = p.parent) == null) { (root = r).red = false; } else if (pp.left == p) {
                    pp.left = r;
                } else { pp.right = r; }
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> root,
                                                 TreeNode<K, V> p) {
            TreeNode<K, V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null) { lr.parent = p; }
                if ((pp = l.parent = p.parent) == null) { (root = l).red = false; } else if (pp.right == p) {
                    pp.right = l;
                } else { pp.left = l; }
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K, V> TreeNode<K, V> balanceInsertion(TreeNode<K, V> root,
                                                      TreeNode<K, V> x) {
            x.red = true;
            for (TreeNode<K, V> xp, xpp, xppl, xppr; ; ) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (!xp.red || (xpp = xp.parent) == null) { return root; }
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                } else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K, V> TreeNode<K, V> balanceDeletion(TreeNode<K, V> root,
                                                     TreeNode<K, V> x) {
            for (TreeNode<K, V> xp, xpl, xpr; ; ) {
                if (x == null || x == root) { return root; } else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (x.red) {
                    x.red = false;
                    return root;
                } else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null) { x = xp; } else {
                        TreeNode<K, V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                            (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        } else {
                            if (sr == null || !sr.red) {
                                if (sl != null) { sl.red = false; }
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                    null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null) { sr.red = false; }
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                } else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null) { x = xp; } else {
                        TreeNode<K, V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                            (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        } else {
                            if (sl == null || !sl.red) {
                                if (sr != null) { sr.red = false; }
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                    null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null) { sl.red = false; }
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * Recursive invariant check
         */
        static <K, V> boolean checkInvariants(TreeNode<K, V> t) {
            TreeNode<K, V> tp = t.parent, tl = t.left, tr = t.right,
                tb = t.prev, tn = (TreeNode<K, V>)t.next;
            if (tb != null && tb.next != t) { return false; }
            if (tn != null && tn.prev != t) { return false; }
            if (tp != null && t != tp.left && t != tp.right) { return false; }
            if (tl != null && (tl.parent != t || tl.hash > t.hash)) { return false; }
            if (tr != null && (tr.parent != t || tr.hash < t.hash)) { return false; }
            if (t.red && tl != null && tl.red && tr != null && tr.red) { return false; }
            if (tl != null && !checkInvariants(tl)) { return false; }
            if (tr != null && !checkInvariants(tr)) { return false; }
            return true;
        }

        /**
         * Returns root of tree containing this node.
         */
        final TreeNode<K, V> root() {
            for (TreeNode<K, V> r = this, p; ; ) {
                if ((p = r.parent) == null) { return r; }
                r = p;
            }
        }

        /**
         * Finds the node starting at root p with the given hash and key. The kc argument caches comparableClassFor(key)
         * upon first use comparing keys.
         */
        final TreeNode<K, V> find(int h, Object k, Class<?> kc) {
            TreeNode<K, V> p = this;
            do {
                int ph, dir;
                K pk;
                TreeNode<K, V> pl = p.left, pr = p.right, q;
                if ((ph = p.hash) > h) { p = pl; } else if (ph < h) { p = pr; } else if ((pk = p.key) == k || (k != null
                    && k.equals(pk))) {
                    return p;
                } else if (pl == null) {
                    p = pr;
                } else if (pr == null) { p = pl; } else if ((kc != null ||
                    (kc = comparableClassFor(k)) != null) &&
                    (dir = compareComparables(kc, k, pk)) != 0) { p = (dir < 0) ? pl : pr; } else if ((q = pr.find(h, k,
                    kc)) != null) { return q; } else { p = pl; }
            } while (p != null);
            return null;
        }

        /**
         * Calls find for root node.
         */
        final TreeNode<K, V> getTreeNode(int h, Object k) {
            return ((parent != null) ? root() : this).find(h, k, null);
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR

        /**
         * Forms tree of the nodes linked from this node.
         */
        final void treeify(Node<K, V>[] tab) {
            TreeNode<K, V> root = null;
            for (TreeNode<K, V> x = this, next; x != null; x = next) {
                next = (TreeNode<K, V>)x.next;
                x.left = x.right = null;
                if (root == null) {
                    x.parent = null;
                    x.red = false;
                    root = x;
                } else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K, V> p = root; ; ) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h) { dir = -1; } else if (ph < h) { dir = 1; } else if ((kc == null &&
                            (kc = comparableClassFor(k)) == null) ||
                            (dir = compareComparables(kc, k, pk)) == 0) { dir = tieBreakOrder(k, pk); }

                        TreeNode<K, V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0) { xp.left = x; } else { xp.right = x; }
                            root = balanceInsertion(root, x);
                            break;
                        }
                    }
                }
            }
            moveRootToFront(tab, root);
        }

        /**
         * Returns a list of non-TreeNodes replacing those linked from this node.
         */
        final Node<K, V> untreeify(HashMap<K, V> map) {
            Node<K, V> hd = null, tl = null;
            for (Node<K, V> q = this; q != null; q = q.next) {
                Node<K, V> p = map.replacementNode(q, null);
                if (tl == null) { hd = p; } else { tl.next = p; }
                tl = p;
            }
            return hd;
        }

        /**
         * Tree version of putVal.
         */
        final TreeNode<K, V> putTreeVal(HashMap<K, V> map, Node<K, V>[] tab,
                                        int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            TreeNode<K, V> root = (parent != null) ? root() : this;
            for (TreeNode<K, V> p = root; ; ) {
                int dir, ph;
                K pk;
                if ((ph = p.hash) > h) { dir = -1; } else if (ph < h) { dir = 1; } else if ((pk = p.key) == k || (
                    k != null && k.equals(pk))) {
                    return p;
                } else if ((kc == null &&
                    (kc = comparableClassFor(k)) == null) ||
                    (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K, V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                            (q = ch.find(h, k, kc)) != null) ||
                            ((ch = p.right) != null &&
                                (q = ch.find(h, k, kc)) != null)) { return q; }
                    }
                    dir = tieBreakOrder(k, pk);
                }

                TreeNode<K, V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    Node<K, V> xpn = xp.next;
                    TreeNode<K, V> x = map.newTreeNode(h, k, v, xpn);
                    if (dir <= 0) { xp.left = x; } else { xp.right = x; }
                    xp.next = x;
                    x.parent = x.prev = xp;
                    if (xpn != null) { ((TreeNode<K, V>)xpn).prev = x; }
                    moveRootToFront(tab, balanceInsertion(root, x));
                    return null;
                }
            }
        }

        /**
         * Removes the given node, that must be present before this call. This is messier than typical red-black
         * deletion code because we cannot swap the contents of an interior node with a leaf successor that is pinned by
         * "next" pointers that are accessible independently during traversal. So instead we swap the tree linkages. If
         * the current tree appears to have too few nodes, the bin is converted back to a plain bin. (The test triggers
         * somewhere between 2 and 6 nodes, depending on tree structure).
         */
        final void removeTreeNode(HashMap<K, V> map, Node<K, V>[] tab,
                                  boolean movable) {
            int n;
            if (tab == null || (n = tab.length) == 0) { return; }
            int index = (n - 1) & hash;
            TreeNode<K, V> first = (TreeNode<K, V>)tab[index], root = first, rl;
            TreeNode<K, V> succ = (TreeNode<K, V>)next, pred = prev;
            if (pred == null) { tab[index] = first = succ; } else { pred.next = succ; }
            if (succ != null) { succ.prev = pred; }
            if (first == null) { return; }
            if (root.parent != null) { root = root.root(); }
            if (root == null
                || (movable
                && (root.right == null
                || (rl = root.left) == null
                || rl.left == null))) {
                tab[index] = first.untreeify(map);  // too small
                return;
            }
            TreeNode<K, V> p = this, pl = left, pr = right, replacement;
            if (pl != null && pr != null) {
                TreeNode<K, V> s = pr, sl;
                while ((sl = s.left) != null) // find successor
                { s = sl; }
                boolean c = s.red;
                s.red = p.red;
                p.red = c; // swap colors
                TreeNode<K, V> sr = s.right;
                TreeNode<K, V> pp = p.parent;
                if (s == pr) { // p was s's direct parent
                    p.parent = s;
                    s.right = p;
                } else {
                    TreeNode<K, V> sp = s.parent;
                    if ((p.parent = sp) != null) {
                        if (s == sp.left) { sp.left = p; } else { sp.right = p; }
                    }
                    if ((s.right = pr) != null) { pr.parent = s; }
                }
                p.left = null;
                if ((p.right = sr) != null) { sr.parent = p; }
                if ((s.left = pl) != null) { pl.parent = s; }
                if ((s.parent = pp) == null) { root = s; } else if (p == pp.left) { pp.left = s; } else {
                    pp.right = s;
                }
                if (sr != null) { replacement = sr; } else { replacement = p; }
            } else if (pl != null) { replacement = pl; } else if (pr != null) { replacement = pr; } else {
                replacement = p;
            }
            if (replacement != p) {
                TreeNode<K, V> pp = replacement.parent = p.parent;
                if (pp == null) { root = replacement; } else if (p == pp.left) { pp.left = replacement; } else {
                    pp.right = replacement;
                }
                p.left = p.right = p.parent = null;
            }

            TreeNode<K, V> r = p.red ? root : balanceDeletion(root, replacement);

            if (replacement == p) {  // detach
                TreeNode<K, V> pp = p.parent;
                p.parent = null;
                if (pp != null) {
                    if (p == pp.left) { pp.left = null; } else if (p == pp.right) { pp.right = null; }
                }
            }
            if (movable) { moveRootToFront(tab, r); }
        }

        /**
         *
         * 将树箱中的节点拆分为较高和较低的树箱，如果拆分后的树太小，则取消树化
         *
         * @param map   the map
         * @param newTable   新数组
         * @param index 需要拆分的树(本节点)在原数组中的位置
         * @param oldTableCapacity   原数组大小
         */
        final void split(HashMap<K, V> map, Node<K, V>[] newTable, int index, int oldTableCapacity) {
            TreeNode<K, V> thisNode = this;
            // 先当成链表拆分 并且也会保留顺序
            TreeNode<K, V> lowHead = null, lowTail = null;
            TreeNode<K, V> highHead = null, highTail = null;
            int lc = 0, hc = 0;
            for (TreeNode<K, V> currentNode = thisNode, next; currentNode != null; currentNode = next) {
                next = (TreeNode<K, V>)currentNode.next;
                currentNode.next = null;
                if ((currentNode.hash & oldTableCapacity) == 0) {
                    if ((currentNode.prev = lowTail) == null) {
                        lowHead = currentNode;
                    } else {
                        lowTail.next = currentNode;
                    }
                    lowTail = currentNode;
                    ++lc;
                } else {
                    if ((currentNode.prev = highTail) == null) { highHead = currentNode; } else { highTail.next = currentNode; }
                    highTail = currentNode;
                    ++hc;
                }
            }

            if (lowHead != null) {
                if (lc <= UNTREEIFY_THRESHOLD) {
                    // 节点数不足6个 反树化
                    // 过程十分简单 因为所有的树节点本身也符合链表节点的特性 所以只需将TreeNode类型转变成怕普通Node即可
                    newTable[index] = lowHead.untreeify(map);
                } else {
                    newTable[index] = lowHead;
                    if (highHead != null) {
                        // 从链表头部重新建树
                        lowHead.treeify(newTable);
                    }
                    // 如果 highHead==null 说明没有拆分 不用建树
                }
            }
            // 扩容的那一侧的树也是一样的操作
            if (highHead != null) {
                if (hc <= UNTREEIFY_THRESHOLD) {
                    newTable[index + oldTableCapacity] = highHead.untreeify(map);
                } else {
                    newTable[index + oldTableCapacity] = highHead;
                    if (lowHead != null) {
                        highHead.treeify(newTable);
                    }
                }
            }
        }
    }

    final class KeySet extends AbstractSet<K> {
        @Override
        public final int size() { return size; }

        @Override
        public final void clear() { HashMap.this.clear(); }

        @Override
        public final Iterator<K> iterator() { return new KeyIterator(); }

        @Override
        public final boolean contains(Object o) { return containsKey(o); }

        @Override
        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }

        @Override
        public final Spliterator<K> spliterator() {
            return new KeySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        @Override
        public final void forEach(Consumer<? super K> action) {
            Node<K, V>[] tab;
            if (action == null) { throw new NullPointerException(); }
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K, V> e = tab[i]; e != null; e = e.next) { action.accept(e.key); }
                }
                if (modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }
    }

    final class Values extends AbstractCollection<V> {
        @Override
        public final int size() { return size; }

        @Override
        public final void clear() { HashMap.this.clear(); }

        @Override
        public final Iterator<V> iterator() { return new ValueIterator(); }

        @Override
        public final boolean contains(Object o) { return containsValue(o); }

        @Override
        public final Spliterator<V> spliterator() {
            return new ValueSpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        @Override
        public final void forEach(Consumer<? super V> action) {
            Node<K, V>[] tab;
            if (action == null) { throw new NullPointerException(); }
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K, V> e = tab[i]; e != null; e = e.next) { action.accept(e.value); }
                }
                if (modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public final int size() { return size; }

        @Override
        public final void clear() { HashMap.this.clear(); }

        @Override
        public final Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) { return false; }
            Entry<?, ?> e = (Entry<?, ?>)o;
            Object key = e.getKey();
            Node<K, V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }

        @Override
        public final boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Entry<?, ?> e = (Entry<?, ?>)o;
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }

        @Override
        public final Spliterator<Entry<K, V>> spliterator() {
            return new EntrySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        @Override
        public final void forEach(Consumer<? super Entry<K, V>> action) {
            Node<K, V>[] tab;
            if (action == null) { throw new NullPointerException(); }
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K, V> e = tab[i]; e != null; e = e.next) { action.accept(e); }
                }
                if (modCount != mc) { throw new ConcurrentModificationException(); }
            }
        }
    }

    abstract class HashIterator {
        Node<K, V> next;        // next entry to return
        Node<K, V> current;     // current entry
        int expectedModCount;  // for fast-fail
        int index;             // current slot

        HashIterator() {
            expectedModCount = modCount;
            Node<K, V>[] t = table;
            current = next = null;
            index = 0;
            if (t != null && size > 0) { // advance to first entry
                do {} while (index < t.length && (next = t[index++]) == null);
            }
        }

        public final boolean hasNext() {
            return next != null;
        }

        final Node<K, V> nextNode() {
            Node<K, V>[] t;
            Node<K, V> e = next;
            if (modCount != expectedModCount) { throw new ConcurrentModificationException(); }
            if (e == null) { throw new NoSuchElementException(); }
            if ((next = (current = e).next) == null && (t = table) != null) {
                do {} while (index < t.length && (next = t[index++]) == null);
            }
            return e;
        }

        public final void remove() {
            Node<K, V> p = current;
            if (p == null) { throw new IllegalStateException(); }
            if (modCount != expectedModCount) { throw new ConcurrentModificationException(); }
            current = null;
            K key = p.key;
            removeNode(hash(key), key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class KeyIterator extends HashIterator
        implements Iterator<K> {
        @Override
        public final K next() { return nextNode().key; }
    }

    final class ValueIterator extends HashIterator
        implements Iterator<V> {
        @Override
        public final V next() { return nextNode().value; }
    }

    /* ------------------------------------------------------------ */
    // Tree bins

    final class EntryIterator extends HashIterator
        implements Iterator<Entry<K, V>> {
        @Override
        public final Entry<K, V> next() { return nextNode(); }
    }

}
