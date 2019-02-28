(ns abci.host-test
  (:require [abci.host            :as host]
            [abci.impl.codec      :as codec]
            [abci.host.middleware :as mw]
            [manifold.stream      :as s]
            [manifold.deferred    :as d]
            [stickler.codec       :as stickler.codec]
            [clojure.test
             :refer [deftest is]])
  (:import [io.netty.buffer ByteBuf Unpooled]))

(def ^:private drained? (partial identical? ::drained?))

(defn- stub-start-server
  "Return a vector of `[stream start-fn]`, where `start-fn` returns a
  `java.io.Closeable` per. Aleph's `start-server`.  `stream` will receive
  a single message when `start-fn` is invoked, and will be closed along
  with `start-fn`'s return value."
  [& [{s :stream}]]
  (let [s (or s (s/stream 1))]
    [s (fn start-server-stub [handler opts]
         @(s/put! s [handler opts])
         (reify java.io.Closeable
           (close [_]
             (s/close! s)
             nil)))]))

(defn- bytes->message-buf [^bytes bs]
  (let [prefix  ^bytes (codec/long->signed-varint64 (alength bs))
        buf     (Unpooled/buffer (+ (alength prefix) (alength bs)))]
    (.writeBytes buf prefix)
    (.writeBytes buf bs)
    buf))

(defn- invalid-message []
  (bytes->message-buf (byte-array [0])))

(let [msg {:stickler/msg          :abci/Request
           :stickler.one-of/value :flush
           :flush                 {:stickler/msg :abci/RequestFlush}}]
  (defn- valid-message []
    (-> (stickler.codec/encode-bytes host/schema msg)
        bytes->message-buf)))

(defn- seq->stream [xs & [{close? :close?}]]
  (let [s (s/stream (* 2 (count xs)))]
    @(s/put-all! s xs)
    (cond-> s close? s/close!)
    s))

(defn- host-start
  ([msg-seq]
   (host-start msg-seq nil))

  ([msg-seq start-opts]
   (host-start msg-seq nil start-opts))

  ([msg-seq handler start-opts]
   (let [[stub-stream stub] (stub-start-server)
         handler-invoked    (volatile! false)]
     (binding [host/*start-server* stub]
       (host/start
        (fn handler-wrapper [req]
          (vreset! handler-invoked req)
          (when handler
            (handler req)))
        start-opts))
     (let [[wrapped-handler _] @(s/take! stub-stream ::drained?)
           msgs                (seq->stream msg-seq)]
       (wrapped-handler msgs nil)
       {:msgs             msgs
        :stub-stream      stub-stream
        :handler-invoked? #(deref handler-invoked)}))))

;; If a correctly length-prefixed, yet invalid message is received from
;; a client, the server's default error handler ought to shut itself
;; down.

(deftest default-on-error
  (let [state (host-start [(invalid-message)])]
    (is (drained? @(s/take! (:stub-stream state) ::drained?))
        "Server was not closed after receipt of invalid message.")
    (s/close! (:msgs state))
    (is (not ((:handler-invoked? state)))
        "Handler was invoked despite invalid message.")))

;; If a correctly length-prefixed, yet invalid message is received from
;; a client, a custom error handler ought to be invoked.

(deftest custom-on-error
  (let [invoked (d/deferred)
        state   (host-start
                 [(invalid-message)]
                 {:on-error (fn custom-on-error [server error]
                              (d/success! invoked (and server error)))})]
    (is (deref invoked 1000 false)
        "Error callback not invoked in time.")
    (is (not (s/closed? (:stub-stream state)))
        "Server was closed despite custom error handler.")
    (is (not ((:handler-invoked? state)))
        "Handler was invoked despite invalid message.")))
