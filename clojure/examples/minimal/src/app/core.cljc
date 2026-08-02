;; The SHARED file — one `.cljc`, with koine as its only dependency.
;;
;; No reader conditional, no `java.*`, no Go interop. This file does not know
;; which runtime it is running on and does not need to: everything that touches
;; the host goes through koine, and koine is the only place a reader conditional
;; lives.
(ns app.core
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.host :as host]
            [koine.process :as proc]))

(defn greet
  "Pure Clojure. Works on any runtime; koine not involved."
  [who]
  (str "hello, " who))

(defn snapshot
  "Everything below touches the HOST — JSON, environment, the filesystem, a
  real subprocess. That is precisely what a `.cljc` normally cannot do
  portably, because the JVM reaches the host through Java interop and cljgo
  through Go interop. koine is the seam that makes it one file."
  [tmp-path]
  (fs/write-file tmp-path "written by app.core\n")
  {:runtime  (name host/id)
   :greeting (greet "clojure")
   ;; koine's encoder sorts keys and keeps 1.0 a float — the two choices that
   ;; let two runtimes emit the same bytes.
   :json     (json/write-str {:b 1 :a 2 :pi 1.0})
   :parsed   (json/read-str "{\"x\":[1,2,3]}")
   :env-home (boolean (env/get-env "HOME"))
   :file     {:written (fs/exists? tmp-path)
              :content (fs/read-file tmp-path)}
   :shell    (str/trim (:out (proc/sh ["echo" "from a real subprocess"])))})

(defn report
  "One line of JSON. Sorted keys, so two runtimes can be diffed byte for byte."
  [tmp-path]
  (json/write-str (snapshot tmp-path)))
