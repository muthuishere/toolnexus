# Shared media fixtures

`fixture.png` is the cross-language parity fixture for multimodal content
(`openspec/changes/add-multimodal-content`). It is an 8x8 PNG, 82 bytes, with four
solid quadrants — red (top-left), green (top-right), blue (bottom-left), white
(bottom-right).

It is deliberately tiny so every port can inline it in a test, and deliberately
distinctive so a live model's description is evidence the image actually arrived
rather than evidence the model guessed.

| file | what it is |
|---|---|
| `fixture.png` | the committed bytes — **the** source of truth |
| `fixture.png.base64` | golden standard base64 (padded, no line breaks) of those bytes |
| `fixture.png.sha256` | golden sha256 of those bytes |

**Every port asserts against the committed goldens, not against its own
re-encoding.** The PNG was generated once by hand (zlib + struct, no image
library); regenerating it is not guaranteed to be byte-stable across zlib
versions, which is exactly why the bytes are committed rather than produced at
test time.

Do not "optimize", re-encode, or regenerate these files. Changing them changes
the goldens in seven ports at once.
