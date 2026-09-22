;; Spike for issue #86 (Clojure): the issue claims `:toolkit` is mandatory in
;; `(run client prompt {:toolkit tk})`. It is a map key, so test whether
;; omitting it actually breaks anything.
(require '[toolnexus.client :as c])

(let [base (System/getenv "SPIKE86_BASE")
      client (c/create-client {:base-url base :style "openai" :model "mock" :api-key "not-a-real-key"})]
  ;; 1. No :toolkit key at all.
  (try
    (let [r (c/run client "write me a haiku" {})]
      (println "clj: no-toolkit run OK, text=" (pr-str (:text r))))
    (catch Throwable e (println "clj: no-toolkit run FAILED:" (.getName (class e)) (.getMessage e))))
  ;; 2. Explicit nil.
  (try
    (let [r (c/run client "write me a haiku" {:toolkit nil})]
      (println "clj: nil-toolkit run OK, text=" (pr-str (:text r))))
    (catch Throwable e (println "clj: nil-toolkit run FAILED:" (.getName (class e)) (.getMessage e))))
  ;; 3. ask, same question.
  (try
    (let [r (c/ask client "write me a haiku" {})]
      (println "clj: no-toolkit ask OK, text=" (pr-str (:text r))))
    (catch Throwable e (println "clj: no-toolkit ask FAILED:" (.getName (class e)) (.getMessage e)))))
