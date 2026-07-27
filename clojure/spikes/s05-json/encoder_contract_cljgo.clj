(require 'cljhost.json)
(alias 'json 'cljhost.json)
(doseq [[label got want]
        [["sorted keys"  (json/write-str {"b" 1 "a" 2 "c" 3}) "{\"a\":2,\"b\":1,\"c\":3}"]
         ["float keeps"  (json/write-str {:f 1.0 :h 100.0})   "{\"f\":1.0,\"h\":100.0}"]
         ["html raw"     (json/write-str "a<b>c&d")           "\"a<b>c&d\""]
         ["unicode raw"  (json/write-str "café ☃")            "\"café ☃\""]
         ["escapes"      (json/write-str "a\tb\nc")           "\"a\\tb\\nc\""]
         ["nested"       (json/write-str {:a {:b [1 2.0 nil true]}}) "{\"a\":{\"b\":[1,2.0,null,true]}}"]
         ["ctrl char"    (json/write-str (str (char 0)))      "\"\\u0000\""]]]
  (println (format "%-13s %s" label (if (= got want) "PASS" (str "FAIL got=" got " want=" want)))))
