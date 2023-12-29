(ns abci.datopia.util
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer]))

(let [charset "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
      base    (count charset)]
  (defn b62-encode [^bytes b]
    (if (empty? b)
      ""
      (let [s          (StringBuilder.)
            zero-count (count (take-while zero? b))]
        (loop [i (BigInteger. 1 b)]
          (when-not (zero? i)
            (.append s (nth charset (mod i base)))
            (recur (quot i base))))
        (str (str/join (repeat zero-count "0")) (.reverse s)))))

  (defn b62-decode [s]
    (if (empty? s)
      (byte-array 0)
      (let [[zeros rest] (split-with (partial = \0) s)
            zero-count   (count zeros)
            rest-n       (reduce
                          #(+ (* %1 base) (str/index-of charset %2))
                          (bigint 0)
                          rest)
            rest-bytes   (-> rest-n biginteger .toByteArray)
            rest-offset  (if (zero? (aget rest-bytes 0)) 1 0)
            rest-count   (- (count rest-bytes) rest-offset)]
        (-> (ByteBuffer/allocate (+ zero-count rest-count))
            (.position zero-count)
            (.put rest-bytes rest-offset rest-count)
            .array)))))
