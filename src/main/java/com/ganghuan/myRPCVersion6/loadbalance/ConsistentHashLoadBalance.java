package com.ganghuan.myRPCVersion6.loadbalance;

import com.ganghuan.myRPCVersion6.common.RPCRequest;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.TreeMap;

/**
 * 一致性哈希：
 * 相同请求键尽量落到同一实例，适合带本地缓存或会话粘性的场景。
 */
public class ConsistentHashLoadBalance implements LoadBalance {
    private static final int VIRTUAL_NODE_COUNT = 32;

    @Override
    public InetSocketAddress select(List<InetSocketAddress> addressList, RPCRequest request) {
        TreeMap<Integer, InetSocketAddress> hashRing = new TreeMap<>();
        for (InetSocketAddress address : addressList) {
            String key = address.getHostName() + ":" + address.getPort();
            for (int i = 0; i < VIRTUAL_NODE_COUNT; i++) {
                hashRing.put(hash(key + "#" + i), address);
            }
        }

        int requestHash = hash(buildRequestKey(request));
        Integer node = hashRing.ceilingKey(requestHash);
        if (node == null) {
            node = hashRing.firstKey();
        }
        return hashRing.get(node);
    }

    @Override
    public String name() {
        return "hash";
    }

    private String buildRequestKey(RPCRequest request) {
        if (request == null) {
            return "default";
        }
        int paramsHash = request.getParams() == null ? 0 : java.util.Arrays.deepHashCode(request.getParams());
        return request.getInterfaceName() + "#" + request.getMethodName() + "#" + paramsHash;
    }

    private int hash(String key) {
        int h = key.hashCode();
        return h == Integer.MIN_VALUE ? 0 : Math.abs(h);
    }
}
