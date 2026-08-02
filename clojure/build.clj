(ns build
  "Release build for the toolnexus Clojure port — a SOURCE-ONLY artifact.

  There is deliberately no `compile-clj` step, for the same reason koine has
  none: this tree's whole premise is that ONE `.cljc` source runs on Clojure
  (JVM) and on cljgo. AOT-compiling here would bake JVM class files into a
  library whose reason to exist is that it also loads on a non-JVM host.

  Usage:
    clojure -T:build jar                 ; target/toolnexus-<v>.jar + pom
    clojure -T:build install             ; into ~/.m2, for local consumers
    clojure -T:build deploy              ; to Clojars (needs the env vars below)

  Deploy credentials come from the ENVIRONMENT only — never a file in this repo:
    CLOJARS_USERNAME  the Clojars account name
    CLOJARS_PASSWORD  a Clojars DEPLOY TOKEN (not the account password)

  This namespace is reachable ONLY through the `:build` alias. It must never
  become a top-level dependency: `./deps-purity-check.sh` resolves the default
  transitive classpath and fails on anything beyond clojure + spec.alpha +
  core.specs.alpha + koine, and tools.build would break that gate — which is the
  same gate that keeps the cljgo half of the port alive."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; net.clojars.muthuishere, not io.github.muthuishere: Clojars pre-verifies
;; net.clojars.<user> for every account, while io.github.<user> needs a one-time
;; GitHub verification this group has not been through (koine hit a 403 "Group
;; 'io.github.muthuishere' doesn't exist" on deploy, 2026-07-30). Same group as
;; koine, and the artifact name `toolnexus` matches every other port.
(def lib 'net.clojars.muthuishere/toolnexus)
(def version "0.12.0")            ; kept in lockstep with the other six ports
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- pom-template [_]
  [[:description
    "Dynamic MCP servers, agent skills, native functions, HTTP endpoints and A2A agents as one uniform Tool interface, with a built-in tool-calling client loop."]
   [:url "https://github.com/muthuishere/toolnexus"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit"]]]
   [:scm
    [:url "https://github.com/muthuishere/toolnexus"]
    [:connection "scm:git:https://github.com/muthuishere/toolnexus.git"]
    [:developerConnection "scm:git:ssh://git@github.com/muthuishere/toolnexus.git"]
    [:tag (or (b/git-process {:git-args "rev-parse HEAD"}) version)]]
   [:developers
    [:developer [:name "Muthukumaran Navaneethakrishnan"]]]])

(defn clean [_] (b/delete {:path "target"}))

(defn jar
  "Build the source jar + pom.

  The tests live in `src/` alongside the library — cljgo compiles one source
  tree, so there is no separate `test/` root to leave behind. They are runnable
  programs, not library code, so `*_test.cljc`, `test_main.cljc` and
  `run_tests.cljc` are excluded from the jar rather than shipped to consumers."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (b/create-basis {:project "deps.edn"})
                :src-dirs  ["src"]
                :pom-data  (pom-template nil)})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir
               :ignores    [#".*_test\.cljc" #".*test_main\.cljc" #".*run_tests\.cljc"]})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "wrote" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     (b/create-basis {:project "deps.edn"})
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "installed" lib version "to ~/.m2"))

(defn deploy
  "Push to Clojars. Reads CLOJARS_USERNAME / CLOJARS_PASSWORD from the
  environment; the token value never appears in this file or in the output."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
