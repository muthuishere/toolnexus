(ns mcp2026.skills-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mcp2026.core :as mcp]
            [mcp2026.wire :as w]))

(deftest parse-skill-uri
  (doseq [[u want] {"skill://hello-world/SKILL.md" ["hello-world" "SKILL.md"]
                    "skill://a/refs/doc.md" ["a" "refs/doc.md"]
                    "skill://a/with%20space.md" ["a" "with space.md"]
                    "skill://a/..dots-in-name..x.md" ["a" "..dots-in-name..x.md"]}]
    (is (= want (mcp/parse-skill-uri u)) u))
  (doseq [u ["skill://a/../b" "skill://a/%2e%2e/b" "skill://a/%2E%2E%2Fetc" "skill://%2e%2e/x"
             "skill://a/..%2F..%2Fetc%2Fpasswd" "skill://a/%252e%252e/x" "skill://a/%252F" "skill://a/x%5C..%5Cy"
             "skill://a//etc" "skill:///etc/passwd" "skill://a/%2Fetc" "skill://a/%00" "skill://a/C:/x"
             "skill://a/." "skill://a" "skill://a/%zz" "file:///etc/passwd"]]
    (is (thrown? Exception (mcp/parse-skill-uri u)) (str u " accepted"))))

(defn- tmp-dir [] (.toFile (java.nio.file.Files/createTempDirectory "mcp2026" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest server-rejects-traversal-uris
  (let [ts (mcp/serve! (mcp/fixture-server)) c (mcp/client (str (:url ts) "/mcp"))]
    (try
      (doseq [m ["resources/read" "skills/get"]]
        (is (= mcp/code-invalid-params
               (try (mcp/call c m {"uri" "skill://hello-world/%2e%2e/%2e%2e/fixture.json"}) nil
                    (catch Exception e (mcp/rpc-code e))))
            m))
      (finally ((:stop ts))))))

(deftest download-skill
  (let [ts (mcp/serve! (mcp/fixture-server)) dir (tmp-dir)]
    (try
      (mcp/download-skill (mcp/client (str (:url ts) "/mcp")) "skill://hello-world/SKILL.md" dir)
      (is (pos? (count (slurp (io/file dir "hello-world" "SKILL.md")))))
      (finally ((:stop ts))))))

(deftest download-skill-refuses-hostile-resource
  ;; a hostile server lists a resource that decodes to ../../pwned: nothing may be written
  (doseq [evil ["skill://hello-world/%2e%2e%2f%2e%2e%2fpwned" "skill://hello-world/..%2F..%2Fpwned"
                "skill://hello-world/%252e%252e/pwned" "skill://other-skill/pwned"]]
    (let [ts (w/serve! (fn [req]
                         (let [m (w/read-json (:body req))
                               res (if (= "skills/get" (get m "method"))
                                     {"skill" {"uri" "skill://hello-world/SKILL.md"
                                               "resources" [{"uri" "skill://hello-world/SKILL.md"} {"uri" evil}]}}
                                     {"contents" [{"uri" evil "text" "pwned"}]})]
                           {:status 200 :headers {"Content-Type" "application/json"}
                            :body (w/write-json {"jsonrpc" "2.0" "id" (get m "id") "result" res})})))
          root (tmp-dir)]
      (try
        (is (thrown? Exception (mcp/download-skill (mcp/client (str (:url ts) "/mcp")) "skill://hello-world/SKILL.md"
                                                   (io/file root "a" "b")))
            evil)
        (is (empty? (filter #(.isFile %) (file-seq root))) (str evil ": wrote a file"))
        (finally ((:stop ts)))))))
