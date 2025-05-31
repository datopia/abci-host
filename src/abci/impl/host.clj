(ns abci.impl.host
  (:require [manifold.deferred    :as d]
            [manifold.stream      :as s]
            [abci.impl.codec      :as codec])
  (:import [io.netty.buffer ByteBuf]))

(defn- buf->stream [^ByteBuf buf dst]
  (d/loop []
    (when (.isReadable buf)
      (.markReaderIndex buf)
      (if-let [length (codec/read-varint64 buf)]
        (if (.isReadable buf length)
          (let [out (byte-array length)]
            (.readBytes buf out)
            (-> (s/put! dst out)
                (d/chain
                 #(if %
                    (d/recur)
                    false))))
          (do
            (.resetReaderIndex buf)
            ::partial))
        (do
          (.resetReaderIndex buf)
          ::partial)))))

(defn- concat-buffers [^ByteBuf a ^ByteBuf b]
  (when-not (.isWritable a (.readableBytes b))
    (.capacity a (+ (.capacity a) (.readableBytes b))))
  (.readBytes b a)
  a)

(defn- buf-or-cat-buf [^ByteBuf buf cat-buf]
  (if-let [catted (some-> cat-buf (concat-buffers buf))]
    (do
      (.release buf)
      catted)
    buf))

(defn stream-splitter [dst]
  (let [cat-buf (volatile! nil)]
    (fn [^ByteBuf buf]
      (let [buf (buf-or-cat-buf buf @cat-buf)]
        (d/let-flow [ret (buf->stream buf dst)]
          (if (false? ret)
            ret
            (do
              (if (identical? ::partial ret)
                (vreset! cat-buf buf)
                (do
                  (.release buf)
                  (vreset! cat-buf nil)))
              true)))))))
