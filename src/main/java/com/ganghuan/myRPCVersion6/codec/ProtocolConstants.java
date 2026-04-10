package com.ganghuan.myRPCVersion6.codec;

/**
 * RPC 自定义协议常量。
 *
 * 协议头结构：
 * 4B magic + 1B version + 2B messageType + 2B serializerType + 4B bodyLength
 */
public final class ProtocolConstants {
    public static final int MAGIC = 0x6D797270;
    public static final byte VERSION = 1;
    public static final int HEADER_LENGTH = 4 + 1 + 2 + 2 + 4;
    public static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    private ProtocolConstants() {
    }
}
