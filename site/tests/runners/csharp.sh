#!/usr/bin/env bash
# Compile and execute every extracted C# docs example.
#
# Hermetic: each snippet becomes a tiny console project with a ProjectReference on
# THIS repo's csharp/ port, so a renamed member fails compilation. Snippets use
# top-level statements, so each needs its own project (one entry point per assembly).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/csharp"
WORK="$REPO_ROOT/site/tests/.work/csharp"
PORT_CSPROJ="$REPO_ROOT/csharp/src/Toolnexus/Toolnexus.csproj"

if [ ! -d "$SNIPPETS" ]; then
	echo "no csharp snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

export DOTNET_NOLOGO=1
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

mkdir -p "$WORK"

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.cs; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .cs)"
	proj="$WORK/$name"
	mkdir -p "$proj"
	cp "$f" "$proj/Program.cs"
	cat > "$proj/$name.csproj" <<EOF
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <AssemblyName>$name</AssemblyName>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="$PORT_CSPROJ" />
  </ItemGroup>
</Project>
EOF
	# -nodeReuse:false: without it, MSBuild keeps a shared build-server node alive
	# across snippets. Two projects touching it in close succession can hit a real
	# file race (MSB4018/MSB3491 "file in use" / "already exists") even though the
	# runner is single-threaded — confirmed by an isolated re-run always passing.
	if out=$(cd "$proj" && TOOLNEXUS_REPO="$REPO_ROOT" dotnet run --nologo -v q -nodeReuse:false 2>&1); then
		pass=$((pass + 1))
		printf '  \033[32mPASS\033[0m %-40s %s\n' "$name" "$(echo "$out" | tail -1)"
	else
		fail=$((fail + 1))
		failed_names+=("$name")
		printf '  \033[31mFAIL\033[0m %s\n' "$name"
		echo "$out" | sed 's/^/        /' | tail -15
	fi
done

echo ""
echo "C#: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
