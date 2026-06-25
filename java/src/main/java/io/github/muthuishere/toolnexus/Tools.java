package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.annotations.Param;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-based native tool collection. Scans an object for
 * {@link ToolMethod @ToolMethod}-annotated methods and turns each into a uniform
 * {@link Tool} (source {@code "native"}), inferring the JSON input schema from the
 * method's parameter types.
 *
 * <p>The Spring-AI {@code @Tool} feel, vendor-neutral.
 */
public final class Tools {
    private Tools() {}

    /** Reflect over all {@code @ToolMethod}-annotated methods on the object. */
    public static List<Tool> fromObject(Object target) {
        List<Tool> out = new ArrayList<>();
        Class<?> cls = target.getClass();
        for (Method method : cls.getMethods()) {
            ToolMethod ann = method.getAnnotation(ToolMethod.class);
            if (ann == null) continue;
            out.add(buildTool(target, method, ann));
        }
        return out;
    }

    private static Tool buildTool(Object target, Method method, ToolMethod ann) {
        String name = ann.name().isEmpty() ? method.getName() : ann.name();
        String description = ann.description();

        Parameter[] params = method.getParameters();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();

        for (Parameter p : params) {
            if (p.getType().equals(ToolContext.class)) {
                paramNames.add("$ctx");
                continue;
            }
            Param pann = p.getAnnotation(Param.class);
            String pname = (pann != null && !pann.name().isEmpty()) ? pann.name() : p.getName();
            paramNames.add(pname);

            Map<String, Object> prop = jsonTypeOf(p.getType(), p.getParameterizedType());
            if (pann != null && !pann.description().isEmpty()) {
                prop.put("description", pann.description());
            }
            properties.put(pname, prop);

            boolean req = pann == null || pann.required();
            if (req) required.add(pname);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);

        method.setAccessible(true);

        return NativeTool.of(name, description, schema, (args, ctx) -> {
            Map<String, Object> a = args == null ? new LinkedHashMap<>() : args;
            Object[] callArgs = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                String pn = paramNames.get(i);
                if ("$ctx".equals(pn)) {
                    callArgs[i] = ctx;
                } else {
                    callArgs[i] = coerce(a.get(pn), params[i].getType());
                }
            }
            try {
                return method.invoke(target, callArgs);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause.getMessage(), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Map a Java type to a JSON-Schema property fragment. */
    static Map<String, Object> jsonTypeOf(Class<?> type, Type generic) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type.equals(String.class) || type.equals(char.class) || type.equals(Character.class)) {
            m.put("type", "string");
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            m.put("type", "boolean");
        } else if (type.equals(int.class) || type.equals(Integer.class)
                || type.equals(long.class) || type.equals(Long.class)
                || type.equals(double.class) || type.equals(Double.class)
                || type.equals(float.class) || type.equals(Float.class)
                || type.equals(short.class) || type.equals(Short.class)
                || Number.class.isAssignableFrom(type)) {
            m.put("type", "number");
        } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            m.put("type", "array");
        } else {
            // Map or POJO -> object
            m.put("type", "object");
        }
        return m;
    }

    /** Best-effort coercion of a JSON-decoded value into the method parameter type. */
    @SuppressWarnings("unchecked")
    static Object coerce(Object value, Class<?> type) {
        if (value == null) {
            if (type.isPrimitive()) {
                if (type.equals(boolean.class)) return false;
                if (type.equals(int.class)) return 0;
                if (type.equals(long.class)) return 0L;
                if (type.equals(double.class)) return 0d;
                if (type.equals(float.class)) return 0f;
                if (type.equals(short.class)) return (short) 0;
                if (type.equals(char.class)) return '\0';
            }
            return null;
        }
        if (type.isInstance(value)) return value;

        if (type.equals(String.class)) return String.valueOf(value);

        if (value instanceof Number) {
            Number n = (Number) value;
            if (type.equals(int.class) || type.equals(Integer.class)) return n.intValue();
            if (type.equals(long.class) || type.equals(Long.class)) return n.longValue();
            if (type.equals(double.class) || type.equals(Double.class)) return n.doubleValue();
            if (type.equals(float.class) || type.equals(Float.class)) return n.floatValue();
            if (type.equals(short.class) || type.equals(Short.class)) return n.shortValue();
        }
        if (value instanceof String) {
            String s = (String) value;
            try {
                if (type.equals(int.class) || type.equals(Integer.class)) return Integer.parseInt(s);
                if (type.equals(long.class) || type.equals(Long.class)) return Long.parseLong(s);
                if (type.equals(double.class) || type.equals(Double.class)) return Double.parseDouble(s);
                if (type.equals(float.class) || type.equals(Float.class)) return Float.parseFloat(s);
                if (type.equals(short.class) || type.equals(Short.class)) return Short.parseShort(s);
                if (type.equals(boolean.class) || type.equals(Boolean.class)) return Boolean.parseBoolean(s);
            } catch (NumberFormatException ignored) {
            }
        }
        if (value instanceof Boolean && (type.equals(boolean.class) || type.equals(Boolean.class))) {
            return value;
        }
        return value;
    }
}
