package com.ganghuan.myRPCVersion6.loadbalance;

import com.ganghuan.myRPCVersion6.common.RPCRequest;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 给服务器地址列表，根据不同的负载均衡策略选择一个
 */
public interface LoadBalance {
    InetSocketAddress select(List<InetSocketAddress> addressList, RPCRequest request);

    String name();
}
