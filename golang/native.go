// Native / annotation tools — turn a plain function into a uniform Tool.
// Mirrors js/src/native.ts (defineTool) and SPEC.md §6.

package toolnexus

import (
	"context"
	"encoding/json"
	"reflect"
	"strings"
)

// emptySchema is the default input schema for a tool with no declared inputs.
func emptySchema() JSONSchema {
	return JSONSchema{
		"type":                 "object",
		"properties":           map[string]any{},
		"additionalProperties": false,
	}
}

// NativeTool wraps a plain function as a uniform Tool (source: "native").
//
// fn returning a string  ⇒ ToolResult{Output: <string>, IsError: false}.
// fn returning an error  ⇒ ToolResult{Output: err.Error(), IsError: true}.
//
// If inputSchema is nil, an empty object schema is used.
func NativeTool(
	name, description string,
	inputSchema JSONSchema,
	fn func(ctx context.Context, args map[string]any) (string, error),
) Tool {
	if inputSchema == nil {
		inputSchema = emptySchema()
	}
	return Tool{
		Name:        name,
		Description: description,
		InputSchema: inputSchema,
		Source:      SourceNative,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			if args == nil {
				args = map[string]any{}
			}
			out, err := fn(ctx, args)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			return ToolResult{Output: out, IsError: false}, nil
		},
	}
}

// NativeToolReflect derives the JSON schema from struct T via `json` tags and
// builds a native Tool. The model-supplied args are decoded into T before fn is
// called.
//
// Type mapping: string→string, int/float→number, bool→boolean, slice/array→array,
// struct/map→object. A field is required unless its json tag carries ",omitempty".
// Fields tagged `json:"-"` are ignored.
func NativeToolReflect[T any](
	name, description string,
	fn func(ctx context.Context, in T) (string, error),
) Tool {
	var zero T
	schema := reflectStructSchema(reflect.TypeOf(zero))
	return Tool{
		Name:        name,
		Description: description,
		InputSchema: schema,
		Source:      SourceNative,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			if args == nil {
				args = map[string]any{}
			}
			var in T
			// Round-trip through JSON so json tags drive the decode, matching
			// the schema we advertised.
			raw, err := json.Marshal(args)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			if err := json.Unmarshal(raw, &in); err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			out, err := fn(ctx, in)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true}, nil
			}
			return ToolResult{Output: out, IsError: false}, nil
		},
	}
}

// reflectStructSchema builds an object JSON-Schema from a struct type.
func reflectStructSchema(t reflect.Type) JSONSchema {
	for t != nil && t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	if t == nil || t.Kind() != reflect.Struct {
		// Not a struct — fall back to a free-form object.
		return emptySchema()
	}
	props := map[string]any{}
	var required []string
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		if !f.IsExported() {
			continue
		}
		name, omitempty, skip := jsonFieldName(f)
		if skip {
			continue
		}
		props[name] = reflectFieldSchema(f.Type)
		if !omitempty {
			required = append(required, name)
		}
	}
	schema := JSONSchema{
		"type":                 "object",
		"properties":           props,
		"additionalProperties": false,
	}
	if len(required) > 0 {
		schema["required"] = required
	}
	return schema
}

// jsonFieldName resolves a struct field's JSON name and omitempty flag from its
// `json` tag. skip is true for `json:"-"`.
func jsonFieldName(f reflect.StructField) (name string, omitempty, skip bool) {
	tag := f.Tag.Get("json")
	if tag == "-" {
		return "", false, true
	}
	parts := strings.Split(tag, ",")
	name = parts[0]
	if name == "" {
		name = f.Name
	}
	for _, p := range parts[1:] {
		if p == "omitempty" {
			omitempty = true
		}
	}
	return name, omitempty, false
}

// reflectFieldSchema maps a Go type to a JSON-Schema fragment.
func reflectFieldSchema(t reflect.Type) map[string]any {
	for t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	switch t.Kind() {
	case reflect.String:
		return map[string]any{"type": "string"}
	case reflect.Bool:
		return map[string]any{"type": "boolean"}
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64,
		reflect.Float32, reflect.Float64:
		return map[string]any{"type": "number"}
	case reflect.Slice, reflect.Array:
		return map[string]any{
			"type":  "array",
			"items": reflectFieldSchema(t.Elem()),
		}
	case reflect.Map:
		return map[string]any{"type": "object"}
	case reflect.Struct:
		return reflectStructSchema(t)
	default:
		return map[string]any{}
	}
}
