defmodule Toolnexus.AcpImageTest do
  @moduledoc "SPEC §8C ACP content parts: media in session/prompt and session/update."
  use ExUnit.Case, async: false

  alias Toolnexus.{Acp, Client}

  @moduletag timeout: 60_000
  @fixture_script Path.expand("support/fake_acp_server.exs", __DIR__)

  # A 1x1 transparent PNG (67 bytes).
  @png Base.decode16!(
         "89504E470D0A1A0A0000000D49484452000000010000000108060000001F15C4" <>
           "890000000D49444154789C63000100000500010D0A2DB40000000049454E44AE" <>
           "426082"
       )
  @b64 Base.encode64(@png)

  defp connect!(scenario) do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    {:ok, pid} = Acp.connect(["elixir", "-pa", jason_ebin, @fixture_script, scenario])
    on_exit(fn -> Acp.close(pid) end)
    pid
  end

  defp image_prompt_blocks(scenario) do
    pid = connect!(scenario)

    req = %{
      messages: [
        %{
          "role" => "user",
          "content" => [
            %{"type" => "text", "text" => "what is in this picture?"},
            %{"type" => "image_url", "image_url" => %{"url" => "data:image/png;base64," <> @b64}}
          ]
        }
      ]
    }

    blocks = Jason.decode!(Acp.generate(pid).(req).content)
    text = for(%{"type" => "text", "text" => t} <- blocks, do: t) |> Enum.join()
    {text, Enum.filter(blocks, &(&1["type"] == "image"))}
  end

  test "an image part travels as an ACP image block when the agent advertises it" do
    {text, images} = image_prompt_blocks("image_caps")
    assert [%{"mimeType" => "image/png", "data" => @b64}] = images
    assert text =~ "what is in this picture?"
    refute text =~ @b64
  end

  test "without promptCapabilities.image only the description is sent" do
    {text, images} = image_prompt_blocks("image_nocaps")
    assert images == []
    assert text =~ "image (image/png, 67 bytes)"
    refute text =~ @b64
  end

  @want_files [
    %{type: "image", mime_type: "image/png", data: @b64, from: "screenshot", call_id: "t1"},
    %{type: "image", mime_type: "image/png", data: @b64, from: "acp", call_id: ""}
  ]

  test "inbound image blocks become tool_result parts and files" do
    pid = connect!("image_out")
    me = self()

    answer =
      Acp.generate_stream(pid).(
        %{messages: [%{"role" => "user", "content" => "shoot"}]},
        &send(me, {:ev, &1})
      )

    assert answer.content == "here"
    assert answer.files == @want_files

    assert_received {:ev, %{type: "tool_result", output: out, parts: parts}}
    assert out == "image (image/png, 67 bytes)"
    assert [%{"type" => "image", "mimeType" => "image/png", "data" => @b64}] = parts
  end

  test "inbound files reach RunResult.files through run and stream" do
    pid = connect!("image_out")
    client = Client.create_in_process(model: "acp", generate_stream: Acp.generate_stream(pid))
    tk = Toolnexus.create_toolkit!(builtins: false)

    r = Client.run(client, "shoot", tk)
    assert r.text == "here"
    assert r.files == @want_files

    %{type: "done", result: done} =
      client |> Client.stream("shoot", tk) |> Enum.to_list() |> List.last()

    assert done.files == @want_files
    assert hd(done.files).call_id == "t1"
  end

  defp media_prompt_blocks(content) do
    pid = connect!("media_caps")
    req = %{messages: [%{"role" => "user", "content" => content}]}
    blocks = Jason.decode!(Acp.generate(pid).(req).content)
    text = for(%{"type" => "text", "text" => t} <- blocks, do: t) |> Enum.join()
    {text, Enum.reject(blocks, &(&1["type"] == "text"))}
  end

  test "an input_audio part travels as an ACP audio block (mp3 -> audio/mpeg)" do
    {text, media} =
      media_prompt_blocks([
        %{"type" => "input_audio", "input_audio" => %{"data" => @b64, "format" => "mp3"}}
      ])

    assert [%{"type" => "audio", "mimeType" => "audio/mpeg", "data" => @b64}] = media
    assert text =~ "audio (audio/mpeg, 67 bytes)"
    refute text =~ @b64
  end

  test "a file part travels as a resource block when embeddedContext is advertised" do
    {_text, media} =
      media_prompt_blocks([
        %{
          "type" => "file",
          "file" => %{
            "filename" => "a.pdf",
            "file_data" => "data:application/pdf;base64," <> @b64
          }
        },
        %{"type" => "file", "file" => %{"file_data" => "data:text/plain;base64," <> @b64}}
      ])

    assert [
             %{
               "type" => "resource",
               "resource" => %{
                 "uri" => "attachment:///a.pdf",
                 "mimeType" => "application/pdf",
                 "blob" => @b64
               }
             },
             %{"type" => "resource", "resource" => %{"uri" => "attachment:///attachment"}}
           ] = media
  end

  test "a URL-only image part gets no block and an empty-mime description" do
    {text, media} =
      media_prompt_blocks([
        %{"type" => "image_url", "image_url" => %{"url" => "https://example.com/cat.png"}},
        %{"type" => "image_url", "image_url" => %{"url" => "data:text/plain,hi"}}
      ])

    assert media == []
    assert text =~ "image (, 0 bytes)\nimage (, 0 bytes)"
  end
end
