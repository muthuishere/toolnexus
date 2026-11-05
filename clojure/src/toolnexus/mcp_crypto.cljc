;; The few crypto/encoding primitives the MCP 2026-07-28 SERVER half needs
;; (SPEC §7C.2): SHA-256 (skill digests, PKCE), base64url, percent-decoding,
;; and RS256 — PKCS#1 v1.5 signatures over SHA-256 — for the default JWT
;; verifier of `mcp-server-auth` and the signing key of the dev authorization
;; server.
;;
;; WHY PLAIN ARITHMETIC. koine has no crypto seam, and the port is one `.cljc`
;; tree with no reader conditionals and no `java.*`. Both hosts do arbitrary
;; precision integers natively (`*'`, `mod`, `quot` promote to BigInt on the JVM
;; and to Go's math/big on cljgo), and RSA is nothing but modular
;; exponentiation, so RS256 is written here once for both hosts:
;;
;;   * verify — s^65537 mod n, compared against the EMSA-PKCS1-v1_5 encoding;
;;   * sign   — CRT exponentiation with the private factors;
;;   * keygen — two random 1024-bit probable primes (trial division, then 24
;;     Miller-Rabin rounds), e = 65537.
;;
;; None of this is constant-time. That is acceptable for its two uses: VERIFY
;; handles only public values, and SIGNING exists for the DEV-ONLY
;; authorization server (`toolnexus.mcp-dev-auth`), which SPEC §7C.2 forbids in
;; production. Do not reuse `rsa-sign` for a production key.
;;
;; Randomness comes from `random-uuid` — a CSPRNG on both hosts
;; (java.util.UUID/randomUUID is SecureRandom; cljgo's is crypto/rand). Each
;; UUID contributes its 30 random hex digits (the version and variant nibbles
;; are dropped).
(ns toolnexus.mcp-crypto
  (:require [clojure.string :as str]
            [koine.codec :as codec]))

;; ---------------------------------------------------------------------------
;; bytes
;; ---------------------------------------------------------------------------

(defn utf8-vals
  "A string's UTF-8 bytes as unsigned values (0-255)."
  [s]
  (if (= "" (str s)) [] (codec/b64-decode-vals (codec/encode (str s)))))

(defn vals->utf8
  "Unsigned byte values back to a string (UTF-8)."
  [vs]
  (if (empty? vs) "" (codec/decode (codec/b64-encode-vals vs))))

(defn valid-utf8?
  "True when the unsigned byte values are well-formed UTF-8 (RFC 3629: no
  overlongs, no surrogates, nothing above U+10FFFF)."
  [vs]
  (let [v (vec vs) n (count v)
        cont? (fn [i] (and (< i n) (= 0x80 (bit-and (nth v i) 0xC0))))]
    (loop [i 0]
      (if (>= i n)
        true
        (let [b (nth v i)]
          (cond
            (< b 0x80) (recur (inc i))
            (and (>= b 0xC2) (<= b 0xDF) (cont? (+ i 1))) (recur (+ i 2))
            (and (>= b 0xE0) (<= b 0xEF) (cont? (+ i 1)) (cont? (+ i 2))
                 (let [b1 (nth v (+ i 1))]
                   (cond (= b 0xE0) (>= b1 0xA0)
                         (= b 0xED) (< b1 0xA0)
                         :else true)))
            (recur (+ i 3))
            (and (>= b 0xF0) (<= b 0xF4) (cont? (+ i 1)) (cont? (+ i 2)) (cont? (+ i 3))
                 (let [b1 (nth v (+ i 1))]
                   (cond (= b 0xF0) (>= b1 0x90)
                         (= b 0xF4) (< b1 0x90)
                         :else true)))
            (recur (+ i 4))
            :else false))))))

(def ^:private hex-chars "0123456789abcdef")

(defn hex "Lower-case hex of unsigned byte values." [vs]
  (apply str (mapcat (fn [b] [(nth hex-chars (quot b 16)) (nth hex-chars (mod b 16))]) vs)))

;; ---------------------------------------------------------------------------
;; SHA-256 (FIPS 180-4)
;; ---------------------------------------------------------------------------

(def ^:private k256
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
   0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
   0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
   0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
   0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
   0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

(def ^:private m32 0xffffffff)

(defn- rotr [x n] (bit-and m32 (bit-or (unsigned-bit-shift-right x n) (bit-shift-left x (- 32 n)))))
(defn- add32 [& xs] (bit-and m32 (reduce + 0 xs)))

(defn- sha256-block [h w16]
  (let [w (loop [w (vec w16) i 16]
            (if (= i 64)
              w
              (let [a (nth w (- i 15)) b (nth w (- i 2))
                    s0 (bit-xor (rotr a 7) (rotr a 18) (unsigned-bit-shift-right a 3))
                    s1 (bit-xor (rotr b 17) (rotr b 19) (unsigned-bit-shift-right b 10))]
                (recur (conj w (add32 (nth w (- i 16)) s0 (nth w (- i 7)) s1)) (inc i)))))
        out (loop [i 0 [a b c d e f g hh] h]
              (if (= i 64)
                [a b c d e f g hh]
                (let [S1 (bit-xor (rotr e 6) (rotr e 11) (rotr e 25))
                      ch (bit-xor (bit-and e f) (bit-and (bit-xor e m32) g))
                      t1 (add32 hh S1 ch (nth k256 i) (nth w i))
                      S0 (bit-xor (rotr a 2) (rotr a 13) (rotr a 22))
                      mj (bit-xor (bit-and a b) (bit-and a c) (bit-and b c))
                      t2 (add32 S0 mj)]
                  (recur (inc i) [(add32 t1 t2) a b c (add32 d t1) e f g]))))]
    (mapv add32 h out)))

(defn sha256
  "SHA-256 of a string (UTF-8) or a seq of unsigned byte values; 32 unsigned
  byte values."
  [x]
  (let [bs   (vec (if (string? x) (utf8-vals x) x))
        bits (* 8 (count bs))
        padded (-> bs (conj 0x80)
                   (into (repeat (mod (- 56 (mod (inc (count bs)) 64)) 64) 0))
                   (into (map (fn [i] (bit-and 0xff (unsigned-bit-shift-right bits (* 8 (- 7 i)))))
                              (range 8))))
        h (reduce (fn [h block]
                    (sha256-block h (map (fn [j]
                                           (let [o (* 4 j)]
                                             (bit-or (bit-shift-left (nth block o) 24)
                                                     (bit-shift-left (nth block (+ o 1)) 16)
                                                     (bit-shift-left (nth block (+ o 2)) 8)
                                                     (nth block (+ o 3)))))
                                         (range 16))))
                  [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a 0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19]
                  (partition 64 (vec padded)))]
    (vec (mapcat (fn [x] [(bit-and 0xff (unsigned-bit-shift-right x 24))
                          (bit-and 0xff (unsigned-bit-shift-right x 16))
                          (bit-and 0xff (unsigned-bit-shift-right x 8))
                          (bit-and 0xff x)])
                 h))))

;; ---------------------------------------------------------------------------
;; base64 / base64url
;; ---------------------------------------------------------------------------

(defn b64url
  "base64url without padding (RFC 4648 §5) of byte values or a string."
  [x]
  (-> (codec/b64-encode-vals (if (string? x) (utf8-vals x) x))
      (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))

(defn b64url-decode-vals
  "base64url (padding optional) -> unsigned byte values. Throws on a character
  outside the alphabet."
  [s]
  (let [s (str s)]
    (when-not (re-matches #"[A-Za-z0-9_-]*" s)
      (throw (ex-info "bad base64url" {})))
    (when (= 1 (mod (count s) 4)) (throw (ex-info "bad base64url" {})))
    (let [std (-> s (str/replace "-" "+") (str/replace "_" "/"))
          pad (apply str (repeat (mod (- 4 (mod (count std) 4)) 4) "="))]
      (vec (codec/b64-decode-vals (str std pad))))))

(defn b64-std-decode
  "Standard base64 (RFC 4648 §4, padded) -> string, or nil when malformed."
  [s]
  (let [s (str s)]
    (when (and (re-matches #"[A-Za-z0-9+/]*={0,2}" s) (zero? (mod (count s) 4)))
      (try (let [vs (codec/b64-decode-vals s)]
             (when (valid-utf8? vs) (vals->utf8 vs)))
           (catch Throwable _ nil)))))

;; ---------------------------------------------------------------------------
;; percent-encoding
;; ---------------------------------------------------------------------------

(def ^:private unreserved
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"))

(defn url-encode
  "Percent-encode every byte outside RFC 3986's unreserved set (UTF-8)."
  [s]
  (apply str (map (fn [b]
                    (let [c (char b)]
                      (if (and (< b 128) (contains? unreserved c))
                        (str c)
                        (str "%" (str/upper-case (hex [b]))))))
                  (utf8-vals s))))

(defn url-decode
  "Percent-decode ONCE; `plus?` also maps `+` to a space (forms). Throws on a
  malformed escape or a result that is not UTF-8."
  ([s] (url-decode s false))
  ([s plus?]
   (let [s (str s) n (count s)]
     (loop [i 0 out []]
       (if (>= i n)
         (if (valid-utf8? out) (vals->utf8 out) (throw (ex-info "bad percent-encoding" {})))
         (let [c (nth s i)]
           (cond
             (= c \%)
             (let [hx (when (<= (+ i 3) n) (str/lower-case (subs s (inc i) (+ i 3))))
                   hi (when hx (str/index-of hex-chars (nth hx 0)))
                   lo (when hx (str/index-of hex-chars (nth hx 1)))]
               (when-not (and hi lo) (throw (ex-info "bad percent-encoding" {})))
               (recur (+ i 3) (conj out (+ (* 16 hi) lo))))
             (and plus? (= c \+)) (recur (inc i) (conj out 32))
             :else (recur (inc i) (into out (utf8-vals (str c)))))))))))

(defn form-encode
  "application/x-www-form-urlencoded of a map, keys sorted (deterministic)."
  [m]
  (str/join "&" (for [k (sort (map str (keys m)))
                      :let [v (get m k)]
                      :when (some? v)]
                  (str (url-encode k) "=" (url-encode (str v))))))

(defn parse-query
  "A query / form string as {name first-value}. Malformed escapes keep the raw text."
  [q]
  (reduce (fn [acc part]
            (if (str/blank? part)
              acc
              (let [[k v] (str/split part #"=" 2)
                    k (try (url-decode k true) (catch Throwable _ k))]
                (if (contains? acc k)
                  acc
                  (assoc acc k (try (url-decode (or v "") true) (catch Throwable _ (or v ""))))))))
          {} (str/split (str q) #"&")))

;; ---------------------------------------------------------------------------
;; randomness
;; ---------------------------------------------------------------------------

(defn- rand-hex
  "n random lower-case hex digits."
  [n]
  (let [one (fn [] (let [h (str/replace (str (random-uuid)) "-" "")]
                     ;; drop the version (index 12) and variant (index 16) nibbles
                     (str (subs h 0 12) (subs h 13 16) (subs h 17))))]
    (subs (apply str (repeatedly (inc (quot n 30)) one)) 0 n)))

(defn rand-token
  "n random url-safe characters (hex) from the host CSPRNG."
  [n]
  (rand-hex n))

;; ---------------------------------------------------------------------------
;; big integers
;; ---------------------------------------------------------------------------

(defn vals->int
  "Big-endian unsigned byte values -> a non-negative integer."
  [vs]
  (reduce (fn [acc b] (+' (*' acc 256) b)) 0 vs))

(defn int->vals
  "A non-negative integer -> big-endian unsigned byte values, left-padded to
  `len` (minimal length when `len` is nil)."
  ([n] (int->vals n nil))
  ([n len]
   (let [vs (loop [n n out ()]
              (if (zero? n) (vec out) (recur (quot n 256) (conj out (int (rem n 256))))))
         vs (if (empty? vs) [0] vs)]
     (if (and len (< (count vs) len))
       (into (vec (repeat (- len (count vs)) 0)) vs)
       vs))))

(defn- hex->int [s]
  (reduce (fn [acc c] (+' (*' acc 16) (str/index-of hex-chars c))) 0 (str/lower-case s)))

(defn- bits-of
  "The exponent's bits, most significant first."
  [e]
  (mapcat (fn [b] (map (fn [i] (bit-and 1 (bit-shift-right b (- 7 i)))) (range 8)))
          (int->vals e)))

(defn mod-pow
  "base^e mod m (square-and-multiply, left to right)."
  [base e m]
  (let [base (mod base m)]
    (reduce (fn [acc bit]
              (let [sq (mod (*' acc acc) m)]
                (if (= 1 bit) (mod (*' sq base) m) sq)))
            1 (bits-of e))))

(defn- egcd-inverse
  "a^-1 mod m (a and m coprime)."
  [a m]
  (loop [r0 m r1 (mod a m) t0 0 t1 1]
    (if (zero? r1)
      (mod t0 m)
      (let [q (quot r0 r1)]
        (recur r1 (-' r0 (*' q r1)) t1 (-' t0 (*' q t1)))))))

(defn- byte-len [n] (count (int->vals n)))

;; ---------------------------------------------------------------------------
;; primes
;; ---------------------------------------------------------------------------

(def ^:private small-primes
  (loop [n 3 ps [2]]
    (if (> n 2000)
      ps
      (recur (+ n 2) (if (some (fn [p] (zero? (rem n p))) (take-while (fn [p] (<= (* p p) n)) ps))
                       ps (conj ps n))))))

(defn- random-below
  "A random integer in [2, n-2]."
  [n]
  (+' 2 (mod (hex->int (rand-hex (+ 16 (* 2 (byte-len n))))) (-' n 3))))

(defn probable-prime?
  "Trial division by the primes below 2000, then `rounds` Miller-Rabin rounds."
  [n rounds]
  (cond
    (< n 2) false
    (some (fn [p] (= n p)) small-primes) true
    (some (fn [p] (zero? (rem n p))) small-primes) false
    :else
    (let [n-1 (-' n 1)
          [d s] (loop [d n-1 s 0] (if (even? d) (recur (quot d 2) (inc s)) [d s]))]
      (every? (fn [_]
                (let [x (mod-pow (random-below n) d n)]
                  (or (= x 1) (= x n-1)
                      (loop [x x i 1]
                        (if (>= i s)
                          false
                          (let [x (mod (*' x x) n)]
                            (cond (= x n-1) true
                                  (= x 1)   false
                                  :else     (recur x (inc i)))))))))
              (range rounds)))))

(defn- random-prime
  "A random `bits`-bit probable prime with its top two bits set (so p*q has
  exactly 2·bits bits) and p ≢ 1 mod e."
  [bits e]
  (let [top (*' 3 (reduce (fn [a _] (*' a 2)) 1 (range (- bits 2))))
        span (reduce (fn [a _] (*' a 2)) 1 (range (- bits 2)))]
    (loop []
      (let [r (mod (hex->int (rand-hex (quot bits 4))) span)
            c (+' top r)
            c (if (even? c) (+' c 1) c)]
        (if (and (not= 1 (mod c e)) (probable-prime? c 24))
          c
          (recur))))))

;; ---------------------------------------------------------------------------
;; RSA / RS256
;; ---------------------------------------------------------------------------

(def ^:private e65537 65537)

(defn rsa-generate
  "A fresh RSA key pair: {:n :e :d :p :q :dp :dq :qinv}. `bits` default 2048."
  ([] (rsa-generate 2048))
  ([bits]
   (let [half (quot bits 2)]
     (loop []
       (let [p (random-prime half e65537)
             q (random-prime half e65537)]
         (if (= p q)
           (recur)
           (let [n   (*' p q)
                 phi (*' (-' p 1) (-' q 1))
                 d   (egcd-inverse e65537 phi)]
             {:n n :e e65537 :d d :p p :q q
              :dp (mod d (-' p 1)) :dq (mod d (-' q 1)) :qinv (egcd-inverse q p)})))))))

(def ^:private sha256-digest-info
  [0x30 0x31 0x30 0x0d 0x06 0x09 0x60 0x86 0x48 0x01 0x65 0x03 0x04 0x02 0x01 0x05 0x00 0x04 0x20])

(defn- emsa-pkcs1 [msg k]
  (let [t (into sha256-digest-info (sha256 msg))]
    (into (into [0x00 0x01] (repeat (- k (count t) 3) 0xff)) (into [0x00] t))))

(defn rsa-sign
  "RS256 (RSASSA-PKCS1-v1_5 + SHA-256) of string `msg`; signature byte values.
  NOT constant-time — dev/test keys only (see the namespace comment)."
  [{:keys [n p q dp dq qinv]} msg]
  (let [k  (byte-len n)
        m  (vals->int (emsa-pkcs1 msg k))
        m1 (mod-pow m dp p)
        m2 (mod-pow m dq q)
        h  (mod (*' qinv (-' m1 m2)) p)
        s  (+' m2 (*' h q))]
    (int->vals s k)))

(defn rsa-verify?
  "True when `sig` (byte values) is a valid RS256 signature of string `msg`
  under the public key {:n :e}."
  [{:keys [n e]} msg sig]
  (let [k (byte-len n)
        s (vals->int sig)]
    (and (= k (count sig))
         (< s n)
         (= (int->vals (mod-pow s e n) k) (emsa-pkcs1 msg k)))))

(defn rsa-jwk
  "The public JWK members {\"kty\" \"n\" \"e\"} of a key."
  [{:keys [n e]}]
  {"kty" "RSA" "n" (b64url (int->vals n)) "e" (b64url (int->vals e))})

(defn jwk->public
  "A JWK's {\"n\" \"e\"} (base64url) -> {:n :e}."
  [jwk]
  {:n (vals->int (b64url-decode-vals (get jwk "n")))
   :e (vals->int (b64url-decode-vals (get jwk "e")))})
