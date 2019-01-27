(ns abci.example.impl.util
  (:require [clojure.edn        :as edn]
            [sputter.state.trie :as trie])
  (:import [java.io
            PushbackReader
            InputStreamReader
            ByteArrayInputStream]))

(defn bytes->edn
  "Read the given byte array as an EDN data structure, or `::invalid` if the
  byte array does not represent valid EDN."
  [bs]
  (with-open [reader (-> bs
                         ByteArrayInputStream.
                         InputStreamReader.
                         PushbackReader.)]
    (try
      (edn/read reader)
      (catch Exception _
        ::invalid))))

(defn edn-value
  "Pass `trie`'s value for `k` to `bytes->edn`.  Return `nil` if `k` is not
  present.  Explicit nils and missing values are indistinguishable."
  [trie k]
  (some-> trie (trie/search k) bytes->edn))

(defn key->str
  "`:a/b` -> `\"a/b\"`
   `:a`   -> `\"a\""
  [k]
  (let [ns (namespace k)]
    (cond->> (name k) ns (str ns "/"))))

(defn valid-tx?
  "Do the bytes in `tx` represent a valid transaction?"
  [tx]
  (let [tx (bytes->edn tx)]
    (and (map?      tx)
         (not-empty tx)
         (every?    keyword? (keys tx)))))
