package com.ganghuan.myRPCVersion6.codec;

import com.ganghuan.myRPCVersion6.common.RPCRequest;
import com.ganghuan.myRPCVersion6.common.RPCResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Protobuf 序列化：
 * 当前项目仍是通用 RPCRequest/RPCResponse 模型，因此这里使用 protobuf 编码消息 envelope，
 * 参数与 data 采用“类型名 + 单值 payload”的方式保存，便于在不改整体调用模型的前提下接入 protobuf。
 */
public class ProtobufSerializer implements Serializer {
    private static final int REQUEST_ID_FIELD = 1;
    private static final int RETRYABLE_FIELD = 2;
    private static final int INTERFACE_NAME_FIELD = 3;
    private static final int METHOD_NAME_FIELD = 4;
    private static final int PARAM_TYPE_FIELD = 5;
    private static final int PARAM_PAYLOAD_FIELD = 6;

    private static final int RESPONSE_REQUEST_ID_FIELD = 1;
    private static final int RESPONSE_CODE_FIELD = 2;
    private static final int RESPONSE_MESSAGE_FIELD = 3;
    private static final int RESPONSE_DATA_TYPE_FIELD = 4;
    private static final int RESPONSE_DATA_PAYLOAD_FIELD = 5;

    @Override
    public byte[] serialize(Object obj) {
        if (obj instanceof RPCRequest) {
            return serializeRequest((RPCRequest) obj);
        }
        if (obj instanceof RPCResponse) {
            return serializeResponse((RPCResponse) obj);
        }
        throw new RuntimeException("protobuf暂不支持此类型: " + obj.getClass().getName());
    }

    @Override
    public Object deserialize(byte[] bytes, int messageType) {
        switch (messageType) {
            case 0:
                return deserializeRequest(bytes);
            case 1:
                return deserializeResponse(bytes);
            default:
                throw new RuntimeException("protobuf暂不支持此消息类型: " + messageType);
        }
    }

    @Override
    public int getType() {
        return 2;
    }

    @Override
    public String getName() {
        return "protobuf";
    }

    private byte[] serializeRequest(RPCRequest request) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CodedOutputStream output = CodedOutputStream.newInstance(bos);

            writeString(output, REQUEST_ID_FIELD, request.getRequestId());
            output.writeBool(RETRYABLE_FIELD, request.isRetryable());
            writeString(output, INTERFACE_NAME_FIELD, request.getInterfaceName());
            writeString(output, METHOD_NAME_FIELD, request.getMethodName());

            if (request.getParamsTypes() != null) {
                for (Class<?> paramType : request.getParamsTypes()) {
                    writeString(output, PARAM_TYPE_FIELD, SerializerSupport.getClassName(paramType));
                }
            }
            if (request.getParams() != null) {
                for (Object param : request.getParams()) {
                    output.writeBytes(PARAM_PAYLOAD_FIELD, ByteString.copyFrom(SerializerSupport.encodeValue(param)));
                }
            }
            output.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("protobuf序列化request失败", e);
        }
    }

    private RPCRequest deserializeRequest(byte[] bytes) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            RPCRequest request = new RPCRequest();
            List<String> paramTypeNames = new ArrayList<>();
            List<byte[]> paramPayloads = new ArrayList<>();

            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0) {
                    break;
                }
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                switch (fieldNumber) {
                    case REQUEST_ID_FIELD:
                        request.setRequestId(input.readString());
                        break;
                    case RETRYABLE_FIELD:
                        request.setRetryable(input.readBool());
                        break;
                    case INTERFACE_NAME_FIELD:
                        request.setInterfaceName(input.readString());
                        break;
                    case METHOD_NAME_FIELD:
                        request.setMethodName(input.readString());
                        break;
                    case PARAM_TYPE_FIELD:
                        paramTypeNames.add(input.readString());
                        break;
                    case PARAM_PAYLOAD_FIELD:
                        paramPayloads.add(input.readByteArray());
                        break;
                    default:
                        input.skipField(tag);
                        break;
                }
            }

            Class<?>[] paramTypes = new Class<?>[paramTypeNames.size()];
            Object[] params = new Object[paramPayloads.size()];
            for (int i = 0; i < paramTypeNames.size(); i++) {
                paramTypes[i] = SerializerSupport.resolveClass(paramTypeNames.get(i));
            }
            for (int i = 0; i < paramPayloads.size(); i++) {
                Class<?> paramType = i < paramTypes.length ? paramTypes[i] : null;
                params[i] = SerializerSupport.decodeValue(paramPayloads.get(i), paramType);
            }
            request.setParamsTypes(paramTypes.length == 0 ? null : paramTypes);
            request.setParams(params.length == 0 ? null : params);
            return request;
        } catch (IOException e) {
            throw new RuntimeException("protobuf反序列化request失败", e);
        }
    }

    private byte[] serializeResponse(RPCResponse response) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CodedOutputStream output = CodedOutputStream.newInstance(bos);

            writeString(output, RESPONSE_REQUEST_ID_FIELD, response.getRequestId());
            output.writeInt32(RESPONSE_CODE_FIELD, response.getCode());
            writeString(output, RESPONSE_MESSAGE_FIELD, response.getMessage());
            writeString(output, RESPONSE_DATA_TYPE_FIELD, SerializerSupport.getClassName(response.getDataType()));
            output.writeBytes(RESPONSE_DATA_PAYLOAD_FIELD, ByteString.copyFrom(SerializerSupport.encodeValue(response.getData())));
            output.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("protobuf序列化response失败", e);
        }
    }

    private RPCResponse deserializeResponse(byte[] bytes) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            RPCResponse response = new RPCResponse();
            byte[] dataPayload = new byte[0];
            String dataTypeName = null;

            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0) {
                    break;
                }
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                switch (fieldNumber) {
                    case RESPONSE_REQUEST_ID_FIELD:
                        response.setRequestId(input.readString());
                        break;
                    case RESPONSE_CODE_FIELD:
                        response.setCode(input.readInt32());
                        break;
                    case RESPONSE_MESSAGE_FIELD:
                        response.setMessage(input.readString());
                        break;
                    case RESPONSE_DATA_TYPE_FIELD:
                        dataTypeName = input.readString();
                        break;
                    case RESPONSE_DATA_PAYLOAD_FIELD:
                        dataPayload = input.readByteArray();
                        break;
                    default:
                        input.skipField(tag);
                        break;
                }
            }

            Class<?> dataType = SerializerSupport.resolveClass(dataTypeName);
            response.setDataType(dataType);
            response.setData(SerializerSupport.decodeValue(dataPayload, dataType));
            return response;
        } catch (IOException e) {
            throw new RuntimeException("protobuf反序列化response失败", e);
        }
    }

    private void writeString(CodedOutputStream output, int fieldNumber, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            output.writeString(fieldNumber, value);
        }
    }
}
