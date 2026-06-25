"""Native / annotation tools — turn a plain function into a uniform Tool.

See ../../SPEC.md §6. Mirrors the JS reference (``js/src/native.ts``), adapted to
Python's idiom: a ``@tool`` decorator that infers the JSON input schema from type
hints + the docstring (first line = description). Explicit ``name`` /
``description`` / ``input_schema`` arguments override inference.

Both ``async def`` and plain ``def`` functions are supported. A function that
returns a ``str`` becomes ``ToolResult(output=str, is_error=False)``; a raised
exception becomes ``ToolResult(is_error=True, output=message)``.
"""
from __future__ import annotations

import asyncio
import inspect
import json
from typing import Any, Callable, Optional, Union, get_args, get_origin, get_type_hints

from .types import JSONSchema, Tool, ToolContext, ToolResult

_EMPTY_SCHEMA: JSONSchema = {
    "type": "object",
    "properties": {},
    "additionalProperties": False,
}


def _python_type_to_json(annotation: Any) -> dict[str, Any]:
    """Map a Python type hint to a JSON-Schema property fragment.

    str→string, int/float→number, bool→boolean, list→array, dict→object.
    Optional[T] / Union[T, None] unwraps to T. Unknown → {} (any).
    """
    # Unwrap Optional[T] / Union[T, None]
    origin = get_origin(annotation)
    if origin is Union:
        non_none = [a for a in get_args(annotation) if a is not type(None)]
        if len(non_none) == 1:
            return _python_type_to_json(non_none[0])
        return {}

    # bool must be checked before int (bool is a subclass of int)
    if annotation is bool:
        return {"type": "boolean"}
    if annotation is int or annotation is float:
        return {"type": "number"}
    if annotation is str:
        return {"type": "string"}

    if origin in (list, tuple, set, frozenset) or annotation in (list, tuple, set):
        return {"type": "array"}
    if origin is dict or annotation is dict:
        return {"type": "object"}

    return {}


def _infer_schema(fn: Callable[..., Any]) -> JSONSchema:
    """Infer a JSON-Schema object from a function's signature + type hints.

    Required = parameters without defaults. A ``ctx``/``context`` parameter (the
    optional :class:`ToolContext`) is excluded from the schema.
    """
    sig = inspect.signature(fn)
    try:
        hints = get_type_hints(fn)
    except Exception:
        hints = {}

    properties: dict[str, Any] = {}
    required: list[str] = []
    for pname, param in sig.parameters.items():
        if pname in ("ctx", "context", "self", "cls"):
            continue
        if param.kind in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD):
            continue
        annotation = hints.get(pname, param.annotation)
        if annotation is inspect.Parameter.empty:
            properties[pname] = {}
        else:
            properties[pname] = _python_type_to_json(annotation)
        if param.default is inspect.Parameter.empty:
            required.append(pname)

    schema: JSONSchema = {
        "type": "object",
        "properties": properties,
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return schema


def _first_docstring_line(fn: Callable[..., Any]) -> str:
    doc = inspect.getdoc(fn)
    if not doc:
        return ""
    return doc.strip().splitlines()[0].strip()


def _accepts_ctx(fn: Callable[..., Any]) -> bool:
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return False
    return any(p in sig.parameters for p in ("ctx", "context"))


def _make_tool(
    fn: Callable[..., Any],
    *,
    name: Optional[str],
    description: Optional[str],
    input_schema: Optional[JSONSchema],
    source: str,
) -> Tool:
    tool_name = name or fn.__name__
    tool_desc = description if description is not None else _first_docstring_line(fn)
    schema = input_schema if input_schema is not None else _infer_schema(fn)
    pass_ctx = _accepts_ctx(fn)
    is_async = inspect.iscoroutinefunction(fn)

    async def execute(
        args: Optional[dict[str, Any]] = None,
        ctx: Optional[ToolContext] = None,
    ) -> ToolResult:
        call_kwargs = dict(args or {})
        if pass_ctx:
            call_kwargs["ctx" if "ctx" in inspect.signature(fn).parameters else "context"] = ctx
        try:
            if is_async:
                out = await fn(**call_kwargs)
            else:
                out = await asyncio.to_thread(lambda: fn(**call_kwargs))
        except Exception as e:  # noqa: BLE001 — surfaced as a tool error
            return ToolResult(output=str(e), is_error=True)

        # Pass through a full ToolResult unchanged.
        if isinstance(out, ToolResult):
            return out
        if isinstance(out, str):
            return ToolResult(output=out, is_error=False)
        return ToolResult(output=json.dumps(out), is_error=False)

    return Tool(
        name=tool_name,
        description=tool_desc,
        input_schema=schema,
        source=source,  # type: ignore[arg-type]
        execute=execute,
    )


def define_tool(
    fn: Optional[Callable[..., Any]] = None,
    *,
    name: Optional[str] = None,
    description: Optional[str] = None,
    input_schema: Optional[JSONSchema] = None,
    source: str = "native",
) -> Union[Tool, Callable[[Callable[..., Any]], Tool]]:
    """Wrap a function as a uniform :class:`Tool` (``source="native"``).

    Direct form::

        t = define_tool(my_fn, name="add", description="Add two numbers.")

    Decorator form is also accepted (returns a Tool, not the function)::

        @define_tool(name="add")
        def add(a: int, b: int) -> str: ...
    """
    if fn is not None:
        return _make_tool(
            fn, name=name, description=description, input_schema=input_schema, source=source
        )

    def wrapper(f: Callable[..., Any]) -> Tool:
        return _make_tool(
            f, name=name, description=description, input_schema=input_schema, source=source
        )

    return wrapper


def tool(
    fn: Optional[Callable[..., Any]] = None,
    *,
    name: Optional[str] = None,
    description: Optional[str] = None,
    input_schema: Optional[JSONSchema] = None,
) -> Union[Tool, Callable[[Callable[..., Any]], Tool]]:
    """Decorator that turns a function into a native :class:`Tool`.

    Usage::

        @tool
        def add(a: int, b: int) -> str:
            \"\"\"Add two numbers and return the sum.\"\"\"
            return str(a + b)

        @tool(name="adder", description="...")
        def add2(a: int, b: int) -> str: ...

    The input schema is inferred from type hints; the first docstring line is the
    description. Explicit ``name`` / ``description`` / ``input_schema`` override
    the inferred values. The decorated object IS the resulting ``Tool``.
    """
    return define_tool(
        fn, name=name, description=description, input_schema=input_schema, source="native"
    )
