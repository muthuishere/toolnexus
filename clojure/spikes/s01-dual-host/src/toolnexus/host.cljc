(ns toolnexus.host
  "S1/S2: the host seam. Everything host-specific lives behind these fns;
  the rest of the port is pure Clojure and never sees a reader conditional."
  #?(:cljgo (:require [cljg.net.http :as http]
                      [cljg.io :as cio])))

(defn platform []
  #?(:clj "jvm" :cljgo "cljgo" :default "unknown"))

;; --- S3: HTTP -------------------------------------------------------------
(defn http-post
  "POST json-string to url with headers map. -> {:status :body}"
  [url headers body]
  #?(:clj
     (let [c   (java.net.http.HttpClient/newHttpClient)
           b   (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
           _   (doseq [[k v] headers] (.header b (name k) (str v)))
           req (-> b
                   (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                   .build)
           res (.send c req (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode res) :body (.body res)})
     :cljgo
     (let [r (http/post url {:headers headers :body body})]
       {:status (:status r) :body (:body r)})))

;; --- S4: subprocess, run-to-completion ------------------------------------
(defn sh
  "Run command vector, feed `in` on stdin. -> {:out :exit}"
  [command in]
  #?(:clj
     (let [pb (java.lang.ProcessBuilder. ^java.util.List (vec command))
           p  (.start pb)]
       (with-open [os (.getOutputStream p)]
         (.write os (.getBytes ^String in "UTF-8")))
       (let [out (slurp (.getInputStream p))]
         {:out out :exit (.waitFor p)}))
     :cljgo
     (let [r (cio/exec (vec command) {:in in})]
       {:out (:out r) :exit (:exit r)})))
