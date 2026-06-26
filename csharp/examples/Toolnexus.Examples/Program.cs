using Toolnexus.Examples;

var which = args.Length > 0 ? args[0].ToLowerInvariant() : "basic";

return which switch
{
    "basic" => await Basic.Run(),
    "agent" => await Agent.Run(),
    "hooks" => await HooksExample.Run(),
    "streaming" => await Streaming.Run(),
    "memory" => await Memory.Run(),
    "advanced" => await Advanced.Run(),
    "openrouter" => await Openrouter.Run(),
    _ => Unknown(which),
};

static int Unknown(string which)
{
    Console.Error.WriteLine($"Unknown example: {which}");
    Console.Error.WriteLine("Usage: dotnet run -- [basic|agent|hooks|streaming|memory|advanced|openrouter]");
    return 1;
}
