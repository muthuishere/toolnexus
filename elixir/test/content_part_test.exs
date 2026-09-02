defmodule Toolnexus.ContentPartTest do
  @moduledoc """
  SPEC §1B — the ContentPart model, the edge constructors, and the §8A
  `(style × part type)` emission matrix with its positive allowlist.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.ContentPart
  alias Toolnexus.ContentPart.Error

  @fixture Path.expand("../../examples/media/fixture.png", __DIR__)
  @golden Path.expand("../../examples/media/fixture.png.base64", __DIR__)

  defp golden, do: @golden |> File.read!() |> String.trim()

  describe "the model (§1B)" do
    test "a path is read at the edge, base64d to the committed golden, and never stored" do
      part = ContentPart.image!(@fixture)

      assert part.type == "image"
      assert part.mime_type == "image/png"
      assert part.data == golden()
      assert part.url == nil
      refute part |> ContentPart.to_map() |> Map.has_key?("path")
    end

    test "the wire map uses mimeType and omits nil fields, and from_map round-trips" do
      part = ContentPart.image!(@fixture)
      map = ContentPart.to_map(part)

      assert Map.keys(map) |> Enum.sort() == ["data", "mimeType", "type"]
      assert map["mimeType"] == "image/png"
      assert ContentPart.from_map(map) == part
    end

    test "type stays a String on the way back in, even for an unknown wire type" do
      part = ContentPart.from_map(%{"type" => "video", "url" => "https://x/y.mp4"})
      assert part.type == "video"
      assert is_binary(part.type)
    end

    test "both data and url, or neither, is a typed error" do
      both = %ContentPart{type: "image", mime_type: "image/png", data: "x", url: "https://x/y.png"}
      assert {:error, %Error{message: msg}} = ContentPart.validate(both)
      assert msg =~ "both"

      assert {:error, %Error{message: msg}} = ContentPart.validate(%ContentPart{type: "image"})
      assert msg =~ "neither"

      assert {:error, %Error{}} = ContentPart.validate(%ContentPart{type: "image", data: "x"})
      assert {:error, %Error{}} = ContentPart.validate(%ContentPart{type: "text"})
      assert {:error, %Error{}} = ContentPart.validate(%ContentPart{type: "video", data: "x"})
      assert {:ok, _} = ContentPart.validate(ContentPart.text("hi"))
      assert {:ok, _} = ContentPart.validate(ContentPart.image!(@fixture))
    end
  end

  describe "the edge constructors (§1B)" do
    test "a data: URL normalises to {mimeType, data} and is never kept as a url" do
      part = ContentPart.image!("data:image/png;base64," <> golden())
      assert part.data == golden()
      assert part.mime_type == "image/png"
      assert part.url == nil
    end

    test "an https URL is kept as a url" do
      part = ContentPart.image!("https://example.com/a.png", mime_type: "image/png")
      assert part.url == "https://example.com/a.png"
      assert part.data == nil
    end

    test "raw bytes are base64d at construction and need an explicit mime type" do
      bin = File.read!(@fixture)
      assert ContentPart.image!({:bytes, bin}, mime_type: "image/png").data == golden()
      assert {:error, %Error{message: msg}} = ContentPart.image({:bytes, bin})
      assert msg =~ ":mime_type"
    end

    test "iodata is normalised at the edge, improper lists included" do
      bin = File.read!(@fixture)
      <<head::binary-size(40), tail::binary>> = bin

      # a plain iolist, a nested one, and an improper list Enum could not walk at all
      assert ContentPart.image!([head, tail], mime_type: "image/png").data == golden()
      assert ContentPart.image!([[head], [[tail]]], mime_type: "image/png").data == golden()

      <<b0, rest::binary>> = bin
      assert ContentPart.image!([b0 | rest], mime_type: "image/png").data == golden()

      assert {:ok, part} = ContentPart.image([head, tail], mime_type: "image/png")
      assert part.data == golden()
      assert part.url == nil

      # iodata is bytes, so it needs an explicit mime type, and a non-iodata list is typed
      assert {:error, %Error{message: msg}} = ContentPart.image([head, tail])
      assert msg =~ ":mime_type"
      assert {:error, %Error{message: msg}} = ContentPart.image([:nope], mime_type: "image/png")
      assert msg =~ "iodata"
      assert_raise Error, fn -> ContentPart.image!([:nope], mime_type: "image/png") end

      assert {:error, %Error{}} = ContentPart.image([head, tail], mime_type: "image/png", max_part_bytes: 10)
    end

    test "a File.Stream is consumed eagerly and takes its mime type from its own path" do
      part = ContentPart.image!(File.stream!(@fixture, 16))

      assert part.mime_type == "image/png"
      assert part.data == golden()
      assert part.url == nil
      refute part |> ContentPart.to_map() |> Map.has_key?("path")

      assert {:ok, ^part} = ContentPart.image(File.stream!(@fixture, 16))

      # a file part off a stream picks up the basename, exactly as a path does
      assert ContentPart.file!(File.stream!(@fixture), mime_type: "application/octet-stream").name ==
               "fixture.png"

      # the same extension rules as a path, and the same failure when the file is not there
      tmp = Path.join(System.tmp_dir!(), "tn-stream-#{System.unique_integer([:positive])}.xyz")
      File.write!(tmp, "hello")
      assert {:error, %Error{message: msg}} = ContentPart.file(File.stream!(tmp))
      assert msg =~ ".xyz"
      assert ContentPart.file!(File.stream!(tmp), mime_type: "text/plain").data == Base.encode64("hello")
      File.rm(tmp)

      assert {:error, %Error{message: msg}} = ContentPart.image(File.stream!("/nope/missing.png"))
      assert msg =~ "stream"
      assert_raise Error, fn -> ContentPart.image!(File.stream!("/nope/missing.png")) end

      assert {:error, %Error{}} = ContentPart.image(File.stream!(@fixture, 16), max_part_bytes: 10)
    end

    test "any enumerable of binary chunks is consumed at construction" do
      chunks = @fixture |> File.read!() |> then(&for <<c::binary-size(1) <- &1>>, do: c)

      assert ContentPart.image!(chunks |> Stream.map(& &1), mime_type: "image/png").data == golden()
      assert {:ok, part} = ContentPart.image(Stream.map(chunks, & &1), mime_type: "image/png")
      assert part.data == golden()

      # an enumerable is bytes: it needs a mime type, and non-binary chunks are typed
      assert {:error, %Error{message: msg}} = ContentPart.image(Stream.map(chunks, & &1))
      assert msg =~ ":mime_type"

      assert {:error, %Error{message: msg}} = ContentPart.image(Stream.map(1..3, fn _ -> :nope end), mime_type: "image/png")
      assert msg =~ "binary chunks"
      assert_raise Error, fn -> ContentPart.image!(%{a: 1}, mime_type: "image/png") end
    end

    test "an unknown extension is refused by name" do
      assert {:error, %Error{message: msg}} = ContentPart.file("/tmp/report.xyz")
      assert msg =~ ".xyz"
      # ... unless the caller names the mime type themselves
      tmp = Path.join(System.tmp_dir!(), "tn-part-#{System.unique_integer([:positive])}.xyz")
      File.write!(tmp, "hello")
      assert ContentPart.file!(tmp, mime_type: "text/plain").data == Base.encode64("hello")
      File.rm(tmp)
    end

    test "a missing file, a bad data: URL and a non-source are typed errors" do
      assert {:error, %Error{message: msg}} = ContentPart.image("/nope/missing.png")
      assert msg =~ "missing.png"
      assert {:error, %Error{}} = ContentPart.image("data:image/png;base64,!!!not base64!!!")
      assert {:error, %Error{}} = ContentPart.image("data:image/png,notbase64")
      assert {:error, %Error{}} = ContentPart.image("data:;base64," <> golden())
      assert {:error, %Error{message: msg}} = ContentPart.image(:not_a_source)
      assert msg =~ "iodata"
      assert {:error, %Error{message: msg}} = ContentPart.new("video", @fixture)
      assert msg =~ "unknown type"
      assert_raise Error, fn -> ContentPart.image!("/nope/missing.png") end
    end

    test "max_part_bytes is measured in DECODED bytes and enforced at the edge" do
      bin = File.read!(@fixture)
      assert byte_size(bin) == 82

      assert {:error, %Error{message: msg}} = ContentPart.image(@fixture, max_part_bytes: 81)
      assert msg =~ "82" and msg =~ "81"
      assert {:ok, _} = ContentPart.image(@fixture, max_part_bytes: 82)
      assert {:error, %Error{}} = ContentPart.image({:bytes, bin}, mime_type: "image/png", max_part_bytes: 10)
      assert {:error, %Error{}} = ContentPart.image("data:image/png;base64," <> golden(), max_part_bytes: 10)
    end

    test "a file part from a path picks up its basename, and the media table is fixed" do
      assert ContentPart.media_for_path("a/b/c.JPEG") == {:ok, {"image/jpeg", "image"}}
      assert ContentPart.media_for_path("a/b/c.txt") == :error
      assert map_size(ContentPart.media_table()) == 8
    end
  end

  describe "safety and accounting (§9)" do
    test "describe/1 is {type, mimeType, bytes} and never carries data" do
      part = ContentPart.image!(@fixture)
      assert ContentPart.describe(part) == %{"type" => "image", "mimeType" => "image/png", "bytes" => 82}
      refute ContentPart.describe(part) |> Map.values() |> Enum.member?(part.data)
    end

    test "inspect renders a part without its bytes" do
      rendered = inspect(ContentPart.image!(@fixture))
      assert rendered =~ "image/png"
      assert rendered =~ "82 bytes"
      refute rendered =~ golden()
      assert inspect(ContentPart.text("hi")) =~ "hi"
      assert inspect(ContentPart.image!("https://x/y.png", mime_type: "image/png")) =~ "url"
    end

    test "the token estimate is byte-derived, not the mimeType string" do
      big = ContentPart.image!({:bytes, :binary.copy(<<0>>, 2_097_152)}, mime_type: "image/png")
      assert ContentPart.byte_size_of(big) == 2_097_152
      assert ContentPart.estimated_tokens(big) == div(2_097_152, 750)
      # a 5 MB image is never charged like the ~9 bytes of "image/png"
      assert ContentPart.estimated_tokens(big) > String.length("image/png")
      # a small part still costs something
      assert ContentPart.estimated_tokens(ContentPart.image!(@fixture)) == 85
      assert ContentPart.estimated_tokens(ContentPart.text("abcd")) == 1
      assert ContentPart.byte_size_of(%ContentPart{type: "image", url: "https://x/y.png"}) == 0
      assert ContentPart.byte_size_of(%ContentPart{type: "image", mime_type: "image/png", data: "abcd"}) == 3
    end
  end

  describe "provider emission (§8A)" do
    setup do
      %{
        img_data: ContentPart.image!(@fixture),
        img_url: ContentPart.image!("https://x/y.png", mime_type: "image/png"),
        file_data: ContentPart.file!({:bytes, "%PDF-"}, mime_type: "application/pdf", name: "r.pdf"),
        file_url: ContentPart.file!("https://x/y.pdf", mime_type: "application/pdf"),
        audio_data: ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg"),
        audio_url: ContentPart.audio!("https://x/y.mp3", mime_type: "audio/mpeg")
      }
    end

    test "openai: inline image is an image_url carrying a data: URL", ctx do
      assert {:ok, blk} = ContentPart.to_block(ctx.img_data, "openai")
      assert blk == %{"type" => "image_url", "image_url" => %{"url" => "data:image/png;base64," <> golden()}}
      assert {:ok, %{"image_url" => %{"url" => "https://x/y.png"}}} = ContentPart.to_block(ctx.img_url, "openai")
    end

    test "openai: file_data carries the data: prefix (a bare base64 string is a 400)", ctx do
      assert {:ok, blk} = ContentPart.to_block(ctx.file_data, "openai")
      assert blk["type"] == "file"
      assert blk["file"]["filename"] == "r.pdf"
      assert blk["file"]["file_data"] == "data:application/pdf;base64," <> Base.encode64("%PDF-")
      assert String.starts_with?(blk["file"]["file_data"], "data:application/pdf;base64,")
    end

    test "openai: audio is input_audio with a format; a URL form is refused", ctx do
      assert {:ok, blk} = ContentPart.to_block(ctx.audio_data, "openai")
      assert blk == %{"type" => "input_audio", "input_audio" => %{"data" => Base.encode64("ID3"), "format" => "mp3"}}
      assert {:unsupported, _} = ContentPart.to_block(ctx.audio_url, "openai")
      assert {:unsupported, _} = ContentPart.to_block(ctx.file_url, "openai")

      wav = ContentPart.audio!({:bytes, "RIFF"}, mime_type: "audio/wav")
      assert {:ok, %{"input_audio" => %{"format" => "wav"}}} = ContentPart.to_block(wav, "openai")
      odd = ContentPart.audio!({:bytes, "x"}, mime_type: "audio/ogg")
      assert {:ok, %{"input_audio" => %{"format" => "ogg"}}} = ContentPart.to_block(odd, "openai")
    end

    test "anthropic: image and document sources, base64 and url", ctx do
      assert {:ok, blk} = ContentPart.to_block(ctx.img_data, "anthropic")
      assert blk == %{"type" => "image", "source" => %{"type" => "base64", "media_type" => "image/png", "data" => golden()}}

      assert {:ok, %{"type" => "image", "source" => %{"type" => "url", "url" => "https://x/y.png"}}} =
               ContentPart.to_block(ctx.img_url, "anthropic")

      assert {:ok, %{"type" => "document", "source" => %{"type" => "base64"}}} =
               ContentPart.to_block(ctx.file_data, "anthropic")

      assert {:ok, %{"type" => "document", "source" => %{"type" => "url"}}} =
               ContentPart.to_block(ctx.file_url, "anthropic")
    end

    test "anthropic × audio is the named refusal", ctx do
      assert {:unsupported, why} = ContentPart.to_block(ctx.audio_data, "anthropic")
      assert why =~ "no audio"
    end

    test "text maps to a text block on both styles, and an unknown type is unsupported" do
      for style <- ["openai", "anthropic"] do
        assert {:ok, %{"type" => "text", "text" => "hi"}} = ContentPart.to_block(ContentPart.text("hi"), style)
      end

      assert {:ok, %{"text" => ""}} = ContentPart.to_block(%ContentPart{type: "text"}, "openai")
      assert {:unsupported, _} = ContentPart.to_block(%ContentPart{type: "video", url: "https://x"}, "openai")
    end

    test "the positive allowlist rejects a block no style declares" do
      assert ContentPart.allowlist("anthropic") == ["text", "image", "document"]
      assert ContentPart.allowlist("openai") == ContentPart.allowlist("unknown-style")

      assert_raise Error, ~r/allowlist/, fn ->
        ContentPart.assert_allowed!(%{"type" => "input_image"}, "openai")
      end

      # an OpenAI-only block never rides an Anthropic request
      assert_raise Error, ~r/allowlist/, fn ->
        ContentPart.assert_allowed!(%{"type" => "image_url"}, "anthropic")
      end
    end

    test "provenance: an attached unsupported part errors, a derived one degrades", ctx do
      assert_raise Error, ~r/anthropic/, fn -> ContentPart.encode([ctx.audio_data], "anthropic") end

      assert [%{"type" => "text", "text" => text}] =
               ContentPart.encode([ctx.audio_data], "anthropic", provenance: :derived)

      assert text =~ "audio" and text =~ "audio/mpeg"
      refute text =~ Base.encode64("ID3")
    end

    test "on_unsupported_part overrides the provenance rule in both directions", ctx do
      assert_raise Error, fn ->
        ContentPart.encode([ctx.audio_data], "anthropic", provenance: :derived, on_unsupported: "error")
      end

      assert [%{"type" => "text"}] =
               ContentPart.encode([ctx.audio_data], "anthropic", provenance: :attached, on_unsupported: "text")

      # an unrecognised override falls back to the provenance rule
      assert_raise Error, fn ->
        ContentPart.encode([ctx.audio_data], "anthropic", provenance: :attached, on_unsupported: "shrug")
      end
    end

    test "encode preserves order and passes wire maps as happily as structs", ctx do
      blocks =
        ContentPart.encode(
          [ContentPart.text("a"), ContentPart.to_map(ctx.img_data), ContentPart.text("b")],
          "openai"
        )

      assert Enum.map(blocks, & &1["type"]) == ["text", "image_url", "text"]
    end
  end

  describe "part?/1 tells a part from a provider block" do
    test "provider blocks are not mistaken for parts" do
      assert ContentPart.part?(ContentPart.text("x"))
      assert ContentPart.part?(%{"type" => "text", "text" => "x"})
      assert ContentPart.part?(%{"type" => "image", "mimeType" => "image/png", "data" => "x"})
      assert ContentPart.part?(%{"type" => "file", "url" => "https://x/y.pdf"})

      refute ContentPart.part?(%{"type" => "image", "source" => %{"type" => "base64"}})
      refute ContentPart.part?(%{"type" => "file", "file" => %{"filename" => "a"}})
      refute ContentPart.part?(%{"type" => "image_url", "image_url" => %{}})
      refute ContentPart.part?(%{"type" => "tool_result"})
      refute ContentPart.part?("nope")
    end
  end
end
