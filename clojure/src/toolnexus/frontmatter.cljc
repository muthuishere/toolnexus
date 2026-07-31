;; SKILL.md frontmatter — a DOCUMENTED SUBSET, deliberately not YAML.
;;
;; koine declined to own YAML and was right to: YAML touches no host, so by
;; koine's charter it is not a seam. JSON is in koine because two runtimes
;; DISAGREED on bytes and someone had to pick; nothing forces YAML the same way.
;;
;; So the port owns this, and owning it means being honest about it. This
;; namespace is called `frontmatter`, never `yaml`, so nobody arrives expecting
;; anchors — and it THROWS on any construct outside the subset from the first
;; commit. A parser that starts permissive and tightens later breaks its users
;; at exactly the moment it becomes correct.
;;
;; The subset, in full:
;;   - a leading `---` line and a closing `---` line
;;   - `key: value` pairs, one per line, at column 0
;;   - values are plain scalars, or single/double quoted scalars
;;   - `#` starts a comment when it begins a line
;;   - blank lines are ignored
;; Everything else — nesting, block scalars (`|`, `>`), anchors (`&`, `*`),
;; flow collections (`[`, `{`), tags (`!`) — is REJECTED with a named error.
(ns toolnexus.frontmatter
  (:require [clojure.string :as str]))

(def ^:private delimiter "---")

(defn- unsupported [line reason]
  (throw (ex-info (str "toolnexus.frontmatter: " reason
                       " — this is a documented subset, not YAML")
                  {:line line :reason reason})))

(defn- unquote-scalar [raw line]
  (let [v (str/trim raw)]
    (cond
      (str/blank? v) ""

      (and (str/starts-with? v "\"") (str/ends-with? v "\"") (> (count v) 1))
      (subs v 1 (dec (count v)))

      (and (str/starts-with? v "'") (str/ends-with? v "'") (> (count v) 1))
      (subs v 1 (dec (count v)))

      ;; A bare value opening a construct we do not implement must not be
      ;; silently taken as a string — that is the silent misparse.
      (contains? #{\| \> \& \* \[ \{ \!} (first v))
      (unsupported line (str "unsupported value construct '" (first v) "'"))

      :else v)))

(defn parse
  "Parse `text` into [meta body]. `meta` has keyword keys.
  No frontmatter at all => [{} text]. Anything outside the subset THROWS."
  [text]
  (let [lines (str/split-lines (str text))]
    (if-not (= delimiter (str/trim (str (first lines))))
      [{} (str text)]
      (loop [remaining (rest lines) acc {}]
        (cond
          (empty? remaining)
          (unsupported nil "frontmatter is not closed by a '---' line")

          (= delimiter (str/trim (first remaining)))
          [acc (str/join "\n" (rest remaining))]

          :else
          (let [line (first remaining)]
            (cond
              (str/blank? line) (recur (rest remaining) acc)
              (str/starts-with? (str/trim line) "#") (recur (rest remaining) acc)

              ;; Indentation is the tell for nesting, which we do not implement.
              (not= line (str/triml line))
              (unsupported line "indented (nested) frontmatter is not supported")

              :else
              (let [idx (str/index-of line ":")]
                (when-not idx (unsupported line "expected 'key: value'"))
                (let [k (str/trim (subs line 0 idx))
                      v (unquote-scalar (subs line (inc idx)) line)]
                  (when (str/blank? k) (unsupported line "empty key"))
                  (recur (rest remaining) (assoc acc (keyword k) v)))))))))))
