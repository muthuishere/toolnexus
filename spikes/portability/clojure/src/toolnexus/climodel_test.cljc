;; Gate: prove a CLI-backed `generate` (ADR 0032 envelope shape), built on
;; `koine.process/sh`, drives the REAL clojure/ `create-in-process-client`
;; end to end and returns a tool call — on WHICHEVER host loads this file
;; (JVM Clojure or cljgo; the source is identical, no reader conditionals).
(ns toolnexus.climodel-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]
            [toolnexus.climodel :as climodel]))

(defn- echo-tool []
  (native/native-tool
   {:name "echo_marker"
    :description "Echo the marker back."
    :input-schema {:type "object"
                   :properties {:received_marker {:type "number"}
                                :model {:type "string"}}
                   :required ["received_marker"]}
    :run (fn [args] (str "echoed " (:received_marker args)))}))

(defn- fakecli-path []
  ;; spikes/portability/fakecli/fakecli.py, relative to THIS ns's project root.
  "../fakecli/fakecli.py")

(deftest cli-backed-generate-returns-a-tool-call-end-to-end
  (let [tk (toolnexus/build {:builtins false :tools [(echo-tool)]})
        generate (climodel/make-cli-generate {:fakecli-path (fakecli-path)})
        c (client/create-in-process-client
           {:model "portability-test"
            :generate generate
            :request-params {:x_portability_marker_never_seen_by_adapter 777777}})
        r (client/run c "irrelevant prompt text" {:toolkit tk})]
    (testing "the run completes"
      (is (= "done" (:status r))))
    (testing "the marker made it through the subprocess and back"
      (is (re-find #"777777" (or (:text r) ""))))))
