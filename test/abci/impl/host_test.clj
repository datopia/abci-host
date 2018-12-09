(ns abci.impl.host-test
  (:require [abci.impl.host  :as host]
            [abci.impl.codec :as codec]
            [manifold.stream :as s]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test
             :refer [defspec]])
  (:import [io.netty.buffer ByteBuf Unpooled]))

(def ^:private gen-buffers
  (gen/fmap
   (fn [lens]
     (for [len lens]
       (let [bs  ^bytes (codec/long->signed-varint64 len)
             buf (Unpooled/buffer (+ len (alength bs)))]
         (.writeBytes buf bs)
         (.writeBytes buf (byte-array len))
         [len buf])))
   (gen/not-empty (gen/vector gen/pos-int))))

(defn- split-buffer [^ByteBuf buf]
  (let [n  (.readableBytes buf)
        at (rand-int n)]
    (if (zero? at)
      [buf]
      (let [a (Unpooled/buffer at)
            b (Unpooled/buffer (- n at))]
        (.readBytes buf a ^int at)
        (.readBytes buf b)
        (.release buf)
        (into [a] (if (< (rand) 0.5)
                    [b]
                    (split-buffer b)))))))

(def ^:private gen-split-buffers
  (gen/fmap
   (fn [v]
     (for [[len buf] v]
       [len (split-buffer buf)]))
   gen-buffers))

(defn- bufs->out-seq [buffers]
  (let [src  (s/stream)
        dst  (s/stream (count buffers))
        conn (s/connect-via src (host/stream-splitter dst) dst)]
    @(s/put-all! src buffers)
    (s/close! src)
    (s/stream->seq dst)))

(defn- ref-counts [buffers]
  (into #{} (for [^ByteBuf b buffers] (.refCnt b))))

(defn- check-buffers [lengths buffers]
  (let [out (bufs->out-seq buffers)]
    (and (= lengths (map alength out))
         (= #{0}    (ref-counts buffers)))))

(defspec stream-splitter-aligned 200
  (prop/for-all [bs gen-buffers]
    (apply check-buffers (apply map vector bs))))

(defspec stream-splitter 200
  (prop/for-all [bs gen-split-buffers]
    (let [[lengths buffers] (apply map vector bs)]
      (check-buffers lengths (apply concat buffers)))))
