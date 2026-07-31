(ns toolnexus.frontmatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [toolnexus.frontmatter :as fm]))

(defn- rejected? [text]
  (try (fm/parse text) false
       (catch Throwable e (boolean (:reason (ex-data e))))))

(deftest parses-the-subset
  (is (= [{:name "hi" :description "there"} "body"]
         (fm/parse "---\nname: hi\ndescription: there\n---\nbody")))
  (testing "quotes are stripped, and a colon inside them survives"
    (is (= "x: y" (:description (first (fm/parse "---\ndescription: 'x: y'\n---\n"))))))
  (testing "comments and blank lines are ignored"
    (is (= {:name "a"} (first (fm/parse "---\n# a comment\n\nname: a\n---\n")))))
  (testing "an empty value is an empty string, not nil"
    (is (= "" (:description (first (fm/parse "---\nname: a\ndescription:\n---\n")))))))

(deftest no-frontmatter-is-not-an-error
  (is (= [{} "just a body"] (fm/parse "just a body"))))

(deftest body-is-everything-after-the-closing-delimiter
  (is (= "line1\nline2" (second (fm/parse "---\nname: a\n---\nline1\nline2")))))

(deftest throws-outside-the-subset
  (testing "this is the whole point: a silent misparse of a skill's metadata is
            the failure that costs you, so every unsupported construct is a
            named error from the FIRST version, not once it has users"
    (is (rejected? "---\nname: |\n---\nb")        "block scalar")
    (is (rejected? "---\nname: >\n---\nb")        "folded scalar")
    (is (rejected? "---\nname: &anchor\n---\nb")  "anchor")
    (is (rejected? "---\nname: *ref\n---\nb")     "alias")
    (is (rejected? "---\nname: [a, b]\n---\nb")   "flow sequence")
    (is (rejected? "---\nname: {a: b}\n---\nb")   "flow mapping")
    (is (rejected? "---\nname: !tag v\n---\nb")   "tag")
    (is (rejected? "---\nname: a\n  nested: b\n---\nb") "nesting")
    (is (rejected? "---\nnocolon\n---\nb")        "not a key: value pair")
    (is (rejected? "---\n: novalue\n---\nb")      "empty key")))

(deftest the-error-names-the-construct
  (let [e (try (fm/parse "---\nname: |\n---\nb") nil (catch Throwable e e))]
    (is (some? e))
    (is (re-find #"unsupported value construct" (:reason (ex-data e))))
    (testing "and it says it is not YAML, so nobody files a bug about anchors"
      (is (re-find #"not YAML" (ex-message e))))))

(deftest names-never-shadow-clojure-core
  (doseq [n (keys (ns-publics 'toolnexus.frontmatter))]
    (is (nil? (resolve (symbol "clojure.core" (name n))))
        (str "toolnexus.frontmatter/" n " shadows clojure.core/" n))))
