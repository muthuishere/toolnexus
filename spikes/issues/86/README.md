# Spike — issue #86: the toolkit-less completion, in all seven ports

Offline reproduction of [#86](https://github.com/muthuishere/toolnexus/issues/86). **No network,
no API key, no cost**: a 40-line mock LLM on `127.0.0.1` speaks the OpenAI chat-completions shape,
answers with a fixed sentence, and logs every request body it saw. Each port then asks the same
question two ways — *with no toolkit* and *with the workaround that exists today* — and the
runner prints whether a `tools` key ever reached the wire.

This is a spike. It reads the library; it does not change it.

## Run it

```sh
./run.sh
```

Prerequisites, all of which the repo's normal build already produces:

| port | needs | if missing |
|---|---|---|
| go | nothing | — |
| js | `js/dist/` | `cd js && npm install && npm run build` |
| python | `toolnexus` importable (it pulls `mcp`) | `python3 -m venv .venv && .venv/bin/pip install -e ../../../python`, then `SPIKE86_PY=.venv/bin/python ./run.sh` |
| java | `java/build/classes/java/main` | `cd java && ./gradlew build --no-daemon` (the probe self-skips otherwise) |
| csharp | .NET SDK | — |
| elixir | `elixir/_build` | `cd elixir && mix deps.get && mix compile` |
| clojure | `clojure` CLI | — |

Knobs: `SPIKE86_PORT` (default 8686), `SPIKE86_PY` (default `python3`).

## Measured output — 2026-09-21, branch `issues-86-93-adrs`

```
--- go (issue says: already correct) ---
go: ok, text="A toolkit-less completion."
--- js (issue says: fixed on jev via Toolkit.empty()) ---
js: typeof Toolkit.empty = undefined
js: no-toolkit ask FAILED: TypeError: Cannot read properties of undefined (reading 'skillsPrompt')
js: bare ask FAILED: TypeError: Cannot read properties of undefined (reading 'on_text')
js: createToolkit({builtins:false}) OK, tools= 0 text= "A toolkit-less completion."
--- python (issue says: toolkit mandatory) ---
py: no-toolkit run FAILED: TypeError: Client.run() missing 1 required positional argument: 'toolkit'
py: None-toolkit run FAILED: AttributeError: 'NoneType' object has no attribute 'skills_prompt'
py: create_toolkit(builtins=False) OK, tools= 0 text= 'A toolkit-less completion.'
--- java (the issue's table omits java entirely) ---
java: null-toolkit run FAILED: NullPointerException: Cannot invoke
      "io.github.muthuishere.toolnexus.Toolkit.skillsPrompt()" because "toolkit" is null
java: Toolkit builtins(false) OK, tools=0 text=A toolkit-less completion.
--- csharp (issue says: needs an overload without Toolkit, or Toolkit?) ---
cs: null-toolkit run FAILED: NullReferenceException: Object reference not set to an instance of an object.
cs: Toolkit.CreateAsync(Builtins=false) OK, tools=0 text=A toolkit-less completion.
--- elixir (issue says: needs an arity that omits the toolkit) ---
ex: run/2 FAILED: UndefinedFunctionError: function Toolnexus.Client.run/2 is undefined or private
ex: nil-toolkit run OK, text="A toolkit-less completion."
ex: Toolkit.build(builtins: false) OK, tools=0 text="A toolkit-less completion."
--- clojure (issue says: :toolkit mandatory) ---
clj: no-toolkit run OK, text= "A toolkit-less completion."
clj: nil-toolkit run OK, text= "A toolkit-less completion."
clj: no-toolkit ask OK, text= "A toolkit-less completion."

--- wire bodies: does any request carry a "tools" key? ---
1..9: tools_key=False  tool_choice_key=False  keys=['messages', 'model']
```

## What the run establishes

1. **The wire half of the issue is already fixed everywhere.** All nine requests — from all seven
   ports, toolkit-less *and* empty-toolkit — carry `keys=['messages','model']`. No `tools`, no
   `tool_choice`. That is §8 Gap 5 from ADR-0001, and it holds in every port. Nothing to do.
2. **Only the call shape differs**, and it differs three ways, not two:
   - go, clojure: **works today**
   - elixir: nil toolkit works; only the short arity is missing
   - js, python, java, csharp: the toolkit-less call is a crash
3. **"Cannot express no tools" is false.** `builtins:false` yields a genuinely empty toolkit
   (`tools=0`) in all four crashing ports. The complaint is ceremony and asynchrony, not
   expressiveness.
4. **The crash is the same crash in three ports** — dereferencing the toolkit for
   `skillsPrompt()` while building the system message, *before* anything touches tools. C#'s
   bare `NullReferenceException` is the same line with less information.

Details, per-port fix shapes, and the conformance-gate argument: `docs/adr/0023-call-shape-parity.md`.

## Layout

```
mock_llm.py   offline OpenAI-shaped LLM; appends every request body to requests.ndjson
run.sh        starts the mock, runs all seven probes, prints the wire-body summary
go/           nil toolkit — compiles and runs
js/probe.mjs  Toolkit.empty existence check + no-toolkit ask + workaround
py/probe.py   missing-arg, explicit None, workaround
java/         Probe.java + run-java-probe.sh (compiles against the built classes)
cs/           Probe.csproj + Program.cs (null! compiles; NRE at runtime)
probe.exs     elixir: run/2 arity, nil toolkit, workaround
clj/probe.clj clojure: no :toolkit key, nil :toolkit, ask
```
