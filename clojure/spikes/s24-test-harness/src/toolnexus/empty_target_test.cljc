;; S24 — the NEGATIVE control.
;;
;; A namespace that requires clojure.test and defines NO deftest. Pointing the
;; counting gate at this must produce a RED verdict. Without this file the gate
;; is untested and we are back where we started: a green run that measured
;; nothing.
;;
;; Do not add a test here. That is the point.
(ns toolnexus.empty-target-test
  (:require [clojure.test]))

(def why "no deftest lives here on purpose — see toolnexus.harness/gate")
