# Publishing toolnexus

Names (all verified free at setup): npm **`toolnexus`**, PyPI **`toolnexus`**,
Go module **`github.com/muthuishere/toolnexus/golang`**, repo **`muthuishere/toolnexus`**.

> npm + PyPI are published **manually** by Muthu (no token-based CI). Commands below are
> copy-paste. Go needs no registry — a GitHub tag is the release.

## 0. One-time: GitHub repo

```sh
cd /Users/muthuishere/muthu/gitworkspace/agentskillsmcp
gh repo create muthuishere/toolnexus --public --source . --remote origin --description "Build an agent in a few lines: MCP + agent skills + native + HTTP tools, one interface, any LLM. JS/Python/Go."
git push -u origin main
```

## 1. npm (JS)  →  `npm i toolnexus`

```sh
cd js
npm login                 # interactive, once
npm run build             # produces dist/
npm publish --access public
```

To bump: edit `version` in `js/package.json`, rebuild, `npm publish`.

## 2. PyPI (Python)  →  `pip install toolnexus`

```sh
cd python
python -m pip install --upgrade build twine
python -m build           # creates dist/*.whl + *.tar.gz
python -m twine upload dist/*     # prompts for PyPI token/credentials
```

To bump: edit `version` in `python/pyproject.toml`, rebuild, re-upload.

## 3. Go  →  `go get github.com/muthuishere/toolnexus/golang`

No registry. Tag a release once the repo is pushed:

```sh
cd /Users/muthuishere/muthu/gitworkspace/agentskillsmcp
git tag golang/v0.1.0     # module is in the golang/ subdir, so tag is path-prefixed
git push origin golang/v0.1.0
```

Then consumers: `go get github.com/muthuishere/toolnexus/golang@v0.1.0`.

CLI install: `go install github.com/muthuishere/toolnexus/golang/cmd/toolnexus@latest`.

## 4. Java  →  `io.github.muthuishere:toolnexus`

Gradle `maven-publish` is configured. Publish manually to Maven Central (via the
Central Portal / OSSRH) or GitHub Packages — both need credentials in
`~/.gradle/gradle.properties` (never commit them):

```sh
cd java
./gradlew build
./gradlew publish          # uses the configured repository + your gradle.properties creds
```

Consumers: `implementation 'io.github.muthuishere:toolnexus:0.1.0'`.
To bump: edit `version` in `java/build.gradle`, rebuild, republish.

## 5. .NET / NuGet  →  `dotnet add package Toolnexus`

Package id **`Toolnexus`** (metadata lives in `csharp/src/Toolnexus/Toolnexus.csproj`).
Pack and push manually to nuget.org with an API key in the environment (never commit it):

```sh
cd csharp
dotnet build -c Release
dotnet pack src/Toolnexus/Toolnexus.csproj -c Release      # -> src/Toolnexus/bin/Release/Toolnexus.0.1.0.nupkg
dotnet nuget push src/Toolnexus/bin/Release/Toolnexus.0.1.0.nupkg \
  --api-key "$NUGET_API_KEY" --source https://api.nuget.org/v3/index.json
```

Consumers: `dotnet add package Toolnexus --version 0.1.0`.
To bump: edit `<Version>` in `csharp/src/Toolnexus/Toolnexus.csproj`, rebuild, repack, repush.

## Pre-publish checklist

- [ ] `js`: `npm run build` clean; `node --experimental-strip-types examples/basic.ts` works
- [ ] `python`: `python -m build` clean; `python examples/basic.py` works
- [ ] `golang`: `go build ./...` + `go vet ./...` clean; `go run ./examples/basic` works
- [ ] `csharp`: `dotnet build` + `dotnet test` clean; `dotnet run -- basic` works
- [ ] README + SPEC version numbers bumped together
- [ ] live OpenRouter agent example passes in at least one language
