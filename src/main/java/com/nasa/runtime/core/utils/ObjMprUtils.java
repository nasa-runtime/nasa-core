package com.nasa.runtime.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nasa.runtime.core.exception.JsonException;

import java.io.*;
import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Nasa
 * 序列化、反序列化工具
 */
@SuppressWarnings("unused")
public abstract class ObjMprUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // 美化输出用, 懒加载
    private static volatile ObjectMapper prettyMapper;

    static {
        configureMapper(OBJECT_MAPPER);
    }

    private static void configureMapper(ObjectMapper mapper) {
        DateFormat dateFormat = DateUtils.dateFormat(DateUtils.f_y_M_d_H_m_s);
        mapper.setDateFormat(dateFormat);
        // 该特性决定了当遇到未知属性（没有映射到属性，没有任何setter或者任何可以处理它的handler），是否应该抛出一个
        // JsonMappingException异常。这个特性一般式所有其他处理方法对未知属性处理都无效后才被尝试，属性保留未处理状态。
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 属性值为null的，不参与序列化
        // Include.NON_NULL 属性为NULL 不序列化
        // Include.NON_EMPTY 属性为 空字符串或者为 NULL 都不序列化
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        // 将小数反序列化为BigDecimal
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // JDK 8+ 时间类型支持 (Duration, LocalDateTime 等)
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static ObjectMapper prettyMapper() {
        if (prettyMapper == null) {
            synchronized (ObjMprUtils.class) {
                if (prettyMapper == null) {
                    prettyMapper = OBJECT_MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT);
                }
            }
        }
        return prettyMapper;
    }


    /**
     * 设置序列化反序列化日期格式输出
     * @param dateFormat 日期格式
     * @param timeZone 时区
     */
    public static void setDateFormat(String dateFormat, String timeZone) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(dateFormat);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(timeZone));
        OBJECT_MAPPER.setDateFormat(simpleDateFormat);
    }


    /**
     * 将JVM类型的对象转换为JSON格式的字符串
     */
    public static <T> String toString(T obj) {
        if (Objects.isNull(obj)) {
            return null;
        }
        if (obj instanceof CharSequence || obj instanceof Character
                || obj instanceof Number || obj instanceof Boolean || obj instanceof Enum) {
            return obj.toString();
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将对象序列化为byte数组
     * 注意，此方法返回的byte[]数组，只能通过deserialize来反序列化
     * 并且要指定反序列化的Class
     */
    public static <T> byte[] serialize(T obj) {
        if (Objects.isNull(obj)) {
            return null;
        }
        if (obj instanceof CharSequence || obj instanceof Character || obj instanceof Number) {
            return obj.toString().getBytes();
        }
        try {
            return OBJECT_MAPPER.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将JSON格式的字符串转换为复杂对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(String json, Class<T> clazz) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        if (clazz.isAssignableFrom(String.class)) {
            return (T) json;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将JSON格式的字符串转换为复杂对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(String json, TypeReference<T> typeReference) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        Type type = typeReference.getType();
        if (type == String.class || type == CharSequence.class) {
            return (T) json;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将JSON格式的字符串转换为复杂对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (ColUtils.isEmpty(bytes)) {
            return null;
        }
        if (clazz.isAssignableFrom(String.class)) {
            return (T) new String(bytes);
        }
        try {
            return OBJECT_MAPPER.readValue(bytes, clazz);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将JSON格式的字符串转换为复杂对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes, TypeReference<T> typeReference) {
        if (ColUtils.isEmpty(bytes)) {
            return null;
        }
        Type type = typeReference.getType();
        if (type == String.class || type == CharSequence.class) {
            return (T) new String(bytes);
        }
        if (type == Object.class) {
            return (T) bytes;
        }
        try {
            return OBJECT_MAPPER.readValue(bytes, typeReference);
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将一个对象序列化成byte[]，必须通过toObject实现反序列化
     */
    public static <T extends Serializable> byte[] toBytes(T value) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {

            oos.writeObject(value);

            return baos.toByteArray();

        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }


    /**
     * 将一个byte[]序列化成对象，必须是通过toBytes序列化的数组
     */
    public static Object toObject(byte[] bytes) {
        if (ColUtils.isEmpty(bytes)) {
            return null;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {

            return ois.readObject();

        } catch (IOException | ClassNotFoundException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    // ==================== 美化输出 ====================

    /**
     * 美化 JSON 输出 (带缩进), 调试/日志用
     */
    public static <T> String toPrettyString(T obj) {
        if (Objects.isNull(obj)) return null;
        try {
            return prettyMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    // ==================== JSON → Map / List ====================

    /**
     * JSON 字符串 → Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        return deserialize(json, Map.class);
    }

    /**
     * JSON 字符串 → {@code List<Map>}。
     */
    public static List<Map<String, Object>> toListMap(String json) {
        return deserialize(json, new TypeReference<>() {});
    }

    /**
     * JSON 字符串 → {@code List<T>}。
     */
    public static <T> List<T> toList(String json, Class<T> elementType) {
        if (StringUtils.isBlank(json)) return null;
        try {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, elementType));
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
    }

    // ==================== 对象互转 ====================

    /**
     * 对象间转换 (如 Map → POJO, POJO → Map), 底层走 Jackson convertValue
     */
    public static <T> T convert(Object source, Class<T> targetType) {
        if (source == null) return null;
        return OBJECT_MAPPER.convertValue(source, targetType);
    }

    /**
     * 对象间转换（支持泛型，如 {@code POJO -> Map<String, Object>}）。
     */
    public static <T> T convert(Object source, TypeReference<T> targetType) {
        if (source == null) return null;
        return OBJECT_MAPPER.convertValue(source, targetType);
    }

    // ==================== JSON 判断 ====================

    /**
     * 判断字符串是否是合法 JSON
     */
    public static boolean isJson(String str) {
        if (StringUtils.isBlank(str)) return false;
        char first = str.charAt(0);
        if (first != '{' && first != '[' && first != '"') return false;
        try {
            OBJECT_MAPPER.readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断字符串是否是 JSON 对象
     */
    public static boolean isJsonObject(String str) {
        return StringUtils.isNotBlank(str) && str.charAt(0) == '{' && isJson(str);
    }

    /**
     * 判断字符串是否是 JSON 数组
     */
    public static boolean isJsonArray(String str) {
        return StringUtils.isNotBlank(str) && str.charAt(0) == '[' && isJson(str);
    }
}
