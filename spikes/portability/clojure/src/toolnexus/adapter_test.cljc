;; Tests for toolnexus.adapter (SPEC §0.7).
;;
;; ONE .cljc suite, run unchanged on Clojure (JVM) and on cljgo. Pure data —
;; no filesystem, no network, no java.*.
(ns toolnexus.adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [toolnexus.adapter :as adapter]
            [toolnexus.tool :as tool]))

(def echo-schema
  {:type       "object"
   :properties {:text {:type "string"}}
   :required   ["text"]})

(defn- t [nm desc schema]
  (tool/tool {:name nm :description desc :input-schema schema
              :execute (fn [_args] (tool/success nm))}))

(def zed  (t "zed"  "Last alphabetically."  {:type "object"}))
(def echo (t "echo" "Echo the input back."  echo-schema))

(def tk (tool/toolkit [zed echo]))

;; ---------------------------------------------------------------------------
;; §0.7 — the three shapes
;; ---------------------------------------------------------------------------

(deftest openai-shape
  (is (= [{:type "function"
           :function {:name "echo" :description "Echo the input back." :parameters echo-schema}}
          {:type "function"
           :function {:name "zed" :description "Last alphabetically." :parameters {:type "object"}}}]
         (adapter/to-openai tk))))

(deftest anthropic-shape
  (is (= [{:name "echo" :description "Echo the input back." :input_schema echo-schema}
          {:name "zed"  :description "Last alphabetically."  :input_schema {:type "object"}}]
         (adapter/to-anthropic tk)))
  (testing "the wire key is snake_case input_schema, never :input-schema"
    (is (contains? (first (adapter/to-anthropic tk)) :input_schema))
    (is (not (contains? (first (adapter/to-anthropic tk)) :input-schema)))))

(deftest gemini-shape
  (is (= [{:functionDeclarations
           [{:name "echo" :description "Echo the input back." :parameters echo-schema}
            {:name "zed"  :description "Last alphabetically."  :parameters {:type "object"}}]}]
         (adapter/to-gemini tk)))
  (testing "always exactly one wrapper element"
    (is (= 1 (count (adapter/to-gemini tk))))))

(deftest gemini-empty-still-wraps
  ;; Every shipped port emits the wrapper with an empty declarations array, not
  ;; an empty outer array.
  (is (= [{:functionDeclarations []}] (adapter/to-gemini (tool/toolkit [])))))

(deftest openai-and-anthropic-empty-are-empty-vectors
  (is (= [] (adapter/to-openai (tool/toolkit []))))
  (is (= [] (adapter/to-anthropic (tool/toolkit [])))))

;; ---------------------------------------------------------------------------
;; input shapes + determinism
;; ---------------------------------------------------------------------------

(deftest accepts-a-toolkit-or-a-tool-seq
  (testing "a toolkit is name-sorted — map order is not a contract on any host"
    (is (= ["echo" "zed"] (mapv #(get-in % [:function :name]) (adapter/to-openai tk)))))
  (testing "an explicit seq keeps the caller's order"
    (is (= ["zed" "echo"] (mapv #(get-in % [:function :name]) (adapter/to-openai [zed echo])))))
  (testing "the same tools, either way, produce the same set of entries"
    (is (= (set (adapter/to-anthropic tk)) (set (adapter/to-anthropic [zed echo]))))))

(deftest order-is-stable-across-repeated-calls
  (is (= (adapter/to-openai tk) (adapter/to-openai tk)))
  (is (= (adapter/to-gemini tk) (adapter/to-gemini tk)))
  (is (= (adapter/to-anthropic tk) (adapter/to-anthropic tk))))

(deftest missing-description-becomes-empty-string
  ;; tool/tool defaults :description to "", so no adapter can emit nil.
  (let [bare (tool/tool {:name "bare" :execute (fn [_] (tool/success "x"))})]
    (is (= "" (get-in (first (adapter/to-openai [bare])) [:function :description])))
    (is (= "" (:description (first (adapter/to-anthropic [bare])))))
    (is (= {:type "object"} (get-in (first (adapter/to-gemini [bare]))
                                    [:functionDeclarations 0 :parameters])))))
