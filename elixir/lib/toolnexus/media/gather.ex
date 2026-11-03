defmodule Toolnexus.Media.Gather do
  @moduledoc false
  # SPEC §7G.5: turn a provider result (bytes, base64, data: URL, http(s) URL, local
  # path) into a MediaFile according to `gather.mode` (url | download | inline).

  alias Toolnexus.Media.{Config, Job, MediaFile}

  @mime %{
    "png" => "image/png",
    "jpg" => "image/jpeg",
    "jpeg" => "image/jpeg",
    "webp" => "image/webp",
    "gif" => "image/gif",
    "mp4" => "video/mp4",
    "webm" => "video/webm",
    "mov" => "video/quicktime",
    "mp3" => "audio/mpeg",
    "wav" => "audio/wav",
    "ogg" => "audio/ogg",
    "m4a" => "audio/mp4",
    "flac" => "audio/flac"
  }

  @ext %{
    "image/png" => ".png",
    "image/jpeg" => ".jpg",
    "image/webp" => ".webp",
    "image/gif" => ".gif",
    "video/mp4" => ".mp4",
    "video/webm" => ".webm",
    "video/quicktime" => ".mov",
    "audio/mpeg" => ".mp3",
    "audio/wav" => ".wav",
    "audio/x-wav" => ".wav",
    "audio/wave" => ".wav",
    "audio/ogg" => ".ogg",
    "audio/mp4" => ".m4a",
    "audio/flac" => ".flac",
    "audio/aac" => ".aac",
    "text/plain" => ".txt"
  }

  @default_mime %{"image" => "image/png", "video" => "video/mp4", "audio" => "audio/mpeg"}

  @doc false
  # Extension table on a path or URL (query and fragment stripped, case-insensitive).
  def mime_of(path_or_url) do
    path = URI.parse(path_or_url).path || ""
    ext = path |> Path.extname() |> String.trim_leading(".") |> String.downcase()
    Map.get(@mime, ext)
  end

  @doc false
  def url_mime(url, kind), do: mime_of(url) || @default_mime[kind]

  @doc false
  def type_of_kind("video"), do: "file"
  def type_of_kind(kind), do: kind

  @doc false
  def type_of_mime("image/" <> _), do: "image"
  def type_of_mime("audio/" <> _), do: "audio"
  def type_of_mime(_), do: "file"

  defp mode(%Job{} = job), do: job.config.gather.mode || job.default_mode
  defp max_bytes(%Job{} = job), do: Config.positive(job.config.gather.max_bytes, 104_857_600)

  defp too_big(job),
    do:
      {:error,
       Job.fail(job, "#{job.verb}: file exceeds gather.maxBytes (#{max_bytes(job)} bytes)")}

  @doc false
  # shape: {:bytes, bin, mime} | {:base64, b64, mime} | {:data_url, s} | {:url, u, kind} | {:path, p}
  def collect(%Job{} = job, {:bytes, bin, mime}) when is_binary(bin) do
    cond do
      byte_size(bin) > max_bytes(job) ->
        too_big(job)

      mode(job) == "inline" ->
        {:ok, %MediaFile{type: type_of_mime(mime), mime_type: mime, data: Base.encode64(bin)}}

      true ->
        save(job, bin, mime)
    end
  end

  def collect(%Job{} = job, {:base64, b64, mime}) do
    case Base.decode64(b64, ignore: :whitespace, padding: false) do
      {:ok, bin} -> collect(job, {:bytes, bin, mime})
      :error -> {:error, Job.fail(job, "#{job.verb}: invalid base64 result")}
    end
  end

  def collect(%Job{} = job, {:data_url, "data:" <> rest}) do
    case String.split(rest, ",", parts: 2) do
      [meta, payload] ->
        [mime | params] = String.split(meta, ";")
        mime = if mime == "", do: "application/octet-stream", else: mime

        if "base64" in params,
          do: collect(job, {:base64, payload, mime}),
          else: collect(job, {:bytes, URI.decode(payload), mime})

      _ ->
        {:error, Job.fail(job, "#{job.verb}: invalid data URL result")}
    end
  end

  def collect(%Job{} = job, {:url, url, kind}) do
    case mode(job) do
      "url" ->
        {:ok, %MediaFile{type: type_of_kind(kind), mime_type: url_mime(url, kind), url: url}}

      m ->
        with {:ok, bin, mime} <- download(job, url, kind) do
          cond do
            byte_size(bin) > max_bytes(job) ->
              {:error,
               Job.fail(
                 job,
                 "#{job.verb}: download failed: file exceeds gather.maxBytes (#{max_bytes(job)} bytes)"
               )}

            m == "inline" ->
              {:ok,
               %MediaFile{type: type_of_kind(kind), mime_type: mime, data: Base.encode64(bin)}}

            true ->
              save(job, bin, mime, type_of_kind(kind), url)
          end
        end
    end
  end

  def collect(%Job{} = job, {:path, path}) do
    mime = mime_of(path) || "application/octet-stream"
    type = type_of_mime(mime)

    case mode(job) do
      "url" ->
        {:ok, %MediaFile{type: type, mime_type: mime, path: path, url: "file://" <> path}}

      m ->
        case File.read(path) do
          {:ok, bin} ->
            cond do
              byte_size(bin) > max_bytes(job) ->
                too_big(job)

              m == "inline" ->
                {:ok, %MediaFile{type: type, mime_type: mime, data: Base.encode64(bin)}}

              true ->
                with {:ok, f} <- save(job, bin, mime, type) do
                  if job.config.gather.keep == false, do: File.rm(path)
                  {:ok, f}
                end
            end

          {:error, r} ->
            {:error,
             Job.fail(job, ~s(#{job.verb}: cannot read "#{path}": #{:file.format_error(r)}))}
        end
    end
  end

  defp download(job, url, kind) do
    with {:ok, resp} <- Job.send(job, :get, url, headers: false, max_bytes: max_bytes(job)) do
      cond do
        resp.status not in 200..299 ->
          {:error, Job.fail(job, "#{job.verb}: download failed: HTTP #{resp.status}")}

        true ->
          ctype =
            (resp.headers["content-type"] || "")
            |> String.split(";")
            |> hd()
            |> String.trim()
            |> String.downcase()

          mime =
            if ctype in ["", "application/octet-stream"], do: url_mime(url, kind), else: ctype

          {:ok, to_bin(resp.body), mime}
      end
    end
  end

  defp to_bin(b) when is_binary(b), do: b
  defp to_bin(nil), do: ""
  defp to_bin(b), do: Jason.encode!(b)

  @doc false
  def save(job, bin, mime, type \\ nil, url \\ nil) do
    if byte_size(bin) > max_bytes(job) do
      too_big(job)
    else
      dir = Path.expand(job.config.gather.dir || Path.join(System.tmp_dir!(), "toolnexus-media"))
      File.mkdir_p!(dir)
      ext = url_ext(url) || Map.get(@ext, mime, ".bin")
      name = "#{job.name}-#{job.verb}-#{System.system_time(:millisecond)}-#{seq()}#{ext}"
      path = Path.join(dir, name)
      File.write!(path, bin)

      {:ok,
       %MediaFile{
         type: type || type_of_mime(mime),
         mime_type: mime,
         path: path,
         url: "file://" <> path
       }}
    end
  end

  # §7G.12 D22: a downloaded file keeps its URL path's own extension (last segment,
  # `.` + 1-5 ASCII letters/digits, lowercased) when it has one.
  defp url_ext(nil), do: nil

  defp url_ext(url) do
    last = (URI.parse(url).path || "") |> String.split("/") |> List.last()

    case Regex.run(~r/\.([A-Za-z0-9]{1,5})$/, last || "") do
      [_, e] -> "." <> String.downcase(e)
      _ -> nil
    end
  end

  # A per-process (OS process) counter from 1.
  defp seq do
    ref =
      case :persistent_term.get({__MODULE__, :seq}, nil) do
        nil ->
          r = :counters.new(1, [:atomics])
          :persistent_term.put({__MODULE__, :seq}, r)
          r

        r ->
          r
      end

    :counters.add(ref, 1, 1)
    :counters.get(ref, 1)
  end
end
