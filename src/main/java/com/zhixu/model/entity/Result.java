package com.zhixu.model.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一响应体：code 为 1 表示成功、0 表示失败。
 * data 中的 snake_case 键会递归转换为 camelCase；id 类字段转为字符串，避免前端精度丢失。
 */
public class Result {
    private final int code;
    private final String msg;
    private final Object data;

    private Result(int code, String msg, Object data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static Result success(Object data) {
        return new Result(1, "success", camelize(data));
    }

    public static Result failure(String msg) {
        return new Result(0, msg, null);
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public Object getData() {
        return data;
    }

    private static Object camelize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = camel(String.valueOf(entry.getKey()));
                Object item = camelize(entry.getValue());
                if ((key.equals("id") || key.endsWith("Id")) && item instanceof Number) {
                    item = item.toString();
                }
                result.put(key, item);
            }
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) {
                result.add(camelize(item));
            }
            return result;
        }
        if (value instanceof Object[]) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Object[]) value) {
                result.add(camelize(item));
            }
            return result;
        }
        return value;
    }

    private static String camel(String key) {
        if (key.indexOf('_') < 0) {
            return key;
        }
        StringBuilder builder = new StringBuilder(key.length());
        boolean upper = false;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                builder.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
