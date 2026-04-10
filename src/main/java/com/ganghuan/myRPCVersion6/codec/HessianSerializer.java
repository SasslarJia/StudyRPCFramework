package com.ganghuan.myRPCVersion6.codec;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian2 二进制序列化：
 * 兼顾对象图支持与较低的字节体积，适合 Java-to-Java 的通用对象传输。
 */
public class HessianSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Hessian2Output output = new Hessian2Output(bos);
            output.writeObject(obj);
            output.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Hessian序列化失败", e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, int messageType) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            Hessian2Input input = new Hessian2Input(bis);
            return input.readObject();
        } catch (IOException e) {
            throw new RuntimeException("Hessian反序列化失败", e);
        }
    }

    @Override
    public int getType() {
        return 3;
    }

    @Override
    public String getName() {
        return "hessian";
    }
}
