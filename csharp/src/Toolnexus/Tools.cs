using System.Reflection;
using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// Tool helpers: the shared <see cref="Sanitize"/> name rule and reflection-based
/// native tool collection (<see cref="FromObject"/>). Scans an object for
/// <see cref="ToolMethodAttribute"/>-annotated methods and turns each into a uniform
/// <see cref="ITool"/> (source <c>"native"</c>), inferring the JSON input schema from
/// the method's parameter types.
/// </summary>
public static partial class Tools
{
    [GeneratedRegex("[^a-zA-Z0-9_-]")]
    private static partial Regex SanitizeRegex();

    /// <summary>Replace anything outside <c>[a-zA-Z0-9_-]</c> with <c>"_"</c> (same as opencode).</summary>
    public static string Sanitize(string? value)
        => value == null ? "" : SanitizeRegex().Replace(value, "_");

    /// <summary>Reflect over all <see cref="ToolMethodAttribute"/>-annotated methods on the object.</summary>
    public static List<ITool> FromObject(object target)
    {
        var output = new List<ITool>();
        foreach (var method in target.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance))
        {
            var ann = method.GetCustomAttribute<ToolMethodAttribute>();
            if (ann == null) continue;
            output.Add(BuildTool(target, method, ann));
        }
        return output;
    }

    private static ITool BuildTool(object target, MethodInfo method, ToolMethodAttribute ann)
    {
        var name = string.IsNullOrEmpty(ann.Name) ? method.Name : ann.Name;
        var description = ann.Description;

        var parameters = method.GetParameters();
        var properties = new Dictionary<string, object?>();
        var required = new List<object?>();
        var paramNames = new List<string>();

        foreach (var p in parameters)
        {
            if (p.ParameterType == typeof(ToolContext))
            {
                paramNames.Add("$ctx");
                continue;
            }

            var pann = p.GetCustomAttribute<ParamAttribute>();
            var pname = pann != null && !string.IsNullOrEmpty(pann.Name) ? pann.Name : p.Name ?? "arg";
            paramNames.Add(pname);

            var prop = JsonTypeOf(p.ParameterType);
            if (pann != null && !string.IsNullOrEmpty(pann.Description))
                prop["description"] = pann.Description;
            properties[pname] = prop;

            var req = pann?.Required ?? true;
            if (req) required.Add(pname);
        }

        var schema = new Dictionary<string, object?>
        {
            ["type"] = "object",
            ["properties"] = properties,
        };
        if (required.Count > 0) schema["required"] = required;
        schema["additionalProperties"] = false;

        return NativeTool.OfAsync(name, description, schema, async (args, ctx) =>
        {
            var a = args ?? new Dictionary<string, object?>();
            var callArgs = new object?[parameters.Length];
            for (var i = 0; i < parameters.Length; i++)
            {
                var pn = paramNames[i];
                if (pn == "$ctx")
                    callArgs[i] = ctx;
                else
                    callArgs[i] = Coerce(a.TryGetValue(pn, out var v) ? v : null, parameters[i].ParameterType);
            }

            object? result;
            try
            {
                result = method.Invoke(target, callArgs);
            }
            catch (TargetInvocationException tie)
            {
                throw tie.InnerException ?? tie;
            }

            // Await Task / Task<T> returns.
            if (result is Task task)
            {
                await task.ConfigureAwait(false);
                var taskType = task.GetType();
                if (taskType.IsGenericType)
                    result = taskType.GetProperty("Result")!.GetValue(task);
                else
                    result = null;
            }
            return result;
        });
    }

    /// <summary>Map a CLR type to a JSON-Schema property fragment.</summary>
    internal static Dictionary<string, object?> JsonTypeOf(Type type)
    {
        var t = Nullable.GetUnderlyingType(type) ?? type;
        var m = new Dictionary<string, object?>();
        if (t == typeof(string) || t == typeof(char))
            m["type"] = "string";
        else if (t == typeof(bool))
            m["type"] = "boolean";
        else if (t == typeof(int) || t == typeof(long) || t == typeof(double) || t == typeof(float)
                 || t == typeof(short) || t == typeof(decimal) || t == typeof(byte))
            m["type"] = "number";
        else if (t.IsArray || (typeof(System.Collections.IEnumerable).IsAssignableFrom(t) && t != typeof(string)
                 && !typeof(System.Collections.IDictionary).IsAssignableFrom(t)))
            m["type"] = "array";
        else
            m["type"] = "object";
        return m;
    }

    /// <summary>Best-effort coercion of a JSON-decoded value into the method parameter type.</summary>
    internal static object? Coerce(object? value, Type type)
    {
        var t = Nullable.GetUnderlyingType(type) ?? type;
        if (value == null)
        {
            if (t.IsValueType) return Activator.CreateInstance(t);
            return null;
        }
        if (t.IsInstanceOfType(value)) return value;

        if (t == typeof(string)) return value.ToString();

        try
        {
            if (t == typeof(int)) return Convert.ToInt32(value);
            if (t == typeof(long)) return Convert.ToInt64(value);
            if (t == typeof(double)) return Convert.ToDouble(value);
            if (t == typeof(float)) return Convert.ToSingle(value);
            if (t == typeof(short)) return Convert.ToInt16(value);
            if (t == typeof(decimal)) return Convert.ToDecimal(value);
            if (t == typeof(bool))
            {
                if (value is bool b) return b;
                return bool.TryParse(value.ToString(), out var parsed) && parsed;
            }
        }
        catch
        {
            // fall through to returning the original value
        }
        return value;
    }
}
