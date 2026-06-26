namespace Toolnexus;

/// <summary>
/// Marks a method as a tool (the Spring-AI <c>@Tool</c> feel, vendor-neutral).
/// <list type="bullet">
///   <item><c>Name</c> — tool name; defaults to the method name.</item>
///   <item><c>Description</c> — tool description.</item>
/// </list>
/// </summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class ToolMethodAttribute : Attribute
{
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";

    public ToolMethodAttribute() { }
    public ToolMethodAttribute(string name, string description = "")
    {
        Name = name;
        Description = description;
    }
}

/// <summary>
/// Describes a single tool parameter. Optional — when omitted, the parameter name
/// and inferred type are used, and the parameter is required.
/// </summary>
[AttributeUsage(AttributeTargets.Parameter)]
public sealed class ParamAttribute : Attribute
{
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";
    public bool Required { get; set; } = true;

    public ParamAttribute() { }
    public ParamAttribute(string name, string description = "")
    {
        Name = name;
        Description = description;
    }
}
